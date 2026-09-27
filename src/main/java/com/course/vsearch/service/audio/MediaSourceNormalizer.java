package com.course.vsearch.service.audio;

import com.course.vsearch.common.BizException;
import com.course.vsearch.config.VSearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 媒体源归一化闸门：把任意来源的媒体文件变成「ffprobe 可读且含音轨」的文件，
 * 使下游（提音频 → ASR → 分段 → 向量化）与源容器格式彻底解耦。
 *
 * 三级策略，逐级以 ffprobe 实测结果验证（不靠扩展名或魔数猜测）：
 *   1. 直接探测：放大 -probesize/-analyzeduration，容忍一般性头部噪声；
 *   2. 容器重同步：扫描前 1MB 命中容器魔数表，剥离魔数之前的非标准字节
 *      （B站 .m4s 等 DASH 分片、被加壳/加头的文件属此类）；
 *   3. 强制解复用器 + 无损重封装为 mkv：魔数缺失但内容完好时兜底。
 *
 * 新增格式支持 = 往魔数表或解复用器列表加一行，不改任何流程代码。
 * 三级全失败（如缺少 moov 初始化段的裸分片）时抛出含 ffprobe 原始输出的可操作错误。
 *
 * 另外承担「多轨拼装」：B站 下载的 .m4s 是画面轨与声音轨两个独立分片，
 * 由 probeStreams 判定各自流组成、muxTracks 无损合并，从而只需上传侧拼一次，
 * 下游链路仍然只面对「一个完整媒体文件」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaSourceNormalizer {

    private static final int SCAN_LIMIT = 1024 * 1024;
    private static final int MAX_CANDIDATES = 6;
    /** 解码抽样秒数：验证候选文件能真正解码出声音，而非仅仅"识别到流" */
    private static final int DECODE_SAMPLE_SECONDS = 8;
    /** 8 秒 16k 单声道 s16le ≈ 256KB，只需确认有非空 PCM 产出 */
    private static final int MIN_PCM_BYTES = 1024;

    /** 强制解复用器候选（按常见度排序），配合 -c copy 无损重封装验证 */
    private static final List<String> DEMUXER_HINTS = List.of(
            "mov", "matroska", "mpegts", "flv", "ogg", "wav", "mp3", "aac", "avi", "asf");

    private final VSearchProperties props;

    /** 单文件归一化：结果必须含可解码音轨（下游提音频 / ASR 的前提） */
    public Path normalize(Path source, Path workDir) {
        return normalize(source, workDir, true);
    }

    /**
     * @param source       本地源文件（任意格式）
     * @param workDir      任务临时目录（重同步/重封装的中间产物写在这里，随任务目录一起清理）
     * @param requireAudio true = 结果必须含可解码音轨；false = 只要求能被 ffmpeg 解析。
     *                     上传时拼接「画面轨 + 声音轨」需要后者：画面轨单独本就没有音轨，
     *                     对它做抽样解码验证必然失败，但解复用（-c copy）完全可用。
     * @return ffprobe 可读的文件路径（可能是原文件，也可能是 workDir 内的中间产物）
     */
    public Path normalize(Path source, Path workDir, boolean requireAudio) {
        // 1. 直接探测
        Probe direct = probe(source, null, requireAudio);
        if (direct.ok()) {
            log.info("媒体源可直接解析，无需归一化: {}", source.getFileName());
            return source;
        }
        String firstError = direct.error();

        // 2. 容器重同步：剥离魔数之前的非标准字节
        for (int offset : findResyncOffsets(source)) {
            Path stripped = workDir.resolve("resync-" + offset + ".bin");
            boolean ok = false;
            try {
                ok = stripPrefix(source, offset, stripped) && probe(stripped, null, requireAudio).ok();
            } catch (Exception e) {
                log.debug("剥离 {} 字节后探测失败: {}", offset, e.getMessage());
            }
            if (ok) {
                log.info("容器重同步成功：剥离前 {} 字节后可直接解析", offset);
                return stripped;
            }
            deleteQuietly(stripped);
        }

        // 3. 强制解复用器 + 无损重封装为 mkv
        for (String hint : DEMUXER_HINTS) {
            Path mkv = workDir.resolve("remux-" + hint + ".mkv");
            boolean ok = false;
            try {
                ok = remux(source, hint, mkv) && probe(mkv, null, requireAudio).ok();
            } catch (Exception e) {
                log.debug("强制 {} 解复用失败: {}", hint, e.getMessage());
            }
            if (ok) {
                log.info("强制解复用器 {} + 无损重封装成功", hint);
                return mkv;
            }
            deleteQuietly(mkv);
        }

        if (!requireAudio) {
            throw new BizException("上传的文件无法被 ffmpeg 解析（直接解析、容器修复、强制重封装均失败），"
                    + "无法参与双轨合并。 ffprobe: " + firstError);
        }
        throw new BizException("视频无法提取音轨：直接解析、容器修复、强制重封装均无法解码出声音。"
                + "若这是 B站 的 .m4s 分片，请把画面轨与声音轨两个文件一起上传"
                + "（B站 音轨文件名通常形如 xxx-1-30280.m4s），平台会自动合并成带画面与声音的完整视频。"
                + " ffprobe: " + firstError);
    }

    /**
     * 探测已可解析文件的流组成，供上传侧判定「哪一份是画面轨、哪一份是声音轨」。
     * 纯探测不要求音轨，故必须先 normalize(..., false) 剥掉容器前的垃圾头。
     */
    public Streams probeStreams(Path file) {
        StreamTypes st = readStreamTypes(file, null);
        if (st.exit() != 0) {
            return new Streams(false, false);
        }
        return new Streams(st.types().contains("video"), st.types().contains("audio"));
    }

    /**
     * 把「画面轨 + 声音轨」（如 B站 DASH 的两个 .m4s）无损合并为单个 MP4：
     * -c copy 不重新编码，毫秒级完成；+faststart 把 moov 前置，浏览器可边下边播，
     * 下游归一化也能直接命中「直接探测」路径。合并后按真实流组成复验，避免产出无声/无画结果。
     */
    public Path muxTracks(Path videoTrack, Path audioTrack, Path target) {
        ExecResult r = exec(List.of(
                props.getFfmpeg().getFfmpegPath(),
                "-y", "-v", "error",
                "-probesize", "50M", "-analyzeduration", "50M", "-i", videoTrack.toString(),
                "-probesize", "50M", "-analyzeduration", "50M", "-i", audioTrack.toString(),
                "-map", "0:v:0", "-map", "1:a:0",
                "-c", "copy",
                "-movflags", "+faststart",
                "-f", "mp4",
                target.toString()), Duration.ofMinutes(10));
        if (r.exit() != 0) {
            throw new BizException("画面轨与声音轨合并失败: " + tail(r.output()));
        }
        Streams s = probeStreams(target);
        if (!s.video() || !s.audio()) {
            throw new BizException("画面轨与声音轨合并后复验失败（video=" + s.video()
                    + ", audio=" + s.audio() + "），请确认两份文件分别是画面轨与声音轨");
        }
        return target;
    }

    /** 文件的流组成 */
    public record Streams(boolean video, boolean audio) {
    }

    /** 扫描前 1MB，返回命中容器魔数的「容器起始偏移」，按出现顺序，最多 MAX_CANDIDATES 个 */
    private List<Integer> findResyncOffsets(Path file) {
        List<Integer> offsets = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = in.readNBytes(SCAN_LIMIT);
            for (int i = 0; i < buf.length && offsets.size() < MAX_CANDIDATES; i++) {
                int off = matchContainerStart(buf, i);
                if (off >= 0 && !offsets.contains(off)) {
                    offsets.add(off);
                }
            }
        } catch (Exception e) {
            log.debug("扫描容器魔数失败: {}", e.getMessage());
        }
        return offsets;
    }

    /**
     * 容器魔数表：命中返回容器起始偏移，否则 -1。新增格式只需在此加一行判断。
     * 覆盖 MP4/fMP4(DASH)、fMP4 分片 styp、Matroska/WebM、FLV、Ogg、RIFF(WAV/AVI)、ASF/WMV、
     * ID3(MP3)、ADTS(AAC)/MP3 帧同步、MIDI、MPEG-TS(188 字节包同步)。
     * 注意：命中魔数只代表"像容器"，最终一律以真实抽样解码验证为准，避免数据区巧合字节误判。
     */
    private int matchContainerStart(byte[] b, int i) {
        int len = b.length;
        if (i + 4 <= len && b[i] == 'f' && b[i + 1] == 't' && b[i + 2] == 'y' && b[i + 3] == 'p'
                && i >= 4 && boxSize(b, i - 4) >= 8) {
            return i - 4;
        }
        // fMP4 媒体分片用 styp 代替 ftyp（结构相同）
        if (i + 4 <= len && b[i] == 's' && b[i + 1] == 't' && b[i + 2] == 'y' && b[i + 3] == 'p'
                && i >= 4 && boxSize(b, i - 4) >= 8) {
            return i - 4;
        }
        if (i + 4 <= len && b[i] == 0x1A && b[i + 1] == 0x45 && b[i + 2] == (byte) 0xDF && b[i + 3] == (byte) 0xA3) {
            return i;
        }
        if (i + 3 <= len && b[i] == 'F' && b[i + 1] == 'L' && b[i + 2] == 'V') {
            return i;
        }
        if (i + 4 <= len && b[i] == 'O' && b[i + 1] == 'g' && b[i + 2] == 'g' && b[i + 3] == 'S') {
            return i;
        }
        if (i + 4 <= len && b[i] == 'R' && b[i + 1] == 'I' && b[i + 2] == 'F' && b[i + 3] == 'F') {
            return i;
        }
        if (i + 4 <= len && b[i] == 0x30 && b[i + 1] == 0x26 && b[i + 2] == (byte) 0xB2 && b[i + 3] == 0x75) {
            return i;
        }
        if (i + 3 <= len && b[i] == 'I' && b[i + 1] == 'D' && b[i + 2] == '3') {
            return i;
        }
        if (i + 2 <= len && (b[i] & 0xFF) == 0xFF
                && ((b[i + 1] & 0xF6) == 0xF0 || (b[i + 1] & 0xFE) == 0xFA)) {
            return i;
        }
        if (i + 4 <= len && b[i] == 'M' && b[i + 1] == 'T' && b[i + 2] == 'h' && b[i + 3] == 'd') {
            return i;
        }
        if (i + 377 <= len && b[i] == 0x47 && b[i + 188] == 0x47 && b[i + 376] == 0x47) {
            return i;
        }
        return -1;
    }

    private int boxSize(byte[] b, int pos) {
        return ((b[pos] & 0xFF) << 24) | ((b[pos + 1] & 0xFF) << 16)
                | ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
    }

    private boolean stripPrefix(Path source, int offset, Path target) throws Exception {
        try (InputStream in = Files.newInputStream(source);
             OutputStream out = Files.newOutputStream(target)) {
            in.skipNBytes(offset);
            in.transferTo(out);
        }
        return Files.size(target) > 0;
    }

    /** 强制解复用器 + 无损重封装为 mkv（mkv 是最宽容的容器），只取第一条音轨 */
    private boolean remux(Path source, String demuxerHint, Path target) {
        ExecResult r = exec(List.of(
                props.getFfmpeg().getFfmpegPath(),
                "-y", "-v", "error",
                "-f", demuxerHint,
                "-i", source.toString(),
                "-map", "0:a:0",
                "-c", "copy",
                "-f", "matroska",
                target.toString()), Duration.ofMinutes(10));
        return r.exit() == 0;
    }

    /**
     * 探测文件是否可解析（放大探测窗口以容忍头部噪声）。
     * requireAudio=true 时额外要求含音轨且能真正解码出声音：缺初始化段的裸 DASH 分片
     * 可能被 ffprobe 识别为 audio，但解码时 100% 坏包（AAC "channel element not allocated"）。
     */
    private Probe probe(Path file, String demuxerHint, boolean requireAudio) {
        StreamTypes st = readStreamTypes(file, demuxerHint);
        if (st.exit() != 0) {
            return new Probe(false, tail(st.output()));
        }
        if (requireAudio) {
            if (!st.types().contains("audio")) {
                return new Probe(false, "未检测到音轨");
            }
            String decodeError = decodeSampleError(file, demuxerHint);
            if (decodeError != null) {
                return new Probe(false, "抽样解码失败: " + decodeError);
            }
            return new Probe(true, null);
        }
        if (st.types().isEmpty()) {
            return new Probe(false, "未检测到任何音视频流");
        }
        return new Probe(true, null);
    }

    /** ffprobe 读取容器内所有流的 codec_type（失败时 output 为错误信息） */
    private StreamTypes readStreamTypes(Path file, String demuxerHint) {
        List<String> cmd = new ArrayList<>(List.of(
                props.getFfmpeg().getFfprobePath(),
                "-v", "error",
                "-probesize", "50M",
                "-analyzeduration", "50M"));
        if (demuxerHint != null) {
            cmd.add("-f");
            cmd.add(demuxerHint);
        }
        cmd.add("-show_entries");
        cmd.add("stream=codec_type");
        cmd.add("-of");
        cmd.add("default=noprint_wrappers=1:nokey=1");
        cmd.add(file.toString());

        ExecResult r = exec(cmd, Duration.ofMinutes(2));
        List<String> types = r.output().lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new StreamTypes(r.exit(), types, r.output());
    }

    /**
     * 解码前 DECODE_SAMPLE_SECONDS 秒为 s16le PCM 输出到 stdout，验证可真正出声。
     * @return null 表示成功；否则返回简短失败原因
     */
    private String decodeSampleError(Path file, String demuxerHint) {
        List<String> cmd = new ArrayList<>(List.of(
                props.getFfmpeg().getFfmpegPath(),
                "-v", "error",
                "-t", String.valueOf(DECODE_SAMPLE_SECONDS)));
        if (demuxerHint != null) {
            cmd.add("-f");
            cmd.add(demuxerHint);
        }
        cmd.add("-i");
        cmd.add(file.toString());
        cmd.add("-map");
        cmd.add("0:a:0");
        cmd.add("-ac");
        cmd.add("1");
        cmd.add("-ar");
        cmd.add("16000");
        cmd.add("-f");
        cmd.add("s16le");
        cmd.add("pipe:1");

        Process process = null;
        try {
            process = new ProcessBuilder(cmd).start();
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            // stdout 是 PCM 数据、stderr 是错误，必须并发读，防止管道阻塞死锁
            Process p = process;
            Thread errReader = new Thread(() -> {
                try {
                    p.getErrorStream().transferTo(err);
                } catch (Exception ignored) {
                    // 进程提前结束时读错误流可能抛异常，忽略
                }
            }, "ffmpeg-stderr-drain");
            errReader.setDaemon(true);
            errReader.start();
            process.getInputStream().transferTo(pcm);
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return "解码超时";
            }
            errReader.join(3000);
            if (process.exitValue() != 0) {
                return tail(err.toString(StandardCharsets.UTF_8));
            }
            if (pcm.size() < MIN_PCM_BYTES) {
                String e = err.toString(StandardCharsets.UTF_8).trim();
                return e.isEmpty() ? "未产出音频数据" : tail(e);
            }
            return null;
        } catch (Exception e) {
            return e.getMessage();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private record Probe(boolean ok, String error) {
    }

    private record StreamTypes(int exit, List<String> types, String output) {
    }

    private record ExecResult(int exit, String output) {
    }

    private ExecResult exec(List<String> command, Duration timeout) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
            Process process = pb.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            process.getInputStream().transferTo(out);
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new ExecResult(-1, "命令执行超时");
            }
            return new ExecResult(process.exitValue(), out.toString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new ExecResult(-1, e.getMessage());
        }
    }

    private static String tail(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() > 300 ? t.substring(t.length() - 300) : t;
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (Exception ignored) {
            // 中间产物清理失败不影响主流程
        }
    }
}
