package com.course.vsearch.service;

import com.course.vsearch.common.BizException;
import com.course.vsearch.config.VSearchProperties;
import com.course.vsearch.constant.ProcessStage;
import com.course.vsearch.constant.VideoStatus;
import com.course.vsearch.dto.DeleteVideoResponse;
import com.course.vsearch.dto.ProgressEvent;
import com.course.vsearch.dto.UploadResponse;
import com.course.vsearch.dto.VideoInfoResponse;
import com.course.vsearch.dto.VideoListItem;
import com.course.vsearch.entity.Video;
import com.course.vsearch.entity.VideoSegment;
import com.course.vsearch.mapper.AsrChunkCheckpointMapper;
import com.course.vsearch.mapper.VideoMapper;
import com.course.vsearch.mapper.VideoSegmentMapper;
import com.course.vsearch.mq.PipelineGateway;
import com.course.vsearch.security.TenantContext;
import com.course.vsearch.service.audio.MediaSourceNormalizer;
import com.course.vsearch.service.lock.DistributedLockService;
import com.course.vsearch.service.pipeline.InFlightTaskRegistry;
import com.course.vsearch.service.pipeline.VideoProcessService;
import com.course.vsearch.service.progress.ProgressService;
import com.course.vsearch.service.storage.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 视频上传与信息查询。支持单文件与「画面轨 + 声音轨」双文件上传（双文件在上传侧合并为单个 MP4），
 * MD5 内容去重 + Redisson 锁防并发重复提交。
 * <p>
 * 上传请求是异步的：请求线程只「落盘 + 算指纹 + 建记录」，归一化 / 双轨合并 / 写对象存储
 * 交给 uploadFinalizeExecutor 在后台完成，故 300MB 级文件也能秒级返回 taskId。
 */
@Slf4j
@Service
public class VideoAppService {

    private final VideoMapper videoMapper;
    private final VideoSegmentMapper segmentMapper;
    private final AsrChunkCheckpointMapper chunkMapper;
    private final StorageService storage;
    private final PipelineGateway pipelineGateway;
    private final DistributedLockService lockService;
    private final ProgressService progressService;
    private final VSearchProperties props;
    private final MediaSourceNormalizer mediaNormalizer;
    /** 上传预处理池：与 videoProcessExecutor（处理流水线独占）隔离，详见 AsyncConfig */
    private final Executor uploadFinalizeExecutor;
    /** 本进程「已接手但尚未结束」的任务登记表：用于把排队等待与真中断区分开，见 isInterrupted */
    private final InFlightTaskRegistry inFlightRegistry;

    public VideoAppService(VideoMapper videoMapper,
                           VideoSegmentMapper segmentMapper,
                           AsrChunkCheckpointMapper chunkMapper,
                           StorageService storage,
                           PipelineGateway pipelineGateway,
                           DistributedLockService lockService,
                           ProgressService progressService,
                           VSearchProperties props,
                           MediaSourceNormalizer mediaNormalizer,
                           InFlightTaskRegistry inFlightRegistry,
                           @Qualifier("uploadFinalizeExecutor") Executor uploadFinalizeExecutor) {
        this.videoMapper = videoMapper;
        this.segmentMapper = segmentMapper;
        this.chunkMapper = chunkMapper;
        this.storage = storage;
        this.pipelineGateway = pipelineGateway;
        this.lockService = lockService;
        this.progressService = progressService;
        this.props = props;
        this.mediaNormalizer = mediaNormalizer;
        this.inFlightRegistry = inFlightRegistry;
        this.uploadFinalizeExecutor = uploadFinalizeExecutor;
    }

