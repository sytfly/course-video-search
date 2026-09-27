package com.course.vsearch.service.pipeline;

import com.course.vsearch.entity.AppUser;
import com.course.vsearch.entity.Tenant;
import com.course.vsearch.mapper.AppUserMapper;
import com.course.vsearch.mapper.TenantMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 启动时准备默认租户与默认管理员账号。
 * <p>
 * 默认租户 {@code t_default} 是存量视频的归属地（schema.sql 已把老数据回填到它名下）。
 * 新注册的账号各有自己的租户，因此看不到这批存量视频——这正是隔离生效的表现。
 * <p>
 * 默认管理员密码只从环境变量 {@code VSEARCH_DEFAULT_ADMIN_PASSWORD} 读：本文件会进版本库，
 * 任何地方写死口令都等于公开。未配置时只建租户、不建账号（不生成弱口令），并打印明确提示。
 */
@Slf4j
@Component
public class TenantBootstrapRunner implements ApplicationRunner {

    public static final String DEFAULT_TENANT_ID = "t_default";
    private static final String DEFAULT_ADMIN_USERNAME = "admin";

    private final TenantMapper tenantMapper;
    private final AppUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final String adminPassword;

    public TenantBootstrapRunner(TenantMapper tenantMapper,
                                 AppUserMapper userMapper,
                                 PasswordEncoder passwordEncoder,
                                 @Value("${VSEARCH_DEFAULT_ADMIN_PASSWORD:}") String adminPassword) {
        this.tenantMapper = tenantMapper;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureDefaultTenant();
        ensureDefaultAdmin();
    }

    private void ensureDefaultTenant() {
        try {
            if (tenantMapper.selectById(DEFAULT_TENANT_ID) == null) {
                Tenant tenant = new Tenant();
                tenant.setId(DEFAULT_TENANT_ID);
                tenant.setName("默认租户");
                tenant.setCreatedAt(LocalDateTime.now());
                tenantMapper.insert(tenant);
                log.info("已创建默认租户 {}（存量视频归属它）", DEFAULT_TENANT_ID);
            }
        } catch (Exception e) {
            log.warn("默认租户初始化失败（不影响启动）: {}", e.getMessage());
        }
    }

    private void ensureDefaultAdmin() {
        if (adminPassword == null || adminPassword.isBlank()) {
            log.warn("未配置 VSEARCH_DEFAULT_ADMIN_PASSWORD，未创建默认管理员账号：存量视频归属租户 {}，"
                    + "现有一律看不到。如需访问请配置该环境变量后重启，或用 /api/auth/register 注册新账号"
                    + "（新账号只能看到自己上传的数据）", DEFAULT_TENANT_ID);
            return;
        }
        try {
            if (userMapper.selectByUsername(DEFAULT_ADMIN_USERNAME) != null) {
                return;
            }
            AppUser admin = new AppUser();
            admin.setUsername(DEFAULT_ADMIN_USERNAME);
            admin.setPasswordHash(passwordEncoder.encode(adminPassword));
            admin.setTenantId(DEFAULT_TENANT_ID);
            admin.setCreatedAt(LocalDateTime.now());
            userMapper.insert(admin);
            log.info("已创建默认管理员账号 {}（归属租户 {}，密码取自 VSEARCH_DEFAULT_ADMIN_PASSWORD）",
                    DEFAULT_ADMIN_USERNAME, DEFAULT_TENANT_ID);
        } catch (Exception e) {
            log.warn("默认管理员账号创建失败: {}", e.getMessage());
        }
    }
}