package com.course.vsearch.service;

import com.course.vsearch.common.BizException;
import com.course.vsearch.config.VSearchProperties;
import com.course.vsearch.constant.ProcessStage;
import com.course.vsearch.dto.ChunkPartResponse;
import com.course.vsearch.dto.ChunkUploadInitRequest;
import com.course.vsearch.dto.ChunkUploadInitResponse;
import com.course.vsearch.dto.ProgressEvent;
import com.course.vsearch.dto.UploadResponse;
import com.course.vsearch.service.progress.ProgressService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBitSet;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 分片上传（大文件断点续传）。三段式：
 * <ol>
 *   <li><b>init</b>：按客户端给的 sessionKey 复用或新建会话，返回 uploadId + 各文件分片计划
 *       （复用时会带回已收到的分片号）；</li>
 *   <li><b>uploadPart</b>：单片独立落盘，重传幂等覆盖，进度记在 Redis 位图里（不依赖客户端记忆）；</li>
 *   <li><b>complete</b>：校验分片齐全 → 按序合并并同时算出内容指纹 → 交给
 *       {@link VideoAppService#submitSpooled} 走与单次上传完全相同的入库路径
 *       （MD5 去重 + 上传锁 + 后台归一化/写存储/投递流水线）。</li>
 * </ol>
 * 与单次上传相比，收益不是「更快」（受上行带宽限制，串行分片甚至略慢），而是：
 * 中断不重传（断点续传）、进度可见（按已确认分片数）、单请求失败代价只有几 MB。
 */
@Slf4j
@Service
public class ChunkedUploadService {

    /** 会话指纹的合法形态：直接拼进 Redis key 与文件路径，故只允许字母 / 数字 / 下划线 / 中划线 */
    private static final Pattern SESSION_KEY = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private static final String SESSION_PREFIX = "vsearch:chunkupload:session:";
    private static final String META_PREFIX = "vsearch:chunkupload:meta:";
    private static final String PARTS_PREFIX = "vsearch:chunkupload:parts:";

    /** 分片大小边界：过小会把 300MB 切成上千个请求，过大则断点粒度太粗、单请求失败代价高 */
    private static final long MIN_CHUNK_SIZE = 256 * 1024L;
    private static final long MAX_CHUNK_SIZE = 64 * 1024 * 1024L;
    /** 与单次上传一致：一个完整视频，或 B站 画面轨 + 声音轨 */
    private static final int MAX_FILES = 2;
    /** 缺片提示里最多列几个分片号（避免报错信息过长） */
    private static final int MISSING_PREVIEW = 10;

    private final RedissonClient redisson;
    private final VSearchProperties props;
    private final ProgressService progressService;
    private final VideoAppService videoAppService;

    public ChunkedUploadService(RedissonClient redisson,
                                VSearchProperties props,
                                ProgressService progressService,
                                VideoAppService videoAppService) {
        this.redisson = redisson;
        this.props = props;
        this.progressService = progressService;
        this.videoAppService = videoAppService;
    }

    // ------------------------------------------------------------------ init

    public ChunkUploadInitResponse init(ChunkUploadInitRequest request) {
        List<ChunkUploadInitRequest.FileSpec> files = request == null ? null : request.files();
        if (files == null || files.isEmpty() || files.size() > MAX_FILES) {
            throw new BizException(400, "分片上传需要 1~2 个文件（完整视频，或 B站 画面轨 + 声音轨）");
        }
        String sessionKey = request.sessionKey();
        if (sessionKey == null || !SESSION_KEY.matcher(sessionKey).matches()) {
            throw new BizException(400, "sessionKey 非法：须为 8~64 位的字母 / 数字 / 下划线 / 中划线");
        }
        long chunkSize = resolveChunkSize(request.chunkSize());
        long maxFileSize = props.getUpload().getMaxFileSize().toBytes();
        for (ChunkUploadInitRequest.FileSpec f : files) {
            if (f == null || f.name() == null || f.name().isBlank()) {
                throw new BizException(400, "文件名不能为空");
            }
            if (f.size() <= 0 || f.size() > maxFileSize) {
                throw new BizException(400, "文件「" + f.name() + "」大小非法或超过上限 "
                        + (maxFileSize / 1024 / 1024) + " MB");
            }
        }

        RBucket<String> session = redisson.getBucket(SESSION_PREFIX + sessionKey);
        String existing = session.get();
        if (existing != null) {
            RMap<String, String> old = meta(existing);
            if (old.isExists() && sameDescriptor(old, files, chunkSize)) {
                ChunkUploadInitResponse resp = describe(existing, chunkSize, true);
                log.info("分片上传会话复用: {} -> {}（{} 个文件，共已收 {} 片）", sessionKey, existing,
                        files.size(), resp.files().stream().mapToInt(
                                ChunkUploadInitResponse.FilePlan::receivedCount).sum());
                return resp;
            }
            // 描述符（文件名/大小）或分片大小变了：这不是同一个文件，旧会话连同已落盘分片一起作废
            log.info("分片上传会话描述符不匹配，重建: {} -> {}", sessionKey, existing);
            discard(existing);
        }

        String uploadId = "cu_" + UUID.randomUUID().toString().replace("-", "");
        RMap<String, String> meta = meta(uploadId);
        meta.put("sessionKey", sessionKey);
        meta.put("chunkSize", String.valueOf(chunkSize));
        meta.put("fileCount", String.valueOf(files.size()));
        for (int i = 0; i < files.size(); i++) {
            ChunkUploadInitRequest.FileSpec f = files.get(i);
            meta.put(key(i, "name"), VideoAppService.sanitizeFileName(f.name()));
            meta.put(key(i, "size"), String.valueOf(f.size()));
            meta.put(key(i, "type"), contentTypeOf(f.name(), f.contentType()));
            meta.put(key(i, "total"), String.valueOf(totalParts(f.size(), chunkSize)));
        }
        meta.expire(sessionTtl(), TimeUnit.HOURS);
        try {
            Files.createDirectories(sessionDir(uploadId));
        } catch (IOException e) {
            discard(uploadId);
            throw new BizException("创建上传会话目录失败: " + e.getMessage(), e);
        }
        session.set(uploadId, sessionTtl(), TimeUnit.HOURS);
        log.info("分片上传会话新建: {} -> {}（{} 个文件，分片 {} MB）", sessionKey, uploadId,
                files.size(), chunkSize / 1024 / 1024);
        return describe(uploadId, chunkSize, false);
    }

    // ------------------------------------------------------------------ part

    public ChunkPartResponse uploadPart(String uploadId, int fileIndex, int partNumber, MultipartFile part) {
        if (part == null || part.isEmpty()) {
            throw new BizException(400, "分片内容为空");
        }
        RMap<String, String> meta = requireMeta(uploadId);
        int fileCount = Integer.parseInt(meta.get("fileCount"));
        if (fileIndex < 0 || fileIndex >= fileCount) {
            throw new BizException(400, "fileIndex 越界: " + fileIndex);
        }
        long chunkSize = Long.parseLong(meta.get("chunkSize"));
        long size = Long.parseLong(meta.get(key(fileIndex, "size")));
        int total = Integer.parseInt(meta.get(key(fileIndex, "total")));
        if (partNumber < 1 || partNumber > total) {
            throw new BizException(400, "分片序号越界: " + partNumber + "（该文件共 " + total + " 片）");
        }

        Path target = partPath(uploadId, fileIndex, partNumber);
        long expected = expectedPartSize(size, chunkSize, total, partNumber);
        try {
            Files.createDirectories(target.getParent());
            try (var in = part.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            long written = Files.size(target);
            if (written != expected) {
                // 落盘长度不符说明这一片是坏的（网络截断 / 客户端切片与 chunkSize 不一致），
                // 必须作废，否则它会带着错误长度混进合并结果
                Files.deleteIfExists(target);
                throw new BizException(400, "分片大小不符：第 " + partNumber + " 片期望 " + expected
                        + " 字节，实际 " + written + " 字节");
            }
        } catch (IOException e) {
            throw new BizException("写入分片失败: " + e.getMessage(), e);
        }

        RBitSet bits = bits(uploadId, fileIndex);
        bits.set(partNumber - 1, true);
        bits.expire(sessionTtl(), TimeUnit.HOURS);
        long received = bits.cardinality();
        return new ChunkPartResponse(fileIndex, partNumber, (int) received, total, received == total);
    }

    // -------------------------------------------------------------- complete

    public UploadResponse complete(String uploadId, String uploadTaskId) {
        RMap<String, String> meta = requireMeta(uploadId);
        int fileCount = Integer.parseInt(meta.get("fileCount"));
        long chunkSize = Long.parseLong(meta.get("chunkSize"));

        // 先校验齐全再合并：半成品一旦走进流水线，失败会落在归一化阶段，排查成本高得多
        for (int i = 0; i < fileCount; i++) {
            int total = Integer.parseInt(meta.get(key(i, "total")));
            List<Integer> missing = missingParts(uploadId, i, total);
            if (!missing.isEmpty()) {
                throw new BizException(409, "分片不完整：文件 " + i + " 缺少 " + missing.size() + " 片（"
                        + preview(missing) + "），续传缺失分片后再提交");
            }
        }

        String progressId = VideoAppService.validUploadTaskId(uploadTaskId);
        publish(progressId, "分片合并与内容校验中");
        String videoId = "v_" + UUID.randomUUID().toString().replace("-", "");
        Path uploadDir = Path.of(props.getFfmpeg().getWorkDir(), "upload", videoId);
        List<VideoAppService.RawUpload> uploads = new ArrayList<>(fileCount);
        try {
            Files.createDirectories(uploadDir);
            for (int i = 0; i < fileCount; i++) {
                uploads.add(assemble(uploadId, i, meta, uploadDir));
            }
        } catch (IOException e) {
            VideoAppService.deleteRecursively(uploadDir);
            throw new BizException("合并上传分片失败: " + e.getMessage(), e);
        }

        // 交给与单次上传相同的入库路径：MD5 去重、上传锁、后台预处理、投递流水线全在这里面。
        // 失败时它自己清理 uploadDir，而分片保留在会话里，客户端补完即可重试 complete。
        UploadResponse resp = videoAppService.submitSpooled(videoId,
                uploads.get(0), uploads.size() > 1 ? uploads.get(1) : null, uploadDir, progressId);
        discard(uploadId);
        log.info("分片上传完成: {} -> {}（命中内容去重: {}）", uploadId, resp.taskId(), resp.duplicated());
        return resp;
    }

    /**
     * 按序合并某文件的全部分片，同一遍读取里算出内容 MD5（与单次上传的指纹口径一致，故跨路径去重可用）。
     * 分片只读不删，故合并失败后会话依然可重试。
     */
    private VideoAppService.RawUpload assemble(String uploadId, int fileIndex,
                                               RMap<String, String> meta, Path uploadDir) throws IOException {
        String name = meta.get(key(fileIndex, "name"));
        String contentType = meta.get(key(fileIndex, "type"));
        int total = Integer.parseInt(meta.get(key(fileIndex, "total")));
        Path target = uploadDir.resolve("file-" + (fileIndex + 1));
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new BizException("计算文件 MD5 失败: " + e.getMessage(), e);
        }
        try (OutputStream out = Files.newOutputStream(target);
             DigestOutputStream digest = new DigestOutputStream(out, md)) {
            for (int n = 1; n <= total; n++) {
                Files.copy(partPath(uploadId, fileIndex, n), digest);
            }
        }
        String md5 = HexFormat.of().formatHex(md.digest());
        log.info("分片合并完成: [{}] {} <- {} 片 / {} MB / md5 {}",
                uploadId, name, total, Files.size(target) / 1024 / 1024, md5);
        return new VideoAppService.RawUpload(name, contentType, target, md5);
    }

    // ----------------------------------------------------------------- 内部

    private ChunkUploadInitResponse describe(String uploadId, long chunkSize, boolean resumed) {
        RMap<String, String> meta = requireMeta(uploadId);
        int fileCount = Integer.parseInt(meta.get("fileCount"));
        List<ChunkUploadInitResponse.FilePlan> plans = new ArrayList<>(fileCount);
        for (int i = 0; i < fileCount; i++) {
            int total = Integer.parseInt(meta.get(key(i, "total")));
            List<Integer> received = receivedParts(uploadId, i, total);
            plans.add(new ChunkUploadInitResponse.FilePlan(i, meta.get(key(i, "name")),
                    Long.parseLong(meta.get(key(i, "size"))), total, received.size(), received));
        }
        return new ChunkUploadInitResponse(uploadId, chunkSize, resumed, plans);
    }

    /** 描述符一致才允许续传：文件名 / 大小 / 分片数任一不同，已落盘的分片都不能再用 */
    private static boolean sameDescriptor(RMap<String, String> meta,
                                         List<ChunkUploadInitRequest.FileSpec> files,
                                         long chunkSize) {
        if (Long.parseLong(meta.get("chunkSize")) != chunkSize) {
            return false;
        }
        if (Integer.parseInt(meta.get("fileCount")) != files.size()) {
            return false;
        }
        for (int i = 0; i < files.size(); i++) {
            ChunkUploadInitRequest.FileSpec f = files.get(i);
            if (!meta.get(key(i, "name")).equals(VideoAppService.sanitizeFileName(f.name()))
                    || Long.parseLong(meta.get(key(i, "size"))) != f.size()) {
                return false;
            }
        }
        return true;
    }

    private List<Integer> receivedParts(String uploadId, int fileIndex, int total) {
        RBitSet bits = bits(uploadId, fileIndex);
        if (bits.cardinality() == 0) {
            return List.of();
        }
        List<Integer> received = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            if (bits.get(i)) {
                received.add(i + 1);
            }
        }
        return received;
    }

    private List<Integer> missingParts(String uploadId, int fileIndex, int total) {
        RBitSet bits = bits(uploadId, fileIndex);
        List<Integer> missing = new ArrayList<>();
        if (bits.cardinality() == total) {
            return missing;
        }
        for (int i = 0; i < total; i++) {
            if (!bits.get(i)) {
                missing.add(i + 1);
            }
        }
        return missing;
    }

    private static String preview(List<Integer> parts) {
        List<Integer> head = parts.size() > MISSING_PREVIEW ? parts.subList(0, MISSING_PREVIEW) : parts;
        return head.toString() + (parts.size() > MISSING_PREVIEW ? " …共 " + parts.size() + " 片" : "");
    }

    /** 会话状态缺失（未 init、已 complete、或被 Redis TTL 回收）一律按同一个语义报错 */
    private RMap<String, String> requireMeta(String uploadId) {
        RMap<String, String> meta = meta(uploadId);
        if (uploadId == null || !meta.isExists()) {
            throw new BizException(404, "分片上传会话不存在或已过期（保留 " + sessionTtl()
                    + " 小时），请重新选择文件上传");
        }
        return meta;
    }

    /** 作废一个会话：Redis 会话指针 / 元数据 / 分片位图 + 已落盘的会话目录 */
    private void discard(String uploadId) {
        RMap<String, String> meta = meta(uploadId);
        String sessionKey = meta.get("sessionKey");
        String fileCount = meta.get("fileCount");
        if (fileCount != null) {
            for (int i = 0; i < Integer.parseInt(fileCount); i++) {
                bits(uploadId, i).delete();
            }
        }
        meta.delete();
        if (sessionKey != null) {
            RBucket<String> session = redisson.getBucket(SESSION_PREFIX + sessionKey);
            if (uploadId.equals(session.get())) {
                session.delete();
            }
        }
        VideoAppService.deleteRecursively(sessionDir(uploadId));
    }

    private long resolveChunkSize(Long requested) {
        long fallback = props.getUpload().getChunkSize().toBytes();
        long size = requested == null ? fallback : requested;
        if (size < MIN_CHUNK_SIZE || size > MAX_CHUNK_SIZE) {
            return Math.min(Math.max(fallback, MIN_CHUNK_SIZE), MAX_CHUNK_SIZE);
        }
        return size;
    }

    private int sessionTtl() {
        return props.getUpload().getSessionTtlHours();
    }

    private RMap<String, String> meta(String uploadId) {
        return redisson.getMap(META_PREFIX + uploadId);
    }

    private RBitSet bits(String uploadId, int fileIndex) {
        return redisson.getBitSet(PARTS_PREFIX + uploadId + ":" + fileIndex);
    }

    private Path sessionDir(String uploadId) {
        return Path.of(props.getFfmpeg().getWorkDir(), "upload-session", uploadId);
    }

    private Path partPath(String uploadId, int fileIndex, int partNumber) {
        return sessionDir(uploadId).resolve(String.valueOf(fileIndex)).resolve("part-" + partNumber);
    }

    private void publish(String uploadProgressId, String message) {
        if (uploadProgressId == null) {
            return;
        }
        try {
            progressService.publish(new ProgressEvent(uploadProgressId, ProcessStage.UPLOADING, 2, message));
        } catch (Exception e) {
            log.debug("分片上传进度推送失败（不影响上传）: {}", e.getMessage());
        }
    }

    private static String key(int fileIndex, String field) {
        return "file" + fileIndex + "." + field;
    }

    private static int totalParts(long size, long chunkSize) {
        return (int) ((size + chunkSize - 1) / chunkSize);
    }

    /** 末片通常短于 chunkSize，其余分片必须正好一个 chunkSize */
    private static long expectedPartSize(long size, long chunkSize, int total, int partNumber) {
        return partNumber < total ? chunkSize : size - chunkSize * (total - 1);
    }

    /** 客户端未给出 MIME 类型时按扩展名兜底（B站 .m4s 在浏览器里 type 常为空） */
    private static String contentTypeOf(String name, String given) {
        if (given != null && !given.isBlank()) {
            return given;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (lower.endsWith(".m4a") || lower.endsWith(".aac")) {
            return "audio/mp4";
        }
        if (lower.endsWith(".mp4") || lower.endsWith(".m4s") || lower.endsWith(".mov")) {
            return "video/mp4";
        }
        if (lower.endsWith(".mkv")) {
            return "video/x-matroska";
        }
        return "application/octet-stream";
    }
}