    /**
     * 上传入口：支持 1~2 个文件。
     * 单文件 = 完整视频或纯音频轨（历史行为不变）；
     * 双文件 = 画面与声音分处两个文件（B站 DASH 的两个 .m4s 典型），
     * 由 resolveSource 按各自真实流组成判定并合并成一个 MP4 再入库。
     * <p>
     * 请求线程内只做两件无法延后的事：把上传文件复制到自己的临时目录（Tomcat 的 multipart
     * 临时文件随请求结束被清理），以及在同一次读取里算出 MD5。归一化 / 双轨合并 / 写对象存储
     * 全部交给后台线程，故这里能秒级返回 taskId，剩余进度由前端订阅该 taskId 的进度流获得。
     *
     * @param uploadTaskId 客户端自带的上传期任务 ID（up_xxx，可空）：请求线程内的落盘+指纹
     *                     期间零响应字节，故用该 ID 提前推 SSE 进度。
     */
    public UploadResponse upload(MultipartFile primary, MultipartFile second, String uploadTaskId) {
        if (primary == null || primary.isEmpty()) {
            throw new BizException(400, "视频文件不能为空");
        }
        MultipartFile extra = (second != null && !second.isEmpty()) ? second : null;
        String uploadProgressId = validUploadTaskId(uploadTaskId);
        publishUpload(uploadProgressId, 1, "接收上传文件");

        String videoId = "v_" + UUID.randomUUID().toString().replace("-", "");
        // 上传临时目录绑定 videoId：后台预处理结束后由后台任务清理，未交给后台的路径在此处回收
        Path uploadDir = Path.of(props.getFfmpeg().getWorkDir(), "upload", videoId);
        RawUpload first;
        RawUpload secondUpload;
        try {
            Files.createDirectories(uploadDir);
            long t0 = System.currentTimeMillis();
            first = spool(primary, uploadDir.resolve("file-1"));
            secondUpload = extra == null ? null : spool(extra, uploadDir.resolve("file-2"));
            log.info("上传[{}] 落盘+指纹耗时 {} ms（{} 个文件，{} MB）", videoId,
                    System.currentTimeMillis() - t0, secondUpload == null ? 1 : 2,
                    (primary.getSize() + (extra == null ? 0 : extra.getSize())) / 1024 / 1024);
        } catch (IOException e) {
            deleteRecursively(uploadDir);
            throw new BizException("接收上传文件失败: " + e.getMessage(), e);
        }
        return submitSpooled(videoId, first, secondUpload, uploadDir, uploadProgressId);
    }

    /**
     * 已落盘的上传文件 → 内容指纹去重 → 建占位记录 → 交给后台预处理。
     * 单次上传（{@link #upload}）与分片上传（{@link ChunkedUploadService#complete} 合并分片后）共用此入口，
     * 保证两条路径的去重键、上传锁、后台预处理与进度口径完全一致。
     * <p>
     * uploadDir 的所有权：只有「交给后台」这条路径不在此清理，其余（去重命中 / 上锁失败 / 异常）都在此回收。
     */
    UploadResponse submitSpooled(String videoId, RawUpload first, RawUpload second,
                                 Path uploadDir, String uploadProgressId) {
        String md5 = dedupKey(first.md5(), second == null ? null : second.md5());
        // 去重与上传锁都按租户拆分：别的租户传同一份文件既不共享记录，也不该争同一把锁
        String tenantId = TenantContext.require();
        try {
            return lockService.tryExecute(
                    "vsearch:lock:upload:" + tenantId + ":" + md5,
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(30),
                    () -> routeUpload(videoId, md5, first, second, uploadDir),
                    () -> {
                        Video existing = videoMapper.selectByMd5(md5);
                        if (existing != null) {
                            deleteRecursively(uploadDir);
                            return new UploadResponse(existing.getVideoId(), statusText(existing.getStatus()), true);
                        }
                        throw new BizException(409, "同一视频正在提交处理，请勿重复上传");
                    });
        } catch (RuntimeException e) {
            deleteRecursively(uploadDir);
            throw e;
        } finally {
            // 无论成功失败都清掉上传期进度快照：响应已带回真实 taskId（或已报错），不留 Redis 垃圾
            clearUploadProgress(uploadProgressId);
        }
    }

