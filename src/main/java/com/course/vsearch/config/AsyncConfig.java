package com.course.vsearch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 本地异步处理线程池（pipeline.type=local 时使用，等价替代 MQ 的解耦作用）。
 */
@Configuration
public class AsyncConfig {

    @Bean("videoProcessExecutor")
    public Executor videoProcessExecutor(VSearchProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getPipeline().getLocalThreads());
        executor.setMaxPoolSize(props.getPipeline().getLocalThreads());
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("video-process-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 上传预处理池（归一化 / 双轨合并 / 写对象存储）。
     * 必须独立于 videoProcessExecutor：后者只有 2 线程且被处理流水线独占，处理一个视频要几分钟，
     * 复用会让上传请求在返回 taskId 之前排队干等，异步化也就失去意义。
     */
    @Bean("uploadFinalizeExecutor")
    public Executor uploadFinalizeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("upload-finalize-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * ASR 音频块的块级并发池。
     * 必须独立于 videoProcessExecutor：后者只有 2 线程且被 LocalPipelineGateway 独占，
     * 复用会导致两个视频并行处理时块级并发退化成串行。
     */
    @Bean("asrExecutor")
    public ThreadPoolTaskExecutor asrExecutor(VSearchProperties props) {
        int n = props.getPipeline().getAsrConcurrency();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(n);
        executor.setMaxPoolSize(n);
        // 任务总数由音频块数决定（1 小时视频约 180 块），不存在无界提交
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("asr-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
