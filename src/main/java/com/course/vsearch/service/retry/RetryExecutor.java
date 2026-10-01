package com.course.vsearch.service.retry;

import com.course.vsearch.config.VSearchProperties;
import com.course.vsearch.observability.BusinessMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 指数退避 + 随机抖动重试。
 * 针对硅基流动 429（限流）/ 503（过载）/ 504 / 网络 IO 异常重试；
 * 其余异常（400 参数错误、401 鉴权）立即失败，不浪费配额。
 *
 * 这里是所有第三方「重试类」调用（ASR、Embedding）的唯一入口，故也是外部调用与重试次数的
 * 单点埋点位置——新增外部调用只要走 execute 就自动被统计，不必在各调用方重复埋点。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryExecutor {

    private final VSearchProperties props;
    private final BusinessMetrics metrics;

    public <T> T execute(String name, Supplier<T> action, Predicate<Throwable> retryable) {
        String api = apiTag(name);
        int max = props.getRetry().getMaxAttempts();
        long base = props.getRetry().getBaseDelayMs();
        long cap = props.getRetry().getMaxDelayMs();

        RuntimeException lastError;
        for (int attempt = 1; attempt <= max; attempt++) {
            try {
                T result = action.get();
                metrics.externalCall(api, "ok");
                return result;
            } catch (RuntimeException e) {
                lastError = e;
                if (!retryable.test(e) || attempt == max) {
                    metrics.externalCall(api, "error");
                    throw e;
                }
                metrics.externalRetry(api);
                long expo = (long) Math.min(cap, base * Math.pow(2, attempt - 1));
                // 全抖动（full jitter）：[0, expo)，避免多实例同时重试打爆服务
                long delay = ThreadLocalRandom.current().nextLong(Math.max(1, expo));
                log.warn("[{}] 第 {} 次调用失败（{}），{}ms 后重试", name, attempt, e.getMessage(), delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("重试等待被中断", ie);
                }
            }
        }
        throw lastError();
    }

    /**
     * 把调用名归一成有限的指标标签：ASR#12 → asr、Embedding[16 篇] → embed。
     * 不能直接用原始 name 当标签值——块下标会变成标签（单视频 60 条时间序列），
     * 直接打爆指标基数，故这里用白名单收敛，未知的一律归 other。
     */
    private static String apiTag(String name) {
        if (name.startsWith("ASR")) {
            return "asr";
        }
        if (name.startsWith("Embedding")) {
            return "embed";
        }
        return "other";
    }

    private static RuntimeException lastError() {
        return new IllegalStateException("重试耗尽");
    }
}