    /**
     * 上传锁内（毫秒级，不含重活）决定这次上传怎么走：命中已有任务则复用，否则建占位记录并交给后台预处理。
     * 上传目录的所有权：只有交给后台的那条路径不在这里清理。
     */
    private UploadResponse routeUpload(String videoId, String md5, RawUpload first, RawUpload second,
                                       Path uploadDir) {
        Video existing = videoMapper.selectByMd5(md5);
        if (existing != null) {
            if (existing.getStatus() == VideoStatus.FAILED) {
                if (!sourceObjectExists(existing)) {
                    // 上次就失败在上传预处理阶段（对象存储里根本没有源文件）：此时重投流水线
                    // 只会在下载源文件时再挂一次，改为重跑预处理
                    log.info("命中失败任务且源文件缺失，重跑上传预处理: {}", existing.getVideoId());
                    return refinalize(existing, first, second, uploadDir);
                }
                log.info("命中失败任务，触发重试: {}", existing.getVideoId());
                deleteRecursively(uploadDir);
                return resubmit(existing);
            }
            if (existing.getStatus() != VideoStatus.DONE && isOrphan(existing.getVideoId())) {
                // 状态声称待处理/处理中，但处理锁空闲 ⇒ 上次进程被强杀留下的孤儿任务，
                // 否则重传只会命中 MD5 去重、任务永不重启
                log.info("检测到孤儿任务（状态 {} 但处理锁空闲），重新入队: {}",
                        existing.getStatus(), existing.getVideoId());
                deleteRecursively(uploadDir);
                return resubmit(existing);
            }
            log.info("命中 MD5 去重: {} -> {}", md5, existing.getVideoId());
            deleteRecursively(uploadDir);
            return new UploadResponse(existing.getVideoId(), statusText(existing.getStatus()), true);
        }

        Video video = new Video();
        video.setVideoId(videoId);
        // 文件名与对象 key 先写占位值：后台预处理（双轨合并可能改名成 .mp4、写入对象存储）完成后回写真实值。
        // 两列都是 NOT NULL，插入时必须带值。
        video.setFileName(first.name());
        video.setMinioUrl(objectKeyFor(videoId, first.name()));
        video.setMd5(md5);
        // 租户在请求线程内取（拦截器不会自动填充 INSERT 的租户列，漏写会直接撞非空约束）
        video.setTenantId(TenantContext.require());
        video.setStatus(VideoStatus.PENDING);
        video.setCreatedAt(LocalDateTime.now());
        video.setUpdatedAt(LocalDateTime.now());
        videoMapper.insert(video);
        enqueueFinalize(videoId, first, second, uploadDir);
        return new UploadResponse(videoId, "processing", false);
    }

    /** 复用已有 videoId 重跑上传预处理：状态复位、清旧进度快照，ASR 断点保留 */
    private UploadResponse refinalize(Video existing, RawUpload first, RawUpload second, Path uploadDir) {
        existing.setStatus(VideoStatus.PENDING);
        existing.setErrorMsg(null);
        existing.setUpdatedAt(LocalDateTime.now());
        videoMapper.updateById(existing);
        progressService.clear(existing.getVideoId());
        enqueueFinalize(existing.getVideoId(), first, second, uploadDir);
        return new UploadResponse(existing.getVideoId(), "processing", false);
    }

    /** 交给后台预处理；上传临时目录的所有权随之转移，由后台任务负责清理 */
    private void enqueueFinalize(String videoId, RawUpload first, RawUpload second, Path uploadDir) {
        // 先登记再投递：预处理池只有 2 线程，排在后面的任务会长时间「状态待处理 + 处理锁空闲」，
        // 不登记就会被 GET /api/video/{id} 误判成中断（见 InFlightTaskRegistry）
        inFlightRegistry.mark(videoId);
        // 预处理跑在独立线程池，没有请求上下文：显式声明 system 模式，
        // 否则拦截器会注入空租户条件（查不到记录 → 上传预处理静默丢弃）
        uploadFinalizeExecutor.execute(() -> TenantContext.runAsSystem(
                () -> finalizeUpload(new FinalizeJob(videoId, uploadDir, first, second))));
    }

    /**
     * 上传预处理（后台线程）：归一化 / 双轨合并 → 写对象存储 → 回写 video 行 → 投递处理流水线。
     * <p>
     * 持处理锁（与 VideoProcessService 同一把）执行：既避免同一任务被并发预处理，
     * 也让上传侧在这段时间内不会把「正在预处理」误判成孤儿任务。锁走看门狗（leaseTime=-1，
     * 存活期间自动续约、进程被强杀后 30 秒内释放），故预处理再久也不会中途失效。
     * 锁必须在投递流水线之前释放，否则流水线自己的 tryLock 会失败、任务被静默跳过。
     */
    private void finalizeUpload(FinalizeJob job) {
        String videoId = job.videoId();
        boolean prepared = false;
        try {
            prepared = Boolean.TRUE.equals(lockService.tryExecute(
                    VideoProcessService.processLockKey(videoId),
                    Duration.ZERO,
                    Duration.ofMillis(-1),
                    () -> prepareSource(job),
                    () -> {
                        log.info("[{}] 上传预处理跳过：处理锁被占用（已有实例在处理）", videoId);
                        return Boolean.FALSE;
                    }));
        } catch (Exception e) {
            markUploadFailed(videoId, e);
        } finally {
            deleteRecursively(job.uploadDir());
        }
        if (!prepared) {
            // 没投递出去（预处理失败 / 处理锁被占），本进程不再持有该任务
            inFlightRegistry.clear(videoId);
            return;
        }
        // 登记在此移交：本地网关投递时会重新登记，之后由 process() 收尾时注销
        inFlightRegistry.clear(videoId);
        try {
            pipelineGateway.submit(videoId);
        } catch (Exception e) {
            // 记录已是 PENDING，可由启动自愈/重传唤醒；不能把预处理成功的任务误标失败
            log.error("[{}] 投递处理流水线失败（任务保持待处理，可被启动自愈重投）: {}", videoId, e.getMessage(), e);
        }
    }

