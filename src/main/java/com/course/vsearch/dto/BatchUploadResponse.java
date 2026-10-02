package com.course.vsearch.dto;

import java.util.List;

/**
 * 批量上传（一条分片会话里装 N 个文件）的合并结果。
 * 服务端按各文件真实的流组成自动分组：纯画面轨 + 纯声音轨配成一对合并，其余各自成组，
 * 每组产出一条视频记录，故返回的是一个列表而不是单个任务。
 *
 * @param items 逐组结果，顺序与文件在会话里的顺序一致
 */
public record BatchUploadResponse(List<Item> items) {

    /**
     * @param fileNames  该组包含的文件名（合并组是两个，单文件组是一个）
     * @param taskId     该组的任务 id（等于 videoId），用于订阅处理进度；该组在进流水线前就被判失败时为 null
     * @param status     任务状态文案（处理中 / 已完成）；该组未进流水线时为 failed
     * @param duplicated 是否命中 MD5 去重（命中时返回已存在的任务，不再重复处理）
     * @param error      该组在进流水线前失败的原因（正常提交时为 null）
     */
    public record Item(List<String> fileNames, String taskId, String status,
                       boolean duplicated, String error) {
    }
}