package com.course.vsearch.service.pipeline;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本进程内「已接手但尚未结束」的任务登记表：本地调度（上传预处理池、本地处理线程池）在投递时登记，
 * 处理结束时注销。
 * <p>
 * 存在的唯一目的：把「排队等锁」和「持有者已死」区分开。这两种情况在外部看来完全一样——
 * 状态未完成、处理锁空闲、进度也不再刷新；仅凭锁 + 进度静默无法区分，排队中的任务其锁本来就空闲，
 * 于是被 {@code GET /api/video/{id}} 误报成 interrupted（前端显示「任务已中断」）。
 * <p>
 * 登记表放在内存而不是 Redis：它必须随进程一起消失。进程被强杀后登记表自然为空，
 * 真正无人处理的任务才会被判为 interrupted；放在 Redis 里则会留下一份永远为真的陈旧标记，
 * 把真中断也一并漏报。
 * <p>
 * 只登记「确定在本进程执行」的投递（本地线程池）。走 MQ 的投递不登记：消息可能被别的实例消费，
 * 本进程无从判断，此时退回「锁空闲 + 进度静默」的旧启发式。
 */
@Component
public class InFlightTaskRegistry {

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /** 投递时登记（幂等，重复投递只留一份） */
    public void mark(String videoId) {
        inFlight.add(videoId);
    }

    /** 处理结束（正常完成 / 失败 / 幂等跳过）时注销 */
    public void clear(String videoId) {
        inFlight.remove(videoId);
    }

    /** 本进程是否已接手该任务（排队中或正在处理） */
    public boolean contains(String videoId) {
        return inFlight.contains(videoId);
    }

    /** 当前在手的任务数，供排查用 */
    public int size() {
        return inFlight.size();
    }
}