    /** 归一化 / 合并 + 写入对象存储 + 回写行。返回 true 表示可以投递处理流水线 */
    private boolean prepareSource(FinalizeJob job) {
        String videoId = job.videoId();
        try {
            publishPrep(videoId, 2, "媒体归一化 / 双轨合并中");
            UploadSource source = resolveSource(job.first(), job.second(), job.uploadDir());
            String objectKey = objectKeyFor(videoId, source.fileName());
            long size = Files.size(source.localFile());
            publishPrep(videoId, 4, "写入对象存储中");
            long t0 = System.currentTimeMillis();
            try (InputStream body = Files.newInputStream(source.localFile())) {
                storage.putObject(objectKey, body, size, source.contentType());
            }
            Video video = videoMapper.selectByVideoId(videoId);
            if (video == null) {
                log.warn("[{}] 上传预处理完成后记录已不存在，丢弃", videoId);
                return false;
            }
            video.setFileName(source.fileName());
            video.setMinioUrl(objectKey);
            video.setUpdatedAt(LocalDateTime.now());
            videoMapper.updateById(video);
            log.info("[{}] 上传预处理完成：{} MB / 对象 {} / 写存储 {} ms",
                    videoId, size / 1024 / 1024, objectKey, System.currentTimeMillis() - t0);
            return true;
        } catch (IOException e) {
            throw new BizException("读取上传文件失败: " + e.getMessage(), e);
        }
    }

    /** 上传预处理失败：落 FAILED 并推失败进度，前端的任务进度流会直接显示原因 */
    private void markUploadFailed(String videoId, Exception e) {
        String msg = "上传预处理失败: " + e.getMessage();
        if (msg.length() > 1000) {
            msg = msg.substring(0, 1000);
        }
        log.error("[{}] {}", videoId, msg, e);
        Video video = videoMapper.selectByVideoId(videoId);
        if (video != null) {
            video.setStatus(VideoStatus.FAILED);
            video.setErrorMsg(msg);
            video.setUpdatedAt(LocalDateTime.now());
            videoMapper.updateById(video);
        }
        try {
            progressService.publish(ProgressEvent.of(videoId, ProcessStage.FAILED, 100, msg));
        } catch (Exception pe) {
            log.debug("上传预处理失败进度推送失败（不影响结果）: {}", pe.getMessage());
        }
        // 任务已终态，本进程不再持有它；避免残留登记把后续真中断掩盖掉
        inFlightRegistry.clear(videoId);
    }

