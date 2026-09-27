package com.course.vsearch.security;

import com.course.vsearch.common.BizException;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 当前线程的数据归属租户。
 * <p>
 * 请求线程由 {@link JwtAuthFilter} 从 JWT 写入；票据鉴权的端点（播放 / 进度流）由调用方显式写入。
 * <p>
 * 后台线程（上传预处理池、处理流水线、RocketMQ 消费者、启动自愈）没有登录态，必须显式用
 * {@link #callAsSystem}/{@link #runAsSystem} 声明「本次按 videoId 直接操作，不加租户条件」，
 * 否则租户拦截器取不到租户、条件恒不成立，会「什么都查不到」。
 * <p>
 * 这里刻意选择失败关闭而不是失败放开：漏声明只会查不到自己的数据并立刻暴露；
 * 反过来（无租户时放开）会把别人的数据放出去，正是本次要杜绝的串台。
 */
public final class TenantContext {

    private static final ThreadLocal<String> TENANT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SYSTEM = new ThreadLocal<>();

    private TenantContext() {
    }

    /** 请求入口写入当前租户 */
    public static void set(String tenantId) {
        TENANT.set(tenantId);
        SYSTEM.remove();
    }

    public static Optional<String> current() {
        return Optional.ofNullable(TENANT.get());
    }

    /** 请求链路里取租户：取不到即视为未登录，直接 401 而不是静默放行 */
    public static String require() {
        String tenantId = TENANT.get();
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(401, "未登录或登录状态已失效");
        }
        return tenantId;
    }

    public static boolean isSystem() {
        return Boolean.TRUE.equals(SYSTEM.get());
    }

    public static void clear() {
        TENANT.remove();
        SYSTEM.remove();
    }

    public static void runAsSystem(Runnable task) {
        callAsSystem(() -> {
            task.run();
            return null;
        });
    }

    /** 后台路径专用：本次执行不看租户、不加租户条件（租户值由业务自己从 video 行取） */
    public static <T> T callAsSystem(Supplier<T> task) {
        String previousTenant = TENANT.get();
        Boolean previousSystem = SYSTEM.get();
        TENANT.remove();
        SYSTEM.set(true);
        try {
            return task.get();
        } finally {
            SYSTEM.remove();
            if (previousSystem != null) {
                SYSTEM.set(previousSystem);
            }
            if (previousTenant != null) {
                TENANT.set(previousTenant);
            }
        }
    }
}