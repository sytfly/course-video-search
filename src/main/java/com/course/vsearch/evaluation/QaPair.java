package com.course.vsearch.evaluation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 检索评估标注：query → 正确答案所在（视频，秒），容差内算命中。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QaPair(String query, String videoId, double expectedStart, double toleranceSeconds) {
}
