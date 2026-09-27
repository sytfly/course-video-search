package com.course.vsearch.service.correct;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纠错规则的回归护栏。
 * <p>
 * 核心命题：中文同音错写由拼音规则自动收敛，<b>不需要在词典里枚举</b>。
 * 因此这里刻意使用词典中不存在、且从未在实测素材里出现过的错写变体——
 * 只要能纠回，就证明纠错能力来自规则而不是词表堆砌。
 * 另一半是防过杀：合法词与含错写前缀的正常表述必须原样保留。
 */
class TerminologyServiceTest {

    private static TerminologyService service;

    @BeforeAll
    static void setUp() {
        service = new TerminologyService();
        service.load();
    }

    private static String correct(String text) {
        return service.correct(text).corrected();
    }

    /** 未见过的同音错写变体：全部应自动收敛到术语表 */
    @Test
    void correctsUnseenHomophoneVariants() {
        // 词典里只写了「三元运算符」这一个术语，以下五种写法均未枚举
        assertEquals("三元运算符", correct("三源运算符"));
        assertEquals("三元运算符", correct("叁元运算符"));
        assertEquals("三元运算符", correct("3员运算符"));
        assertEquals("三元运算符", correct("三原运算符"));
        assertEquals("三元表达式", correct("三员表达式"));
    }

    /** 拼音规则须能处理夹在正文中的错写，不依赖整句匹配 */
    @Test
    void correctsHomophoneInsideSentence() {
        assertEquals("用三元运算符可以简化这段代码", correct("用三原运算符可以简化这段代码"));
        assertEquals("那什么是三元运算符呢", correct("那什么是三员运算符呢"));
    }

    /** 同一术语只替换一次、不重复命中（替换后跳过已处理区间） */
    @Test
    void doesNotRewriteTwice() {
        CorrectionResult result = service.correct("三原运算符");
        assertEquals("三元运算符", result.corrected());
        assertEquals(1, result.traces().size());
        assertEquals("pinyin", result.traces().get(0).rule());
    }

    /** 防过杀：已正确的术语、含错写前缀的合法表述、同音的合法词一律不动 */
    @Test
    void keepsLegitimateTextUntouched() {
        assertEquals("三元运算符", correct("三元运算符"));
        // 「复制」是合法词，只有带「给」的短语才纠
        assertEquals("复制一行代码", correct("复制一行代码"));
        // 「复值」是「重复值」的子串，必须不被动
        assertEquals("重复值为3", correct("重复值为3"));
        assertEquals("县城的线程池", correct("县城的线程池"));
    }

    /** 错写不在词典里但能纠回，说明纠错来自规则而非词条 */
    @Test
    void traceRecordsRuleAndWindow() {
        CorrectionResult result = service.correct("三源运算符");
        CorrectionTrace trace = result.traces().get(0);
        assertEquals("三源运算符", trace.before());
        assertEquals("三元运算符", trace.term());
        assertTrue(trace.confidence() > 0.9, "同音精确匹配的置信度应高于 0.9");
    }
}