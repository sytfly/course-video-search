package com.course.vsearch.service.ai;

import com.course.vsearch.common.BizException;
import com.course.vsearch.config.VSearchProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 硅基流动 OpenAI 兼容 API 客户端：ASR 与 Embedding。
 */
@Slf4j
@Component
public class SiliconFlowClient {

    private final RestClient restClient;
    private final VSearchProperties props;

    public SiliconFlowClient(RestClient siliconFlowRestClient, VSearchProperties props) {
        this.restClient = siliconFlowRestClient.mutate()
                .baseUrl(props.getSiliconflow().getBaseUrl())
                .build();
        this.props = props;
    }

    private static final String CRLF = "\r\n";

    /**
     * POST /v1/audio/transcriptions —— 官方仅返回 text，时间戳由分块偏移保证。
     *
     * 注意：不能用 MultiValueMap + FileSystemResource 的常规写法。硅基 ASR 网关不支持
     * Transfer-Encoding: chunked（实测返回 500 空响应体），而 Spring 的 SimpleClientHttpRequest
     * 对无法预知总长度的 multipart 请求会自动降级为 chunked 上传。因此这里手动拼装 multipart
     * 字节数组，使请求携带确定的 Content-Length（音频块 ≤30s/24kbps，约 90KB，缓冲无压力）。
     */
    public AsrResponse transcribe(Path audioFile) {
        ensureApiKey();
        byte[] fileBytes;
        try {
            fileBytes = Files.readAllBytes(audioFile);
        } catch (IOException e) {
            throw new BizException("读取音频块失败: " + audioFile.getFileName());
        }
        String boundary = "----vsearch" + UUID.randomUUID();
        String fileName = sanitizePartName(audioFile.getFileName().toString());
        String model = props.getSiliconflow().getAsrModel();

        ByteArrayOutputStream body = new ByteArrayOutputStream(fileBytes.length + 512);
        writeAscii(body, "--" + boundary + CRLF
                + "Content-Disposition: form-data; name=\"model\"" + CRLF + CRLF
                + model + CRLF
                + "--" + boundary + CRLF
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"" + CRLF
                + "Content-Type: audio/mpeg" + CRLF + CRLF);
        body.writeBytes(fileBytes);
        writeAscii(body, CRLF + "--" + boundary + "--" + CRLF);

        return restClient.post()
                .uri("/audio/transcriptions")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(new MediaType(MediaType.MULTIPART_FORM_DATA,
                        Map.of("boundary", boundary)))
                .body(body.toByteArray())
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleErrorResponse)
                .body(AsrResponse.class);
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
    }

    /** 防止文件名中的 CR/LF/引号破坏 multipart 结构（实际块名为应用生成的纯英文，此处为防御） */
    private static String sanitizePartName(String name) {
        return name.replaceAll("[\\r\\n\"]", "_");
    }

    /**
     * 统一错误处理：记录服务端返回体（硅基 500 常无 body，此时至少留状态码），
     * 并按状态码抛出标准异常，供 isRetryable 判定是否退避重试。
     */
    private void handleErrorResponse(org.springframework.http.HttpRequest request,
                                     ClientHttpResponse response) throws java.io.IOException {
        HttpStatusCode code = response.getStatusCode();
        byte[] bodyBytes = StreamUtils.copyToByteArray(response.getBody());
        String body = new String(bodyBytes, StandardCharsets.UTF_8);
        log.warn("硅基流动 ASR 调用失败: status={}, body={}", code.value(),
                body.isBlank() ? "(empty)" : body);
        if (code.is5xxServerError()) {
            throw HttpServerErrorException.create(code, code.toString(),
                    response.getHeaders(), bodyBytes, StandardCharsets.UTF_8);
        }
        throw HttpClientErrorException.create(code, code.toString(),
                response.getHeaders(), bodyBytes, StandardCharsets.UTF_8);
    }

    /** POST /v1/embeddings，支持字符串数组批量 */
    public EmbeddingResponse embed(List<String> inputs) {
        ensureApiKey();
        return restClient.post()
                .uri("/embeddings")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EmbeddingRequest(props.getSiliconflow().getEmbeddingModel(), inputs))
                .retrieve()
                .body(EmbeddingResponse.class);
    }

    private void ensureApiKey() {
        if (props.getSiliconflow().getApiKey() == null
                || props.getSiliconflow().getApiKey().isBlank()) {
            throw new BizException("未配置硅基流动 API Key，请设置环境变量 SILICONFLOW_API_KEY");
        }
    }

    private String bearer() {
        return "Bearer " + props.getSiliconflow().getApiKey();
    }

    /**
     * 可重试判定：429 限流 / 5xx 服务端错误（硅基 ASR 偶发 500/502 多为瞬时故障，
     * 配合指数退避重试）；400、401 等参数或鉴权错误立即失败，不浪费重试配额。
     */
    public static boolean isRetryable(Throwable t) {
        if (t instanceof HttpStatusCodeException e) {
            int code = e.getStatusCode().value();
            return code == 429 || code >= 500;
        }
        return t instanceof ResourceAccessException;
    }

    public record AsrResponse(String text) {
    }

    public record EmbeddingRequest(String model, List<String> input) {
    }

    public record EmbeddingResponse(List<EmbeddingData> data, Usage usage) {
    }

    public record EmbeddingData(List<Float> embedding, int index) {
    }

    public record Usage(int prompt_tokens, int total_tokens) {
    }
}
