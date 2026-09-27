package com.course.vsearch.mq;

/**
 * 视频处理任务投递入口。
 * 本地线程池 / RocketMQ 两种实现，按配置切换。
 */
public interface PipelineGateway {

    void submit(String videoId);
}
