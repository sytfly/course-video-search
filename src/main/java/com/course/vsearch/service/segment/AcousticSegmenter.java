package com.course.vsearch.service.segment;

import com.course.vsearch.config.VSearchProperties;
import com.course.vsearch.service.ai.AsrLine;
import com.course.vsearch.service.ai.EmbeddingService;
import com.course.vsearch.service.audio.AudioPreprocessResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 声学特征方案：以长停顿（超过 pauseSeconds 的静音）作为话题边界候选。
 * 受最小/最大段长约束：窗口内选「最长」静音下刀；窗口内无候选则到点硬切保底。
 */
@Component
public class AcousticSegmenter implements SegmentationStrategy {

    @Override
    public String name() {
        return "acoustic";
    }

    @Override
    public List<TopicSegment> segment(SegmentContext ctx, VSearchProperties props, EmbeddingService embeddingService) {
        VSearchProperties.Acoustic cfg = props.getSegment().getAcoustic();
        double min = cfg.getMinSegmentSeconds();
        double max = cfg.getMaxSegmentSeconds();
        double pause = cfg.getPauseSeconds();
        double duration = ctx.duration();
        List<AsrLine> lines = ctx.lines();

        if (lines.size() <= 1) {
            return SegmentSupport.buildFromCuts(lines, List.of(), duration);
        }

        List<AudioPreprocessResult.Silence> longPauses = ctx.silences().stream()
                .filter(s -> s.duration() >= pause)
                .toList();

        List<Double> cutTimes = new ArrayList<>();
        double segStart = 0;
        while (duration - segStart > max) {
            double lo = segStart + min;
            double hi = segStart + max;
            AudioPreprocessResult.Silence chosen = null;
            for (AudioPreprocessResult.Silence s : longPauses) {
                double t = s.end();
                if (t >= lo && t <= hi) {
                    if (chosen == null || s.duration() > chosen.duration()) {
                        chosen = s;
                    }
                }
            }
            double cutTime = chosen != null ? chosen.end() : segStart + max;
            cutTimes.add(cutTime);
            segStart = cutTime;
        }

        List<Integer> cuts = SegmentSupport.toLineCutIndices(lines, cutTimes);
        return SegmentSupport.buildFromCuts(lines, cuts, duration);
    }
}
