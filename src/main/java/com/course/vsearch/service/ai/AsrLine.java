package com.course.vsearch.service.ai;

/**
 * 一条带时间戳的 ASR 文本（时间戳来自分块偏移）。
 */
public record AsrLine(double start, double end, String text) {
}
