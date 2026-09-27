package com.course.vsearch.mq;

import com.course.vsearch.service.pipeline.InFlightTaskRegistry;
import com.course.vsearch.service.pipeline.VideoProcessService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * 本地线程池实现（默认）：开发期无需部署 RocketMQ 即可跑通全链路。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "vsearch.pipeline.type", havingValue = "local", matchIfMissing = true)
public class LocalPipelineGateway implements PipelineGateway {

    private final Executor executor;
    private final VideoProcessService processService;
    private final InFlightTaskRegistry inFlight;

    public LocalPipelineGateway(@Qualifier("videoProcessExecutor") Executor executor,
                                VideoProcessService processService,
                                InFlightTaskRegistry inFlight) {
        this.executor = executor;
        this.processService = processService;
        this.inFlight = inFlight;
    }

    @Override
    public void submit(String videoId) {
        log.info("提交本地处理任务: {}", videoId);
        // 先登记再投递：从这一刻到 process() 收尾之间任务都可能「状态未完成 + 锁空闲」（还在池里排队），
        // 登记后 GET /api/video/{id} 才不会把排队误判成中断。见 InFlightTaskRegistry
        inFlight.mark(videoId);
        executor.execute(() -> processService.process(videoId));
    }
}
