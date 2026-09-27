package com.course.vsearch.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProgressEvent {
    private String taskId;
    /** 阶段：asr / segment / embed / ... */
    private String stage;
    /** 0-100 */
    private int progress;
    private String message;

    public static ProgressEvent of(String taskId, String stage, int progress, String message) {
        return new ProgressEvent(taskId, stage, progress, message);
    }
}
