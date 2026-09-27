package com.course.vsearch.evaluation;

import com.course.vsearch.config.VSearchProperties;
import com.course.vsearch.dto.SearchRequest;
import com.course.vsearch.dto.SearchResult;
import com.course.vsearch.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索评估：Recall@K（正确片段出现在 topK 且时间戳偏差在容差内）
 * 与命中样本的平均时间戳偏差（秒）。
 * <p>
 * 一次 topK=max(ks) 的检索即可推导全部 K：结果排序稳定，Recall@k 只看前 k 名，
 * 故不必对每个 k 重发一次（否则每个 k 都要重复一次 embedding 调用）。
 * <p>
 * 走 {@link SearchService#searchRawRanking}（绕过线上高置信单条截断）测的是**排名质量**；
 * 另在明细里标注命中片段是否会被线上闸门实际交付（best 分低于 min-similarity 时线上只给 1 条），
 * 区分「没排上来」与「排上来了但被闸门截断」两类失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalEvaluator {

    private final SearchService searchService;
    private final VSearchProperties props;

    /**
     * @param scopedToVideo true = 片内检索（请求带 videoId，限定该视频，对应前端选中视频后的主流程）；
     *                      false = 全库检索（跨视频，前端未选中视频时的行为）
     */
    public List<Report> evaluate(List<QaPair> pairs, int[] ks, boolean scopedToVideo) {
        int maxK = 1;
        for (int k : ks) {
            maxK = Math.max(maxK, k);
        }
        int total = pairs.size();
        int[] ranks = new int[total];
        double[] deviations = new double[total];
        boolean[] highConf = new boolean[total];

        for (int i = 0; i < total; i++) {
            QaPair pair = pairs.get(i);
            SearchRequest req = new SearchRequest();
            req.setQuery(pair.query());
            req.setTopK(maxK);
            if (scopedToVideo) {
                req.setVideoId(pair.videoId());
            }
            List<SearchResult> results = searchService.searchRawRanking(req);

            int rank = 0;
            double bestDev = Double.MAX_VALUE;
            for (int r = 0; r < results.size(); r++) {
                SearchResult res = results.get(r);
                if (!pair.videoId().equals(res.getVideoId())) {
                    continue;
                }
                double dev = Math.abs(res.getStartTime() - pair.expectedStart());
                bestDev = Math.min(bestDev, dev);
                if (dev <= pair.toleranceSeconds() && rank == 0) {
                    rank = r + 1;
                }
            }
            ranks[i] = rank;
            deviations[i] = bestDev == Double.MAX_VALUE ? -1 : bestDev;
            highConf[i] = !results.isEmpty()
                    && results.get(0).getScore() >= props.getSearch().getMinSimilarity();
            int lowLimit = props.getSearch().getLowConfidenceLimit();
            // 线上闸门：高置信 topK 全交付；低置信只交付前 lowLimit 条
            boolean gateHides = rank > 0 && !highConf[i] && rank > lowLimit;
            String gate = gateHides
                    ? String.format("（best 低置信，线上只给前 %d 条，用户拿不到第 %d 名）", lowLimit, rank)
                    : "";
            log.info("[评估] 明细 范围={} query=[{}] → 名次={}{}，top1={}",
                    scopedToVideo ? "片内" : "全库", pair.query(),
                    rank == 0 ? "未命中" : String.valueOf(rank), gate, describeTop1(results));
        }

        // 交付口径：套线上软闸门后用户实际拿不拿得到正确片段（@5 排名命中且不被低置信单批截断）
        int lowLimit = props.getSearch().getLowConfidenceLimit();
        int deliveredAt5 = 0;
        for (int i = 0; i < total; i++) {
            if (ranks[i] > 0 && ranks[i] <= 5 && (highConf[i] || ranks[i] <= lowLimit)) {
                deliveredAt5++;
            }
        }
        log.info("[评估] 交付口径 范围={}：线上闸门后用户实际可拿到答案 @1={}/{}，@5={}/{}（低置信给前 {} 条）",
                scopedToVideo ? "片内" : "全库",
                java.util.stream.IntStream.range(0, total).mapToObj(i -> ranks[i] == 1).filter(b -> b).count(),
                total, deliveredAt5, total, lowLimit);

        List<Report> reports = new ArrayList<>(ks.length);
        for (int k : ks) {
            int hits = 0;
            double deviationSum = 0;
            for (int i = 0; i < total; i++) {
                if (ranks[i] > 0 && ranks[i] <= k) {
                    hits++;
                    deviationSum += deviations[i];
                }
            }
            double recall = total == 0 ? 0 : (double) hits / total;
            reports.add(new Report(k, total, hits, recall, hits == 0 ? 0 : deviationSum / hits));
        }
        return reports;
    }

    public record Report(int k, int total, int hits, double recallAtK, double avgDeviationSeconds) {

        public String pretty() {
            double[] ci = wilson(hits, total);
            return String.format("Recall@%d = %d/%d = %.2f%%（95%% Wilson CI [%.2f%%, %.2f%%]），命中样本平均时间戳偏差 = %.1fs",
                    k, hits, total, recallAtK * 100, ci[0] * 100, ci[1] * 100, avgDeviationSeconds);
        }

        /**
         * Wilson 得分区间：小样本比例的置信区间，比正态近似稳健（n 小、比例接近 0/1 时仍给出合理边界）。
         * 报告里必须带上它——n=16 时 Recall@1 与 Recall@3 的区间是重叠的，说明两者排序差异不显著。
         */
        private static double[] wilson(int hits, int total) {
            if (total == 0) {
                return new double[]{0, 1};
            }
            double n = total;
            double p = (double) hits / n;
            double z = 1.96;
            double z2 = z * z;
            double denom = 1 + z2 / n;
            double center = (p + z2 / (2 * n)) / denom;
            double half = z / denom * Math.sqrt(p * (1 - p) / n + z2 / (4 * n * n));
            return new double[]{Math.max(0, center - half), Math.min(1, center + half)};
        }
    }

    /** top1 摘要：为空说明候选被相关性闸门全部滤掉（而非排错名次），是两种「未命中」的关键区别 */
    private static String describeTop1(List<SearchResult> results) {
        if (results.isEmpty()) {
            return "无（候选被相关性闸门全部过滤）";
        }
        SearchResult r = results.get(0);
        return String.format("%s@%.1fs(分=%.3f)", tail(r.getVideoId()), r.getStartTime(), r.getScore());
    }

    private static String tail(String videoId) {
        if (videoId == null) {
            return "-";
        }
        return videoId.length() <= 8 ? videoId : videoId.substring(videoId.length() - 8);
    }
}