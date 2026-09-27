package com.course.vsearch.service.storage;

import com.course.vsearch.common.BizException;
import com.course.vsearch.config.VSearchProperties;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class MinioStorageService implements StorageService {

    private final MinioClient minioClient;
    private final VSearchProperties props;

    @Override
    public String putObject(String objectKey, InputStream in, long size, String contentType) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(props.getMinio().getBucket())
                    .object(objectKey)
                    .stream(in, size, 32 * 1024 * 1024L)
                    .contentType(contentType)
                    .build());
            log.info("MinIO 上传完成: {}", objectKey);
            return objectKey;
        } catch (Exception e) {
            throw new BizException("MinIO 上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream getObject(String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(props.getMinio().getBucket())
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            throw new BizException("MinIO 下载失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean objectExists(String objectKey) {
        try {
            minioClient.statObject(io.minio.StatObjectArgs.builder()
                    .bucket(props.getMinio().getBucket())
                    .object(objectKey)
                    .build());
            return true;
        } catch (io.minio.errors.ErrorResponseException e) {
            // S3 NoSuchKey / 404 → 不存在；其余错误交给外层
            String code = e.errorResponse() == null ? "" : String.valueOf(e.errorResponse().code());
            if ("NoSuchKey".equals(code)) {
                return false;
            }
            throw new BizException("MinIO 对象查询失败: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new BizException("MinIO 对象查询失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String presignedGetUrl(String objectKey, Duration expiry) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(props.getMinio().getBucket())
                    .object(objectKey)
                    .expiry((int) expiry.getSeconds(), TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            throw new BizException("生成预签名地址失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void removeObject(String objectKey) {
        try {
            minioClient.removeObject(io.minio.RemoveObjectArgs.builder()
                    .bucket(props.getMinio().getBucket())
                    .object(objectKey)
                    .build());
            log.info("MinIO 删除完成: {}", objectKey);
        } catch (Exception e) {
            throw new BizException("MinIO 删除失败: " + e.getMessage(), e);
        }
    }
}
