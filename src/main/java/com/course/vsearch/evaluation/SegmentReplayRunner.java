package com.course.vsearch.evaluation;

import com.course.vsearch.entity.AsrChunkCheckpoint;
import com.course.vsearch.entity.Video;
import com.course.vsearch.entity.VideoSegment;
import com.course.vsearch.mapper.AsrChunkCheckpointMapper;
import com.course.vsearch.mapper.VideoMapper;
import com.course.vsearch.mapper.VideoSegmentMapper;
import com.course.vsearch.security.TenantContext;
import com.course.vsearch.service.ai.AsrLine;
import com.course.vsearch.service.correct.CorrectionResult;
import com.course.vsearch.service.correct.CorrectionTrace;
import com.course.vsearch.service.correct.TerminologyService;
import com.course.vsearch.service.segment.SegmentContext;
import com.course.vsearch.service.segment.SegmentationService;
import com.course.vsearch.service.segment.TopicSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 离线观测通道：指定 vsearch.eval.replay-video-id 时在启动后直接读库，不经过上传/ASR 链路。
 * <p>
 * 三种模式：
 * <ul>
 *   <li>默认（复算分段）：读 video_asr_chunk + video.duration 重跑当前分段策略，打印边界与命中统计。</li>
 *   <li>--vsearch.eval.dump-segments=true（导出片段）：读 video_segment 打印已落库片段的
 *       序号/起止/章节/正文，用于人工标注检索 QA（搜索返回的就是这些片段，不含未落库的复算结果）。</li>
 *   <li>--vsearch.eval.replay-correct=true（纠错复算）：读 video_asr_chunk 的<strong>未纠错原文</strong>
 *       重跑术语纠错，逐块打印原文/修正/痕迹，用于收集真实 ASR 错写与核对误纠。</li>
 * </ul>
 * replay-video-id 支持逗号分隔多个 videoId。
 * <p>
 * 用途：调分段参数或做检索标注时不必再走「置 FAILED → 重传命中 MD5 → 复用断点」的重跑链路，
 * 几秒内出结果且不消耗 ASR / embedding 配额。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "vsearch.eval.replay-video-id")
public class SegmentReplayRunner implements ApplicationRunner {

    /** 片段边界判定容差（秒）：一个 ASR 块的长度，与人工标注时的时间戳精度匹配 */
    private static final double TOLERANCE_SECONDS = 20;

    private final VideoMapper videoMapper;
    private final AsrChunkCheckpointMapper asrChunkMapper;
    private final VideoSegmentMapper segmentMapper;
    private final SegmentationService segmentationService;
    private final TerminologyService terminologyService;

    @Override
    public void run(ApplicationArguments args) {
        // 离线复算按 videoId 直接读库，没有登录上下文：显式声明 system 模式（不加租户条件）
        TenantContext.runAsSystem(() -> replayIds(args));
    }

    private void replayIds(ApplicationArguments args) {
        List<String> ids = splitIds(args.getOptionValues("vsearch.eval.replay-video-id"));
        if (ids.isEmpty()) {
            return;
        }
        boolean dumpSegments = flag(args, "vsearch.eval.dump-segments");
        boolean replayCorrect = flag(args, "vsearch.eval.replay-correct");
        for (String videoId : ids) {
            if (replayCorrect) {
                replayCorrection(videoId);
            } else if (dumpSegments) {
                dumpStoredSegments(videoId);
            } else {
                replay(videoId);
            }
        }
    }

    /** 布尔开关：只要传了且值不是 false 就算开启 */
    private static boolean flag(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        return values != null && values.stream().anyMatch(v -> !"false".equalsIgnoreCase(v));
    }

