package com.course.vsearch.service.storage;

import java.io.InputStream;
import java.time.Duration;

/**
 * 对象存储抽象（当前实现为 MinIO，便于替换）。
 */
public interface StorageService {

    /** 上传对象，返回对象 key */
    String putObject(String objectKey, InputStream in, long size, String contentType);

    InputStream getObject(String objectKey);

    /** 对象是否存在（用于播放时选择归一化版本/回退原始对象） */
    boolean objectExists(String objectKey);

    /** 生成预签名下载/播放地址 */
    String presignedGetUrl(String objectKey, Duration expiry);

    /** 删除对象。对象不存在不算失败（S3 的删除本身是幂等的），便于「删不干净就再删一次」 */
    void removeObject(String objectKey);

    /** 归一化可播放 MP4 的对象 key（与 videoId 绑定，不入库 */
    static String playableKey(String videoId) {
        return "playable/" + videoId + ".mp4";
    }
}
