package com.course.vsearch.service.correct;

/**
 * 单次纠错留痕，用于评估「纠错准确率 / 误纠率」。
 *
 * @param rule   canonical=大小写归一, alias=词典别名, fuzzy=拉丁词模糊匹配, phrase=短语级修正
 * @param before 纠错前片段
 * @param term   命中的标准术语
 * @param confidence 置信度
 */
public record CorrectionTrace(String rule, String before, String term, double confidence) {
}
