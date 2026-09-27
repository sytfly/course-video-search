package com.course.vsearch.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class VideoInfoResponse {
    private String videoId;
    private String fileName;
    private BigDecimal duration;
    private Integer status;
    private String errorMsg;
    private String playUrl;
    private Integer segmentCount;
    /** 状态仍是待处理/处理中，但处理方已消失（进度静默且处理锁空闲）——重传同一文件即可复用断点续跑 */
    private Boolean interrupted;
}
