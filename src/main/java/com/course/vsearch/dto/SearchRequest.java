package com.course.vsearch.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SearchRequest {

    @NotBlank(message = "query 不能为空")
    private String query;

    @Min(1)
    @Max(20)
    private int topK = 5;

    /** 可选：限定单个视频；为空则全库检索 */
    private String videoId;
}
