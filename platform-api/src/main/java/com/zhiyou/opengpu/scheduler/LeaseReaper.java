package com.zhiyou.opengpu.scheduler;

import com.zhiyou.opengpu.common.AfterCommit;
import com.zhiyou.opengpu.config.OpenGpuProperties;
import com.zhiyou.opengpu.domain.AttemptStatus;
import com.zhiyou.opengpu.domain.Task;
import com.zhiyou.opengpu.domain.TaskStatus;
import com.zhiyou.opengpu.queue.TaskQueue;
import com.zhiyou.opengpu.repository.TaskAttemptRepository;
import com.zhiyou.opengpu.repository.TaskRepository;
import com.zhiyou.opengpu.statemachine.TaskStateMachine;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 租约回收（AT-05）。
 *
 * <p>Worker 掉线或执行中断后，若心跳未续租且租约过期，平台必须把任务重新放回队列，
 * 或按重试预算标记为最终失败，避免任务永久卡死。
 *
 * <p>每个任务单独一个事务处理，单个任务失败不影响其余任务。
 */
@Slf4j
@Component
public class LeaseReaper {

    private static final String ERROR_CODE = "LEASE_EXPIRED";

    private static final List<TaskStatus> IN_FLIGHT = List.of(
            TaskStatus.ASSIGNED, TaskStatus.RUNNING, TaskStatus.UPLOADING);

    private final TaskRepository taskRepository;
    private final TaskAttemptRepository attemptRepository;
    private final TaskStateMachine stateMachine;
    private final TaskQueue taskQueue;
    private final AfterCommit afterCommit;
    private final TransactionTemplate transactionTemplate;
    private final OpenGpuProperties properties;

    public LeaseReaper(TaskRepository taskRepository,
                       TaskAttemptRepository attemptRepository,
                       TaskStateMachine stateMachine,
                       TaskQueue taskQueue,
                       AfterCommit afterCommit,
                       PlatformTransactionManager transactionManager,
                       OpenGpuProperties properties) {
        this.taskRepository = taskRepository;
        this.attemptRepository = attemptRepository;
        this.stateMachine = stateMachine;
        this.taskQueue = taskQueue;
        this.afterCommit = afterCommit;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${opengpu.task.lease-reaper-interval-seconds:15}000",
            initialDelayString = "${opengpu.task.lease-reaper-interval-seconds:15}000")
    public void reapExpiredLeases() {
        Instant now = Instant.now();
        List<Task> expired = taskRepository.findByStatusInAndLeaseExpiresAtBefore(IN_FLIGHT, now);
        if (expired.isEmpty()) {
            return;
        }
        log.info("租约回收：发现 {} 个过期租约", expired.size());
        for (Task task : expired) {
            try {
                transactionTemplate.executeWithoutResult(status -> recycle(task.getId()));
            } catch (Exception e) {
                log.error("回收任务失败: taskId={}", task.getId(), e);
            }
        }
    }

    private void recycle(java.util.UUID taskId) {
        Task task = taskRepository.findById(taskId).orElse(null);
        if (task == null || !task.getStatus().isInFlight()) {
            return;
        }

        java.util.UUID leaseId = task.getLeaseId();
        int newRetryCount = task.getRetryCount() + 1;
        boolean requeue = newRetryCount <= task.getMaxRetry();
        TaskStatus target = requeue ? TaskStatus.QUEUED : TaskStatus.FAILED;

        stateMachine.assertTransition(task.getStatus(), target);

        if (leaseId != null) {
            attemptRepository.findByLeaseId(leaseId).ifPresent(attempt ->
                    attempt.end(AttemptStatus.LEASE_EXPIRED, ERROR_CODE, "租约到期，平台回收任务"));
        }

        Instant now = Instant.now();
        task.setStatus(target);
        task.setRetryCount(newRetryCount);
        task.setErrorCode(ERROR_CODE);
        task.setErrorMessage(requeue
                ? "Worker 租约到期，任务已重新排队（第 " + newRetryCount + " 次）"
                : "Worker 租约到期且重试次数已用尽");
        task.setProgress(0);
        task.clearLease();
        if (target == TaskStatus.FAILED) {
            task.setFinishedAt(now);
        }

        log.warn("任务 {}: taskId={}, retryCount={}/{}",
                requeue ? "已回收重新排队" : "已标记为最终失败",
                task.getId(), newRetryCount, task.getMaxRetry());

        if (requeue) {
            final java.util.UUID id = task.getId();
            afterCommit.run(() -> taskQueue.publish(id));
        }
    }

    /** 供测试与手动触发使用。 */
    public long leaseSeconds() {
        return properties.getTask().getLeaseSeconds();
    }
}
