package com.course.vsearch.service.correct;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 拉丁技术词的「中式耳音」音近匹配：拼音纠错的姊妹篇。
 *
 * <p><b>问题形态</b>：讲师按英文发音念技术词，ASR 按音节拼出拉丁串，错写是<b>听</b>出来的而不是
 * 打出来的，因此与正确词的普通编辑距离可以很远（Redis→reice/reis/raice、Lua→lu/luva、
 * Ctrl→conttrol/contrl、false→foalse/forse、System→sstem）。错写空间开放，正确面有限
 * （Java 关键字/JDK 标识符/课程领域词），故与拼音纠错同构：不枚举错写，只维护正确面，
 * 用音近距离把任意错听收敛回正确面。
 *
 * <p><b>距离模型</b>（加权 Levenshtein，阈值在全库 replay 上校准）：
 * <ul>
 *   <li>元音之间替换 0.25、元音增删 0.25（a/e/i/o/u 在弱听写下互相覆盖）；</li>
 *   <li>辅音混淆对 r↔l、c↔s 替换 0.4（r/l 是汉语者经典混淆；c 在拼音式拼写里常发 /s/）；</li>
 *   <li>第一次辅音增删 0.35（弱读辅音脱落，如 Redis 中间的 d），其后 1.0；</li>
 *   <li>其余辅音替换 1.2、元辅跨界 1.0。</li>
 * </ul>
 *
 * <p><b>护栏</b>（保守第一版，宁漏勿误）：token 含数字不碰（K4/ide2）；全大写或首字母后还有
 * 大写字母不碰（STRA/AR/intI/IDD——代码变量与黏连代码留给后续版本）；长度不足/长度比越界不碰；
 * 命中 stopwords（语料实证的合法英文词，折叠前后各查一次）不碰；token 与任一正确面精确同形
 * 一律不碰（全库 replay 实证：否则 java 会被 JVM 抢、false 会被 File 抢）；硬门：首字母必须
 * 相同；最终候选必须达到阈值且对次优候选有足够 margin（避免歧义）。
 */
final class LatinPhoneticMatcher {

    record Candidate(String term, double confidence, List<String> spoken) {
    }

    record Hit(String term, double score, double confidence) {
    }

    private final List<Candidate> candidates;
    private final Set<String> stopwords;
    /** 正确面折叠形态：token 已是正确拼写时绝不纠（否则 java 会被 JVM、false 会被 File 抢走） */
    private final Set<String> exactForms;
    private final double threshold;
    private final double shortTokenThreshold;
    private final double margin;
    private final int minTokenLength;

    private LatinPhoneticMatcher(List<Candidate> candidates, Set<String> stopwords,
                                 double threshold, double shortTokenThreshold,
                                 double margin, int minTokenLength) {
        this.candidates = candidates;
        this.stopwords = stopwords;
        Set<String> forms = new HashSet<>();
        for (Candidate c : candidates) {
            forms.add(collapseDoubles(c.term().toLowerCase(Locale.ROOT)));
        }
        this.exactForms = Set.copyOf(forms);
        this.threshold = threshold;
        this.shortTokenThreshold = shortTokenThreshold;
        this.margin = margin;
        this.minTokenLength = minTokenLength;
    }

