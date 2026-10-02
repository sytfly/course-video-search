package com.course.vsearch.service;

import com.course.vsearch.common.BizException;
import com.course.vsearch.config.VSearchProperties;
import com.course.vsearch.constant.ProcessStage;
import com.course.vsearch.dto.BatchUploadResponse;
import com.course.vsearch.dto.ChunkPartResponse;
import com.course.vsearch.dto.ChunkUploadInitRequest;
import com.course.vsearch.dto.ChunkUploadInitResponse;
import com.course.vsearch.dto.ProgressEvent;
import com.course.vsearch.dto.UploadResponse;
import com.course.vsearch.security.TenantContext;
import com.course.vsearch.service.audio.MediaSourceNormalizer;
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
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
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
    /** 缺片提示里最多列几个分片号（避免报错信息过长） */
    private static final int MISSING_PREVIEW = 10;
    /**
     * 批次暂存目录名：分片先合并到这里，探测各文件真实流组成并分组后，
     * 再把每组的文件 move 进各自的 upload/&lt;videoId&gt;/（同卷重命名，300MB 级文件不复制数据）。
     */
    private static final String MERGED_DIR = "merged";
    /** 时长偏差比较的浮点容差 */
    private static final double EPS = 1e-9;

    private final RedissonClient redisson;
    private final VSearchProperties props;
    private final ProgressService progressService;
    private final VideoAppService videoAppService;
    private final MediaSourceNormalizer mediaNormalizer;

    public ChunkedUploadService(RedissonClient redisson,
                                VSearchProperties props,
                                ProgressService progressService,
                                VideoAppService videoAppService,
                                MediaSourceNormalizer mediaNormalizer) {
        this.redisson = redisson;
        this.props = props;
        this.progressService = progressService;
        this.videoAppService = videoAppService;
        this.mediaNormalizer = mediaNormalizer;
    }

    // ------------------------------------------------------------------ init

    public ChunkUploadInitResponse init(ChunkUploadInitRequest request) {
        List<ChunkUploadInitRequest.FileSpec> files = request == null ? null : request.files();
        int maxFiles = props.getUpload().getMaxBatchFiles();
        if (files == null || files.isEmpty() || files.size() > maxFiles) {
            throw new BizException(400, "分片上传需要 1~" + maxFiles + " 个文件（完整视频，或 B站 画面轨 + 声音轨）");
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

        RBucket<String> session = redisson.getBucket(sessionPointerKey(TenantContext.require(), sessionKey));
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
        meta.put("tenantId", TenantContext.require());
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

    public BatchUploadResponse complete(String uploadId, String uploadTaskId) {
        RMap<String, String> meta = requireMeta(uploadId);
        int fileCount = Integer.parseInt(meta.get("fileCount"));

        // 先校验齐全再合并：半成品一旦走进流水线，失败会落在归一化阶段，排查成本高得多
        for (int i = 0; i < fileCount; i++) {
            int total = Integer.parseInt(meta.get(key(i, "total")));
            List<Integer> missing = missingParts(uploadId, i, total);
            if (!missing.isEmpty()) {
                throw new BizException(409, "分片不完整：文件 " + i + " 缺少 " + missing.size() + " 片（"
                        + preview(missing) + "），续传缺失分片后再提交");
            }
        }
        ensureDiskSpace(uploadId, meta, fileCount);

        String progressId = VideoAppService.validUploadTaskId(uploadTaskId);
        publish(progressId, "分片合并与内容校验中");
        Path stagingDir = sessionDir(uploadId).resolve(MERGED_DIR);
        List<VideoAppService.RawUpload> uploads = new ArrayList<>(fileCount);
        try {
            Files.createDirectories(stagingDir);
            for (int i = 0; i < fileCount; i++) {
                uploads.add(assemble(uploadId, i, meta, stagingDir));
            }
        } catch (IOException e) {
            VideoAppService.deleteRecursively(stagingDir);
            throw new BizException("合并上传分片失败: " + e.getMessage(), e);
        }

        publish(progressId, "识别画面轨与声音轨并分组");
        List<BatchUploadResponse.Item> items = submitGroups(uploadId, uploads, progressId);
        discard(uploadId);
        log.info("分片上传完成: {} -> {} 组（{} 个文件）", uploadId, items.size(), fileCount);
        return new BatchUploadResponse(items);
    }

    // --------------------------------------------------------------- 分组与提交

    /**
     * 把 N 个已合并的文件按「真实流组成」分组并逐组入库。
     * 规则：纯画面 + 纯声音且时长接近 → 配成一对（沿用现成的双轨合并）；音画俱全或配不上对的 → 各成一组。
     * 单组失败只影响这一组，原因回给客户端，其余组照常提交。
     */
    private List<BatchUploadResponse.Item> submitGroups(String uploadId,
                                                       List<VideoAppService.RawUpload> uploads,
                                                       String progressId) {
        List<Probe> probes = new ArrayList<>(uploads.size());
        for (int i = 0; i < uploads.size(); i++) {
            probes.add(probe(uploadId, i, uploads.get(i)));
        }
        int[] partner = pairTracks(probes);

        List<BatchUploadResponse.Item> items = new ArrayList<>();
        for (int i = 0; i < probes.size(); i++) {
            Probe p = probes.get(i);
            int j = partner[i];
            if (j >= 0) {
                // 配对组只在较小下标处提交一次，保证 items 顺序与文件顺序一致
                if (i < j) {
                    items.add(submitGroup(List.of(p, probes.get(j)), progressId));
                }
                continue;
            }
            if (p.error() != null) {
                items.add(failedItem(List.of(p.name()), p.error()));
                continue;
            }
            if (p.video() && !p.audio()) {
                // 纯画面且没有可配的声音轨：不能放它单体进流水线——单文件路径不做音轨校验，
                // 会先建记录、写对象存储，直到提取音频时才抛 ffmpeg 原始报错，用户无从判断原因
                items.add(failedItem(List.of(p.name()),
                        "这是纯画面文件，没有可用音轨，无法识别语音内容。请把它配套的声音轨一起选上"
                                + "（B站 声音轨文件名通常形如 xxx-1-30280.m4s）；若只有无声录屏，请先录制带讲解声音的视频。"));
                continue;
            }
            items.add(submitGroup(List.of(p), progressId));
        }
        return items;
    }

    /**
     * 探测单个文件真实的流组成与时长。必须先归一化：B站 .m4s 头部带非标准字节，
     * 裸文件 ffprobe 直接报 Invalid data。每份文件独占一个子目录——归一化中间产物按偏移量命名
     * （如 resync-9.bin），两份文件共用目录会互相覆盖。探测副本留在会话目录里，随最后的 discard 回收。
     */
    private Probe probe(String uploadId, int index, VideoAppService.RawUpload upload) {
        Path dir = sessionDir(uploadId).resolve("probe-" + index);
        try {
            Files.createDirectories(dir);
            Path readable = mediaNormalizer.normalize(upload.file(), dir, false);
            MediaSourceNormalizer.Streams s = mediaNormalizer.probeStreams(readable);
            double duration = mediaNormalizer.durationSeconds(readable);
            log.info("批量上传文件 {} 流组成: video={}, audio={}, 时长 {} 秒",
                    upload.name(), s.video(), s.audio(), String.format("%.2f", duration));
            return new Probe(upload, s.video(), s.audio(), duration, null);
        } catch (Exception e) {
            // 单份文件解析不了不该拖垮整批：把它单独标成失败组，其余组继续
            log.warn("批量上传文件 {} 无法解析: {}", upload.name(), e.getMessage());
            return new Probe(upload, false, false, 0,
                    "文件无法解析为音视频（" + e.getMessage() + "），请确认文件完整且未损坏");
        }
    }

    /**
     * 贪心配对：按画面轨时长降序，各自挑一个「时长偏差在容差内且最接近」的声音轨，一个声音轨只配一次。
     * 时长是主判据（实测 B站 的 video_track.m4s 与 src.m4s 没有公共词干，只能靠时长定），
     * 文件名仅在偏差平手时用于裁决。容差沿用合并前一致性校验的同一个阈值，
     * 保证「配对通过」的对一定不会在合并时又被拒。
     */
    private int[] pairTracks(List<Probe> probes) {
        int[] partner = new int[probes.size()];
        Arrays.fill(partner, -1);
        List<Integer> videos = new ArrayList<>();
        List<Integer> audios = new ArrayList<>();
        for (int i = 0; i < probes.size(); i++) {
            Probe p = probes.get(i);
            if (p.error() != null || p.duration() <= 0) {
                continue;
            }
            if (p.video() && !p.audio()) {
                videos.add(i);
            } else if (p.audio() && !p.video()) {
                audios.add(i);
            }
        }
        videos.sort((x, y) -> Double.compare(probes.get(y).duration(), probes.get(x).duration()));

        double allowed = props.getFfmpeg().getMuxMaxDurationDrift();
        boolean[] taken = new boolean[probes.size()];
        for (int v : videos) {
            int best = -1;
            double bestDrift = Double.MAX_VALUE;
            double bestStem = -1;
            for (int a : audios) {
                if (taken[a]) {
                    continue;
                }
                double drift = drift(probes.get(v).duration(), probes.get(a).duration());
                if (drift > allowed) {
                    continue;
                }
                double stem = stemSimilarity(probes.get(v).name(), probes.get(a).name());
                if (drift < bestDrift - EPS || (Math.abs(drift - bestDrift) <= EPS && stem > bestStem)) {
                    best = a;
                    bestDrift = drift;
                    bestStem = stem;
                }
            }
            if (best >= 0) {
                taken[best] = true;
                partner[v] = best;
                partner[best] = v;
                log.info("批量上传配对: 画面轨 {} / 声音轨 {}（时长偏差 {}%）",
                        probes.get(v).name(), probes.get(best).name(),
                        String.format("%.2f", bestDrift * 100));
            }
        }
        return partner;
    }

    /**
     * 把一组文件落成一条视频记录：自建 upload/&lt;videoId&gt;/ → 把文件 move 进去（同卷重命名，不复制数据）
     * → 交给与单次上传完全相同的入库路径（去重、上传锁、后台预处理、投递流水线）。
     * 注意传给 submitSpooled 的必须是原始合并文件：归一化/合并产物是后台预处理自己产出的，
     * 且批次暂存目录稍后会被 discard 清掉。
     */
    private BatchUploadResponse.Item submitGroup(List<Probe> group, String progressId) {
        List<String> names = group.stream().map(Probe::name).toList();
        String videoId = "v_" + UUID.randomUUID().toString().replace("-", "");
        Path uploadDir = Path.of(props.getFfmpeg().getWorkDir(), "upload", videoId);
        try {
            Files.createDirectories(uploadDir);
            List<VideoAppService.RawUpload> moved = new ArrayList<>(group.size());
            for (int i = 0; i < group.size(); i++) {
                VideoAppService.RawUpload src = group.get(i).upload();
                Path target = uploadDir.resolve("file-" + (i + 1));
                Files.move(src.file(), target);
                moved.add(new VideoAppService.RawUpload(src.name(), src.contentType(), target, src.md5()));
            }
            UploadResponse resp = videoAppService.submitSpooled(videoId, moved.get(0),
                    moved.size() > 1 ? moved.get(1) : null, uploadDir, progressId);
            return new BatchUploadResponse.Item(names, resp.taskId(), resp.status(), resp.duplicated(), null);
        } catch (Exception e) {
            // submitSpooled 在失败路径上自己会清 uploadDir，这里只是兜底
            VideoAppService.deleteRecursively(uploadDir);
            log.warn("批量上传分组提交失败 {}: {}", names, e.getMessage());
            return new BatchUploadResponse.Item(names, null, "failed", false, e.getMessage());
        }
    }

    private static BatchUploadResponse.Item failedItem(List<String> names, String error) {
        return new BatchUploadResponse.Item(names, null, "failed", false, error);
    }

    /**
     * 合并前粗查本地可用空间：批次是 N 个文件之和，合并副本又要占掉同等体积，
     * 空间不够时先拒绝并保留会话——分片还在，客户端腾出空间后可直接重试 complete。
     */
    private void ensureDiskSpace(String uploadId, RMap<String, String> meta, int fileCount) {
        long need = 0;
        for (int i = 0; i < fileCount; i++) {
            need += Long.parseLong(meta.get(key(i, "size")));
        }
        try {
            long usable = Files.getFileStore(sessionDir(uploadId)).getUsableSpace();
            if (usable < need) {
                throw new BizException(507, "本地磁盘空间不足：本批共 " + (need / 1024 / 1024) + " MB，可用 "
                        + (usable / 1024 / 1024) + " MB。已上传的分片仍保留，腾出空间后可直接重试提交。");
            }
        } catch (IOException e) {
            // 查不到空间就不阻断：真写不下时 assemble 会抛 IOException，那条路径有明确的错误与清理
            log.warn("查询磁盘可用空间失败，跳过空间预检: {}", e.getMessage());
        }
    }

    /** 两份文件的时长相对偏差；任一时长探不到时返回最大值（即视为不可配对） */
    private static double drift(double a, double b) {
        double max = Math.max(a, b);
        return max <= 0 ? Double.MAX_VALUE : Math.abs(a - b) / max;
    }

    /** 文件名相似度：去扩展名后的公共前缀占比，仅用于时长偏差平手时的裁决 */
    private static double stemSimilarity(String a, String b) {
        String x = stripExtension(a);
        String y = stripExtension(b);
        int limit = Math.min(x.length(), y.length());
        int common = 0;
        while (common < limit && x.charAt(common) == y.charAt(common)) {
            common++;
        }
        int max = Math.max(x.length(), y.length());
        return max == 0 ? 0 : (double) common / max;
    }

    private static String stripExtension(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String base = slash >= 0 ? name.substring(slash + 1) : name;
        int dot = base.lastIndexOf('.');
        return (dot > 0 ? base.substring(0, dot) : base).toLowerCase(Locale.ROOT);
    }

    /** 单个文件的探测结果：error 非空表示这份文件解析不了，不参与配对 */
    private record Probe(VideoAppService.RawUpload upload, boolean video, boolean audio,
                         double duration, String error) {
        String name() {
            return upload.name();
        }
    }

    /**
     * 按序合并某文件的全部分片，同一遍读取里算出内容 MD5（与单次上传的指纹口径一致，故跨路径去重可用）。
     * 分片只读不删，故合并失败后会话依然可重试。
     */
    private VideoAppService.RawUpload assemble(String uploadId, int fileIndex,
                                               RMap<String, String> meta, Path targetDir) throws IOException {
        String name = meta.get(key(fileIndex, "name"));
        String contentType = meta.get(key(fileIndex, "type"));
        int total = Integer.parseInt(meta.get(key(fileIndex, "total")));
        Path target = targetDir.resolve("file-" + (fileIndex + 1));
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

    /** 会话状态缺失（未 init、已 complete、或被 Redis TTL 回收）一律按同一个语义报错。
     * uploadId 由客户端提供，故必须校验会话归属：拿别人的 uploadId 也一律「不存在」。 */
    private RMap<String, String> requireMeta(String uploadId) {
        RMap<String, String> meta = meta(uploadId);
        if (uploadId == null || !meta.isExists() || !TenantContext.require().equals(meta.get("tenantId"))) {
            throw new BizException(404, "分片上传会话不存在或已过期（保留 " + sessionTtl()
                    + " 小时），请重新选择文件上传");
        }
        return meta;
    }

    /** 作废一个会话：Redis 会话指针 / 元数据 / 分片位图 + 已落盘的会话目录 */
    private void discard(String uploadId) {
        RMap<String, String> meta = meta(uploadId);
        String tenantId = meta.get("tenantId");
        String sessionKey = meta.get("sessionKey");
        String fileCount = meta.get("fileCount");
        if (fileCount != null) {
            for (int i = 0; i < Integer.parseInt(fileCount); i++) {
                bits(uploadId, i).delete();
            }
        }
        meta.delete();
        if (tenantId != null && sessionKey != null) {
            RBucket<String> session = redisson.getBucket(sessionPointerKey(tenantId, sessionKey));
            if (uploadId.equals(session.get())) {
                session.delete();
            }
        }
        VideoAppService.deleteRecursively(sessionDir(uploadId));
    }

    /**
     * 会话指针 key：以租户为前缀。sessionKey 由客户端按「文件名 + 大小 + 修改时间」算出，
     * 不同租户传同一个文件会得到同一个 sessionKey，不加租户前缀就会互相复用对方的会话。
     */
    private static String sessionPointerKey(String tenantId, String sessionKey) {
        return SESSION_PREFIX + tenantId + ":" + sessionKey;
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