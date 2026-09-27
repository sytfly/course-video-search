package com.course.vsearch.service.correct;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 术语纠错（ASR 后处理）：
 * 1. canonical —— 拉丁术语大小写归一（redis/REDIS → Redis），最高置信；
 * 2. fuzzy     —— 连续拉丁 token 与术语归一化编辑距离 ≥0.85 才纠（高置信阈值，防误纠）；
 * 2b. latin-phonetic —— ASR 按英文读音「听拼」出的拉丁串（reice→Redis、conttrol→Ctrl、
 *                foalse→false、sstem→System），普通编辑距离够不着；由 LatinPhoneticMatcher 在
 *                Java 标准面/课程领域词的有限正确面上按加权音近距离收敛，带首字母、长度带、
 *                合法英文词豁免、歧义 margin 等多重护栏，详见该类；
 * 3. alias     —— 词典别名（"瑞迪斯" → Redis，拉丁术语的音译错写），长度受 minAliasLength 约束防单字误伤；
 * 4. phrase    —— 短语级修正：错写本身是合法词、只在特定上下文里才错的，必须写完整短语
 *                （如「复制给一个变量」→「赋值给一个变量」；不能把「复制」「复值」做成别名，
 *                 否则「复制一行」「重复值」会被误伤）；
 * 5. pinyin    —— 中文同音错写的通用解：把文本窗口与术语都转无调拼音做精确匹配。
 *                <p><b>为什么需要它</b>：ASR 的同音错写变体是无限的（同一节课就有「3元运算符/三原运算符/
 *                三元运算负」多种写法），逐条枚举别名永远追不上。拼音匹配把「无限的错写」收敛到
 *                「有限的术语表」——任意未见过的同音错写都能自动命中，不需要维护成本。
 *                <p><b>边界</b>：拼音只能处理「同音且错写本身不成词」的情况。同音但本身是合法词的错写
 *                （「复制给」的复制、「复值」是「重复值」的子串）拼音无法区分，只能靠 phrase 写明确短语。
 *                窗口长度下限 minPinyinLength 默认 3：同音碰撞在短词上不可接受（线程/县城 同音）。
 * 全部纠错留痕 CorrectionTrace，供评估纠错准确率/误纠率。
 */
@Slf4j
@Service
public class TerminologyService {

    /**
     * 拉丁 token：首词 + 允许以空格连接孤立单字母（如 "S Q L"）。
     * 负向前瞻保证延展的是单字母，不会把 "love SQL" 这种正常英文句子合并。
     */
    private static final Pattern LATIN_TOKEN =
            Pattern.compile("[A-Za-z][A-Za-z0-9]*(?:\\s+[A-Za-z0-9](?![A-Za-z0-9]))*");
    private static final double FUZZY_THRESHOLD = 0.85;
    /** 拼音精确同音匹配的置信度折扣：同音不同字的碰撞客观存在，故略低于显式别名 */
    private static final double PINYIN_CONFIDENCE_FACTOR = 0.95;
    /** 单窗口拼音组合数上限：多音字叠加会导致组合爆炸，超限即放弃该窗口（宁可漏纠不可误纠） */
    private static final int MAX_PINYIN_FORMS = 8;
    private static final int DEFAULT_MIN_PINYIN_LENGTH = 3;
    /** 数字读法：使「3元运算符」的拼音与「三元运算符」一致，从而被同一条规则自动收敛 */
    private static final String[] DIGIT_PINYIN =
            {"ling", "yi", "er", "san", "si", "wu", "liu", "qi", "ba", "jiu"};
    private static final HanyuPinyinOutputFormat PINYIN_FORMAT = new HanyuPinyinOutputFormat();

    static {
        PINYIN_FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        PINYIN_FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        PINYIN_FORMAT.setVCharType(HanyuPinyinVCharType.WITH_V);
    }

