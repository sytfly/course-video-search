package com.course.vsearch.service.segment;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 话题分段结果（尚未纠错/向量化）。
 */
@Data
@AllArgsConstructor
public class TopicSegment {
    private double start;
    private double end;
    private String text;
}
