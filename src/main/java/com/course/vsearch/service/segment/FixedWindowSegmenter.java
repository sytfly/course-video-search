package com.course.vsearch.service.segment;

import com.course.vsearch.config.VSearchProperties;
import com.course.vsearch.service.ai.AsrLine;
import com.course.vsearch.service.ai.EmbeddingService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 基线方案：固定时间窗口聚合。实现最简单，但会在话题中间切断，作为对照。
 */
@Component
public class FixedWindowSegmenter implements SegmentationStrategy {

    @Override
    public String name() {
        return "fixed";
    }

    @Override
    public List<TopicSegment> segment(SegmentContext ctx, VSearchProperties props, EmbeddingService embeddingService) {
        int window = props.getSegment().getFixed().getWindowSeconds();
        List<AsrLine> lines = ctx.lines();

        List<Integer> cuts = new ArrayList<>();
        long prevBucket = -1;
        for (int i = 0; i < lines.size(); i++) {
            long bucket = (long) (lines.get(i).start() / window);
            if (prevBucket >= 0 && bucket != prevBucket) {
                cuts.add(i);
            }
            prevBucket = bucket;
        }
        return SegmentSupport.buildFromCuts(lines, cuts, ctx.duration());
    }
}
