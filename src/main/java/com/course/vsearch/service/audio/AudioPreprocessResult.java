package com.course.vsearch.service.audio;

import java.nio.file.Path;
import java.util.List;

/**
 * 音频预处理产物。
 *
 * @param duration 视频/音频总时长（秒）
 * @param audioFile 提取出的 16k 单声道音频
 * @param chunks   按 VAD 静音点切好的 ASR 分块（块的 start/end 即时间戳基线）
 * @param silences VAD 检出的全部静音段（声学话题分段的输入）
 * @param playableMedia 归一化并 remux 成 MP4 的可播放媒体（用于浏览器播放，纯音频亦封进 MP4）
 * @param workDir  本次任务的临时目录，close 时整体清理
 */
public record AudioPreprocessResult(double duration,
                                    Path audioFile,
                                    List<AudioChunk> chunks,
                                    List<Silence> silences,
                                    Path playableMedia,
                                    Path workDir) implements AutoCloseable {

    public record AudioChunk(double start, double end, Path file) {
    }

    public record Silence(double start, double end, double duration) {
    }

    @Override
    public void close() {
        try {
            if (workDir != null) {
                deleteRecursively(workDir.toFile());
            }
        } catch (Exception ignored) {
            // 临时目录清理失败不影响主流程
        }
    }

    private static void deleteRecursively(java.io.File f) {
        if (f.isDirectory()) {
            java.io.File[] children = f.listFiles();
            if (children != null) {
                for (java.io.File c : children) {
                    deleteRecursively(c);
                }
            }
        }
        f.delete();
    }
}
