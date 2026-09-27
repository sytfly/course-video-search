package com.course.vsearch.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Date;

/**
 * 无状态 JWT：令牌里带 userId / tenantId，服务端不存会话，重启与多实例都不受影响。
 * 密钥只从环境变量读（{@code VSEARCH_JWT_SECRET}），不落配置文件、不进日志。
 */
@Slf4j
@Component
public class JwtService {

    private static final String CLAIM_USER_ID = "uid";
    private static final String CLAIM_TENANT_ID = "tid";

    /** 令牌有效期：7 天。个人自用场景下过短只会让用户反复登录，过长则失窃窗口变大 */
    private static final long TTL_SECONDS = 7 * 24 * 3600L;

    private final SecretKey key;

    /** 登录态主体：解析令牌的唯一产物，租户就在里面，无需再查库 */
    public record Principal(Long userId, String username, String tenantId) {
    }

    public JwtService(@Value("${VSEARCH_JWT_SECRET:}") String secret) {
        this.key = Keys.hmacShaKeyFor(resolveKeyBytes(secret));
    }

    private static byte[] resolveKeyBytes(String secret) {
        if (secret == null || secret.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            log.warn("未配置 VSEARCH_JWT_SECRET，本次启动使用随机密钥：进程重启后所有已签发令牌失效，用户需重新登录。"
                    + "部署时请通过环境变量配置一个 32 字节以上的随机串。");
            return random;
        }
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        if (raw.length >= 32) {
            return raw;
        }
        // HMAC-SHA256 要求密钥至少 32 字节：不足时用 SHA-256 归一，避免因一个环境变量配短了就启动失败
        log.warn("VSEARCH_JWT_SECRET 不足 32 字节，已按 SHA-256 归一化使用；建议改成 32 字节以上的随机串。");
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("初始化 JWT 密钥失败", e);
        }
    }

    public String issue(Long userId, String username, String tenantId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_TENANT_ID, tenantId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(TTL_SECONDS)))
                .signWith(key)
                .compact();
    }

    public long ttlSeconds() {
        return TTL_SECONDS;
    }

    /** 校验并解析：令牌非法 / 过期 / 缺租户一律返回 null，不抛异常打扰过滤链 */
    public Principal parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            String tenantId = claims.get(CLAIM_TENANT_ID, String.class);
            if (tenantId == null || tenantId.isBlank()) {
                return null;
            }
            return new Principal(claims.get(CLAIM_USER_ID, Long.class), claims.getSubject(), tenantId);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}