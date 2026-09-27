package com.course.vsearch.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.course.vsearch.security.TenantContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;
import java.util.Set;

/**
 * 租户拦截器：给 video / video_segment / video_asr_chunk 的每条 SQL 自动补上 tenant_id 条件。
 * <p>
 * 这样「按 videoId 查片段」「全库列表」「按 md5 去重」等既有 SQL 一行都不用改，
 * 也不会因为将来新加查询忘了写条件而漏数据。
 * <p>
 * 两个例外：
 * <ul>
 *   <li>账号与租户表本身（app_user / tenant）不参与租户过滤，登录才能跨租户找到用户；</li>
 *   <li>{@link TenantContext#isSystem()} 为真时完全不加条件——后台流水线、MQ 消费者、
 *       启动自愈这些路径没有登录态，靠 videoId（全局唯一）定位数据，租户值由它们自己写库。</li>
 * </ul>
 * 注：pgvector 的 &lt;=&gt; 运算符与 unnest(ARRAY[...]) 这类 SQL 交给 JSqlParser 解析有风险，
 * 故检索的两条召回语句显式加了租户参数、并用 @InterceptorIgnore 跳过本拦截器，见 VideoSegmentMapper。
 */
@Configuration
public class MybatisPlusConfig {

    private static final Set<String> GLOBAL_TABLES = Set.of("app_user", "tenant");

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                // 无租户且非 system 时给空串：条件恒不成立（查不到数据），失败关闭而不是全量放开
                return new StringValue(TenantContext.current().orElse(""));
            }

            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }

            @Override
            public boolean ignoreTable(String tableName) {
                return TenantContext.isSystem()
                        || GLOBAL_TABLES.contains(tableName.toLowerCase(Locale.ROOT));
            }
        }));
        return interceptor;
    }
}