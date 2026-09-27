package com.course.vsearch.dto;

import java.util.List;

/**
 * 分片上传初始化响应。
 *
 * @param uploadId  会话 ID，后续 uploadPart / complete 都要带上
 * @param chunkSize 服务端最终采用的分片大小（客户端必须按它切片，否则末片长度校验不过）
 * @param resumed   是否为复用已有会话：true 说明本次是断点续传，files 里带回了已收到的分片号
 * @param files     各文件的分片计划
 */
public record ChunkUploadInitResponse(String uploadId, long chunkSize, boolean resumed, List<FilePlan> files) {

    /**
     * @param receivedParts 已收齐（已落盘且长度校验通过）的分片序号，从 1 开始；断点续传时客户端只补缺失的片
     */
    public record FilePlan(int index, String name, long size, int totalParts,
                           int receivedCount, List<Integer> receivedParts) {
    }
}