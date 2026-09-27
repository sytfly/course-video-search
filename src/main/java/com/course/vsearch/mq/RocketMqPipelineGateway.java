package com.course.vsearch.mq;

import com.course.vsearch.common.JsonUtils;
import com.course.vsearch.config.VSearchProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * RocketMQ 投递实现：上传接口只负责发消息，耗时处理全部异步化（接口响应 &lt;1s）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "vsearch.pipeline.type", havingValue = "rocketmq")
public class RocketMqPipelineGateway implements PipelineGateway {

    private final DefaultMQProducer producer;
    private final VSearchProperties props;

    public RocketMqPipelineGateway(DefaultMQProducer producer, VSearchProperties props) {
        this.producer = producer;
        this.props = props;
    }

    @Override
    public void submit(String videoId) {
        try {
            byte[] body = JsonUtils.toJson(new VideoProcessMessage(videoId)).getBytes(StandardCharsets.UTF_8);
            Message message = new Message(props.getRocketmq().getTopic(), "process", body);
            producer.send(message);
            log.info("RocketMQ 任务已投递: {}", videoId);
        } catch (Exception e) {
            throw new IllegalStateException("RocketMQ 投递失败: " + e.getMessage(), e);
        }
    }
}
