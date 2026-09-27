package com.course.vsearch.service.segment;

import com.course.vsearch.service.ai.AsrLine;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 ASR 块级文本（每块约 20s）拆成句子级片段，供语义分段使用。
 * <p>
 * 动机：块边界由 VAD 决定，常切在句子中间（如「我可以按一个快捷键，叫做」｜「会跟其他的软件冲突啊」），
 * 跨块相似度骤降会在该处造出假边界深度峰。拆句后候选边界落到句首，块内也能出现候选。
 * <p>
 * 时间戳：ASR 只给块级时间，无逐词对齐，故按「句内字符占比」在块区间内线性插值，
 * 首句起点与末句终点严格等于块的 start/end（秒级误差，对章节跳转足够）。
 */
final class SentenceSplitter {

    /** 句末标点 + 逗号。中文 ASR 的逗号即小句边界，用它把「各位同学，接下来…」这类套话单独切出 */
    private static final String DELIMITERS = "。！？!?；;，,";
    /** 短于该长度的碎片并入前一句，避免「好，」「😊」这类噪声各自成句 */
    private static final int MIN_CHARS = 6;

    private SentenceSplitter() {
    }

    static List<AsrLine> split(List<AsrLine> lines) {
        List<AsrLine> out = new ArrayList<>(lines.size() * 2);
        for (AsrLine line : lines) {
            out.addAll(splitOne(line));
        }
        return out;
    }

    private static List<AsrLine> splitOne(AsrLine line) {
        String text = line.text();
        if (text.isEmpty()) {
            return List.of(line);
        }
        List<String> merged = mergeShortPieces(text);
        if (merged.size() <= 1) {
            return List.of(line);
        }
        int total = text.length();
        double span = line.end() - line.start();
        List<AsrLine> out = new ArrayList<>(merged.size());
        int cursor = 0;
        for (String piece : merged) {
            double start = line.start() + span * cursor / total;
            cursor += piece.length();
            double end = line.start() + span * cursor / total;
            out.add(new AsrLine(start, end, piece));
        }
        return out;
    }

    /** 按标点切分后合并过短碎片；字符不丢不重，拼接结果与原文一致 */
    private static List<String> mergeShortPieces(String text) {
        List<String> pieces = new ArrayList<>();
        StringBuilder piece = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            piece.append(ch);
            if (DELIMITERS.indexOf(ch) >= 0) {
                pieces.add(piece.toString());
                piece.setLength(0);
            }
        }
        if (piece.length() > 0) {
            pieces.add(piece.toString());
        }

        List<String> merged = new ArrayList<>(pieces.size());
        StringBuilder buffer = new StringBuilder();
        for (String p : pieces) {
            buffer.append(p);
            if (buffer.length() >= MIN_CHARS) {
                merged.add(buffer.toString());
                buffer.setLength(0);
            }
        }
        if (buffer.length() > 0) {
            if (merged.isEmpty()) {
                merged.add(buffer.toString());
            } else {
                merged.set(merged.size() - 1, merged.get(merged.size() - 1) + buffer);
            }
        }
        return merged;
    }
}