package com.course.vsearch.service.pipeline;

import com.course.vsearch.constant.ProcessStage;
import com.course.vsearch.constant.VideoStatus;
import com.course.vsearch.dto.ProgressEvent;
import com.course.vsearch.entity.Video;
import com.course.vsearch.entity.VideoSegment;
import com.course.vsearch.mapper.VideoMapper;
import com.course.vsearch.mapper.VideoSegmentMapper;
import com.course.vsearch.service.ai.AsrLine;
import com.course.vsearch.service.ai.AsrService;
import com.course.vsearch.service.ai.EmbeddingService;
import com.course.vsearch.service.audio.AudioPreprocessResult;
import com.course.vsearch.service.audio.AudioPreprocessService;
import com.course.vsearch.service.chapter.ChapterTitleService;
import com.course.vsearch.service.correct.CorrectionResult;
import com.course.vsearch.service.correct.TerminologyService;
import com.course.vsearch.service.progress.ProgressService;
import com.course.vsearch.service.segment.SegmentContext;
import com.course.vsearch.service.segment.SegmentationService;
import com.course.vsearch.service.segment.TopicSegment;
import com.course.vsearch.service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 视频处理流水线总编排：
 * 下载 → 音频提取/VAD → 分块 ASR → 术语纠错 → 话题分段 → 段向量化 → 章节标题 → 入库。
 * Redisson 锁保证同一视频不会被 MQ 重复投递/多实例并发处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoProcessService {

    private static final String LOCK_PREFIX = "vsearch:lock:process:";

    /** 处理锁 key。上传侧判孤儿任务也要用，故对外暴露，避免 key 格式两处维护 */
    public static String processLockKey(String videoId) {
        return LOCK_PREFIX + videoId;
    }

    private final VideoMapper videoMapper;
    private final VideoSegmentMapper segmentMapper;
    private final StorageService storage;
    private final AudioPreprocessService audioPreprocess;
    private final AsrService asrService;
    private final TerminologyService terminologyService;
    private final SegmentationService segmentationService;
    private final EmbeddingService embeddingService;
    private final ChapterTitleService chapterTitleService;
    private final ProgressService progressService;
    private final InFlightTaskRegistry inFlightRegistry;
    private final RedissonClient redisson;

    public void process(String videoId) {
        RLock lock = redisson.getLock(processLockKey(videoId));
        boolean locked;
        try {
            // 看门狗模式（leaseTime = -1，由 Redisson 每 10s 自动续约）：
            // 1) 进程存活则锁永不过期，避免固定租约在长任务上提前过期导致重复消费；
            // 2) 进程被强杀后续约停止，锁在 lockWatchdogTimeout(30s) 内自动释放，
            //    上传侧据此判定"状态说在处理、实际无人处理"的孤儿任务并重新入队。
            locked = lock.tryLock(0, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!locked) {
            log.info("视频正在被其他实例处理，跳过: {}", videoId);
            return;
        }

        Video video = videoMapper.selectByVideoId(videoId);
        if (video == null) {
            // 任务已不存在（被删除），本进程不再持有它，登记表要一并释放
            inFlightRegistry.clear(videoId);
            lock.unlock();
            throw new IllegalStateException("视频不存在: " + videoId);
        }
        if (video.getStatus() == VideoStatus.DONE) {
            log.info("视频已处理完成，幂等跳过: {}", videoId);
            inFlightRegistry.clear(videoId);
            lock.unlock();
            return;
        }

        Path sourceFile = null;
        try {
            markStatus(video, VideoStatus.PROCESSING, null);
            publish(videoId, ProcessStage.UPLOADED, 5, "开始处理");

            sourceFile = downloadSource(video);
            publish(videoId, ProcessStage.AUDIO_EXTRACT, 8, "提取音频");

            try (AudioPreprocessResult audio = audioPreprocess.prepare(sourceFile, videoId)) {
                publish(videoId, ProcessStage.VAD, 15,
                        "VAD 完成，" + audio.silences().size() + " 个静音段，"
                                + audio.chunks().size() + " 个识别分块");

                // 回写归一化可播放 MP4（带浏览器友好的 Content-Type），失败不阻断识别主流程
                try {
                    String playableKey = StorageService.playableKey(videoId);
                    try (InputStream pin = Files.newInputStream(audio.playableMedia())) {
                        storage.putObject(playableKey, pin, Files.size(audio.playableMedia()), "video/mp4");
                    }
                } catch (Exception pe) {
                    log.warn("[{}] 可播放版本回写失败，播放将回退原始对象: {}", videoId, pe.getMessage());
                }

                // 1. 分块 ASR（15% -> 65%）
                List<AsrLine> rawLines = asrService.transcribe(videoId, audio,
                        p -> publish(videoId, ProcessStage.ASR, 15 + (int) (p * 0.50),
                                "语音识别中 " + p + "%"));
                if (rawLines.isEmpty()) {
                    throw new IllegalStateException("ASR 未识别到任何语音内容");
                }

                // 2. 术语纠错（65% -> 72%）
                List<AsrLine> lines = new ArrayList<>(rawLines.size());
                int correctedCount = 0;
                for (AsrLine line : rawLines) {
                    CorrectionResult r = terminologyService.correct(line.text());
                    if (r.changed()) {
                        correctedCount++;
                    }
                    lines.add(new AsrLine(line.start(), line.end(), r.corrected()));
                }
                log.info("[{}] 术语纠错完成，{}/{} 句发生修正", videoId, correctedCount, lines.size());
                publish(videoId, ProcessStage.CORRECT, 72, "术语纠错完成");

                // 3. 话题分段（72% -> 80%）
                SegmentContext ctx = new SegmentContext(videoId, audio.duration(),
                        lines, audio.silences());
                List<TopicSegment> topics = segmentationService.segment(ctx);
                publish(videoId, ProcessStage.SEGMENT, 80, "话题分段完成，" + topics.size() + " 段");

                // 4. 段向量化（80% -> 90%）
                List<String> texts = topics.stream().map(TopicSegment::getText).toList();
                List<float[]> vectors = embeddingService.embedBatch(texts);
                publish(videoId, ProcessStage.EMBED, 90, "向量化完成");

                // 5. 章节标题 + 落库（90% -> 99%）
                String strategy = segmentationService.currentStrategy().name();
                segmentMapper.deleteByVideoId(videoId);
                for (int i = 0; i < topics.size(); i++) {
                    TopicSegment seg = topics.get(i);
                    String title = chapterTitleService.generateTitle(seg.getText());
                    int percent = 90 + (int) (9.0 * (i + 1) / Math.max(1, topics.size()));
                    publish(videoId, ProcessStage.CHAPTER, percent,
                            "生成章节标题 " + (i + 1) + "/" + topics.size());

                    VideoSegment entity = new VideoSegment();
                    entity.setVideoId(videoId);
                    entity.setSegmentIndex(i);
                    entity.setStartTime(seconds(seg.getStart()));
                    entity.setEndTime(seconds(seg.getEnd()));
                    entity.setTextContent(seg.getText());
                    entity.setCorrectedText(seg.getText());
                    entity.setEmbedding(vectors.get(i));
                    entity.setChapterTitle(title);
                    entity.setStrategy(strategy);
                    segmentMapper.insert(entity);
                }

                // 6. 完成
                video.setStatus(VideoStatus.DONE);
                video.setDuration(seconds(audio.duration()));
                video.setErrorMsg(null);
                video.setUpdatedAt(LocalDateTime.now());
                videoMapper.updateById(video);
                publish(videoId, ProcessStage.DONE, 100, "处理完成");
                log.info("[{}] 处理完成，共 {} 个片段", videoId, topics.size());
            }
        } catch (Exception e) {
            markFailed(videoId, video, e.getClass().getSimpleName(), e.getMessage(), e);
        } catch (Throwable t) {
            // Error（如 LinkageError/类加载问题）也必须落 FAILED，否则任务永久卡 PROCESSING
            markFailed(videoId, video, t.getClass().getSimpleName(), t.getMessage(), t);
            if (t instanceof Error err) {
                throw err;
            }
        } finally {
            // 本进程不再持有该任务：此后若状态仍未完成且锁空闲，就是真的无人处理（可判 interrupted）
            inFlightRegistry.clear(videoId);
            if (sourceFile != null) {
                try {
                    Files.deleteIfExists(sourceFile);
                } catch (Exception ignored) {
                    // 删除临时源文件失败不影响结果
                }
            }
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private Path downloadSource(Video video) throws Exception {
        String suffix = "";
        String fileName = video.getFileName();
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0) {
            suffix = fileName.substring(dot);
        }
        Path dir = Path.of("./data/ffmpeg", video.getVideoId());
        Files.createDirectories(dir);
        Path target = dir.resolve("source" + suffix);
        try (InputStream in = storage.getObject(video.getMinioUrl())) {
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private void markStatus(Video video, int status, String errorMsg) {
        video.setStatus(status);
        video.setErrorMsg(errorMsg);
        video.setUpdatedAt(LocalDateTime.now());
        videoMapper.updateById(video);
    }

    /** 统一失败收口：日志、落 FAILED（截断 1000 字）、推送失败进度 */
    private void markFailed(String videoId, Video video, String errorType,
                            String rawMessage, Throwable cause) {
        log.error("[{}] 视频处理失败: {}", videoId, errorType, cause);
        String msg = errorType + ": " + (rawMessage == null ? "" : rawMessage);
        if (msg.length() > 1000) {
            msg = msg.substring(0, 1000);
        }
        markStatus(video, VideoStatus.FAILED, msg);
        publish(videoId, ProcessStage.FAILED, 100, "处理失败: " + msg);
    }

    private void publish(String videoId, String stage, int percent, String message) {
        progressService.publish(ProgressEvent.of(videoId, stage, percent, message));
    }

    private static BigDecimal seconds(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