    /** 支持 --vsearch.eval.replay-video-id=v_a,v_b 与重复传参两种写法 */
    private static List<String> splitIds(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (String value : raw) {
            for (String piece : value.split(",")) {
                String id = piece.trim();
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /** 导出已落库片段：搜索返回的正是这些片段，标注 QA 以此为准 */
    private void dumpStoredSegments(String videoId) {
        if ("all".equals(videoId)) {
            listAllVideos();
            return;
        }
        Video video = videoMapper.selectByVideoId(videoId);
        if (video == null) {
            log.warn("[片段导出] video 不存在: {}", videoId);
            return;
        }
        List<VideoSegment> segments = segmentMapper.listByVideoId(videoId);
        log.info("[片段导出] video={} 状态={} 时长={}s 片段数={} 策略={}",
                videoId, video.getStatus(), video.getDuration(), segments.size(),
                segments.isEmpty() ? "-" : segments.get(0).getStrategy());
        for (VideoSegment s : segments) {
            log.info("[片段导出] #{} {}~{} 章节=[{}] 正文={}",
                    s.getSegmentIndex(), round2(s.getStartTime().doubleValue()),
                    round2(s.getEndTime().doubleValue()),
                    s.getChapterTitle() == null ? "" : s.getChapterTitle(),
                    s.getTextContent());
        }
    }

    /** 全库视频清单（含 MD5 与片段数）：用于识别重复上传造成的索引污染 */
    private void listAllVideos() {
        List<Video> videos = videoMapper.selectList(null);
        log.info("[片段导出] 全库共 {} 个视频", videos.size());
        for (Video v : videos) {
            log.info("[片段导出] video={} 文件=[{}] 时长={}s 状态={} MD5={} 片段数={}",
                    v.getVideoId(), v.getFileName(), v.getDuration(), v.getStatus(), v.getMd5(),
                    segmentMapper.countByVideoId(v.getVideoId()));
        }
    }

    /**
     * 纠错复算：读 ASR 断点（未纠错的原文）重跑术语纠错，逐块打印原文与修正痕迹。
     * 两个用途：① 收集真实 ASR 错写，据此扩词表；② 核对误纠（原文与修正并排，肉眼可比）。
     */
    private void replayCorrection(String videoId) {
        List<AsrChunkCheckpoint> chunks = asrChunkMapper.listByVideoId(videoId);
        if (chunks.isEmpty()) {
            log.warn("[纠错复算] 无 ASR 断点数据: {}", videoId);
            return;
        }
        int correctedChunks = 0;
        List<CorrectionTrace> traces = new ArrayList<>();
        for (AsrChunkCheckpoint c : chunks) {
            String raw = c.getTextContent() == null ? "" : c.getTextContent();
            CorrectionResult r = terminologyService.correct(raw);
            log.info("[纠错复算] #{} 原文={}", c.getChunkIndex(), raw);
            if (r.changed()) {
                correctedChunks++;
                traces.addAll(r.traces());
                log.info("[纠错复算] #{} 修正={} 痕迹={}", c.getChunkIndex(), r.corrected(),
                        r.traces().stream().map(SegmentReplayRunner::formatTrace)
                                .collect(Collectors.joining(" | ")));
            }
        }
        Map<String, Long> byRule = traces.stream().collect(Collectors.groupingBy(
                CorrectionTrace::rule, LinkedHashMap::new, Collectors.counting()));
        log.info("[纠错复算] video={} 共 {} 块，{} 块发生修正，痕迹 {} 条，规则分布={}",
                videoId, chunks.size(), correctedChunks, traces.size(), byRule);
    }

    private static String formatTrace(CorrectionTrace t) {
        return String.format("%s:「%s」→「%s」(%.2f)", t.rule(), t.before(), t.term(), t.confidence());
    }

    private void replay(String videoId) {
        Video video = videoMapper.selectByVideoId(videoId);
        if (video == null) {
            log.warn("[分段复算] video 不存在: {}", videoId);
            return;
        }
        List<AsrChunkCheckpoint> chunks = asrChunkMapper.listByVideoId(videoId);
        if (chunks.isEmpty()) {
            log.warn("[分段复算] 无 ASR 断点数据: {}", videoId);
            return;
        }
        List<AsrLine> lines = new ArrayList<>(chunks.size());
        for (AsrChunkCheckpoint c : chunks) {
            lines.add(new AsrLine(c.getStartTime().doubleValue(), c.getEndTime().doubleValue(), c.getTextContent()));
        }
        double duration = video.getDuration() == null ? lines.get(lines.size() - 1).end() : video.getDuration().doubleValue();

        log.info("[分段复算] video={}，{} 个 ASR 块，时长 {}s，策略={}",
                videoId, lines.size(), String.format("%.2f", duration),
                segmentationService.currentStrategy().name());
        SegmentContext ctx = new SegmentContext(videoId, duration, lines, List.of());
        List<TopicSegment> topics = segmentationService.segment(ctx);

        List<Double> boundaries = new ArrayList<>();
        for (int i = 1; i < topics.size(); i++) {
            boundaries.add(round2(topics.get(i).getStart()));
        }
        log.info("[分段复算] 输出边界（不含 0）: {}", boundaries);

        List<Double> ref = EvaluationRunner.referenceBoundaries(videoId);
        if (ref.isEmpty()) {
            log.info("[分段复算] 标注文件中无该视频的 ref，跳过命中统计");
            return;
        }
        int hit = 0;
        for (double r : ref) {
            if (boundaries.stream().anyMatch(b -> Math.abs(b - r) <= TOLERANCE_SECONDS)) {
                hit++;
            }
        }
        long falseCuts = boundaries.stream()
                .filter(b -> ref.stream().noneMatch(r -> Math.abs(b - r) <= TOLERANCE_SECONDS))
                .count();
        log.info("[分段复算] ref={}（容差 ±{}s）→ 命中 {}/{}，漏切 {}，伪边界 {}",
                ref, (int) TOLERANCE_SECONDS, hit, ref.size(), ref.size() - hit, falseCuts);
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}