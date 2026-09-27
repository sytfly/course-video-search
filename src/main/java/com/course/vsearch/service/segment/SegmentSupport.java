package com.course.vsearch.service.segment;

import com.course.vsearch.service.ai.AsrLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 分段策略公共工具：边界时间点 → 行索引切点 → TopicSegment。
 */
final class SegmentSupport {

    private SegmentSupport() {
    }

    /** 每个切点：下一段第一行的起始时间；统一转换成「排他性行索引」 */
    static List<Integer> toLineCutIndices(List<AsrLine> lines, List<Double> boundaryTimes) {
        TreeSet<Integer> cuts = new TreeSet<>();
        int n = lines.size();
        for (double t : boundaryTimes) {
            int idx = lowerBoundByStart(lines, t);
            if (idx > 0 && idx < n) {
                cuts.add(idx);
            }
        }
        return new ArrayList<>(cuts);
    }

    static int lowerBoundByStart(List<AsrLine> lines, double t) {
        int lo = 0;
        int hi = lines.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).start() < t) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    static List<TopicSegment> buildFromCuts(List<AsrLine> lines, List<Integer> cuts, double duration) {
        List<TopicSegment> result = new ArrayList<>();
        int from = 0;
        for (int cut : cuts) {
            if (cut > from) {
                result.add(join(lines, from, cut));
                from = cut;
            }
        }
        if (from < lines.size()) {
            TopicSegment last = join(lines, from, lines.size());
            // 末尾对齐到音频总时长
            last.setEnd(Math.max(last.getEnd(), duration));
            result.add(last);
        }
        return result;
    }

    static TopicSegment join(List<AsrLine> lines, int from, int toExclusive) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < toExclusive; i++) {
            sb.append(lines.get(i).text());
        }
        return new TopicSegment(lines.get(from).start(), lines.get(toExclusive - 1).end(), sb.toString());
    }
}