    private List<TechTerm> terms = List.of();
    private List<Fix> phrases = List.of();
    private int minAliasLength = 2;
    private int minPinyinLength = DEFAULT_MIN_PINYIN_LENGTH;
    /** 无调拼音 → 术语，由术语表构建（有限集合），文本侧任意同音错写查表即可收敛 */
    private Map<String, TechTerm> pinyinIndex = Map.of();
    /** 可匹配的术语字数（降序）：扫描窗口按此长度尝试，长窗优先 */
    private int[] pinyinLengths = new int[0];
    /** 拉丁技术词音近纠错（reice→Redis、conttrol→Ctrl 等 ASR 听拼错误），正确面来自 latin-surface.json */
    private LatinPhoneticMatcher latinPhonetic;

    @PostConstruct
    public void load() {
        try (InputStream in = new ClassPathResource("dictionary/tech-terms.json").getInputStream()) {
            ObjectMapper mapper = new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            DictionaryFile file = mapper.readValue(in, DictionaryFile.class);
            this.terms = file.terms() == null ? List.of() : file.terms();
            this.phrases = file.phrases() == null ? List.of() : file.phrases();
            this.minAliasLength = file.minAliasLength();
            this.minPinyinLength = file.minPinyinLength() <= 0
                    ? DEFAULT_MIN_PINYIN_LENGTH : file.minPinyinLength();
            buildPinyinIndex();
            loadLatinSurface();
            log.info("技术词典加载完成：{} 个术语、{} 条短语修正、拼音索引 {} 条（窗口下限 {} 字）",
                    terms.size(), phrases.size(), pinyinIndex.size(), minPinyinLength);
        } catch (Exception e) {
            throw new IllegalStateException("技术词典加载失败: " + e.getMessage(), e);
        }
    }

    /** 拉丁音近纠错正确面：Java 标准面（latin-surface.json）+ 技术词典中的拉丁术语（Redis 等） */
    private void loadLatinSurface() throws Exception {
        List<TechTerm> latinTechTerms = terms.stream()
                .filter(t -> isLatin(t.term()) && t.term().length() >= 2)
                .toList();
        try (InputStream in = new ClassPathResource("dictionary/latin-surface.json").getInputStream()) {
            this.latinPhonetic = LatinPhoneticMatcher.load(in, latinTechTerms);
            log.info("拉丁音近纠错正确面加载完成：{} 个候选词", latinPhonetic.candidateCount());
        }
    }

    public CorrectionResult correct(String text) {
        List<CorrectionTrace> traces = new ArrayList<>();
        String result = correctLatinTokens(text, traces);
        result = replaceAliases(result, traces);
        result = replacePhrases(result, traces);
        result = replaceByPinyin(result, traces);
        return new CorrectionResult(text, result, traces);
    }

