package com.course.vsearch.security;

import com.course.vsearch.util.RandomIds;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 短期访问票据：给「带不了请求头」的两个通道用——浏览器的 &lt;video&gt; 标签与 EventSource。
 * <p>
 * 先用 Authorization 调 {@code POST /api/video/ticket} 换票，再把票据放进 query。
 * 票据与「租户 + 资源 id」绑定，故换到别人的 taskId / videoId 也通不过校验。
 * <p>
 * 有效期 2 小时，与播放用的 MinIO 预签名 URL 对齐：&lt;video&gt; 播放过程中会持续发 Range 请求，
 * 每次都要重新过一遍票据校验，票据若在播放中途过期会导致播放卡死。期内可重复使用（不做一次性核销）：
 * EventSource 断线会自动重连同一个 URL，一次性票据会让重连必然失败。
 * 暴露面由「资源绑定 + 换票时已完成归属校验」收敛，与预签名 URL 同一量级。
 */
@Service
@RequiredArgsConstructor
public class AccessTicketService {

    private static final String PREFIX = "vsearch:ticket:";
    private static final Duration TTL = Duration.ofHours(2);
    private static final char SEPARATOR = '|';

    private final RedissonClient redisson;

    public String issue(String tenantId, String resourceId) {
        String ticket = RandomIds.hex(24);
        redisson.getBucket(PREFIX + ticket)
                .set(tenantId + SEPARATOR + resourceId, TTL);
        return ticket;
    }

    /** 校验票据并返回其绑定的租户；票据不存在 / 已过期 / 与资源不匹配一律返回 null */
    public String verify(String ticket, String resourceId) {
        if (ticket == null || ticket.isBlank() || resourceId == null) {
            return null;
        }
        RBucket<String> bucket = redisson.getBucket(PREFIX + ticket);
        String value = bucket.get();
        if (value == null) {
            return null;
        }
        int at = value.lastIndexOf(SEPARATOR);
        if (at <= 0 || !value.substring(at + 1).equals(resourceId)) {
            return null;
        }
        return value.substring(0, at);
    }
}