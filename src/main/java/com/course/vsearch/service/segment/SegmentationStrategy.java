package com.course.vsearch.service.segment;

import com.course.vsearch.config.VSearchProperties;
import com.course.vsearch.service.ai.EmbeddingService;

import java.util.List;

/**
 * 话题分段策略：fixed / semantic / acoustic，三种实现按统一接口对比。
 */
public interface SegmentationStrategy {

    /** 策略标识，落库到 video_segment.strategy，评估时按此区分 */
    String name();

    List<TopicSegment> segment(SegmentContext ctx,
                               VSearchProperties props,
                               EmbeddingService embeddingService);
}
