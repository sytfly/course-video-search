package com.course.vsearch.controller;

import com.course.vsearch.common.Result;
import com.course.vsearch.constant.ProcessStage;
import com.course.vsearch.dto.ChunkPartResponse;
import com.course.vsearch.dto.ChunkUploadInitRequest;
import com.course.vsearch.dto.ChunkUploadInitResponse;
import com.course.vsearch.dto.DeleteVideoResponse;
import com.course.vsearch.dto.ProgressEvent;
import com.course.vsearch.dto.SearchRequest;
import com.course.vsearch.dto.SearchResult;
import com.course.vsearch.dto.UploadResponse;
import com.course.vsearch.dto.VideoInfoResponse;
import com.course.vsearch.dto.VideoListItem;
import com.course.vsearch.security.AccessTicketService;
import com.course.vsearch.security.TenantContext;
import com.course.vsearch.service.ChunkedUploadService;
import com.course.vsearch.service.SearchService;
import com.course.vsearch.service.VideoAppService;
import com.course.vsearch.service.progress.SseProgressManager;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/video")
@RequiredArgsConstructor
public class VideoController {

    private final VideoAppService videoAppService;
    private final ChunkedUploadService chunkedUploadService;
    private final SearchService searchService;
    private final SseProgressManager sseManager;
    private final AccessTicketService accessTicketService;

    /**
     * 6.1 上传视频：存 MinIO → 发异步任务 → 立即返回任务 ID。
     * 支持 1~2 个文件：单文件为完整视频或纯音频轨；
     * 两个文件为 B站 等 DASH 源的「画面轨 + 声音轨」，顺序无关，服务端按真实流组成自动合并。
     *
     * @param uploadTaskId 客户端自带的上传期任务 ID（形如 up_xxx，可空）。服务端的 MD5 指纹、
     *                     归一化/合并、写对象存储都同步跑在本请求线程上，期间没有任何响应字节，
     *                     故用这个 ID 提前推 SSE 进度，消除大文件的静默期；返回的 taskId 才是真实处理任务。
     */
    @PostMapping("/upload")
    public Result<UploadResponse> upload(
            @RequestParam(value = "video", required = false) MultipartFile video,
            @RequestParam(value = "audio", required = false) MultipartFile audio,
            @RequestParam(value = "uploadTaskId", required = false) String uploadTaskId) {
        return Result.ok(videoAppService.upload(video, audio, uploadTaskId));
    }

    /**
     * 6.1.1 分片上传-初始化：按 sessionKey 复用或新建会话，返回 uploadId 与各文件的分片计划。
     * 断点续传就发生在这里：断线 / 刷新后客户端用同一批文件（同一 sessionKey）再 init，
     * 响应里的 receivedParts 告诉它哪些片已经在服务端，只需补缺失的片。
     */
    @PostMapping("/upload/init")
    public Result<ChunkUploadInitResponse> initChunkedUpload(@RequestBody ChunkUploadInitRequest request) {
        return Result.ok(chunkedUploadService.init(request));
    }

    /** 6.1.2 分片上传-上传单片：单片独立落盘并校验长度，同序号重传直接覆盖（失败重试幂等） */
    @PostMapping("/upload/part")
    public Result<ChunkPartResponse> uploadChunkPart(@RequestParam("uploadId") String uploadId,
                                                     @RequestParam("fileIndex") int fileIndex,
                                                     @RequestParam("partNumber") int partNumber,
                                                     @RequestParam("part") MultipartFile part) {
        return Result.ok(chunkedUploadService.uploadPart(uploadId, fileIndex, partNumber, part));
    }

    /** 6.1.3 分片上传-合并：校验分片齐全 → 按序合并并算内容指纹 → 走与单次上传相同的入库路径 */
    @PostMapping("/upload/complete")
    public Result<UploadResponse> completeChunkedUpload(@RequestParam("uploadId") String uploadId,
                                                        @RequestParam(value = "uploadTaskId", required = false)
                                                        String uploadTaskId) {
        return Result.ok(chunkedUploadService.complete(uploadId, uploadTaskId));
    }

