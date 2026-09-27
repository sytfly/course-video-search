package com.course.vsearch.dto;

import lombok.Data;

import java.util.List;

/**
 * 删除视频的结果。
 * 数据库部分要么全删要么不删；对象存储的清理失败不阻断删除（否则会出现列表里永远删不掉的条目），
 * 失败项列在 warnings 里并带上对象 key，供人工用 mc rm 兜底。
 */
@Data
public class DeleteVideoResponse {
    private String videoId;
    private List<String> warnings;

    public DeleteVideoResponse(String videoId, List<String> warnings) {
        this.videoId = videoId;
        this.warnings = warnings;
    }
}