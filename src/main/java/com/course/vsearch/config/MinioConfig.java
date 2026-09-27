package com.course.vsearch.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class MinioConfig {

    private final VSearchProperties props;

    @Bean
    public MinioClient minioClient() {
        VSearchProperties.Minio cfg = props.getMinio();
        MinioClient client = MinioClient.builder()
                .endpoint(cfg.getEndpoint())
                .credentials(cfg.getAccessKey(), cfg.getSecretKey())
                .build();
        ensureBucket(client, cfg.getBucket());
        return client;
    }

    private void ensureBucket(MinioClient client, String bucket) {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("MinIO bucket 已创建: {}", bucket);
            }
        } catch (Exception e) {
            // 启动时 MinIO 未就绪不应阻断应用（基础设施可能晚启动），使用时会再次报错
            log.warn("MinIO bucket 初始化失败（稍后重试）: {}", e.getMessage());
        }
    }
}
