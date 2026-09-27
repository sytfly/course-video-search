package com.course.vsearch.service.correct;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * @param confidence 命中该术语纠错的置信度（0~1）
 * @param aliases    ASR 常见错误写法（中文音译、错误拼写等）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TechTerm(String term, double confidence, List<String> aliases) {
}
