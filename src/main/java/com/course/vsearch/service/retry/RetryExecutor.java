package com.course.vsearch.service.retry;

import com.course.vsearch.config.VSearchProperties;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryExecutor {

    private final VSearchProperties props;

    public <T> T execute(String name, Supplier<T> action, Predicate<Throwable> retryable) {
        int max = props.getRetry().getMaxAttempts();
        long base = props.getRetry().getBaseDelayMs();
        long cap = props.getRetry().getMaxDelayMs();

        RuntimeException lastError;
        for (int attempt = 1; attempt <= max; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                lastError = e;
                if (!retryable.test(e) || attempt == max) {
                    throw e;
                }
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

    private static RuntimeException lastError() {
        return new IllegalStateException("重试耗尽");
    }
}
