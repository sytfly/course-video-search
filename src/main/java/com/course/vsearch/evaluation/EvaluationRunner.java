package com.course.vsearch.evaluation;

import com.course.vsearch.config.VSearchProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 评估入口：vsearch.eval.enabled=true 时，启动后读取标注文件输出报告。
 * 标注文件放 src/main/resources/eval/，格式见同目录样例。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "vsearch.eval.enabled", havingValue="true")
public class EvaluationRunner implements ApplicationRunner {

    private final RetrievalEvaluator retrievalEvaluator;
    private final VSearchProperties props;

    @Override
    public void run(ApplicationArguments args) {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        runWindowDiff(mapper);
        runRetrieval(mapper);
    }

    private void runWindowDiff(ObjectMapper mapper) {
        SegmentationFile file = readJson(mapper, "eval/segmentation-boundaries.json",
                new TypeReference<SegmentationFile>() {
                });
        List<SegmentationCase> cases = file == null ? null : file.videos();
        if (cases == null || cases.isEmpty()) {
            log.info("[评估] 无分段标注，跳过 WindowDiff");
            return;
        }
        log.info("[评估] ========== WindowDiff（越低越好）==========");
        int refTotal = cases.stream().mapToInt(c -> c.ref() == null ? 0 : c.ref().size()).sum();
        log.info("[评估] 分段样本：{} 个视频 / {} 条真值边界（WindowDiff 是聚合值，样本量与视频构成必须一并披露）",
                cases.size(), refTotal);
        for (SegmentationCase c : cases) {
            for (Map.Entry<String, List<Double>> e : c.hyp().entrySet()) {
                double wd = WindowDiff.evaluate(c.ref(), e.getValue(), c.duration(), c.windowSeconds());
                log.info("[评估] video={} 策略={} WindowDiff={}", c.videoId(), e.getKey(),
                        String.format("%.4f", wd));
            }
        }
    }

    private void runRetrieval(ObjectMapper mapper) {
        QaFile file = readJson(mapper, "eval/qa-pairs.json", new TypeReference<QaFile>() {
        });
        List<QaPair> pairs = file == null ? null : file.pairs();
        if (pairs == null || pairs.isEmpty()) {
            log.info("[评估] 无 QA 标注，跳过检索评估");
            return;
        }
        log.info("[评估] ========== 检索评估：{} 条标注，mode={}，相关性闸门={} ==========",
                pairs.size(), props.getSearch().getMode(), props.getSearch().getMinSimilarity());
        Map<String, Long> byVideo = pairs.stream().collect(Collectors.groupingBy(
                QaPair::videoId, LinkedHashMap::new, Collectors.counting()));
        log.info("[评估] 样本构成：{} 个视频 → {}", byVideo.size(), byVideo.entrySet().stream()
                .map(e -> tail(e.getKey()) + "=" + e.getValue() + "条").collect(Collectors.joining("，")));
        for (boolean scoped : new boolean[]{false, true}) {
            String scope = scoped ? "片内（请求带 videoId，限定所属视频）" : "全库（跨视频）";
            for (RetrievalEvaluator.Report report : retrievalEvaluator.evaluate(pairs, new int[]{1, 3, 5}, scoped)) {
                log.info("[评估] 范围={} {}", scope, report.pretty());
            }
        }
    }

    private <T> T readJson(ObjectMapper mapper, String path, TypeReference<T> type) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return mapper.readValue(in, type);
        } catch (Exception e) {
            log.warn("[评估] 读取 {} 失败: {}", path, e.getMessage());
            return null;
        }
    }

    /** videoId 太长，日志里只留尾部 8 位，与检索明细的写法一致 */
    private static String tail(String videoId) {
        if (videoId == null) {
            return "-";
        }
        return videoId.length() <= 8 ? videoId : videoId.substring(videoId.length() - 8);
    }

    /** 供分段复算（SegmentReplayRunner）复用：取标注文件中该视频的真值边界，无则空列表 */
    static List<Double> referenceBoundaries(String videoId) {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        SegmentationFile file = readSegmentationFile(mapper);
        List<SegmentationCase> cases = file == null ? null : file.videos();
        if (cases == null) {
            return List.of();
        }
        return cases.stream()
                .filter(c -> videoId.equals(c.videoId()))
                .findFirst()
                .map(SegmentationCase::ref)
                .orElse(List.of());
    }

    private static SegmentationFile readSegmentationFile(ObjectMapper mapper) {
        try (InputStream in = new ClassPathResource("eval/segmentation-boundaries.json").getInputStream()) {
            return mapper.readValue(in, new TypeReference<SegmentationFile>() {
            });
        } catch (Exception e) {
            log.warn("[评估] 读取分段标注失败: {}", e.getMessage());
            return null;
        }
    }

    // ---- 标注文件结构（record，访问器风格与调用处一致；Jackson 2.12+ 原生支持 record 反序列化）----

    public record SegmentationFile(List<SegmentationCase> videos) {
    }

    public record SegmentationCase(String videoId,
                                   double duration,
                                   double windowSeconds,
                                   List<Double> ref,
                                   /** key = fixed / semantic / acoustic */
                                   Map<String, List<Double>> hyp) {
    }

    public record QaFile(List<QaPair> pairs) {
    }
}
