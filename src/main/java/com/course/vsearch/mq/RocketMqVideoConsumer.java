package com.course.vsearch.mq;

import com.course.vsearch.common.JsonUtils;
import com.course.vsearch.config.VSearchProperties;
import com.course.vsearch.service.pipeline.VideoProcessService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * RocketMQ 消费者：调用视频处理流水线。
 * 业务失败（ASR/向量化异常）由流水线内部落库 FAILED 并正常 ACK；
 * 未预期的运行时异常返回 RECONSUME_LATER 走 MQ 重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "vsearch.pipeline.type", havingValue = "rocketmq")
public class RocketMqVideoConsumer {

    private final DefaultMQPushConsumer consumer;
    private final VSearchProperties props;
    private final VideoProcessService processService;

    @PostConstruct
    public void subscribe() throws Exception {
        consumer.subscribe(props.getRocketmq().getTopic(), "*");
        consumer.registerMessageListener(this::onMessages);
        consumer.start();
        log.info("RocketMQ 消费者已启动，topic={}", props.getRocketmq().getTopic());
    }

    private ConsumeConcurrentlyStatus onMessages(List<MessageExt> msgs,
                                                  org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext context) {
        for (MessageExt msg : msgs) {
            try {
                VideoProcessMessage payload = JsonUtils.fromJson(
                        new String(msg.getBody(), StandardCharsets.UTF_8), VideoProcessMessage.class);
                processService.process(payload.videoId());
            } catch (Exception e) {
                log.error("消息处理异常，稍后重试 msgId={}: {}", msg.getMsgId(), e.getMessage(), e);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    @PreDestroy
    public void shutdown() {
        consumer.shutdown();
    }
}
