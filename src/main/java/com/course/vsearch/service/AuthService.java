package com.course.vsearch.service;

import com.course.vsearch.common.BizException;
import com.course.vsearch.dto.AuthResponse;
import com.course.vsearch.dto.LoginRequest;
import com.course.vsearch.dto.RegisterRequest;
import com.course.vsearch.entity.AppUser;
import com.course.vsearch.entity.Tenant;
import com.course.vsearch.mapper.AppUserMapper;
import com.course.vsearch.mapper.TenantMapper;
import com.course.vsearch.security.JwtService;
import com.course.vsearch.security.TenantContext;
import com.course.vsearch.util.RandomIds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 注册 / 登录。开放注册，不做角色与权限点：一个账号配一个租户，
 * 注册成功即可用，后续所有数据都挂在自己的租户下。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 用户名形态：只允许字母 / 数字 / 下划线 / 点 / 中划线，避免不可见字符造成「看着一样却登不上」 */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]{3,32}$");

    /** 下限 6 位是为了挡住「123456」这类无效口令之外的最基本要求；上限 72 字节是 BCrypt 的输入上限 */
    private static final int MIN_PASSWORD_LENGTH = 6;
    private static final int MAX_PASSWORD_LENGTH = 72;

    private final AppUserMapper userMapper;
    private final TenantMapper tenantMapper;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    public AuthResponse register(RegisterRequest request) {
        String username = requireValidUsername(request == null ? null : request.username());
        String password = requireValidPassword(request == null ? null : request.password());
        if (userMapper.selectByUsername(username) != null) {
            throw new BizException(409, "用户名已被占用，请换一个");
        }

        String tenantId = "t_" + RandomIds.hex(12);
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setName(username + " 的空间");
        tenant.setCreatedAt(LocalDateTime.now());
        tenantMapper.insert(tenant);

        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setTenantId(tenantId);
        user.setCreatedAt(LocalDateTime.now());
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发注册同名：唯一索引兜底
            throw new BizException(409, "用户名已被占用，请换一个");
        }
        log.info("注册成功: {}（租户 {}）", username, tenantId);
        return response(user);
    }

    public AuthResponse login(LoginRequest request) {
        String username = request == null || request.username() == null ? "" : request.username().trim();
        String password = request == null || request.password() == null ? "" : request.password();
        AppUser user = userMapper.selectByUsername(username);
        // 账号不存在与密码错误返回同一句话，避免被用来枚举有哪些账号
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BizException(401, "用户名或密码不正确");
        }
        return response(user);
    }

    /** 当前登录账号：前端用它判断令牌是否还有效（失效会先被过滤链拦成 401） */
    public AuthResponse me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth == null ? null : auth.getName();
        AppUser user = username == null ? null : userMapper.selectByUsername(username);
        if (user == null) {
            throw new BizException(401, "登录状态已失效，请重新登录");
        }
        return response(user);
    }

    private AuthResponse response(AppUser user) {
        return new AuthResponse(
                jwtService.issue(user.getId(), user.getUsername(), user.getTenantId()),
                user.getUsername(), user.getTenantId(), jwtService.ttlSeconds());
    }

    private static String requireValidUsername(String raw) {
        String username = raw == null ? "" : raw.trim();
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new BizException(400, "用户名须为 3~32 位的字母、数字、下划线、点或中划线");
        }
        return username;
    }

    private static String requireValidPassword(String raw) {
        String password = raw == null ? "" : raw;
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new BizException(400, "密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            throw new BizException(400, "密码过长，最多 " + MAX_PASSWORD_LENGTH + " 个字符");
        }
        return password;
    }
}