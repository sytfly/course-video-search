package com.course.vsearch.service.ai;

import com.course.vsearch.entity.AsrChunkCheckpoint;
import com.course.vsearch.mapper.AsrChunkCheckpointMapper;
import com.course.vsearch.service.audio.AudioPreprocessResult;
import com.course.vsearch.service.ratelimit.ApiRateLimiter;
import com.course.vsearch.service.retry.RetryExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;

/**
 * 分块 ASR：每块单独识别，块的 start/end 就是该文本的时间戳。
 * 块级并发（asrExecutor）+ 令牌桶限流 + 指数退避重试 + 块级断点续跑。
 */
@Slf4j
@Service
public class AsrService {

    /** SenseVoice 会输出 <|HAPPY|>、<|Speech|>、<|BGM|> 这类富文本标签，检索前剥离 */
    private static final Pattern SENSE_VOICE_TAGS = Pattern.compile("<\\|[A-Z_]+\\|>");

    /** 失败块占比超过该值则整体失败；未超过则跳过失败块，用部分结果继续 */
    private static final double MAX_FAILURE_RATIO = 0.3;

    /** 复用断点时允许的区间偏差（秒）。VAD 边界会随分块参数/版本漂移，容差内视为同一块 */
    private static final double BOUNDARY_TOLERANCE = 0.05;

    private final SiliconFlowClient client;
    private final ApiRateLimiter rateLimiter;
    private final RetryExecutor retryExecutor;
    private final AsrChunkCheckpointMapper checkpointMapper;
    private final ThreadPoolTaskExecutor asrExecutor;

    public AsrService(SiliconFlowClient client,
                      ApiRateLimiter rateLimiter,
                      RetryExecutor retryExecutor,
                      AsrChunkCheckpointMapper checkpointMapper,
                      @Qualifier("asrExecutor") ThreadPoolTaskExecutor asrExecutor) {
        this.client = client;
        this.rateLimiter = rateLimiter;
        this.retryExecutor = retryExecutor;
        this.checkpointMapper = checkpointMapper;
        this.asrExecutor = asrExecutor;
    }

    /**
     * 块级并发识别 + 块级断点续跑。并发完成顺序 != 块顺序，故结果按下标回填数组，
     * 保证文本与时间戳严格对应；单块最终失败不抛出（记 warn 后跳过），
     * 失败占比超过 {@link #MAX_FAILURE_RATIO} 才整体失败。
     *
     * @param videoId  按块读写断点的归属键
     * @param tenantId 数据归属租户：本方法跑在后台线程，没有登录上下文，断点落库必须显式带上
     * @param progress 0-100 的块级进度回调，可为 null；并发下由原子计数保证单调递增
     */
    public List<AsrLine> transcribe(String videoId, String tenantId, AudioPreprocessResult audio,
                                    IntConsumer progress) {
        List<AudioPreprocessResult.AudioChunk> chunks = audio.chunks();
        int n = chunks.size();
        if (n == 0) {
            return List.of();
        }

        Map<Integer, AsrChunkCheckpoint> checkpoints = loadCheckpoints(videoId);
        Run run = new Run(n);

        List<Future<Void>> futures = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            futures.add(asrExecutor.submit(
                    task(videoId, tenantId, i, chunks.get(i), checkpoints, run, n, progress)));
        }

