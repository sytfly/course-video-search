package com.course.vsearch.service.lock;

import com.course.vsearch.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Redisson 分布式锁：上传时按 MD5 加锁，防止同一文件并发重复提交与重复消费。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DistributedLockService {

    private final RedissonClient redisson;

    /**
     * @return true=拿到锁并执行；false=锁被占用（重复提交）
     */
    public <T> T tryExecute(String lockKey, Duration waitTime, Duration leaseTime,
                            Supplier<T> action, Supplier<T> onLockBusy) {
        RLock lock = redisson.getLock(lockKey);
        boolean locked = false;
        try {
            locked = lock.tryLock(waitTime.toMillis(), leaseTime.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!locked) {
                return onLockBusy.get();
            }
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("获取分布式锁被中断: " + lockKey, e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 锁是否正被任意实例持有。用于判定"状态声称在处理、但实际无人处理"的孤儿任务：
     * 处理锁走看门狗模式，进程被强杀后 30 秒内自动释放，故锁空闲即代表持有者已消失。
     */
    public boolean isLocked(String lockKey) {
        return redisson.getLock(lockKey).isLocked();
    }
}
