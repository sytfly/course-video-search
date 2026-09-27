package com.course.vsearch.service.pipeline;

import com.course.vsearch.entity.Video;
import com.course.vsearch.mapper.VideoMapper;
import com.course.vsearch.security.TenantContext;
import com.course.vsearch.service.VideoAppService;
import com.course.vsearch.service.lock.DistributedLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 启动自愈：扫描 status ∈ {PENDING, PROCESSING} 的任务，凡是处理锁空闲的一律重新入队。
 * <p>
 * 背景：任务在中途随进程重启被强杀时，终态事件发不出去，DB 状态永远停在「处理中」、
 * 前端进度条永久定格；而原先的孤儿检测只挂在上传入口，必须重传同一文件才会触发。
 * 处理锁走 Redisson 看门狗，进程消失后 30 秒内自动释放，故「锁空闲」即「无人处理」。
 * <p>
 * 重新入队复用 videoId / MinIO 对象 / video_asr_chunk 断点，已识别过的块不会重跑。
 * <p>
 * 时序坑：进程刚被强杀时看门狗锁可能还没过期（≤30s），首扫会把这类任务误当「仍在处理」
 * 跳过且此后无人再扫 → 永久卡住。故对首扫时锁仍被持有的任务安排一次 35s 后的延迟复查：
 * 届时死任务的锁必已释放（活任务则仍被持有，继续不干预）。
 * 通过 vsearch.pipeline.startup-recovery=false 关闭。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "vsearch.pipeline.startup-recovery", havingValue = "true",
        matchIfMissing = true)
public class StartupRecoveryRunner implements ApplicationRunner {

    /** 延迟复查间隔：略大于 Redisson 看门狗默认 lockWatchdogTimeout（30s） */
    private static final long RECHECK_DELAY_SECONDS = 35;

    private final VideoMapper videoMapper;
    private final DistributedLockService lockService;
    private final VideoAppService videoAppService;

    @Override
    public void run(ApplicationArguments args) {
        // 启动阶段没有任何登录上下文：显式声明按 videoId 直接操作（租户条件不参与）
        TenantContext.runAsSystem(this::scan);
    }

    private void scan() {
        List<Video> unfinished;
        try {
            unfinished = videoMapper.selectUnfinished();
        } catch (Exception e) {
            log.warn("启动自愈扫描失败，跳过（不影响启动）: {}", e.getMessage());
            return;
        }
        if (unfinished.isEmpty()) {
            log.info("启动自愈：无未完成任务");
            return;
        }

        int recovered = 0;
        List<String> lockedButMaybeDead = new ArrayList<>();
        for (Video video : unfinished) {
            String videoId = video.getVideoId();
            if (lockService.isLocked(VideoProcessService.processLockKey(videoId))) {
                // 有实例正持有处理锁：可能是活任务，也可能是刚被强杀、看门狗锁尚未过期（≤30s）
                lockedButMaybeDead.add(videoId);
                continue;
            }
            try {
                videoAppService.resubmit(video);
                recovered++;
                log.info("启动自愈：重新入队 {}（原状态 {}）", videoId, video.getStatus());
            } catch (Exception e) {
                log.error("启动自愈：重新入队 {} 失败: {}", videoId, e.getMessage(), e);
            }
        }
        log.info("启动自愈完成：未完成 {} 个 → 重新入队 {} 个，锁持有待定 {} 个（{}s 后复查，复用 ASR 断点续跑）",
                unfinished.size(), recovered, lockedButMaybeDead.size(), RECHECK_DELAY_SECONDS);

        if (!lockedButMaybeDead.isEmpty()) {
            scheduleRecheck(lockedButMaybeDead);
        }
    }

    /**
     * 一次性延迟复查：看门狗超时后锁仍被持有 ⇒ 真有活实例，永不干预；锁已释放 ⇒ 死任务，补投。
     * 守护线程，JVM 退出时不阻止关闭；复查只做一次（再之后的崩溃由下次启动的首扫兜底）。
     */
    private void scheduleRecheck(List<String> videoIds) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "startup-recovery-recheck");
            t.setDaemon(true);
            return t;
        });
        scheduler.schedule(() -> TenantContext.runAsSystem(() -> {
            int recovered = 0;
            for (String videoId : videoIds) {
                try {
                    if (lockService.isLocked(VideoProcessService.processLockKey(videoId))) {
                        continue;
                    }
                    Video video = videoMapper.selectByVideoId(videoId);
                    // 锁释放的窗口期可能已被上传入口的孤儿检测捡走并跑完，终态任务不再重投
                    if (video == null || video.getStatus() == com.course.vsearch.constant.VideoStatus.DONE) {
                        continue;
                    }
                    videoAppService.resubmit(video);
                    recovered++;
                    log.info("启动自愈延迟复查：确认锁已过期，重新入队 {}（原状态 {}）",
                            videoId, video.getStatus());
                } catch (Exception e) {
                    log.error("启动自愈延迟复查：{} 失败: {}", videoId, e.getMessage(), e);
                }
            }
            if (recovered > 0) {
                log.info("启动自愈延迟复查完成：补投 {} 个死任务", recovered);
            }
            scheduler.shutdown();
        }), RECHECK_DELAY_SECONDS, TimeUnit.SECONDS);
    }
}