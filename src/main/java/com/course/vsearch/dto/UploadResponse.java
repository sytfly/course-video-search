package com.course.vsearch.dto;

/**
 * @param duplicated 是否命中 MD5 去重（命中时返回已存在的任务，不再重复处理）
 */
public record UploadResponse(String taskId, String status, boolean duplicated) {
}
