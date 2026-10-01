package com.course.vsearch.service.ratelimit;

import com.course.vsearch.observability.BusinessMetrics;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

/**
 * 硅基流动调用的全局限流（令牌桶，Redis 实现，多实例共享配额）。
 * 免费额度有限，默认 2 permits/s，所有 ASR/Embedding/LLM 调用前先 acquire。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiRateLimiter {

    public static final String LIMITER_KEY = "vsearch:ratelimit:siliconflow";

    private final RedissonClient redisson;
    private final com.course.vsearch.config.VSearchProperties props;
    private final BusinessMetrics metrics;

    @PostConstruct
    public void init() {
        RRateLimiter limiter = redisson.getRateLimiter(LIMITER_KEY);
        // setRate 覆盖历史配置，保证以本次启动配置为准
        limiter.setRate(RateType.OVERALL,
                (long) props.getRatelimit().getPermitsPerSecond(),
                1, RateIntervalUnit.SECONDS);
        log.info("令牌桶限流初始化: {} permits/s", props.getRatelimit().getPermitsPerSecond());
    }

    /** 阻塞直到获取一个令牌。等待耗时记入指标：等待变长即说明配额成了流水线瓶颈 */
    public void acquire() {
        long began = System.nanoTime();
        redisson.getRateLimiter(LIMITER_KEY).acquire();
        metrics.rateLimitWait((System.nanoTime() - began) / 1_000_000);
    }
}
