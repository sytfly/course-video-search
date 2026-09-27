package com.course.vsearch.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ASR 块级断点：单个音频块识别成功即落库。
 * 任务中途失败后重传同一文件时，区间与本次 VAD 结果一致的旧记录会被直接复用；
 * 区间不一致（VAD 边界漂移）则重算并覆盖，避免时间戳错配。
 */
@Data
@TableName("video_asr_chunk")
public class AsrChunkCheckpoint {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String videoId;
    private Integer chunkIndex;
    private BigDecimal startTime;
    private BigDecimal endTime;
    private String textContent;
    /** 数据归属租户（与 video 行同值；upsert 语句显式写入） */
    private String tenantId;
    private LocalDateTime createdAt;
}