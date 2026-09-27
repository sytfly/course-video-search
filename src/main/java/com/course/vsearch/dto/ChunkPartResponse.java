package com.course.vsearch.dto;

/**
 * 单个分片的上传结果（同一分片重传幂等覆盖，用于失败重试）。
 *
 * @param receivedCount 该文件已收到的分片数
 * @param fileComplete  该文件的分片是否已全部收齐
 */
public record ChunkPartResponse(int fileIndex, int partNumber, int receivedCount,
                                int totalParts, boolean fileComplete) {
}