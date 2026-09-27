package com.course.vsearch.dto;

import java.util.List;

/**
 * 分片上传初始化请求。
 *
 * @param sessionKey 客户端按「文件名 + 大小 + 修改时间」算出的稳定指纹。服务端据此复用同一会话，
 *                   刷新页面 / 断网重连后用同一批文件再 init 即可拿回已上传的分片号，只补缺失的片——
 *                   这是断点续传的服务端依据（会话状态存 Redis，24h 内有效）
 * @param chunkSize  客户端期望的分片大小（字节，可空；越界时服务端取默认值并按响应里的值切片）
 * @param files      1~2 个文件（完整视频，或 B站 画面轨 + 声音轨）
 */
public record ChunkUploadInitRequest(String sessionKey, Long chunkSize, List<FileSpec> files) {

    /**
     * @param name        原始文件名
     * @param size        文件总字节数（用于算总分片数、校验末片长度）
     * @param contentType MIME 类型（可空，服务端按扩展名兜底）
     */
    public record FileSpec(String name, long size, String contentType) {
    }
}