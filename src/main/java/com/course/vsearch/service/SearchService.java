package com.course.vsearch.service;

import com.course.vsearch.config.VSearchProperties;
import com.course.vsearch.config.VSearchProperties.Search;
import com.course.vsearch.dto.SearchRequest;
import com.course.vsearch.dto.SearchResult;
import com.course.vsearch.entity.Video;
import com.course.vsearch.entity.VideoSegment;
import com.course.vsearch.handler.PgVectorTypeHandler;
import com.course.vsearch.mapper.VideoMapper;
import com.course.vsearch.mapper.VideoSegmentMapper;
import com.course.vsearch.service.ai.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 语义搜索：
 * - vector：query → bge-m3 → pgvector 余弦 topK；
 * - hybrid：向量召回 ∪ 关键词（CJK bigram / 拉丁词 ILIKE）召回，统一按语义相关性排序。
 *
 * 相关性（SearchResult.score，0~1）＝ 余弦相似度 ＋ keywordBoost × 关键词命中率（上限 keywordBoost）。
 * 两档软闸门：>= vsearch.search.min-similarity 视为高置信，按 topK 正常返回；
 * 否则（[min-similarity-floor, min-similarity)）只返回最接近的 1 条并标记 lowConfidence；
 * < floor 一律不返回（返回空代表确实没有相关内容）。
 * 之所以不再用 RRF 分数对外暴露：Σ1/(k+rank) 与查询内容无关（top1 恒为 0.016），
 * 既无法解释也无法设阈值，会把真实语义相似度覆盖掉。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private final EmbeddingService embeddingService;
    private final VideoSegmentMapper segmentMapper;
    private final VideoMapper videoMapper;
    private final VSearchProperties props;

    public List<SearchResult> search(SearchRequest request) {
        List<VideoSegment> ranked = rankCandidates(request);
        Search cfg = props.getSearch();

        boolean highConfidence = !ranked.isEmpty() && ranked.get(0).getScore() >= cfg.getMinSimilarity();
        // 高置信按请求 topK 正常交付；低置信只给前 lowConfidenceLimit 条弱展示（全部打 lowConfidence 标记，
        // 前端逐条徽标 + 整批提示）——实测 5/20 条正确答案排在第 2/3 名，单条截断会把答案截没。
        // lowConfidenceLimit 同时受 topK 约束：请求只要 1 条时不会多给。
        int limit = highConfidence
                ? request.getTopK()
                : Math.min(cfg.getLowConfidenceLimit(), request.getTopK());
        List<SearchResult> results = ranked.stream()
                .limit(limit)
                .map(seg -> toResult(seg, cfg.getMinSimilarity()))
                .toList();

        if (results.isEmpty()) {
            log.info("检索无结果：query=[{}]，候选全部低于低地板 {}",
                    request.getQuery(), cfg.getMinSimilarityFloor());
        } else if (!highConfidence) {
            log.info("检索无高置信结果：query=[{}]，best={}（低于 {}），返回前 {} 条低置信候选",
                    request.getQuery(), String.format("%.3f", results.get(0).getScore()), cfg.getMinSimilarity(),
                    results.size());
        }
        return results;
    }

    /**
     * 评估专用：跳过高置信单条截断，返回 floor 之上的完整 topK 排名。
     * 为什么线上 {@link #search} 不能直接用于 Recall@K：best 分落在 [floor, min-similarity) 时
     * 线上只交付 1 条，Recall@3/@5 会被结构性地压成与 Recall@1 相同；评测要回答的是
     * 「正确片段在排第几」与「闸门会不会把它交付出去」两件不同的事，故排名与闸门分开测。
     */
    public List<SearchResult> searchRawRanking(SearchRequest request) {
        return rankCandidates(request).stream()
                .limit(request.getTopK())
                .map(seg -> toResult(seg, props.getSearch().getMinSimilarity()))
                .toList();
    }

    /** 双路召回 → 相关性打分 → floor 过滤 → 按分排序；不做高置信截断 */
    private List<VideoSegment> rankCandidates(SearchRequest request) {
        float[] queryVector = embeddingService.embed(request.getQuery());
        String vectorLiteral = PgVectorTypeHandler.toPgLiteral(queryVector);
        int pool = Math.max(props.getSearch().getCandidatePool(), request.getTopK());

        List<VideoSegment> vectorHits = segmentMapper.vectorTopN(
                vectorLiteral, request.getVideoId(), pool);

        List<VideoSegment> candidates;
        List<String> grams = List.of();
        if ("hybrid".equalsIgnoreCase(props.getSearch().getMode())) {
            grams = extractGrams(request.getQuery());
            List<VideoSegment> keywordHits = grams.isEmpty()
                    ? List.of()
                    : segmentMapper.keywordTopN(grams, vectorLiteral, request.getVideoId(), pool);
            candidates = merge(vectorHits, keywordHits);
            log.debug("混合检索：向量 {}，关键词 {}，候选 {}",
                    vectorHits.size(), keywordHits.size(), candidates.size());
        } else {
            candidates = vectorHits;
        }

        Search cfg = props.getSearch();
        int gramCount = grams.size();
        return candidates.stream()
                .peek(seg -> seg.setScore(relevance(seg, gramCount, cfg.getKeywordBoost())))
                .filter(seg -> seg.getScore() >= cfg.getMinSimilarityFloor())
                .sorted(Comparator.comparingDouble(VideoSegment::getScore).reversed())
                .toList();
    }

    /**
     * 合并两路召回：同一片段的语义分一致，但关键词分支额外带 matchedGrams，
     * 故以关键词分支的实例为准（先放关键词、向量分支只补漏）。
     */
    private List<VideoSegment> merge(List<VideoSegment> vectorHits, List<VideoSegment> keywordHits) {
        Map<Long, VideoSegment> byId = new LinkedHashMap<>();
        keywordHits.forEach(s -> byId.put(s.getId(), s));
        vectorHits.forEach(s -> byId.putIfAbsent(s.getId(), s));
        return new ArrayList<>(byId.values());
    }

    /**
     * 片段对一个查询的相关性 ∈ [0, 1]：
     * 语义余弦打底（查询改写、同义表达都能召回），关键词命中率给小幅加成
     * （缩写与专有术语如 RDB/AOF 常被纯语义低估，原文精确命中即视为强证据；
     * 命中率 = 命中的 bigram 数 / 查询切出的 bigram 总数，命中越多加成越接近上限）。
     */
    private double relevance(VideoSegment seg, int gramCount, double boost) {
        double cosine = Math.max(0.0, seg.getScore() == null ? 0 : seg.getScore());
        if (gramCount == 0 || seg.getMatchedGrams() == null) {
            return Math.min(1.0, cosine);
        }
        double hitRate = Math.min(1.0, seg.getMatchedGrams() / (double) gramCount);
        return Math.min(1.0, cosine + boost * hitRate);
    }

    private SearchResult toResult(VideoSegment seg, double highConfidenceThreshold) {
        SearchResult r = new SearchResult();
        r.setVideoId(seg.getVideoId());
        Video video = videoMapper.selectByVideoId(seg.getVideoId());
        if (video != null) {
            r.setVideoName(video.getFileName());
        }
        r.setSegmentIndex(seg.getSegmentIndex());
        r.setStartTime(seg.getStartTime().doubleValue());
        r.setEndTime(seg.getEndTime().doubleValue());
        r.setText(seg.getTextContent());
        r.setChapterTitle(seg.getChapterTitle());
        double score = seg.getScore() == null ? 0 : seg.getScore();
        r.setScore(score);
        r.setLowConfidence(score < highConfidenceThreshold);
        return r;
    }

    /**
     * 查询分词（用于 ILIKE 关键词召回）：
     * 拉丁词原样小写保留（Redis、Kafka）；CJK 连续片段做相邻字 bigram（持久、久化）。
     */
    static List<String> extractGrams(String query) {
        Set<String> grams = new HashSet<>();
        int i = 0;
        while (i < query.length()) {
            char c = query.charAt(i);
            if (isLatinStart(c)) {
                int j = i + 1;
                while (j < query.length() && isLatinPart(query.charAt(j))) {
                    j++;
                }
                grams.add(query.substring(i, j).toLowerCase());
                i = j;
            } else {
                if (isCjk(c) && i + 1 < query.length() && isCjk(query.charAt(i + 1))) {
                    grams.add(query.substring(i, i + 2));
                }
                i++;
            }
        }
        return new ArrayList<>(grams);
    }

    private static boolean isLatinStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isLatinPart(char c) {
        return isLatinStart(c) || (c >= '0' && c <= '9') || c == '+' || c == '#' || c == '.';
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }
}
