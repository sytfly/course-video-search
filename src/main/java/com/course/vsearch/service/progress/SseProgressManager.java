package com.course.vsearch.service.progress;

import com.course.vsearch.common.JsonUtils;
import com.course.vsearch.constant.ProcessStage;
import com.course.vsearch.dto.ProgressEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 维护 taskId -> SseEmitter，订阅 Redis Topic 完成跨实例转发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseProgressManager {

    private final RedissonClient redisson;
    private final ProgressService progressService;

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private int listenerId;

    @PostConstruct
    public void subscribe() {
        RTopic topic = redisson.getTopic(ProgressService.CHANNEL);
        listenerId = topic.addListener(String.class, (channel, json) -> dispatch(json));
    }

    @PreDestroy
    public void unsubscribe() {
        redisson.getTopic(ProgressService.CHANNEL).removeListener(listenerId);
        emitters.values().forEach(SseEmitter::complete);
    }

    public SseEmitter connect(String taskId) {
        // 0 = 不超时
        SseEmitter emitter = new SseEmitter(0L);
        emitters.put(taskId, emitter);
        emitter.onCompletion(() -> emitters.remove(taskId, emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            emitters.remove(taskId, emitter);
        });
        emitter.onError(e -> emitters.remove(taskId, emitter));

        // 先回放最新快照（晚加入连接也能立即看到当前阶段）
        ProgressEvent snapshot = progressService.snapshot(taskId);
        if (snapshot != null) {
            send(emitter, snapshot);
        }
        return emitter;
    }

    private void dispatch(String json) {
        try {
            ProgressEvent event = JsonUtils.fromJson(json, ProgressEvent.class);
            SseEmitter emitter = emitters.get(event.getTaskId());
            if (emitter != null) {
                send(emitter, event);
                if (ProcessStage.DONE.equals(event.getStage())
                        || ProcessStage.FAILED.equals(event.getStage())) {
                    emitter.complete();
                    emitters.remove(event.getTaskId(), emitter);
                }
            }
        } catch (Exception e) {
            log.warn("SSE 分发失败: {}", e.getMessage());
        }
    }

    private void send(SseEmitter emitter, ProgressEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(JsonUtils.toJson(event)));
        } catch (IOException e) {
            log.debug("SSE 发送失败，连接可能已断开: {}", e.getMessage());
        }
    }
}