    static LatinPhoneticMatcher load(InputStream surfaceJson, List<TechTerm> latinTechTerms)
            throws IOException {
        JsonNode root = new ObjectMapper().readTree(surfaceJson);
        double threshold = root.path("threshold").asDouble(0.76);
        double shortThreshold = root.path("shortTokenThreshold").asDouble(0.9);
        double margin = root.path("margin").asDouble(0.05);
        int minLen = root.path("minTokenLength").asInt(2);

        Set<String> stops = new HashSet<>();
        for (JsonNode s : root.path("stopwords")) {
            stops.add(s.asText().toLowerCase(Locale.ROOT));
        }

        List<Candidate> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode group : root.path("surface")) {
            double confidence = group.path("confidence").asDouble(0.9);
            for (JsonNode item : group.path("terms")) {
                if (item.isTextual()) {
                    addCandidate(list, seen, item.asText(), confidence, List.of());
                } else {
                    List<String> spoken = new ArrayList<>();
                    for (JsonNode sp : item.path("spoken")) {
                        spoken.add(collapseDoubles(sp.asText().toLowerCase(Locale.ROOT)));
                    }
                    addCandidate(list, seen, item.path("term").asText(), confidence, spoken);
                }
            }
        }
        // tech-terms.json 中的拉丁术语（Redis/Kafka/...）并入正确面，置信度取词典自身值
        for (TechTerm tech : latinTechTerms) {
            if (tech.term().matches("[A-Za-z0-9]+") && tech.term().length() >= 2) {
                addCandidate(list, seen, tech.term(), tech.confidence(), List.of());
            }
        }
        return new LatinPhoneticMatcher(List.copyOf(list), Set.copyOf(stops),
                threshold, shortThreshold, margin, minLen);
    }

    private static void addCandidate(List<Candidate> list, Set<String> seen,
                                     String term, double confidence, List<String> spoken) {
        if (term == null || term.isBlank()) {
            return;
        }
        String key = term.toLowerCase(Locale.ROOT);
        if (seen.add(key)) {
            list.add(new Candidate(term, confidence, spoken));
        }
    }

    /**
     * @return 命中的正确词与音近得分；未命中返回 null
     */
    Hit match(String rawToken) {
        if (rawToken == null || rawToken.length() < minTokenLength) {
            return null;
        }
        boolean hasLower = false;
        for (int i = 0; i < rawToken.length(); i++) {
            char c = rawToken.charAt(i);
            if (c >= '0' && c <= '9') {
                return null; // 含数字 token（K4/ide2）：第一版不处理
            }
            boolean upper = c >= 'A' && c <= 'Z';
            if (upper && i > 0) {
                return null; // 内部大写（STRA/intI/IDD）：变量与黏连代码留给后续版本
            }
            if (c >= 'a' && c <= 'z') {
                hasLower = true;
            }
        }
        if (!hasLower) {
            return null; // 全大写缩写（AR/GS）不碰
        }
        String lower = rawToken.toLowerCase(Locale.ROOT);
        // stopwords 在折叠前后都要查：address 折掉 dd 成 adress 后会绕过停用表漂向 Arrays
        if (stopwords.contains(lower)) {
            return null;
        }
        String heard = collapseDoubles(lower);
        if (stopwords.contains(heard) || exactForms.contains(heard)) {
            return null;
        }
        double required = heard.length() == 2 ? shortTokenThreshold : threshold;

        List<Scored> ranked = new ArrayList<>();
        for (Candidate candidate : candidates) {
            List<String> targets = new ArrayList<>(candidate.spoken().size() + 1);
            targets.add(collapseDoubles(candidate.term().toLowerCase(Locale.ROOT)));
            targets.addAll(candidate.spoken());
            for (int ti = 0; ti < targets.size(); ti++) {
                String target = targets.get(ti);
                if (ti == 0 && heard.equals(target)) {
                    // 已与正确面完全一致：无需纠错（大小写归一不在本规则范围），跳过避免空痕迹
                    continue;
                }
                if (heard.equals(target)) {
                    // control 是 Ctrl 的完整读音形态：等值即高置信直取
                    return new Hit(candidate.term(), 0.98, candidate.confidence());
                }
                if (heard.charAt(0) != target.charAt(0)) {
                    continue;
                }
                // 长度带非对称：错听只会丢音（heard 更短）或多加一个弱元音（luva/foalse），
                // heard 比 target 长出 2 个以上必非该词——否则 forse 会被短词 for 以「纯删除」抢走
                if (heard.length() > target.length() + 1) {
                    continue;
                }
                double ratio = (double) Math.min(heard.length(), target.length())
                        / Math.max(heard.length(), target.length());
                if (ratio < 0.6) {
                    continue;
                }
                Alignment alignment = align(heard, target);
                if (alignment.score() > 0) {
                    ranked.add(new Scored(candidate.term(), candidate.confidence(),
                            alignment.score(), alignment.penalty()));
                }
            }
        }
        if (ranked.isEmpty()) {
            return null;
        }
        ranked.sort(Comparator.comparingDouble(Scored::score).reversed());
        Scored top = ranked.get(0);
        if (top.score() < required) {
            return null;
        }
        // 同分（≤0.02）视为等价，取缺口位置最靠前的：前缀弱音脱落（sstem 丢 sy → System）
        // 天然比中后段插音（→ Stream）更符合 ASR 错听形态
        List<Scored> tieGroup = new ArrayList<>();
        for (Scored s : ranked) {
            if (top.score() - s.score() <= 0.02) {
                tieGroup.add(s);
            } else {
                break;
            }
        }
        Scored winner = tieGroup.stream()
                .min(Comparator.comparingDouble(Scored::penalty))
                .orElse(top);
        double nextTierScore = tieGroup.size() < ranked.size()
                ? ranked.get(tieGroup.size()).score() : 0.0;
        if (top.score() - nextTierScore < margin) {
            return null; // 与非同分候选差距不足：有歧义，宁漏勿误
        }
        return new Hit(winner.term(), winner.score(), winner.confidence());
    }

    /** 候选评分明细（penalty 为缺口位置罚分，越小缺口越靠前） */
    private record Scored(String term, double confidence, double score, double penalty) {
    }

    int candidateCount() {
        return candidates.size();
    }

    /**
     * 折叠相邻重复字母，模拟 ASR 的辅音拖长（conttrol→control、sstem→stem）。
     * 只折辅音：元音双拼是真实拼写（TreeMap 的 ee、loop 的 oo），折叠会把目标词改坏。
     */
    static String collapseDoubles(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i > 0 && c == s.charAt(i - 1) && !isVowel(c)) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 音近相似度：1 - 加权编辑距离 / 较长串长度；任何「预算外」对齐直接判不相似（返回 0）。
     *
     * <p>预算（在全库实测变体上归纳，防止短词靠一堆廉价缺口漂向长词）：
     * <ul>
     *   <li>k：辅音增删全局至多 1 次（弱读辅音脱落，Redis 中间的 d / luva 末尾的 v）；</li>
     *   <li>v：元音替换/增删全局至多 2 次（a/e/i/o/u/y 在弱听写下互相覆盖或脱落）；</li>
     *   <li>对齐只允许「相同 / 元音互换 / r↔l / c↔s」，硬辅音替换与元辅跨界一律禁止。</li>
     * </ul>
     * 实证：reice/raice/rease→Redis 用满 1 辅缺 + 2 元音操作（rease 走 a→i 元音对齐）；
     * tamp 想漂向 TreeMap 需要超预算操作（≥2 个辅音缺口或 3 个元音操作），得 0 分；
     * thide→Thread 需要 i/r 元辅跨界对齐，同样被拒。
     */
    static double phoneticScore(String heard, String target) {
        return align(heard, target).score;
    }

    /** 对齐结果：score 为相似度；penalty 为所有缺口的位置和（越小越偏前缀，用于同分裁决） */
    private record Alignment(double score, double penalty) {
    }

    private static Alignment align(String heard, String target) {
        int n = heard.length();
        int m = target.length();
        double inf = Double.POSITIVE_INFINITY;
        // dp[i][j][k][v]：k=已用辅音缺口名额（0/1），v=已用元音操作数（0..MAX_VOWEL_OPS）
        double[][][][] dp = new double[n + 1][m + 1][2][MAX_VOWEL_OPS + 1];
        // pen：与 dp 同构的最小缺口位置和（同代价时优先前缀缺口，如 sstem→System 而非 Stream）
        double[][][][] pen = new double[n + 1][m + 1][2][MAX_VOWEL_OPS + 1];
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j <= m; j++) {
                for (int k = 0; k < 2; k++) {
                    Arrays.fill(dp[i][j][k], inf);
                    Arrays.fill(pen[i][j][k], inf);
                }
            }
        }
        dp[0][0][0][0] = 0;
        pen[0][0][0][0] = 0;
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j <= m; j++) {
                for (int k = 0; k < 2; k++) {
                    for (int v = 0; v <= MAX_VOWEL_OPS; v++) {
                        double base = dp[i][j][k][v];
                        if (base == inf) {
                            continue;
                        }
                        double basePen = pen[i][j][k][v];
                        if (i < n) { // 删除 heard 字符（位置按 heard 序号计）
                            CharacterOp op = gapOp(heard.charAt(i), k, v);
                            if (op != null) {
                                relax(dp, pen, i + 1, j, op.k, op.v,
                                        base + op.cost, basePen + (i + 1));
                            }
                        }
                        if (j < m) { // 插入 target 字符（位置按 target 序号计）
                            CharacterOp op = gapOp(target.charAt(j), k, v);
                            if (op != null) {
                                relax(dp, pen, i, j + 1, op.k, op.v,
                                        base + op.cost, basePen + (j + 1));
                            }
                        }
                        if (i < n && j < m) { // 对齐（含替换，不产生缺口位置）
                            CharacterOp op = subOp(heard.charAt(i), target.charAt(j), k, v);
                            if (op != null) {
                                relax(dp, pen, i + 1, j + 1, op.k, op.v,
                                        base + op.cost, basePen);
                            }
                        }
                    }
                }
            }
        }
        double cost = inf;
        double gapPenalty = inf;
        for (int k = 0; k < 2; k++) {
            for (int v = 0; v <= MAX_VOWEL_OPS; v++) {
                if (dp[n][m][k][v] < cost) {
                    cost = dp[n][m][k][v];
                    gapPenalty = pen[n][m][k][v];
                }
            }
        }
        if (cost == inf) {
            return new Alignment(0.0, inf);
        }
        return new Alignment(1.0 - cost / Math.max(n, m), gapPenalty / Math.max(n, m));
    }

    private static void relax(double[][][][] dp, double[][][][] pen,
                              int i, int j, int k, int v, double cost, double gapPenalty) {
        if (cost < dp[i][j][k][v] - 1e-12) {
            dp[i][j][k][v] = cost;
            pen[i][j][k][v] = gapPenalty;
        } else if (Math.abs(cost - dp[i][j][k][v]) <= 1e-12 && gapPenalty < pen[i][j][k][v]) {
            pen[i][j][k][v] = gapPenalty;
        }
    }

    private static final int MAX_VOWEL_OPS = 2;
    private static final double CHEAP_GAP = 0.35;
    private static final double VOWEL_GAP = 0.25;

    /** 一次编辑操作：代价 + 操作后状态；null 表示该操作超出预算/被禁止 */
    private record CharacterOp(double cost, int k, int v) {
    }

    private static CharacterOp gapOp(char c, int k, int v) {
        if (isVowel(c)) {
            return v < MAX_VOWEL_OPS ? new CharacterOp(VOWEL_GAP, k, v + 1) : null;
        }
        return k == 0 ? new CharacterOp(CHEAP_GAP, 1, v) : null;
    }

    private static CharacterOp subOp(char a, char b, int k, int v) {
        if (a == b) {
            return new CharacterOp(0, k, v);
        }
        boolean va = isVowel(a);
        boolean vb = isVowel(b);
        if (va && vb) {
            return v < MAX_VOWEL_OPS ? new CharacterOp(0.25, k, v + 1) : null;
        }
        if (va != vb) {
            return null; // 元辅跨界对齐禁止
        }
        if ((a == 'r' && b == 'l') || (a == 'l' && b == 'r')
                || (a == 'c' && b == 's') || (a == 's' && b == 'c')) {
            return new CharacterOp(0.4, k, v);
        }
        return null; // 硬辅音替换禁止
    }

    private static boolean isVowel(char c) {
        // y 按半元音处理：ASR 听拼时 y 常占元音位（system 的 y），按辅音计费会把 sstem 判给 Stream
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' || c == 'y';
    }
}
