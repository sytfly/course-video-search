package com.course.vsearch.mq;

import com.course.vsearch.config.VSearchProperties;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RocketMQ 客户端装配，仅在 vsearch.pipeline.type=rocketmq 时生效。
 */
@Configuration
@ConditionalOnProperty(name = "vsearch.pipeline.type", havingValue = "rocketmq")
public class RocketMqConfig {

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQProducer mqProducer(VSearchProperties props) {
        DefaultMQProducer producer = new DefaultMQProducer(props.getRocketmq().getGroup());
        producer.setNamesrvAddr(props.getRocketmq().getNameServer());
        producer.setSendMsgTimeout(5000);
        return producer;
    }

    @Bean(destroyMethod = "shutdown")
    public DefaultMQPushConsumer mqConsumer(VSearchProperties props) {
        DefaultMQPushConsumer consumer =
                new DefaultMQPushConsumer(props.getRocketmq().getConsumerGroup());
        consumer.setNamesrvAddr(props.getRocketmq().getNameServer());
        // 处理是分钟级任务，限流由业务线程承担；这里控制消费线程数
        consumer.setConsumeThreadMin(1);
        consumer.setConsumeThreadMax(2);
        return consumer;
    }
}
