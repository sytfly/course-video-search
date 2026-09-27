package com.course.vsearch.service.segment;

import com.course.vsearch.service.ai.AsrLine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 话语标记检测器回归护栏（2026-09-26，6 视频/19 条边界扩样本后重写规则时建立）。
 * 语料均取自真实 ASR 转写：HARD/GATED 必须命中的真转场，与旧规则造成多切的同话题动手引导句。
 */
class DiscourseMarkerDetectorTest {

    private static AsrLine line(double start, String text) {
        return new AsrLine(start, start + 3, text);
    }

    /** gap i 的边界时间 = 第 i+1 句起点；返回所有候选边界时间（秒） */
    private static List<Double> cueTimes(List<AsrLine> sentences) {
        Map<Integer, String> cues = DiscourseMarkerDetector.detect(sentences);
        List<Double> times = new ArrayList<>();
        cues.keySet().stream().sorted().forEach(g -> times.add(sentences.get(g + 1).start()));
        return times;
    }

    @Test
    void hardCues_hit() {
        // 顺序排列、间隔 60s（避开 15s cluster 与 30s 最小段长语义）
        List<AsrLine> s = List.of(
                line(0, "hello，各位同学，我们开始上课。"),
                line(60, "各位同学，接下来我们来学习第四种运算符关系运算符。"),
                line(120, "我们再来写第二种写法，用字符数组调整。"),
                line(180, "好，那么这个时候呢我们就要想了，否则返回什么。"),
                line(240, "我又该怎么样去执行这个脚本呢？"),
                line(300, "那真正重点的是什么呢？"),
                line(360, "同学们我们一起来思考一下这个问题。"),
                line(420, "是的呢，那问题我们发现了该怎么解决呢？"));
        List<Double> times = cueTimes(new ArrayList<>(s));
        for (double t : new double[]{60, 120, 180, 240, 300, 360, 420}) {
            assertTrue(times.contains(t), "HARD 转场未命中: " + t + " 实际=" + times);
        }
    }

    @Test
    void oldFalseOpenings_noLongerFire() {
        // 这些都是旧规则（接下来我们/下面我们）切出来的多切点，话题内部动手引导，不得再触发
        List<AsrLine> s = List.of(
                line(0, "在这里呢我已经新建好了一个类。"),
                line(60, "那么接下来我们就开始来演示，先定义变量。"),
                line(120, "在下面我们可以来输出A和B，右键运行。"),
                line(180, "那么下面我们就用等等来进行比较A等于B吗？"),
                line(240, "好，那么下面我们开始来写for小括号三个语句。"),
                line(300, "那接下来我们来做第二步比较，再拿结果跟第三个比。"),
                line(360, "好，那么接下来我们就来分析一下，第一步该做什么。"),
                line(420, "好，那么接下来我们就来看一看代码该怎么写。"));
        assertTrue(cueTimes(new ArrayList<>(s)).isEmpty(),
                "话题内部引导句不应触发 cue: " + cueTimes(new ArrayList<>(s)));
    }

    @Test
    void gatedQuestion_requiresRecentCloser() {
        // 收束语 + 20s 内设问 = 真转场（Redis 418.7：「这就是Lua脚本的语法了」→「那问题来了」）
        List<AsrLine> withCloser = new ArrayList<>(List.of(
                line(0, "我们先讲点别的内容。"),
                line(100, "好，这就是Lua脚本的一个语法了。"),
                line(105, "那问题来了，我又该怎么样去执行这个脚本呢？")));
        assertTrue(cueTimes(withCloser).contains(105.0));

        // 裸设问：话题内部自问自答，不命中（「我该怎么做啊」在真实语料中出现极高频）
        List<AsrLine> bare = new ArrayList<>(List.of(
                line(0, "如果我要做getname，"),
                line(60, "我该怎么做啊，是不是直接调用。")));
        assertFalse(cueTimes(bare).contains(60.0));

        // 伪收束（不带「了」）不应开门：「这就是它这个参数的一个概念啊」→「那新的问题来了」
        List<AsrLine> fakeCloser = new ArrayList<>(List.of(
                line(0, "前面讲点内容。"),
                line(100, "啊，这就是它这里这个参数的一个概念啊。"),
                line(105, "那新的问题来了，这个key能不能有多个。")));
        assertFalse(cueTimes(fakeCloser).contains(105.0));

        // 收束语超过 20s 窗口后才设问，不命中
        List<AsrLine> staleCloser = new ArrayList<>(List.of(
                line(0, "前面讲点内容。"),
                line(100, "好，这就是Lua脚本的语法了。"),
                line(140, "那问题来了，接下来怎么执行。")));
        assertFalse(cueTimes(staleCloser).contains(140.0));
    }

    @Test
    void openingLineOfVideo_isNotACue() {
        List<AsrLine> s = new ArrayList<>(List.of(
                line(0, "各位同学，接下来我们来学习第三种运算符赋值运算符。")));
        assertTrue(cueTimes(s).isEmpty());
    }

    @Test
    void clusterWithin15Seconds_keepsLast() {
        // 连续收尾+开场（367.3 各位同学 → 374.6 各位同学…来写练习）只保留后者
        List<AsrLine> s = new ArrayList<>(List.of(
                line(0, "前面讲计算过程。"),
                line(367, "好了，各位同学，"),
                line(374, "各位同学，接下来我们利用3元运算符来写两个小练习。")));
        List<Double> times = cueTimes(s);
        assertTrue(times.contains(374.0));
        assertFalse(times.contains(367.0));
    }
}
