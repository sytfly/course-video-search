package com.course.vsearch.service.ai;

import com.course.vsearch.config.VSearchProperties;
import com.course.vsearch.service.ratelimit.ApiRateLimiter;
import com.course.vsearch.service.retry.RetryExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * bge-m3 向量化：批量调用 + 维度校验。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final SiliconFlowClient client;
    private final ApiRateLimiter rateLimiter;
    private final RetryExecutor retryExecutor;
    private final VSearchProperties props;

    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> all = new ArrayList<>(texts.size());
        int batchSize = props.getSiliconflow().getEmbeddingBatchSize();
        for (int from = 0; from < texts.size(); from += batchSize) {
            int to = Math.min(texts.size(), from + batchSize);
            List<String> batch = texts.subList(from, to);
            List<float[]> vectors = retryExecutor.execute(
                    "Embedding[" + from + "," + to + ")",
                    () -> {
                        rateLimiter.acquire();
                        SiliconFlowClient.EmbeddingResponse resp = client.embed(batch);
                        // API 不保证顺序，按 index 归位
                        List<float[]> ordered = new ArrayList<>(batch.size());
                        for (int i = 0; i < batch.size(); i++) {
                            ordered.add(null);
                        }
                        for (SiliconFlowClient.EmbeddingData d : resp.data()) {
                            List<Float> v = d.embedding();
                            float[] arr = new float[v.size()];
                            for (int j = 0; j < v.size(); j++) {
                                arr[j] = v.get(j);
                            }
                            ordered.set(d.index(), arr);
                        }
                        for (float[] v : ordered) {
                            if (v == null) {
                                throw new IllegalStateException("Embedding 返回缺少数据项");
                            }
                        }
                        return ordered;
                    },
                    SiliconFlowClient::isRetryable);
            all.addAll(vectors);
        }
        if (!all.isEmpty() && all.get(0).length != props.getSiliconflow().getEmbeddingDim()) {
            throw new IllegalStateException("Embedding 维度不匹配：实际 " + all.get(0).length
                    + "，配置 " + props.getSiliconflow().getEmbeddingDim()
                    + "（请同步调整 video_segment.embedding 列定义）");
        }
        return all;
    }
}
