package com.course.vsearch.service.progress;

import com.course.vsearch.common.JsonUtils;
import com.course.vsearch.config.VSearchProperties;
import com.course.vsearch.dto.ProgressEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 进度推送：最新事件写 Redis Hash（重启/晚加入的 SSE 连接可回放快照），
 * 同时 publish 到 Redis Topic，由各实例的 SseManager 接收转发给本地连接，实现跨实例。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProgressService {

    public static final String CHANNEL = "vsearch:progress:channel";
    private static final String KEY_PREFIX = "vsearch:progress:";

    private final RedissonClient redisson;
    private final VSearchProperties props;

    public void publish(ProgressEvent event) {
        String key = KEY_PREFIX + event.getTaskId();
        RMap<String, String> snapshot = redisson.getMap(key);
        snapshot.put("stage", event.getStage());
        snapshot.put("progress", String.valueOf(event.getProgress()));
        snapshot.put("message", event.getMessage() == null ? "" : event.getMessage());
        // 写入时刻：供 GET /api/video/{id} 判断任务是否已静默（服务端进程消失后进度不会再被刷新）
        snapshot.put("updatedAt", String.valueOf(System.currentTimeMillis()));
        snapshot.expire(props.getProgress().getTtlHours(), TimeUnit.HOURS);

        RTopic topic = redisson.getTopic(CHANNEL);
        topic.publish(JsonUtils.toJson(event));
        log.debug("进度推送 {} -> {} {}%", event.getTaskId(), event.getStage(), event.getProgress());
    }

    /** 最新进度事件的写入时刻（epoch 毫秒）；无快照返回 null，表示该任务从未推送过进度 */
    public Long lastUpdateMillis(String taskId) {
        RMap<String, String> snapshot = redisson.getMap(KEY_PREFIX + taskId);
        String ts = snapshot.get("updatedAt");
        if (ts == null) {
            return null;
        }
        try {
            return Long.parseLong(ts);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public ProgressEvent snapshot(String taskId) {
        RMap<String, String> snapshot = redisson.getMap(KEY_PREFIX + taskId);
        if (!snapshot.isExists() || !snapshot.containsKey("stage")) {
            return null;
        }
        return new ProgressEvent(
                taskId,
                snapshot.get("stage"),
                Integer.parseInt(snapshot.get("progress")),
                snapshot.get("message"));
    }

    /** 清除进度快照。失败任务重试前调用，避免晚加入的 SSE 连接回放旧的 failed 状态。 */
    public void clear(String taskId) {
        redisson.getMap(KEY_PREFIX + taskId).delete();
    }
}
