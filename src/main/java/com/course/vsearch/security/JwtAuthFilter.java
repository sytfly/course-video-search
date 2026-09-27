package com.course.vsearch.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 解析 {@code Authorization: Bearer <jwt>}，写入 SecurityContext 与 {@link TenantContext}。
 * <p>
 * 令牌无效时不做拦截：这里只负责「解析得出就设置」，拒绝由 SecurityFilterChain 的授权规则统一裁决，
 * 保证 401 只有一处出口、返回体格式统一。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            JwtService.Principal principal = jwtService.parse(header.substring(BEARER.length()).trim());
            if (principal != null) {
                TenantContext.set(principal.tenantId());
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        principal.username(), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            // 线程会被容器复用（以及 SSE 异步派发）：必须清干净，避免租户串到下一个请求
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }
}