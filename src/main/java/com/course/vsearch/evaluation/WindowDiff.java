package com.course.vsearch.evaluation;

import java.util.List;

/**
 * WindowDiff（Pevzner &amp; Hearst, 2002）：分段边界质量指标，越低越好，0 为完全一致。
 * <p>
 * 以固定步长（默认 1s）滑动窗口，比较参考边界与预测边界在每个窗口内的「有无」是否一致，
 * 不一致窗口数 / 窗口总数。
 */
public final class WindowDiff {

    private WindowDiff() {
    }

    /**
     * @param refBoundaries 人工标注边界时间（秒，不含 0 和 duration 端点）
     * @param hypBoundaries 算法预测边界时间（秒）
     * @param duration      音频总时长（秒）
     * @param windowSeconds 评估窗口大小（通常取平均段长）
     */
    public static double evaluate(List<Double> refBoundaries,
                                  List<Double> hypBoundaries,
                                  double duration,
                                  double windowSeconds) {
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("windowSeconds 必须为正数");
        }
        int windows = 0;
        int mismatch = 0;
        for (double t = 0; t + windowSeconds <= duration; t += 1.0) {
            boolean inRef = containsBoundaryIn(refBoundaries, t, t + windowSeconds);
            boolean inHyp = containsBoundaryIn(hypBoundaries, t, t + windowSeconds);
            if (inRef != inHyp) {
                mismatch++;
            }
            windows++;
        }
        return windows == 0 ? 0 : (double) mismatch / windows;
    }

    private static boolean containsBoundaryIn(List<Double> boundaries, double from, double to) {
        for (Double b : boundaries) {
            if (b > from && b <= to) {
                return true;
            }
        }
        return false;
    }
}
