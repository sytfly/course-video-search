package com.course.vsearch.service.audio;

import com.course.vsearch.common.BizException;
import com.course.vsearch.config.VSearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于 ffmpeg 的音频预处理，解决三个问题：
 * 1. 硅基流动 ASR 单文件 50MB / 1 小时限制 —— 提取 16k 单声道 24kbps 音频，40 分钟约 7MB；
 * 2. 官方 ASR 接口不返回时间戳 —— 用 VAD 静音点切成 8~30s 小块，分块起始偏移即天然时间戳；
 * 3. 长静音同时作为「声学特征话题分段」的边界候选。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AudioPreprocessService {

    private static final Pattern SILENCE_START = Pattern.compile("silence_start:\\s*(-?[\\d.]+)");
    private static final Pattern SILENCE_END =
            Pattern.compile("silence_end:\\s*([\\d.]+)\\s*\\|\\s*silence_duration:\\s*([\\d.]+)");
    private static final Pattern MAX_VOLUME = Pattern.compile("max_volume:\\s*(-?[\\d.]+|-inf)");

    /** 整段最大音量低于该值视为纯静音：数字静音底噪约 -91dB，正常语音峰值远高于 -70dB */
    private static final double SILENT_MAX_VOLUME_DB = -70.0;

    private final VSearchProperties props;
    private final MediaSourceNormalizer mediaNormalizer;

    /**
     * @param sourceVideo 本地视频文件（任意容器格式，由归一化闸门负责兼容）
     * @param taskId      任务 ID（临时目录名）
     */
    public AudioPreprocessResult prepare(Path sourceVideo, String taskId) {
        VSearchProperties.Ffmpeg cfg = props.getFfmpeg();
        try {
            Path workDir = Path.of(cfg.getWorkDir(), taskId);
            Files.createDirectories(workDir);

            // 归一化闸门：任意容器 → ffprobe/ffmpeg 可读文件，下游不再感知源格式
            Path media = mediaNormalizer.normalize(sourceVideo, workDir);

            // 可播放版本：统一 remux 成浏览器友好的 MP4（纯音频 AAC 同样封进 MP4）
            Path playable = workDir.resolve("playable.mp4");
            exportPlayable(media, playable);

            Path audioFile = workDir.resolve("audio.mp3");
            extractAudio(media, audioFile);

            // 时长取自提取后的音频：与源容器解耦，且 ASR 时间戳天然对齐音频时间轴
            double duration = probeDuration(audioFile);
            log.info("[{}] 音频时长: {}s", taskId, duration);

            // 静音闸门：整段无有效信号则在 VAD/ASR 前快速失败，避免无谓的分钟级识别开销
            assertAudioHasSignal(audioFile, taskId);

            List<AudioPreprocessResult.Silence> silences = detectSilences(audioFile);
            log.info("[{}] VAD 检出静音段 {} 个", taskId, silences.size());

            List<double[]> ranges = planRanges(duration, silences, cfg);
            List<AudioPreprocessResult.AudioChunk> chunks = exportChunks(audioFile, ranges, workDir);
            log.info("[{}] ASR 分块 {} 个", taskId, chunks.size());

            return new AudioPreprocessResult(duration, audioFile, chunks, silences, playable, workDir);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("音频预处理失败: " + e.getMessage(), e);
        }
    }

    /**
     * 导出浏览器可播放的 MP4：优先无损 remux（-c copy，毫秒级）；
     * 若源编码无法装进 MP4（如 mkv 内的 hevc/aac-pcm 等），回退为 H.264/AAC 转码。
     */
    private void exportPlayable(Path media, Path playable) {
        try {
            run(List.of(
                    props.getFfmpeg().getFfmpegPath(),
                    "-y", "-i", media.toString(),
                    "-map", "0",
                    "-c", "copy",
                    "-f", "mp4",
                    playable.toString()),
                    Duration.ofMinutes(10));
        } catch (Exception copyFail) {
            log.warn("可播放版本 remux 失败，回退 H.264/AAC 转码: {}", copyFail.getMessage());
            try {
                run(List.of(
                        props.getFfmpeg().getFfmpegPath(),
                        "-y", "-i", media.toString(),
                        "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                        "-c:a", "aac", "-b:a", "128k",
                        "-f", "mp4",
                        playable.toString()),
                        Duration.ofMinutes(30));
            } catch (Exception e) {
                throw new BizException("生成可播放 MP4 失败: " + e.getMessage(), e);
            }
        }
    }

    private double probeDuration(Path video) throws Exception {
        String out = run(List.of(
                props.getFfmpeg().getFfprobePath(),
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                video.toString()),
                Duration.ofMinutes(5));
        return Double.parseDouble(out.trim());
    }

    /**
     * 音轨有效性闸门：整段最大音量仍低于阈值即判定为纯静音（无语音内容），
     * 直接给出可读原因，避免对无声视频白跑 VAD/ASR（分钟级）与对象存储写入。
     */
    private void assertAudioHasSignal(Path audio, String taskId) throws Exception {
        String out = run(List.of(
                props.getFfmpeg().getFfmpegPath(),
                "-i", audio.toString(),
                "-af", "volumedetect",
                "-f", "null", "-"),
                Duration.ofMinutes(5));
        Matcher m = MAX_VOLUME.matcher(out);
        if (!m.find()) {
            // 解析不到就放行，交由后续 ASR 兜底判断
            log.warn("[{}] 未能解析音轨最大音量，跳过静音闸门", taskId);
            return;
        }
        String raw = m.group(1);
        double maxDb = raw.contains("inf") ? Double.NEGATIVE_INFINITY : Double.parseDouble(raw);
        log.info("[{}] 音轨最大音量: {} dB", taskId, raw);
        if (maxDb < SILENT_MAX_VOLUME_DB) {
            throw new BizException("该视频音轨为纯静音（最大音量 " + raw
                    + " dB），未检测到任何语音内容，请上传包含讲解声音的视频");
        }
    }

    private void extractAudio(Path video, Path audio) throws Exception {
        run(List.of(
                props.getFfmpeg().getFfmpegPath(),
                "-y", "-i", video.toString(),
                "-vn",
                "-ac", "1",
                "-ar", "16000",
                "-b:a", props.getFfmpeg().getAudioBitrate(),
                "-f", "mp3",
                audio.toString()),
                Duration.ofMinutes(30));
        if (!Files.exists(audio) || Files.size(audio) == 0) {
            throw new BizException("音频提取失败，输出文件为空（请检查 ffmpeg 是否支持该视频编码）");
        }
    }

    private List<AudioPreprocessResult.Silence> detectSilences(Path audio) throws Exception {
        String out = run(List.of(
                props.getFfmpeg().getFfmpegPath(),
                "-i", audio.toString(),
                "-af", "silencedetect=noise=" + props.getFfmpeg().getVadNoiseDb()
                        + "dB:d=" + props.getFfmpeg().getVadMinSilence(),
                "-f", "null", "-"),
                Duration.ofMinutes(10));

        List<AudioPreprocessResult.Silence> result = new ArrayList<>();
        Double pendingStart = null;
        for (String line : out.split("\\R")) {
            Matcher endM = SILENCE_END.matcher(line);
            Matcher startM = SILENCE_START.matcher(line);
            if (endM.find()) {
                double end = Double.parseDouble(endM.group(1));
                double dur = Double.parseDouble(endM.group(2));
                double start = pendingStart != null ? pendingStart : end - dur;
                result.add(new AudioPreprocessResult.Silence(Math.max(0, start), end, dur));
                pendingStart = null;
            } else if (startM.find()) {
                pendingStart = Double.parseDouble(startM.group(1));
            }
        }
        return result;
    }

    /**
     * 贪心规划分块区间：
     * 从 start 出发，目标 chunkTarget；在 [start+min, start+max] 内选距离目标点最近的
     * 静音中点下刀（说话停顿处，语句完整）；找不到则在目标点硬切。
     */
    private List<double[]> planRanges(double duration,
                                      List<AudioPreprocessResult.Silence> silences,
                                      VSearchProperties.Ffmpeg cfg) {
        int min = cfg.getChunkMinSeconds();
        int target = cfg.getChunkTargetSeconds();
        int max = cfg.getChunkMaxSeconds();

        List<Double> cuts = new ArrayList<>();
        double start = 0;
        while (duration - start > max) {
            double ideal = start + target;
            double lo = start + min;
            double hi = start + max;
            double bestCut = -1;
            double bestDist = Double.MAX_VALUE;
            for (AudioPreprocessResult.Silence s : silences) {
                double mid = (s.start() + s.end()) / 2.0;
                if (mid >= lo && mid <= hi) {
                    double dist = Math.abs(mid - ideal);
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestCut = mid;
                    }
                }
            }
            double cut = bestCut > 0 ? bestCut : ideal;
            cuts.add(cut);
            start = cut;
        }
        cuts.add(duration);

        // 转成区间，过短的尾块并入前一块
        List<double[]> ranges = new ArrayList<>();
        double prev = 0;
        for (double c : cuts) {
            if (c - prev < 0.1) {
                continue;
            }
            ranges.add(new double[]{prev, c});
            prev = c;
        }
        if (ranges.size() > 1) {
            double[] last = ranges.get(ranges.size() - 1);
            if (last[1] - last[0] < min) {
                double[] prevRange = ranges.get(ranges.size() - 2);
                prevRange[1] = last[1];
                ranges.remove(ranges.size() - 1);
            }
        }
        return ranges;
    }

    /**
     * 重编码导出分块（-ss/-to 作为输出选项 + libmp3lame，切点帧精确，保证时间戳对齐）。
     */
    private List<AudioPreprocessResult.AudioChunk> exportChunks(Path audio, List<double[]> ranges, Path workDir)
            throws Exception {
        List<AudioPreprocessResult.AudioChunk> chunks = new ArrayList<>();
        for (int i = 0; i < ranges.size(); i++) {
            double[] r = ranges.get(i);
            Path file = workDir.resolve(String.format("chunk-%04d.mp3", i));
            run(List.of(
                    props.getFfmpeg().getFfmpegPath(),
                    "-y", "-i", audio.toString(),
                    "-ss", String.format("%.3f", r[0]),
                    "-to", String.format("%.3f", r[1]),
                    "-c:a", "libmp3lame",
                    "-b:a", props.getFfmpeg().getAudioBitrate(),
                    "-f", "mp3",
                    file.toString()),
                    Duration.ofMinutes(5));
            chunks.add(new AudioPreprocessResult.AudioChunk(r[0], r[1], file));
        }
        return chunks;
    }

    /**
     * 执行外部命令并收集合并输出（ffmpeg 进度信息在 stderr）。
     */
    private String run(List<String> command, Duration timeout) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        Process process = pb.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        process.getInputStream().transferTo(out);
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new BizException("命令执行超时: " + command.get(0));
        }
        int exit = process.exitValue();
        String text = out.toString(StandardCharsets.UTF_8);
        if (exit != 0) {
            String tail = text.length() > 1000 ? text.substring(text.length() - 1000) : text;
            throw new BizException(command.get(0) + " 退出码 " + exit + ": " + tail);
        }
        return text;
    }
}
