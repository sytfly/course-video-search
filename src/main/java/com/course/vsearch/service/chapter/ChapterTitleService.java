package com.course.vsearch.service.chapter;

import com.course.vsearch.config.VSearchProperties;
import com.course.vsearch.observability.BusinessMetrics;
import com.course.vsearch.service.ratelimit.ApiRateLimiter;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * LangChain4j + 硅基流动 LLM 生成章节标题（OpenAI 兼容协议）。
 * 未配置 Key 或关闭开关时优雅降级：返回 null，不阻断主流水线。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterTitleService {

    private static final String SYSTEM_PROMPT = """
            你是技术课程视频的章节标题生成器。根据给定的课程片段文字，生成一个不超过20个字的中文章节标题。
            要求：只输出标题本身，不要引号、序号、标点结尾和任何解释；标题要具体，优先保留技术名词（如 Redis、RDB、AOF、Kafka）。
            """;

    private final VSearchProperties props;
    private final ApiRateLimiter rateLimiter;
    private final BusinessMetrics metrics;

    private ChatModel chatModel;

    @PostConstruct
    public void init() {
        String apiKey = props.getSiliconflow().getApiKey();
        if (!props.getChapter().isEnabled() || !StringUtils.hasText(apiKey)) {
            log.warn("章节标题生成未启用（chapter.enabled=false 或未配置 SILICONFLOW_API_KEY）");
            return;
        }
        this.chatModel = OpenAiChatModel.builder()
                .baseUrl(props.getSiliconflow().getBaseUrl())
                .apiKey(apiKey)
                .modelName(props.getSiliconflow().getChatModel())
                .maxTokens(64)
                .temperature(0.3)
                .timeout(Duration.ofSeconds(30))
                .maxRetries(2)
                .build();
        log.info("章节标题模型初始化完成: {}", props.getSiliconflow().getChatModel());
    }

    public String generateTitle(String segmentText) {
        if (chatModel == null) {
            return null;
        }
        try {
            rateLimiter.acquire();
            // 标题只看开头 800 字即可，控制 token 成本
            String snippet = segmentText.length() > 800 ? segmentText.substring(0, 800) : segmentText;
            ChatResponse response = chatModel.chat(ChatRequest.builder()
                    .messages(SystemMessage.from(SYSTEM_PROMPT),
                            UserMessage.from("片段文字：" + snippet))
                    .build());
            // 章节标题走 LangChain4j 自带的 maxRetries，不经 RetryExecutor，
            // 故这里单独记外部调用：否则「第三方调用次数」会漏掉 LLM 这一路
            metrics.externalCall("chat", "ok");
            String title = response.aiMessage().text();
            if (title != null) {
                title = title.replaceAll("[\\r\\n\"“”]", "").trim();
                if (title.length() > 50) {
                    title = title.substring(0, 50);
                }
            }
            return title;
        } catch (Exception e) {
            // 标题是 P1 增强能力，失败不阻断入库
            metrics.externalCall("chat", "error");
            log.warn("章节标题生成失败，跳过: {}", e.getMessage());
            return null;
        }
    }
}