    /**
     * 把上传文件复制到自己的临时目录，并在同一次读取里算 MD5（只读一遍、只写一遍）。
     * 必须在上传请求线程内完成：Tomcat 的 multipart 临时文件在请求结束后即被清理。
     */
    private static RawUpload spool(MultipartFile file, Path target) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new BizException("计算文件 MD5 失败: " + e.getMessage(), e);
        }
        try (InputStream in = file.getInputStream();
             DigestInputStream dis = new DigestInputStream(in, md);
             OutputStream out = Files.newOutputStream(target)) {
            dis.transferTo(out);
        }
        return new RawUpload(sanitizeFileName(file.getOriginalFilename()), contentTypeOf(file),
                target, HexFormat.of().formatHex(md.digest()));
    }

    /** 对象存储里是否已有该任务的源文件（区分「上传预处理没跑完」与「处理阶段失败」） */
    private boolean sourceObjectExists(Video video) {
        try {
            return storage.objectExists(video.getMinioUrl());
        } catch (Exception e) {
            log.warn("[{}] 查询源文件是否存在失败，按不存在处理（重跑上传预处理）: {}",
                    video.getVideoId(), e.getMessage());
            return false;
        }
    }

    private static String objectKeyFor(String videoId, String fileName) {
        return "videos/" + videoId + "/" + fileName;
    }

    /**
     * 上传预处理（后台）阶段的进度：推到真实 videoId 上。
     * 前端的切换时机是「POST 返回后订阅该 taskId」，故这些事件必须挂在任务进度流下，而非 up_xxx。
     */
    private void publishPrep(String videoId, int progress, String message) {
        try {
            progressService.publish(ProgressEvent.of(videoId, ProcessStage.UPLOADING, progress, message));
        } catch (Exception e) {
            log.debug("上传预处理进度推送失败（不影响上传）: {}", e.getMessage());
        }
    }

    /**
     * 确定要入库的单一媒体文件。
     * 规则按「真实流组成」判定而非文件名，故两个文件的先后顺序无所谓：
     * 1) 某一份自身已同时含画面与声音 → 直接用它（无需合并）；
     * 2) 一份只有画面、另一份只有声音 → 无损合并成一个 MP4；
     * 3) 只有一份含音轨 → 用它（另一份视为无效，仅记录告警）；
     * 4) 都不含音轨 → 报错并提示一并上传 B站 音频轨。
     * 返回值指向本地临时文件（即请求线程已落盘的副本或归一化/合并产物），workDir 由调用方负责清理。
     */
    private UploadSource resolveSource(RawUpload first, RawUpload second, Path workDir) {
        if (second == null) {
            // 单文件沿用历史行为：不做归一化，直接把收到的文件写入对象存储，
            // 容器垃圾头由下游 AudioPreprocessService 处理
            return new UploadSource(first.name(), first.contentType(), first.file());
        }
        Track t1 = readTrack(first, workDir.resolve("track-1"));
        Track t2 = readTrack(second, workDir.resolve("track-2"));

        if (t1.video() && t1.audio()) {
            log.info("主文件已含画面与声音，忽略第二个文件");
            return new UploadSource(first.name(), first.contentType(), t1.file());
        }
        if (t2.video() && t2.audio()) {
            log.info("第二个文件已含画面与声音，忽略主文件");
            return new UploadSource(second.name(), second.contentType(), t2.file());
        }
        if (t1.video() && t2.audio()) {
            return muxedSource(first, t1, t2, workDir);
        }
        if (t2.video() && t1.audio()) {
            return muxedSource(first, t2, t1, workDir);
        }
        if (t1.audio()) {
            log.warn("第二个文件既无画面也无声音，忽略");
            return new UploadSource(first.name(), first.contentType(), t1.file());
        }
        if (t2.audio()) {
            log.warn("主文件既无画面也无声音，改用第二个文件");
            return new UploadSource(second.name(), second.contentType(), t2.file());
        }
        throw new BizException("上传的文件中没有任何音轨（只有画面轨），无法识别语音内容。"
                + "请把 B站 的画面轨与声音轨两个文件一起上传（声音轨文件名通常形如 xxx-1-30280.m4s）；"
                + "若上传的是无声录屏，请先录制带讲解声音的视频。");
    }

    /** 画面轨 + 声音轨无损合并，文件名取主文件基名并换成 .mp4 */
    private UploadSource muxedSource(RawUpload primary, Track video, Track audio, Path workDir) {
        Path merged = mediaNormalizer.muxTracks(video.file(), audio.file(), workDir.resolve("merged.mp4"));
        log.info("双轨合并完成: {}（画面轨 {} / 声音轨 {}）",
                merged.getFileName(), video.file().getFileName(), audio.file().getFileName());
        return new UploadSource(mp4Name(primary.name()), "video/mp4", merged);
    }

    /**
     * 归一化 + 探测流组成。B站 .m4s 头部有非标准字节，
     * 必须先经归一化剥离才能被 ffprobe 解析，故探测对象是归一化后的路径。
     * 每份文件独占一个子目录：归一化中间产物按偏移量命名（如 resync-9.bin），
     * 两条轨若共用目录会互相覆盖，导致「合并的两个输入其实是同一个文件」。
     */
    private Track readTrack(RawUpload upload, Path trackDir) {
        try {
            Files.createDirectories(trackDir);
            Path readable = mediaNormalizer.normalize(upload.file(), trackDir, false);
            MediaSourceNormalizer.Streams s = mediaNormalizer.probeStreams(readable);
            log.info("上传文件 {} 流组成: video={}, audio={}",
                    upload.name(), s.video(), s.audio());
            return new Track(readable, s.video(), s.audio());
        } catch (IOException e) {
            throw new BizException("归一化上传文件失败: " + e.getMessage(), e);
        }
    }

    /** 单份上传文件的流组成 */
    private record Track(Path file, boolean video, boolean audio) {
    }

    /** 已落盘的上传文件（请求线程内完成拷贝，避免 Tomcat 请求结束后临时文件被清理）。
     * 包级可见：分片上传合并分片后复用同一入口 {@link #submitSpooled}。 */
    record RawUpload(String name, String contentType, Path file, String md5) {
    }

    /** 上传预处理任务：videoId + 上传临时目录（由后台任务负责清理）+ 两份原始文件 */
    private record FinalizeJob(String videoId, Path uploadDir, RawUpload first, RawUpload second) {
    }

    /** 待入库的单一媒体文件；localFile 指向上传临时目录内（或归一化/合并产物）的本地文件 */
    private record UploadSource(String fileName, String contentType, Path localFile) {
    }

    /**
     * 复用 videoId 与 MinIO 对象重新入队：重置状态、清掉旧进度快照，
     * 但保留 ASR 断点（video_asr_chunk）——这正是续跑的依据。
     * process 落库前会 deleteByVideoId 清理旧片段，天然支持从任意阶段重试。
     * 两个调用方：上传时命中失败/孤儿任务，以及启动自愈 {@code StartupRecoveryRunner}。
     */
    public UploadResponse resubmit(Video existing) {
        existing.setStatus(VideoStatus.PENDING);
        existing.setErrorMsg(null);
        existing.setUpdatedAt(LocalDateTime.now());
        videoMapper.updateById(existing);
        progressService.clear(existing.getVideoId());
        pipelineGateway.submit(existing.getVideoId());
        return new UploadResponse(existing.getVideoId(), "processing", false);
    }

    /**
     * 状态非 FAILED、非 DONE 时判孤儿：处理锁空闲说明没有实例真的在处理它。
     * 处理锁走 Redisson 看门狗，进程被强杀后 30 秒内自动释放，故锁空闲即持有者已消失。
     */
    private boolean isOrphan(String videoId) {
        try {
            return !lockService.isLocked(VideoProcessService.processLockKey(videoId));
        } catch (Exception e) {
            // 查询失败时保守处理：当作仍在处理，避免误重投
            log.warn("[{}] 查询处理锁失败，按仍在处理处理: {}", videoId, e.getMessage());
            return false;
        }
    }

    /**
     * 任务是否已中断。供前端在 SSE 静默后反查：状态仍是待处理/处理中，但处理锁空闲，
     * 且进度快照已静默超过 interruptedAfterSeconds —— 说明持有者（后端进程）已消失，任务永远不会自愈。
     * 静默阈值用于排除「刚入队、消息尚未被消费」的正常窗口（此时锁本来就空闲）。
     * 只做判定、不自动重投：重投由启动自愈或用户重传触发。
     * <p>
     * 例外：本进程已接手的任务一律不算中断。排队等锁的任务同样「状态未完成 + 锁空闲 + 进度不刷新」，
     * 与真中断在外部完全无法区分（预处理池 2 线程、处理池 2 线程，排在后面的任务可以等很久），
     * 故用 InFlightTaskRegistry 这个随进程消失的内存登记表来区分。
     */
    private boolean isInterrupted(Video video) {
        if (video.getStatus() != VideoStatus.PENDING && video.getStatus() != VideoStatus.PROCESSING) {
            return false;
        }
        if (inFlightRegistry.contains(video.getVideoId())) {
            return false;
        }
        Long lastUpdate = progressService.lastUpdateMillis(video.getVideoId());
        // 无进度快照时退回 video.updated_at（提交时写入），避免把「刚入队、消息尚未消费」误判为中断
        long lastMillis;
        if (lastUpdate != null) {
            lastMillis = lastUpdate;
        } else if (video.getUpdatedAt() != null) {
            lastMillis = video.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } else {
            lastMillis = 0L;
        }
        if (System.currentTimeMillis() - lastMillis < props.getProgress().getInterruptedAfterSeconds() * 1000L) {
            return false;
        }
        return isOrphan(video.getVideoId());
    }

    /**
     * 视频库列表：全部视频按上传时间倒序，附状态、时长、片段数与章节骨架，
     * 让用户不看 videoId 也能认出「这个是哪个视频」。
     * 章节一次批量取（listBriefAll），不做逐视频查询。
     */
    public List<VideoListItem> list() {
        List<Video> videos = videoMapper.selectAllOrdered();
        if (videos.isEmpty()) {
            return List.of();
        }
        Map<String, List<VideoSegment>> briefs = segmentMapper.listBriefAll().stream()
                .collect(Collectors.groupingBy(VideoSegment::getVideoId));
        return videos.stream()
                .map(v -> toListItem(v, briefs.getOrDefault(v.getVideoId(), List.of())))
                .toList();
    }

    private static VideoListItem toListItem(Video video, List<VideoSegment> segments) {
        VideoListItem item = new VideoListItem();
        item.setVideoId(video.getVideoId());
        item.setFileName(video.getFileName());
        item.setStatus(video.getStatus());
        item.setStatusText(statusText(video.getStatus()));
        item.setDuration(video.getDuration());
        item.setSegmentCount(segments.size());
        item.setCreatedAt(video.getCreatedAt());
        item.setErrorMsg(video.getErrorMsg());
        item.setChapters(segments.stream().map(s -> {
            VideoListItem.Chapter chapter = new VideoListItem.Chapter();
            chapter.setTitle(s.getChapterTitle());
            chapter.setStartTime(s.getStartTime());
            chapter.setEndTime(s.getEndTime());
            return chapter;
        }).toList());
        return item;
    }

    /**
     * 删除视频：对象存储（源文件 + 归一化可播放产物）、片段、ASR 断点、进度快照、本地工作目录一并清掉。
     * <p>
     * 处理锁被持有 ⇒ 确实有实例在处理它，直接拒绝（否则中途把源文件抽走会让流水线崩在半路）；
     * 「状态未完成但锁空闲」的孤儿任务允许删除。
     * 数据库三张表的删除是原子的；对象存储清理失败不阻断删除——否则会在列表里留下永远删不掉的条目，
     * 失败项连对象 key 一起放进 warnings 返回，便于人工兜底。
     */
    @Transactional
    public DeleteVideoResponse delete(String videoId) {
        Video video = requireOwned(videoId, TenantContext.require());
        // isOrphan 是「处理锁空闲」的判定，取反即「有实例正持有处理锁」
        if (!isOrphan(videoId)) {
            throw new BizException(409, "该视频正在处理中，等处理结束（或后端停止）后再删除");
        }

        List<String> warnings = new ArrayList<>();
        removeObjectQuietly(video.getMinioUrl(), warnings);
        removeObjectQuietly(StorageService.playableKey(videoId), warnings);

        int segments = segmentMapper.deleteByVideoId(videoId);
        int chunks = chunkMapper.deleteByVideoId(videoId);
        videoMapper.deleteByVideoId(videoId);
        progressService.clear(videoId);
        // 本地工作目录（流水线 scratch 与上传临时目录）：清理失败只告警，不影响删除结果
        deleteRecursively(Path.of(props.getFfmpeg().getWorkDir(), videoId));
        deleteRecursively(Path.of(props.getFfmpeg().getWorkDir(), "upload", videoId));

        log.info("[{}] 已删除视频「{}」：片段 {} 条 / ASR 断点 {} 条 / 对象清理告警 {} 项",
                videoId, video.getFileName(), segments, chunks, warnings.size());
        return new DeleteVideoResponse(videoId, warnings);
    }

    private void removeObjectQuietly(String objectKey, List<String> warnings) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            storage.removeObject(objectKey);
        } catch (Exception e) {
            log.warn("删除对象存储文件失败（对象可能残留，需人工清理）: {} ({})", objectKey, e.getMessage());
            warnings.add("对象未删除 " + objectKey + "：" + e.getMessage());
        }
    }

    public VideoInfoResponse getInfo(String videoId) {
        Video video = requireOwned(videoId, TenantContext.require());
        VideoInfoResponse resp = new VideoInfoResponse();
        resp.setVideoId(video.getVideoId());
        resp.setFileName(video.getFileName());
        resp.setDuration(video.getDuration());
        resp.setStatus(video.getStatus());
        resp.setErrorMsg(video.getErrorMsg());
        resp.setSegmentCount(segmentMapper.countByVideoId(videoId));
        resp.setInterrupted(isInterrupted(video));
        if (video.getStatus() == VideoStatus.DONE) {
            resp.setPlayUrl(resolvePlayUrl(video));
        }
        return resp;
    }

    /** 播放地址：票据校验已拿到租户，用「按租户查」避免请求线程无上下文时拦截器注入空条件 */
    public String playUrl(String videoId, String tenantId) {
        return resolvePlayUrl(requireOwned(videoId, tenantId));
    }

    /**
     * 归属校验：按「租户 + videoId」精确查，不符即 404（不区分「不存在」与「不是你的」，避免探测）。
     * 显式带租户而非依赖拦截器——持票通道（&lt;video&gt; / EventSource）没有请求上下文，
     * 拦截器只会注入空租户条件（tenant_id = ''）从而查不到任何记录。
     */
    private Video requireOwned(String videoId, String tenantId) {
        Video video = videoMapper.selectByVideoIdAndTenant(videoId, tenantId);
        if (video == null) {
            throw new BizException(404, "视频不存在: " + videoId);
        }
        return video;
    }

    /** 换票据前的归属校验入口：上传期进度 ID（up_xxx）不代表任何资源，直接放行 */
    public void verifyResourceAccess(String resourceId, String tenantId) {
        if (resourceId == null || resourceId.startsWith("up_")) {
            return;
        }
        requireOwned(resourceId, tenantId);
    }

    /** 优先返回归一化 MP4（浏览器可直接播放），历史任务缺失时回退原始对象 */
    private String resolvePlayUrl(Video video) {
        String playableKey = StorageService.playableKey(video.getVideoId());
        try {
            if (storage.objectExists(playableKey)) {
                return storage.presignedGetUrl(playableKey, Duration.ofHours(2));
            }
        } catch (Exception e) {
            // 查询失败不阻断播放，回退原始对象
            log.warn("查询可播放对象失败，回退原始对象: {}", e.getMessage());
        }
        return storage.presignedGetUrl(video.getMinioUrl(), Duration.ofHours(2));
    }

    private static String statusText(int status) {
        return switch (status) {
            case VideoStatus.DONE -> "done";
            case VideoStatus.FAILED -> "failed";
            default -> "processing";
        };
    }

    /** 上传期进度 ID 的合法形态。强制 up_ 前缀，防止客户端拿真实 videoId 覆盖别的任务的进度快照 */
    private static final Pattern UPLOAD_TASK_ID = Pattern.compile("^up_[A-Za-z0-9]{8,32}$");

    /** 校验并归一化客户端自带的上传期进度 ID；非法返回 null（包级可见：分片上传完成阶段复用） */
    static String validUploadTaskId(String uploadTaskId) {
        if (uploadTaskId == null || !UPLOAD_TASK_ID.matcher(uploadTaskId).matches()) {
            return null;
        }
        return uploadTaskId;
    }

    /**
     * 推送「请求线程内」的上传阶段进度（仅落盘 + 指纹这几秒）。这段时间前端收不到任何响应字节，
     * 只能靠客户端自带的 uploadProgressId 提前订阅 SSE 才看得到；响应返回后的归一化/合并/写存储
     * 改用 {@link #publishPrep}（挂在真实 videoId 下）。推送失败绝不影响上传本身。
     */
    private void publishUpload(String uploadProgressId, int progress, String message) {
        if (uploadProgressId == null) {
            return;
        }
        try {
            progressService.publish(new ProgressEvent(uploadProgressId, ProcessStage.UPLOADING, progress, message));
        } catch (Exception e) {
            log.debug("上传阶段进度推送失败（不影响上传）: {}", e.getMessage());
        }
    }

    /** 清掉上传期进度快照，避免每次上传都在 Redis 留下一个 24h 的垃圾键 */
    private void clearUploadProgress(String uploadProgressId) {
        if (uploadProgressId == null) {
            return;
        }
        try {
            progressService.clear(uploadProgressId);
        } catch (Exception e) {
            log.debug("清理上传阶段进度失败（不影响上传）: {}", e.getMessage());
        }
    }

    /**
     * 去重键。单文件沿用文件 MD5（与历史数据、已有唯一索引 uk_video_md5 完全兼容）；
     * 双文件对「两份源文件 MD5 排序后拼接」再哈希，使去重与上传顺序无关
     * （B站 画面轨/声音轨谁在前都算同一个视频，避免换序重复处理）。
     */
    private static String dedupKey(String first, String second) {
        if (second == null) {
            return first;
        }
        String pair = first.compareTo(second) <= 0 ? first + ":" + second : second + ":" + first;
        return md5Hex(pair.getBytes(StandardCharsets.UTF_8));
    }

    private static String md5Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (Exception e) {
            throw new BizException("计算内容 MD5 失败: " + e.getMessage(), e);
        }
    }

    private static String contentTypeOf(MultipartFile file) {
        return file.getContentType() == null ? "application/octet-stream" : file.getContentType();
    }

    /** 合并产物改名：取主上传文件基名、扩展名换成 .mp4，与真实容器一致（下游据此落 local 源文件） */
    private static String mp4Name(String name) {
        String base = sanitizeFileName(name);
        int dot = base.lastIndexOf('.');
        return (dot > 0 ? base.substring(0, dot) : base) + ".mp4";
    }

    /** 递归清理上传临时目录（含归一化中间产物），失败不阻断上传结果。
     * 包级可见：分片上传的会话目录清理复用（见 ChunkedUploadService#discard）。 */
    static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 单个文件清理失败不影响整体
                }
            });
        } catch (Exception e) {
            log.warn("清理上传临时目录失败: {} ({})", dir, e.getMessage());
        }
    }

    /** 防止路径穿越与非法对象 key（包级可见：分片上传的会话元数据同样要落文件名） */
    static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "video.mp4";
        }
        String base = name.replace("\\", "/");
        base = base.substring(base.lastIndexOf('/') + 1);
        return base.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
    }
}
