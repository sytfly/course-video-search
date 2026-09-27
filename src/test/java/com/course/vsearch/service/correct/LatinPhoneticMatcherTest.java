package com.course.vsearch.service.correct;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拉丁音近纠错护栏。
 * <p>
 * 正面样本全部来自 11 个课程视频原始 ASR 的实测错写（data/asr-raw-dump.txt 取证）；
 * 反面样本同样取自同一语料——它们是合法变量名、正常英文词、代码黏连或第一版明确不碰的形态，
 * 必须原样保留。阈值与模型在全库 replay 对照上校调，本测试锁定回归。
 */
class LatinPhoneticMatcherTest {

    private static LatinPhoneticMatcher matcher;
    private static TerminologyService service;

    @BeforeAll
    static void setUp() throws Exception {
        try (InputStream in = new ClassPathResource("dictionary/latin-surface.json").getInputStream()) {
            // Redis 来自技术词典（拉丁技术词在 Service 装配时并入正确面），测试显式补上
            matcher = LatinPhoneticMatcher.load(in,
                    List.of(new TechTerm("Redis", 0.98, List.of())));
        }
        service = new TerminologyService();
        service.load();
    }

    private static String correct(String text) {
        return service.correct(text).corrected();
    }

    // ---------- 正面：语料实证错写应收敛到正确面 ----------

    @Test
    void correctsObservedRedisVariants() {
        // Redis 在全库原始转写中正确形态出现 0 次，实际写法如下（含 rease.call 语境）
        assertTerm("Redis", "reice");
        assertTerm("Redis", "reis");
        assertTerm("Redis", "raice");
        assertTerm("Redis", "rease");
    }

    @Test
    void correctsObservedKeywordVariants() {
        assertTerm("false", "foalse");
        assertTerm("false", "forse");
    }

    @Test
    void correctsObservedJdkVariants() {
        assertTerm("System", "sstem");
    }

    @Test
    void correctsObservedCtrlVariants() {
        assertTerm("Ctrl", "conttrol"); // 双写折叠后即完整读音 control
        assertTerm("Ctrl", "contrl");
        assertTerm("Ctrl", "control");
    }

    @Test
    void correctsObservedLuaVariants() {
        assertTerm("Lua", "lu");
        assertTerm("Lua", "luva");
    }

    @Test
    void correctsInsideChineseSentence() {
        String out = correct("reice的官方给我们提供了一个内置的函数");
        assertTrue(out.contains("Redis"), "句中 reice 应纠为 Redis：" + out);
        CorrectionResult result = service.correct("如果前面是foalse");
        assertEquals("如果前面是false", result.corrected());
        assertEquals("latin-phonetic", result.traces().get(0).rule());
    }

    // ---------- 反面护栏：以下一律不得纠 ----------

    @Test
    void keepsStopwordsUntouched() {
        for (String w : List.of("hello", "bug", "student", "value", "phone", "oppo", "oppos")) {
            assertNull(matcher.match(w), "合法词不应被纠：" + w);
        }
    }

    @Test
    void keepsDigitAndVariableTokensUntouched() {
        for (String w : List.of("K4", "ide2", "bugK005")) {
            assertNull(matcher.match(w), "含数字 token 第一版不碰：" + w);
        }
        for (String w : List.of("STRA", "AR", "SC", "IDD", "GPT", "RV")) {
            assertNull(matcher.match(w), "全大写/内部大写 token 第一版不碰：" + w);
        }
        for (String w : List.of("intI", "inkI", "forI")) {
            assertNull(matcher.match(w), "代码黏连 token 第一版不碰：" + w);
        }
    }

    @Test
    void neverCorrectsAlreadyCorrectSurfaceWords() {
        // 全库 replay 实证：没有全局精确豁免时 java 被 JVM 抢 21 次、false 被 File 抢 15 次
        for (String w : List.of("java", "false", "true", "class", "case", "super", "char", "while", "for")) {
            assertNull(matcher.match(w), "正确面词不应被纠错：" + w);
        }
    }

    @Test
    void stopwordsAreCheckedBeforeAndAfterConsonantCollapse() {
        // address 折掉 dd 曾变 adress 绕过停用表漂向 Arrays；alt 是 IDE 快捷键里的真实 Alt 键
        assertNull(matcher.match("address"));
        assertNull(matcher.match("alt"));
    }

    @Test
    void keepsConservativeMissesUntouched() {
        // 这些虽然是错写，但正确面/距离在第一版保守策略下不满足，宁可漏纠不可误纠
        assertNull(matcher.match("tamp"));     // temp 不是 Java 标准面；且预算禁止其漂向 TreeMap
        assertNull(matcher.match("thide"));    // Thread 的变体需要元辅跨界对齐，预算拒绝
        assertNull(matcher.match("roate"));    // rotate 是课堂自定义方法名，不进标准面
        assertNull(matcher.match("newcancanner")); // 黏连代码，第二版处理
        assertNull(matcher.match("ro"));       // 2 字母短 token 必须过短串高阈值，不得误纠为 run 等
    }

    @Test
    void serviceKeepsLegitimateSentence() {
        String sentence = "hello world，student 的 name 和 value 都 set 好了";
        assertEquals(sentence, correct(sentence));
    }

    @Test
    void serviceCorrectsBaseWordBeforeSpaceJoinedSingleLetter() {
        // 语料实证（IDEA 快捷键集原始 ASR）：空格是语音边界，首词该纠、单字母尾巴保留
        assertEquals("Ctrl C", correct("conttrol C"));
        assertEquals("Ctrl V", correct("conttrol V"));
        assertEquals("Ctrl Ctrl V", correct("conttrol Ccontrl V"));
    }

    @Test
    void serviceDoesNotSplitGluedCodeOrSqLStyleTokens() {
        // 无空格的单字母序列没有可纠正的首词，保持原样（S Q L 不是任何术语的错写）
        assertEquals("S Q L", correct("S Q L"));
    }

    private static void assertTerm(String expected, String raw) {
        LatinPhoneticMatcher.Hit hit = matcher.match(raw);
        assertNotNull(hit, "「" + raw + "」应命中纠错");
        assertEquals(expected, hit.term(), "「" + raw + "」纠错目标错误");
    }
}
