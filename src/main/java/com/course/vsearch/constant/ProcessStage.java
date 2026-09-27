package com.course.vsearch.constant;

/** 处理流水线阶段（SSE 推送用） */
public final class ProcessStage {
    /** 上传阶段（落盘/指纹，以及后台的归一化/合并/写对象存储），0~5% */
    public static final String UPLOADING = "uploading";
    public static final String UPLOADED = "uploaded";
    public static final String AUDIO_EXTRACT = "audio_extract";
    public static final String VAD = "vad";
    public static final String ASR = "asr";
    public static final String CORRECT = "correct";
    public static final String SEGMENT = "segment";
    public static final String EMBED = "embed";
    public static final String CHAPTER = "chapter";
    public static final String DONE = "done";
    public static final String FAILED = "failed";

    private ProcessStage() {
    }
}