    /** 规则 1+2：拉丁 token 级别替换 */
    private String correctLatinTokens(String text, List<CorrectionTrace> traces) {
        Matcher matcher = LATIN_TOKEN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group();
            String replacement = matchToken(token, traces);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 正则会把「空格连接的孤立单字母」并进 token（S Q L、conttrol C）。compact 后首词与尾字母
     * 黏成 conttrolC 会被音近规则的内部大写护栏拒绝，但空格本身是语音边界——故先只对首词纠错，
     * 命中则保留原空格与单字母尾巴（conttrol C → Ctrl C）；首词不命中再退回整串匹配。
     * 真正无空格的代码黏连（intI、CcontrlV 的整体）不会被拆，仍按保守策略原样保留。
     */
    private String matchToken(String token, List<CorrectionTrace> traces) {
        int space = token.indexOf(' ');
        if (space > 0) {
            String base = token.substring(0, space);
            String fixed = matchTerm(base, traces);
            if (!fixed.equals(base)) {
                return fixed + token.substring(space);
            }
        }
        return matchTerm(token, traces);
    }

    private String matchTerm(String token, List<CorrectionTrace> traces) {
        String compact = token.replaceAll("\\s+", "");
        for (TechTerm term : terms) {
            // 规则 1：忽略大小写完全相等 → 直接归一
            if (compact.equalsIgnoreCase(term.term())) {
                if (!compact.equals(term.term())) {
                    traces.add(new CorrectionTrace("canonical", token, term.term(), term.confidence()));
                }
                return term.term();
            }
        }
        // 规则 2：归一化编辑距离（仅对足够长的 token，阈值高）
        if (compact.length() >= 4) {
            TechTerm best = null;
            double bestSim = 0;
            for (TechTerm term : terms) {
                if (!isLatin(term.term())) {
                    continue;
                }
                double sim = similarity(compact.toLowerCase(), term.term().toLowerCase());
                if (sim > bestSim) {
                    bestSim = sim;
                    best = term;
                }
            }
            if (best != null && bestSim >= FUZZY_THRESHOLD) {
                traces.add(new CorrectionTrace("fuzzy", token, best.term(),
                        best.confidence() * bestSim));
                return best.term();
            }
        }
        // 规则 2b：拉丁音近匹配（ASR 听拼：reice→Redis、conttrol→Ctrl、foalse→false、sstem→System）
        if (latinPhonetic != null) {
            LatinPhoneticMatcher.Hit phonetic = latinPhonetic.match(compact);
            if (phonetic != null) {
                traces.add(new CorrectionTrace("latin-phonetic", token, phonetic.term(),
                        phonetic.confidence() * phonetic.score()));
                return phonetic.term();
            }
        }
        return token;
    }

    /** 规则 3：词典别名直接替换（含中文音译与中文术语的同音错写） */
    private String replaceAliases(String text, List<CorrectionTrace> traces) {
        List<Fix> fixes = new ArrayList<>();
        for (TechTerm term : terms) {
            if (term.aliases() == null) {
                continue;
            }
            for (String alias : term.aliases()) {
                if (alias.length() >= minAliasLength) {
                    fixes.add(new Fix(alias, term.term(), term.confidence()));
                }
            }
        }
        // 长别名优先：短别名先命中会把长别名吃掉（如「3元运算符」被形如「3元」的别名抢先替换）
        fixes.sort(Comparator.comparingInt((Fix f) -> f.from().length()).reversed());
        String result = text;
        for (Fix fix : fixes) {
            result = replaceAll(result, fix, "alias", traces);
        }
        return result;
    }

    /** 规则 4：短语级修正 */
    private String replacePhrases(String text, List<CorrectionTrace> traces) {
        String result = text;
        for (Fix fix : phrases) {
            if (fix.from() == null || fix.to() == null || fix.from().length() < minAliasLength) {
                continue;
            }
            result = replaceAll(result, fix, "phrase", traces);
        }
        return result;
    }

    /**
     * 规则 5：拼音匹配。单趟扫描文本，在每个位置按「术语字数从长到短」尝试窗口，
     * 窗口拼音命中术语表即替换，然后跳过整个已替换区间（避免在替换结果里二次匹配）。
     */
    private String replaceByPinyin(String text, List<CorrectionTrace> traces) {
        if (pinyinLengths.length == 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            TechTerm hit = null;
            int hitLength = 0;
            String hitWindow = null;
            for (int len : pinyinLengths) {
                if (i + len > text.length()) {
                    continue;
                }
                String window = text.substring(i, i + len);
                TechTerm term = lookupPinyin(window);
                if (term != null && !term.term().equals(window)) {
                    hit = term;
                    hitLength = len;
                    hitWindow = window;
                    break;
                }
            }
            if (hit == null) {
                sb.append(text.charAt(i));
                i++;
                continue;
            }
            sb.append(hit.term());
            traces.add(new CorrectionTrace("pinyin", hitWindow, hit.term(),
                    hit.confidence() * PINYIN_CONFIDENCE_FACTOR));
            i += hitLength;
        }
        return sb.toString();
    }

    /** 窗口的同音术语；拼音签名不含声调，故「3元运算符/三原运算符/三元运算负」都命中同一术语 */
    private TechTerm lookupPinyin(String window) {
        for (String form : pinyinForms(window)) {
            TechTerm term = pinyinIndex.get(form);
            if (term != null) {
                return term;
            }
        }
        return null;
    }

    /** 术语表的拼音索引：只收「全部由汉字或数字构成」且不少于 minPinyinLength 字的术语 */
    private void buildPinyinIndex() {
        Map<String, TechTerm> index = new HashMap<>();
        Set<Integer> lengths = new TreeSet<>();
        for (TechTerm term : terms) {
            Set<String> forms = pinyinForms(term.term());
            if (forms.isEmpty()) {
                continue;
            }
            lengths.add(term.term().length());
            for (String form : forms) {
                TechTerm prev = index.put(form, term);
                if (prev != null && !prev.term().equals(term.term())) {
                    log.warn("拼音索引冲突：{} 同时对应「{}」与「{}」，按后写入者生效",
                            form, prev.term(), term.term());
                }
            }
        }
        this.pinyinIndex = Map.copyOf(index);
        // 降序：长窗优先，避免短窗先命中把长术语切碎
        this.pinyinLengths = lengths.stream()
                .sorted(Comparator.reverseOrder())
                .mapToInt(Integer::intValue)
                .toArray();
    }

    /**
     * 字符串的无调拼音候选集合（多音字按所有读音展开）。
     * 含汉字/数字以外的字符即返回空集——拉丁术语不做拼音匹配；
     * 组合数超上限同样放弃，宁可漏纠不可误纠。
     */
    private Set<String> pinyinForms(String text) {
        if (text.length() < minPinyinLength) {
            return Set.of();
        }
        Set<String> forms = new HashSet<>();
        forms.add("");
        for (char c : text.toCharArray()) {
            List<String> readings = readingsOf(c);
            if (readings.isEmpty()) {
                return Set.of();
            }
            Set<String> next = new HashSet<>();
            for (String base : forms) {
                for (String reading : readings) {
                    next.add(base + reading);
                }
            }
            if (next.size() > MAX_PINYIN_FORMS) {
                return Set.of();
            }
            forms = next;
        }
        return forms;
    }

    /** 单字读音（无调）：数字按中文读法，汉字取拼音，其余字符返回空表示不可拼音匹配 */
    private static List<String> readingsOf(char c) {
        if (c >= '0' && c <= '9') {
            return List.of(DIGIT_PINYIN[c - '0']);
        }
        if (c < 0x4E00 || c > 0x9FFF) {
            return List.of();
        }
        try {
            String[] readings = PinyinHelper.toHanyuPinyinStringArray(c, PINYIN_FORMAT);
            return readings == null || readings.length == 0 ? List.of() : List.of(readings);
        } catch (BadHanyuPinyinOutputFormatCombination e) {
            return List.of();
        }
    }

    /**
     * 全文替换并按实际命中次数逐条留痕（痕迹条数 = 真实替换次数），
     * 使「纠回多少」可以直接用痕迹统计衡量。
     */
    private static String replaceAll(String text, Fix fix, String rule, List<CorrectionTrace> traces) {
        int idx = text.indexOf(fix.from());
        if (idx < 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        int pos = 0;
        int hits = 0;
        while (idx >= 0) {
            sb.append(text, pos, idx).append(fix.to());
            pos = idx + fix.from().length();
            hits++;
            idx = text.indexOf(fix.from(), pos);
        }
        sb.append(text, pos, text.length());
        for (int i = 0; i < hits; i++) {
            traces.add(new CorrectionTrace(rule, fix.from(), fix.to(), fix.confidence()));
        }
        return sb.toString();
    }

    private static boolean isLatin(String s) {
        return s.matches("[A-Za-z0-9]+");
    }

    /** 归一化编辑距离相似度：1 - dist / maxLen */
    static double similarity(String a, String b) {
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) {
            return 1;
        }
        return 1.0 - (double) levenshtein(a, b) / maxLen;
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            //noinspection SuspiciousNameCombination
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    private record DictionaryFile(int minAliasLength, int minPinyinLength,
                                 List<TechTerm> terms, List<Fix> phrases) {
    }

    /**
     * 单条「错写 → 正确写法」修正。
     * JSON 里可额外写 reason 字段记录该条的实测依据，代码不解析，仅作文档（读词典时可见原因）。
     */
    private record Fix(String from, String to, double confidence) {
    }
}