    /** 6.2 语义搜索：自然语言 → 向量 → topK 时间戳片段 */
    @PostMapping("/search")
    public Result<List<SearchResult>> search(@Valid @RequestBody SearchRequest request) {
        return Result.ok(searchService.search(request));
    }

    /**
     * 6.6 换取访问票据：&lt;video&gt; 标签与 EventSource 都带不了 Authorization 头，
     * 故先用带鉴权的本接口换一张与「租户 + 资源」绑定的 60 秒票据，再把票据放进 query 使用。
     * 换票时即完成归属校验，故拿别人的 videoId 换不到票。
     *
     * @param resourceId 视频 id（v_xxx）或上传期进度 id（up_xxx，不代表任何资源）
     */
    @PostMapping("/ticket")
    public Result<String> ticket(@RequestParam("resourceId") String resourceId) {
        String tenantId = TenantContext.require();
        videoAppService.verifyResourceAccess(resourceId, tenantId);
        return Result.ok(accessTicketService.issue(tenantId, resourceId));
    }

    /**
     * 6.3 SSE 实时处理进度。
     * 票据无效时返回一个立刻以 failed 事件收尾的流，而不是抛异常——EventSource 对 HTTP 错误状态
     * 会自动重连，抛异常会形成重连风暴；发 failed 事件则前端按正常失败路径关闭连接。
     */
    @GetMapping(value = "/progress/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter progress(@PathVariable String taskId,
                               @RequestParam(value = "ticket", required = false) String ticket) {
        if (accessTicketService.verify(ticket, taskId) == null) {
            return rejected(taskId);
        }
        return sseManager.connect(taskId);
    }

    private static SseEmitter rejected(String taskId) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            emitter.send(SseEmitter.event().name("progress").data(ProgressEvent.of(
                    taskId, ProcessStage.FAILED, 100, "进度访问票据无效或已过期，请刷新页面重试")));
        } catch (Exception e) {
            // 写入失败说明连接已经断开，无需处理
        }
        emitter.complete();
        return emitter;
    }

    /** 6.4 视频库列表：按上传时间倒序，含文件名 / 时长 / 状态 / 片段数 / 章节骨架。
     * 供前端展示「我传过哪些视频」，不必面对 v_xxx 这样的裸 ID。
     * 字面量路径 /list 优先于 /{videoId} 匹配（Spring 的路径比较规则），不会与 info 冲突。
     */
    @GetMapping("/list")
    public Result<List<VideoListItem>> list() {
        return Result.ok(videoAppService.list());
    }

    /**
     * 6.5 删除视频：连同对象存储、片段、ASR 断点、本地工作目录一起清理（清理重复与失败条目用）。
     * 正在处理中（处理锁被持有）的任务会返回 409，避免把文件从流水线脚下抽走。
     */
    @DeleteMapping("/{videoId}")
    public Result<DeleteVideoResponse> delete(@PathVariable String videoId) {
        return Result.ok(videoAppService.delete(videoId));
    }

    /** 查询视频处理状态 / 片段数 / 播放地址 */
    @GetMapping("/{videoId}")
    public Result<VideoInfoResponse> info(@PathVariable String videoId) {
        return Result.ok(videoAppService.getInfo(videoId));
    }

    /** 播放（302 到 MinIO 预签名地址，前端 video 标签可直接跳转 #t=秒）。
     * 不带 Authorization 头，故用 query 里的票据鉴权；票据的租户直接决定查哪个租户的视频。 */
    @GetMapping("/play/{videoId}")
    public org.springframework.http.ResponseEntity<Void> play(
            @PathVariable String videoId,
            @RequestParam(value = "ticket", required = false) String ticket) {
        String tenantId = accessTicketService.verify(ticket, videoId);
        if (tenantId == null) {
            return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
        }
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.FOUND)
                .location(URI.create(videoAppService.playUrl(videoId, tenantId)))
                .build();
    }
}
