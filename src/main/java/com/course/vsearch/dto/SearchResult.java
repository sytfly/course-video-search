package com.course.vsearch.dto;

import lombok.Data;

@Data
public class SearchResult {
    private String videoId;
    private String videoName;
    private Integer segmentIndex;
    private double startTime;
    private double endTime;
    private String text;
    private String chapterTitle;
    /** 相关性得分 ∈ [0,1]：语义余弦相似度 + 关键词命中加成，越大越相关 */
    private double score;

    /** 相关性低于高置信阈值（vsearch.search.min-similarity），属于「仅供参考」的候选；前端据此提示 */
    private boolean lowConfidence;
}
