package com.course.vsearch.service.segment;

import com.course.vsearch.service.ai.AsrLine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 话语标记（cue phrase）边界候选：讲师用显式措辞切换话题。
 * <p>
 * 这类显式切换两侧用词重合度高（都围着同一主题词讲），相似度在切换处是局部峰而非谷，
 * TextTiling 的深度信号出不来（实测 373.35s 处深度恰为 0），故单独提取为最高优先级信号：
 * 命中且满足最小段长即直接切分，不再等 max-segment-seconds 的强制窗口。
 * <p>
 * 规则分两档（2026-09-26 扩到 6 视频 / 19 条边界后重写，离线探针 15/19 命中、0 多切；
 * 旧规则只有「各位同学/接下来我们/下面我们」，在 Redis Lua、继承两集 8 漏 7，且「下面我们」
 * 在动手引导句里高频出现造成大量多切）：
 * <ul>
 *   <li><b>HARD（高置信，直接切）</b>：①「各位同学」（讲师只在正式转场喊）；②「接下来…来学习」；
 *       ③「来写第 N 个/种…」练习/解法宣告；④「真正重点」⑤「同学们一起来思考/想/分析」
 *       ⑥「就要想了」⑦「又该怎么…」（话题推进问句，30s 段长门控挡掉同话题内的紧跟重复）
 *       ⑧「问题发现/出现…怎么解决」（窄模式，专门覆盖设问式转场）；</li>
 *   <li><b>GATED（设问需收束前缀）</b>：「问题来了/该怎么写/怎么办/到底要…」这类自问在话题内部
 *       极高频（裸模式 21 中仅 5 真），只有前 20s 内出现收束语（「这就是…了」「知道了…」
 *       「弄明白…以后」「学完/讲完」）才算转场——收束+设问同现是「承上启下」的可靠形态。</li>
 * </ul>
 * 注意：「接下来我们/下面我们」本身<b>不再</b>触发——「下面我们写一行代码」「接下来我们来分析」
 * 是同话题内动手引导，旧规则的多切大半来源于此。
 */
final class DiscourseMarkerDetector {

    /** HARD：正式话题宣告，命中即候选（仍受 15s cluster 与最小段长门控） */
    private static final Pattern HARD = Pattern.compile(
            "各位同学"
            + "|接下来(我们|咱们)?来学习"
            + "|(来|再来)写第[一二三四五六七八九十两0-9]+(个|种)"
            + "|真正(的)?重点"
            + "|同学们(我们|咱们)?(一起)?来(思考|想|分析)"
            + "|就要想了"
            + "|又该怎么"
            + "|问题(我们|现在|就)?(是)?(发现|出现)[^，。？?!！]{0,12}(该怎么|怎么|如何)(解决|办)");

    /** GATED：设问式过渡，必须前 20s 内有收束语（CLOSER）才成立 */
    private static final Pattern GATED_QUESTION = Pattern.compile(
            "问题来了|该怎么(写|解决|执行|做|理解|去写|去做)|怎么办|到底要(学|怎么|做)");

    /** 收束语：一个话题讲完的小结措辞，作为 GATED_QUESTION 的门控前缀 */
    private static final Pattern CLOSER = Pattern.compile(
            "这就是[^，。！？!?]{0,16}了|知道了[^，。]{0,12}|弄明白[^，。]{0,8}(以后|了)|学完|讲完|学习完");

    /** 话题收尾：收尾话说完才是新话题，故边界落在下一句起点 */
    private static final Pattern CLOSING = Pattern.compile(
            "(就|先)学习到(这个)?地方|(就|先)学到(这个)?地方|(就|先)到这里|告一段落|下节课再见");

    /** GATED 设问与收束语的最大间隔 */
    private static final double GATE_WINDOW_SECONDS = 20;

    /**
     * 同一话题切换常被讲成连续几句（收尾「咱们就先学习到这个地方。」+ 开场「各位同学，接下来…」），
     * 实测 367.31/373.35/374.62 三处候选其实是一个边界。cluster 内只保留最后一个候选——
     * 新话题起于开场句，切在这里才落在新话题开头，且顺带避免 15s 内的重复切分。
     */
    private static final double CLUSTER_SECONDS = 15;

    private DiscourseMarkerDetector() {
    }

    /**
     * 返回候选边界：key 为 gap 下标（gap i 的边界时间 = lines[i+1].start，与 SemanticSegmenter 下标口径一致），
     * value 为命中的标记片段（诊断用，便于核对误报来源）。
     * 句首即视频开头的开场白不产生候选，句尾的收尾同理。
     */
    static Map<Integer, String> detect(List<AsrLine> lines) {
        Map<Integer, String> cues = new LinkedHashMap<>();
        double lastCloser = -GATE_WINDOW_SECONDS * 10;
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i).text();
            double start = lines.get(i).start();

            Matcher closer = CLOSER.matcher(text);
            if (closer.find()) {
                lastCloser = start;
            }

            Matcher hard = HARD.matcher(text);
            if (i > 0 && hard.find()) {
                cues.putIfAbsent(i - 1, "HARD:" + hard.group() + "|" + snippet(text));
            }

            Matcher gated = GATED_QUESTION.matcher(text);
            if (i > 0 && gated.find() && start - lastCloser <= GATE_WINDOW_SECONDS) {
                cues.putIfAbsent(i - 1, "GATED:" + gated.group() + "|" + snippet(text));
            }

            Matcher closing = CLOSING.matcher(text);
            if (i + 1 < lines.size() && closing.find()) {
                cues.putIfAbsent(i, "CLOSING:" + closing.group() + "|" + snippet(text));
            }
        }
        List<Integer> gaps = new ArrayList<>(cues.keySet());
        for (int k = 0; k + 1 < gaps.size(); k++) {
            double current = lines.get(gaps.get(k) + 1).start();
            double next = lines.get(gaps.get(k + 1) + 1).start();
            if (next - current <= CLUSTER_SECONDS) {
                cues.remove(gaps.get(k));
            }
        }
        return cues;
    }

    /** 命中句的前 18 字，用于人工核对候选是真是假（误报多来自套话出现在话题中途） */
    private static String snippet(String text) {
        return text.length() <= 18 ? text : text.substring(0, 18) + "…";
    }
}
