package com.course.vsearch.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.course.vsearch.handler.PgVectorTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName(value = "video_segment", autoResultMap = true)
public class VideoSegment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String videoId;
    private Integer segmentIndex;
    private BigDecimal startTime;
    private BigDecimal endTime;
    private String textContent;
    private String correctedText;

    /** 数据归属租户。处理流水线在后台写入，没有登录上下文，故必须显式赋值 */
    private String tenantId;

    @TableField(typeHandler = PgVectorTypeHandler.class)
    private float[] embedding;

    private String chapterTitle;
    /** 产生该片段的分段策略：fixed / semantic / acoustic */
    private String strategy;
    private LocalDateTime createdAt;

    /** 检索时该片段的语义相似度（余弦，越大越相关），非表字段 */
    @TableField(exist = false)
    private Double score;

    /** 检索时被查询词 hit 到的 bigram 个数，非表字段；关键词召回分支才有值 */
    @TableField(exist = false)
    private Integer matchedGrams;
}
