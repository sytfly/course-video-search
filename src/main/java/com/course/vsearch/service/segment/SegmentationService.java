package com.course.vsearch.service.segment;

import com.course.vsearch.common.BizException;
import com.course.vsearch.config.VSearchProperties;
import com.course.vsearch.service.ai.EmbeddingService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 分段策略编排：按配置 vsearch.segment.strategy 选择，便于三种方案跑同一批数据对比 WindowDiff。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SegmentationService {

    private final List<SegmentationStrategy> strategies;
    private final VSearchProperties props;
    private final EmbeddingService embeddingService;

    private Map<String, SegmentationStrategy> strategyMap;

    @PostConstruct
    public void init() {
        strategyMap = strategies.stream()
                .collect(Collectors.toMap(SegmentationStrategy::name, Function.identity()));
    }

    public SegmentationStrategy currentStrategy() {
        String name = props.getSegment().getStrategy();
        SegmentationStrategy strategy = strategyMap.get(name);
        if (strategy == null) {
            throw new BizException("未知分段策略: " + name + "，可选: " + strategyMap.keySet());
        }
        return strategy;
    }

    public List<TopicSegment> segment(SegmentContext ctx) {
        SegmentationStrategy strategy = currentStrategy();
        long t0 = System.currentTimeMillis();
        List<TopicSegment> segments = strategy.segment(ctx, props, embeddingService);
        log.info("[{}] 分段策略={}，得到 {} 个话题段，耗时 {}ms",
                ctx.videoId(), strategy.name(), segments.size(), System.currentTimeMillis() - t0);
        return segments;
    }
}