        for (Future<Void> f : futures) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("ASR 并发执行被中断", e);
            } catch (ExecutionException e) {
                // 任务内部已兜住所有异常，正常不会走到这里
                throw new IllegalStateException("ASR 并发执行异常", e.getCause());
            }
        }

        if (run.failed.get() > n * MAX_FAILURE_RATIO) {
            throw new IllegalStateException("ASR 失败块过多: " + run.failed.get() + "/" + n
                    + "（阈值 " + (int) (MAX_FAILURE_RATIO * 100) + "%）");
        }

        logCost(n, run);

        // 按下标顺序组装，保证时间戳单调有序
        List<AsrLine> lines = new ArrayList<>(n);
        for (AsrLine line : run.results) {
            if (line != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    /** 载入该视频已有断点。读表失败不阻断识别，退化为全量重跑。 */
    private Map<Integer, AsrChunkCheckpoint> loadCheckpoints(String videoId) {
        Map<Integer, AsrChunkCheckpoint> map = new HashMap<>();
        try {
            for (AsrChunkCheckpoint c : checkpointMapper.listByVideoId(videoId)) {
                map.put(c.getChunkIndex(), c);
            }
        } catch (Exception e) {
            log.warn("[{}] 读取 ASR 断点失败，本次全量重跑: {}", videoId, e.getMessage());
            return Map.of();
        }
        if (!map.isEmpty()) {
            log.info("[{}] 载入 ASR 断点 {} 块，区间一致的块将直接复用", videoId, map.size());
        }
        return map;
    }

    /**
     * 断点可用性判定：区间必须与本次 VAD 结果一致。
     * 只用 chunk_index 作键不安全——VAD 边界漂移会让同一下标对应不同音频区间，导致时间戳错配。
     */
    private boolean reusable(AsrChunkCheckpoint cp, AudioPreprocessResult.AudioChunk chunk) {
        if (cp == null || cp.getTextContent() == null || cp.getTextContent().isBlank()) {
            return false;
        }
        return Math.abs(cp.getStartTime().doubleValue() - chunk.start()) <= BOUNDARY_TOLERANCE
                && Math.abs(cp.getEndTime().doubleValue() - chunk.end()) <= BOUNDARY_TOLERANCE;
    }

    /** 单块识别任务。异常一律在任务内兜住，绝不逃出任务边界，否则等待方会丢掉全部已完成结果。 */
    private Callable<Void> task(String videoId,
                                String tenantId,
                                int idx,
                                AudioPreprocessResult.AudioChunk chunk,
                                Map<Integer, AsrChunkCheckpoint> checkpoints,
                                Run run,
                                int total,
                                IntConsumer progress) {
        return () -> {
            boolean reused = false;
            long began = System.currentTimeMillis();
            try {
                AsrChunkCheckpoint cp = checkpoints.get(idx);
                if (reusable(cp, chunk)) {
                    // 断点命中：区间与本次 VAD 结果一致，直接复用文本，不再发请求
                    reused = true;
                    run.reused.incrementAndGet();
                    run.results[idx] = new AsrLine(chunk.start(), chunk.end(), cp.getTextContent());
                } else {
                    String raw = retryExecutor.execute(
                            "ASR#" + idx,
                            () -> {
                                rateLimiter.acquire();
                                SiliconFlowClient.AsrResponse resp = client.transcribe(chunk.file());
                                return resp.text() == null ? "" : resp.text();
                            },
                            SiliconFlowClient::isRetryable);
                    String text = clean(raw);
                    if (!text.isBlank()) {
                        run.results[idx] = new AsrLine(chunk.start(), chunk.end(), text);
                        saveCheckpoint(videoId, tenantId, idx, chunk, text);
                    }
                }
            } catch (Throwable t) {
                run.failed.incrementAndGet();
                log.warn("[ASR#{}] 块识别失败，已跳过 [{}s]: {}", idx, chunk.start(), t.getMessage());
            } finally {
                // 复用块不产生 API 耗时，不纳入统计（否则均值会被大量 0 拉低）
                if (!reused) {
                    long cost = System.currentTimeMillis() - began;
                    run.apiCostSum.addAndGet(cost);
                    run.apiCostMax.accumulateAndGet(cost, Math::max);
                }
                int finished = run.done.incrementAndGet();
                if (progress != null) {
                    progress.accept(finished * 100 / total);
                }
                if (finished % 10 == 0 || finished == total) {
                    log.info("ASR 进度 {}/{}", finished, total);
                }
            }
            return null;
        };
    }

    /** 单条立即落库（不包事务）。落库失败只丢该块的续跑能力，不影响本次识别结果。 */
    private void saveCheckpoint(String videoId, String tenantId, int idx,
                                AudioPreprocessResult.AudioChunk chunk, String text) {
        try {
            checkpointMapper.upsert(videoId, idx, seconds(chunk.start()), seconds(chunk.end()), text, tenantId);
        } catch (Exception e) {
            log.warn("[ASR#{}] 断点落库失败，该块无法续跑: {}", idx, e.getMessage());
        }
    }

    /** 耗时汇总：块放大后单块耗时是 responseTimeout 取值的唯一依据 */
    private void logCost(int n, Run run) {
        int reused = run.reused.get();
        int called = n - reused;
        long avg = called == 0 ? 0 : run.apiCostSum.get() / called;
        log.info("ASR 全部完成: {} 块（复用断点 {} 块，实际调用 {} 块，失败 {} 块），"
                        + "单块耗时 平均 {}ms / 最大 {}ms（并发 {}）",
                n, reused, called, run.failed.get(), avg, run.apiCostMax.get(),
                asrExecutor.getCorePoolSize());
    }

    private static BigDecimal seconds(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    /** 单次 transcribe 的共享状态：并发任务按下标回填结果，计数与进度由原子量保证 */
    private static final class Run {
        private final AsrLine[] results;
        private final AtomicInteger done = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private final AtomicInteger reused = new AtomicInteger();
        private final AtomicLong apiCostSum = new AtomicLong();
        private final AtomicLong apiCostMax = new AtomicLong();

        private Run(int n) {
            this.results = new AsrLine[n];
        }
    }

    private String clean(String raw) {
        return SENSE_VOICE_TAGS.matcher(raw).replaceAll("")
                .replaceAll("\\s+", " ")
                .trim();
    }
}