package com.course.vsearch.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 视频库列表项：回答「我传过哪些视频、分别是什么」。
 * 章节标题由视频内容生成，是辨认视频最有效的信息（文件名往往只是 src.m4s 这类无语义名字）。
 */
@Data
public class VideoListItem {
    private String videoId;
    private String fileName;
    private Integer status;
    /** done / processing / failed */
    private String statusText;
    private BigDecimal duration;
    private Integer segmentCount;
    private LocalDateTime createdAt;
    private String errorMsg;
    /** 章节骨架（标题 + 起止秒），点击可跳转到对应时间 */
    private List<Chapter> chapters;

    @Data
    public static class Chapter {
        private String title;
        private BigDecimal startTime;
        private BigDecimal endTime;
    }
}