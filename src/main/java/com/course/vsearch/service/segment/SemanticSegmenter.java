package com.course.vsearch.service.segment;

import com.course.vsearch.config.VSearchProperties;
import com.course.vsearch.service.ai.AsrLine;
import com.course.vsearch.service.ai.EmbeddingService;
import com.course.vsearch.util.VectorMath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 语义分段方案（TextTiling 思想的工程化实现）——候选粒度与深度粒度解耦：
 * <p>
 * 1. 深度信号算在<b>块粒度</b>：ASR 块（约 20s）逐块向量化，相邻块余弦做三点滑动平均，
 *    在相似度谷值处取 depth = 左峰深度 + 右峰深度，depth &gt; mean + k*std 才是深度候选边界；
 * 2. 定位信号算在<b>句粒度</b>：块边界由 VAD 决定、常切在句中，故把块拆成句子，
 *    话语标记（「各位同学，接下来…」这类套话）只在句粒度上找，切点落在句首；
 * 3. 两类候选都归一成「边界时间」，按时间升序统一扫描：话语标记满足最小段长即直接切，
 *    深度候选按 depth 竞争；无合格候选时由 max-segment-seconds 强制切，保证不出现超长段。
 * <p>
 * 为什么解耦（2026-09-25 实测，真值 169.11/373.35/603.24/879.26，判据 ±20s 命中/漏切/伪边界）：
 * 句粒度单独用来算深度会退化——逗号切出的短碎片（如「好了，各位同学，」）语义退化成噪声，
 * 在 72.51s 造出假峰，命中从块粒度的 2/4 掉到 1/4；而话语标记又必须靠句粒度才定位得准
 * （块粒度下套话会与前后文粘在同一块里）。故深度回到块粒度、话语标记留在句粒度：
 * 话语标记一个都没命中时，候选只剩深度与块边界，自然退化为「块粒度深度 + 强制窗」兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticSegmenter implements SegmentationStrategy {

    @Override
    public String name() {
        return "semantic";
    }

    /** 候选边界：time 为边界时间，depth 为块粒度深度（话语标记候选不参与深度竞争），cue 标记是否话语标记 */
    private record Candidate(double time, double depth, boolean cue) {
    }

    @Override
    public List<TopicSegment> segment(SegmentContext ctx, VSearchProperties props, EmbeddingService embeddingService) {
        List<AsrLine> blocks = ctx.lines();
        double duration = ctx.duration();
        if (blocks.size() <= 1) {
            return SegmentSupport.buildFromCuts(blocks, List.of(), duration);
        }

        // 0. 句粒度只用于定位话语标记 + 承载输出文本，不参与向量化（深度留在块粒度）
        List<AsrLine> lines = SentenceSplitter.split(blocks);

        // 1. 块粒度深度
        List<String> texts = blocks.stream().map(AsrLine::text).toList();
        List<float[]> vectors = embeddingService.embedBatch(texts);
        int m = vectors.size() - 1;
        double[] sim = new double[m];
        for (int i = 0; i < m; i++) {
            sim[i] = VectorMath.cosine(vectors.get(i), vectors.get(i + 1));
        }
        double[] smoothed = smooth(sim);
        double[] depth = depth(smoothed);
        double mean = mean(depth);
        double std = std(depth, mean);
        double k = props.getSegment().getSemantic().getBoundaryK();
        double threshold = mean + k * std;

        Map<Integer, String> cues = DiscourseMarkerDetector.detect(lines);
        log.info("[{}] 语义分段：{} 块 / {} 句，块间相似度均值 {}，depth 阈值 {} (mean={},std={})，话语标记候选 {} 处",
                ctx.videoId(), blocks.size(), lines.size(), String.format("%.3f", mean(sim)),
                String.format("%.3f", threshold), String.format("%.3f", mean),
                String.format("%.3f", std), cues.size());
        if (log.isDebugEnabled()) {
            // 诊断用：逐个块间 gap 打印深度（gap j 对应边界时间 blocks[j+1].start），
            // 便于离线核对「漏切/误切」是阈值问题还是深度信号本身不足
            StringBuilder sb = new StringBuilder();
            for (double d : depth) {
                sb.append(String.format("%.4f ", d));
            }
            log.debug("[{}] 块粒度深度序列（gap j → blocks[j+1].start）: {}", ctx.videoId(), sb);
            if (!cues.isEmpty()) {
                // 话语标记候选：边界时间[句 gap]命中片段，核对误报来源与下标口径
                StringBuilder cue = new StringBuilder();
                cues.forEach((g, hit) -> cue.append(String.format("%.2f[%d]%s ", lines.get(g + 1).start(), g, hit)));
                log.debug("[{}] 话语标记候选（边界时间[句gap]命中）: {}", ctx.videoId(), cue);
            }
        }

        // 2. 候选合并：块边界（深度）+ 句首（话语标记），按时间升序；同刻话语标记优先
        List<Candidate> candidates = new ArrayList<>(m + cues.size());
        for (int j = 0; j < m; j++) {
            candidates.add(new Candidate(blocks.get(j + 1).start(), depth[j], false));
        }
        cues.keySet().forEach(g -> candidates.add(new Candidate(lines.get(g + 1).start(), 0, true)));
        candidates.sort(Comparator.comparingDouble(Candidate::time)
                .thenComparingInt(c -> c.cue() ? 0 : 1));

        // 3. 段长约束下选边界
        int minSec = props.getSegment().getSemantic().getMinSegmentSeconds();
        int maxSec = props.getSegment().getSemantic().getMaxSegmentSeconds();
        List<Double> cuts = new ArrayList<>();
        double segStart = 0;
        double bestDepth = -1;
        double bestTime = -1;
        for (Candidate c : candidates) {
            double elapsed = c.time() - segStart;
            // 话语标记是讲师显式切换话题的措辞，满足最小段长即直接切。
            // 为什么不只当「高优先级候选」：真值边界间距 169~276s，比 max-segment-seconds(300s)
            // 更密，靠强制切窗每窗只切一次必然漏切（实测尾部 879.26s 处永远轮不到），
            // 且同窗多个候选同分时 tie-break 偏向最早者，会把切点一路带偏。
            if (c.cue() && elapsed >= minSec) {
                cuts.add(c.time());
                segStart = c.time();
                bestDepth = -1;
                bestTime = -1;
                continue;
            }
            if (!c.cue() && elapsed >= minSec && c.depth() >= threshold && c.depth() > bestDepth) {
                bestDepth = c.depth();
                bestTime = c.time();
            }
            if (elapsed >= maxSec) {
                // 窗口内没有合格候选：退回「最接近 segStart+maxSec 的候选」强制切
                double cutTime = bestDepth >= 0 ? bestTime
                        : forceTime(candidates, c.time(), segStart + maxSec, segStart + minSec);
                if (!Double.isNaN(cutTime)) {
                    cuts.add(cutTime);
                    segStart = cutTime;
                    bestDepth = -1;
                    bestTime = -1;
                }
            }
        }
        // 末尾过短的段并入前一段（通过切点回退）
        if (!cuts.isEmpty() && duration - cuts.get(cuts.size() - 1) < minSec) {
            cuts.remove(cuts.size() - 1);
        }
        return SegmentSupport.buildFromCuts(lines, SegmentSupport.toLineCutIndices(lines, cuts), duration);
    }

    /** 在 (lower, upper] 的候选里取最接近 target 的边界时间；无候选返回 NaN */
    private static double forceTime(List<Candidate> candidates, double upper, double target, double lower) {
        double best = Double.NaN;
        double bestDist = Double.MAX_VALUE;
        for (Candidate c : candidates) {
            if (c.time() <= lower || c.time() > upper) {
                continue;
            }
            double dist = Math.abs(c.time() - target);
            if (dist < bestDist) {
                bestDist = dist;
                best = c.time();
            }
        }
        return best;
    }

    /** 从每个谷值向两侧找局部峰，深度 = 左右峰相对谷值的落差之和；端点无峰则贡献 0 */
    private static double[] depth(double[] smoothed) {
        int m = smoothed.length;
        double[] depth = new double[m];
        for (int i = 0; i < m; i++) {
            int l = i;
            while (l > 0 && smoothed[l - 1] >= smoothed[l]) {
                l--;
            }
            int r = i;
            while (r < m - 1 && smoothed[r + 1] >= smoothed[r]) {
                r++;
            }
            depth[i] = Math.max(0, smoothed[l] - smoothed[i])
                    + Math.max(0, smoothed[r] - smoothed[i]);
        }
        return depth;
    }

    private static double[] smooth(double[] x) {
        double[] out = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            double sum = x[i];
            int cnt = 1;
            if (i > 0) {
                sum += x[i - 1];
                cnt++;
            }
            if (i < x.length - 1) {
                sum += x[i + 1];
                cnt++;
            }
            out[i] = sum / cnt;
        }
        return out;
    }

    private static double mean(double[] x) {
        double s = 0;
        for (double v : x) {
            s += v;
        }
        return s / x.length;
    }

    private static double std(double[] x, double mean) {
        double s = 0;
        for (double v : x) {
            s += (v - mean) * (v - mean);
        }
        return Math.sqrt(s / x.length);
    }
}