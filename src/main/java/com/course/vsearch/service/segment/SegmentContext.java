package com.course.vsearch.service.segment;

import com.course.vsearch.service.ai.AsrLine;
import com.course.vsearch.service.audio.AudioPreprocessResult;

import java.util.List;

/**
 * 分段输入：带时间戳的 ASR 句子 + 音频时长 + VAD 静音特征。
 */
public record SegmentContext(String videoId,
                             double duration,
                             List<AsrLine> lines,
                             List<AudioPreprocessResult.Silence> silences) {
}
