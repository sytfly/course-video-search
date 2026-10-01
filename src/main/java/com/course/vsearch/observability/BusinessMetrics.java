package com.course.vsearch.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * 业务指标埋点（Prometheus 口径）。
 *
 * 定位：只记录，不参与任何业务判断——指标写失败也不能影响主流程，
 * 故这里不做任何参数校验与异常处理，埋点点位也不得改变原有控制流。
 *
 * 为什么集中在一个类：指标名与标签值是「后续改造的验收口径」（换召回源、换 AI 厂商时靠它做前后对比），
 * 分散在十几个调用点会让口径漂移。所有指标统一 vsearch. 前缀，见 README「可观测性」。
 */
@Component
@RequiredArgsConstructor
public class BusinessMetrics {

    private final MeterRegistry registry;

    // ---------------------------------------------------------------- 检索

    /** 检索端到端计时起点（含 query 向量化 + 两路召回 SQL + 排序 + 闸门） */
    public Timer.Sample startSearch() {
        return Timer.start(registry);
    }

    /**
     * @param entry  api=线上接口 / eval=离线评测通道（searchRawRanking）
     * @param result high=有高置信结果 / low=仅低置信 / empty=空 / raw=评测不计闸门 / error=抛异常
     */
    public void searchFinished(Timer.Sample sample, String entry, String result) {
        sample.stop(Timer.builder("vsearch.search.duration")
                .tag("entry", entry).tag("result", result).register(registry));
    }

    /**
     * 单次检索各路召回的命中条数。source=vector/keyword（两路原始召回）/merged（合并去重后）。
     * 用 DistributionSummary 而不是 Counter：要看的是「每次召回命中多少条」的分布，
     * 加总没有意义（同一片段可能被两路同时召回）。
     */
    public void searchHits(String source, int hits) {
        DistributionSummary.builder("vsearch.search.hits")
                .tag("source", source).register(registry).record(hits);
    }

    /**
     * 单次检索被淘汰的候选条数。reason=floor（低于低地板被丢弃）/truncate（高于地板但被 topK
     * 或低置信条数截断，即交付口径的损失）。
     */
    public void searchDropped(String reason, int dropped) {
        if (dropped <= 0) {
            return;
        }
        DistributionSummary.builder("vsearch.search.dropped")
                .tag("reason", reason).register(registry).record(dropped);
    }

    // ---------------------------------------------------------- 处理流水线

    /**
     * 在给定阶段内执行并记时。异常原样向上抛出（只加观测，不改控制流）。
     * 用 Callable 而非 Supplier：下载源文件、ffmpeg 预处理等阶段会抛受检异常。
     */
    public <T> T stage(String stage, Callable<T> body) throws Exception {
        Timer.Sample sample = Timer.start(registry);
        boolean ok = false;
        try {
            T result = body.call();
            ok = true;
            return result;
        } finally {
            sample.stop(Timer.builder("vsearch.pipeline.stage.duration")
                    .tag("stage", stage).tag("result", ok ? "ok" : "error").register(registry));
        }
    }

    /** 视频任务终态计数。result=success/failed/skipped（skipped=锁被其他实例持有或已完成，幂等跳过） */
    public void task(String result) {
        Counter.builder("vsearch.pipeline.task")
                .tag("result", result).register(registry).increment();
    }

    /**
     * ASR 单块耗时。result=ok/failed/reused。
     * 与 AsrService 日志口径的差异：日志里复用断点的块不计入均值（否则会被大量 0 拉低），
     * 指标里按 result 分开记，既能看真实调用块的长尾，也能看断点复用率。
     */
    public void asrChunk(long millis, String result) {
        Timer.builder("vsearch.asr.chunk.duration")
                .tag("result", result).register(registry).record(Duration.ofMillis(millis));
    }

    // ------------------------------------------------------------ 外部调用

    /** 第三方调用计数。api=asr/embed/chat/other，result=ok/error（每次 execute 记一次，重试不计入） */
    public void externalCall(String api, String result) {
        Counter.builder("vsearch.external.call")
                .tag("api", api).tag("result", result).register(registry).increment();
    }

    /** 触发指数退避重试的次数。api=asr/embed/other */
    public void externalRetry(String api) {
        Counter.builder("vsearch.external.retry")
                .tag("api", api).register(registry).increment();
    }

    /** 令牌桶等待耗时：等待变长即说明 2 permits/s 的配额成了流水线瓶颈 */
    public void rateLimitWait(long millis) {
        Timer.builder("vsearch.ratelimit.wait").register(registry).record(Duration.ofMillis(millis));
    }
}