package com.zhiyou.opengpu.service;

import com.zhiyou.opengpu.common.AfterCommit;
import com.zhiyou.opengpu.common.BizException;
import com.zhiyou.opengpu.common.ErrorCode;
import com.zhiyou.opengpu.config.OpenGpuProperties;
import com.zhiyou.opengpu.domain.AttemptStatus;
import com.zhiyou.opengpu.domain.Task;
import com.zhiyou.opengpu.domain.TaskAttempt;
import com.zhiyou.opengpu.domain.TaskResult;
import com.zhiyou.opengpu.domain.TaskStatus;
import com.zhiyou.opengpu.queue.TaskQueue;
import com.zhiyou.opengpu.repository.TaskAttemptRepository;
import com.zhiyou.opengpu.repository.TaskRepository;
import com.zhiyou.opengpu.repository.TaskResultRepository;
import com.zhiyou.opengpu.statemachine.TaskStateMachine;
import com.zhiyou.opengpu.storage.StorageService;
import com.zhiyou.opengpu.web.dto.WorkerTaskDtos;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Worker 执行过程中的回调：进度、完成、失败。
 *
 * <p>所有写操作都强制校验 {@code taskId + leaseId + workerId} 三元组，
 * 并拒绝过期租约，确保过期 Worker 无法覆盖新执行结果。
 */
@Slf4j
@Service
public class TaskExecutionService {

    private final TaskRepository taskRepository;
    private final TaskAttemptRepository attemptRepository;
    private final TaskResultRepository resultRepository;
    private final TaskStateMachine stateMachine;
    private final StorageService storageService;
    private final TaskQueue taskQueue;
    private final AfterCommit afterCommit;
    private final OpenGpuProperties properties;

    public TaskExecutionService(TaskRepository taskRepository,
                                TaskAttemptRepository attemptRepository,
                                TaskResultRepository resultRepository,
                                TaskStateMachine stateMachine,
                                StorageService storageService,
                                TaskQueue taskQueue,
                                AfterCommit afterCommit,
                                OpenGpuProperties properties) {
        this.taskRepository = taskRepository;
        this.attemptRepository = attemptRepository;
        this.resultRepository = resultRepository;
        this.stateMachine = stateMachine;
        this.storageService = storageService;
        this.taskQueue = taskQueue;
        this.afterCommit = afterCommit;
        this.properties = properties;
    }

    /** FR-W05：上报进度并续租；progress 达到 100 时进入 UPLOADING。 */
    @Transactional
    public WorkerTaskDtos.ProgressResponse progress(UUID workerId, UUID taskId,
                                                    WorkerTaskDtos.ProgressRequest request) {
        Task task = requireLease(taskId, workerId, request.leaseId());
        renewLease(task);

        int reported = Math.max(0, Math.min(100, request.progress()));
        task.setProgress(Math.max(task.getProgress(), reported));

        TaskAttempt attempt = attemptRepository.findByLeaseId(request.leaseId()).orElse(null);

        // 第一次进度回调即视为 Worker 已开始推理
        if (task.getStatus() == TaskStatus.ASSIGNED) {
            stateMachine.assertTransition(task.getStatus(), TaskStatus.RUNNING);
            task.setStatus(TaskStatus.RUNNING);
            if (attempt != null) {
                attempt.setStatus(AttemptStatus.RUNNING);
            }
        }

        if (reported >= WorkerTaskDtos.PROGRESS_UPLOADING_THRESHOLD
                && task.getStatus() == TaskStatus.RUNNING) {
            stateMachine.assertTransition(task.getStatus(), TaskStatus.UPLOADING);
            task.setStatus(TaskStatus.UPLOADING);
            if (attempt != null) {
                attempt.setStatus(AttemptStatus.UPLOADING);
            }
        }

        return new WorkerTaskDtos.ProgressResponse(
                task.getId(), task.getStatus().name(), task.getProgress(), task.getLeaseExpiresAt());
    }

    /**
     * FR-W06 / AT-06 / AT-07：提交结果元数据。幂等。
     */
    @Transactional
    public WorkerTaskDtos.CompleteResponse complete(UUID workerId, UUID taskId,
                                                    WorkerTaskDtos.CompleteRequest request) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> BizException.notFound("任务不存在: " + taskId));

        // 幂等：同一租约重复提交，直接返回既有结果
        if (Objects.equals(task.getLeaseId(), request.leaseId())) {
            TaskResult existing = resultRepository.findByTaskId(taskId).orElse(null);
            if (existing != null && task.getStatus() == TaskStatus.SUCCEEDED) {
                log.info("重复的完成上报（幂等返回）: taskId={}, leaseId={}", taskId, request.leaseId());
                return new WorkerTaskDtos.CompleteResponse(
                        taskId, TaskStatus.SUCCEEDED.name(), existing.getId(), existing.getFileUrl());
            }
        }

        task = requireLease(taskId, workerId, request.leaseId());
        verifyResultFile(request);

        if (task.getStatus() == TaskStatus.RUNNING) {
            stateMachine.assertTransition(task.getStatus(), TaskStatus.UPLOADING);
            task.setStatus(TaskStatus.UPLOADING);
        }
        stateMachine.assertTransition(task.getStatus(), TaskStatus.SUCCEEDED);
        task.setStatus(TaskStatus.SUCCEEDED);
        task.setProgress(100);
        task.setFinishedAt(Instant.now());
        task.setErrorCode(null);
        task.setErrorMessage(null);

        TaskResult result = resultRepository.findByTaskId(taskId).orElseGet(
                () -> TaskResult.builder().taskId(taskId).build());
        result.setFileKey(request.fileKey());
        result.setFileUrl(storageService.publicUrl(request.fileKey()));
        result.setFileSize(request.fileSize());
        result.setChecksum(request.checksum());
        result.setDurationSeconds(request.durationSeconds());
        result.setWidth(request.width());
        result.setHeight(request.height());
        result = resultRepository.save(result);

        TaskAttempt attempt = attemptRepository.findByLeaseId(request.leaseId()).orElse(null);
        if (attempt != null) {
            attempt.setStatus(AttemptStatus.SUCCEEDED);
            attempt.setEndedAt(Instant.now());
        }

        log.info("任务已完成: taskId={}, workerId={}, resultId={}, fileKey={}",
                taskId, workerId, result.getId(), result.getFileKey());
        return new WorkerTaskDtos.CompleteResponse(
                taskId, TaskStatus.SUCCEEDED.name(), result.getId(), result.getFileUrl());
    }

    /**
     * FR-W05 / AT-05：上报失败。按重试预算决定回队列还是终态失败。
     */
    @Transactional
    public WorkerTaskDtos.FailResponse fail(UUID workerId, UUID taskId,
                                            WorkerTaskDtos.FailRequest request) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> BizException.notFound("任务不存在: " + taskId));

        // 幂等：该租约已结束且任务已不再持有该租约
        TaskAttempt attempt = attemptRepository.findByLeaseId(request.leaseId()).orElse(null);
        if (attempt != null && attempt.getStatus().isEnded()
                && !Objects.equals(task.getLeaseId(), request.leaseId())) {
            log.info("重复的失败上报（幂等返回）: taskId={}, leaseId={}", taskId, request.leaseId());
            return new WorkerTaskDtos.FailResponse(
                    taskId, task.getStatus().name(), task.getRetryCount(), task.getErrorCode());
        }

        task = requireLease(taskId, workerId, request.leaseId());

        boolean retryable = request.retryable() == null || request.retryable();
        int newRetryCount = task.getRetryCount() + 1;
        boolean requeue = retryable && newRetryCount <= task.getMaxRetry();

        if (requeue) {
            stateMachine.assertTransition(task.getStatus(), TaskStatus.QUEUED);
            task.setStatus(TaskStatus.QUEUED);
            task.setRetryCount(newRetryCount);
            task.setProgress(0);
            task.clearLease();
            task.setErrorCode(request.errorCode());
            task.setErrorMessage(request.errorMessage());
            log.warn("任务失败并重新入队: taskId={}, retryCount={}/{}, errorCode={}",
                    taskId, newRetryCount, task.getMaxRetry(), request.errorCode());

            final UUID id = task.getId();
            afterCommit.run(() -> taskQueue.publish(id));
        } else {
            stateMachine.assertTransition(task.getStatus(), TaskStatus.FAILED);
            task.setStatus(TaskStatus.FAILED);
            task.setRetryCount(newRetryCount);
            task.setFinishedAt(Instant.now());
            task.setErrorCode(request.errorCode());
            task.setErrorMessage(request.errorMessage());
            task.clearLease();
            log.warn("任务最终失败: taskId={}, retryCount={}/{}, retryable={}, errorCode={}",
                    taskId, newRetryCount, task.getMaxRetry(), retryable, request.errorCode());
        }

        if (attempt != null) {
            attempt.end(AttemptStatus.FAILED, request.errorCode(), request.errorMessage());
        }

        return new WorkerTaskDtos.FailResponse(
                taskId, task.getStatus().name(), task.getRetryCount(), task.getErrorCode());
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 续租：把租约到期时间向后推一个租约周期。 */
    private void renewLease(Task task) {
        task.setLeaseExpiresAt(Instant.now().plusSeconds(properties.getTask().getLeaseSeconds()));
    }

    /**
     * 校验租约：必须同时匹配 taskId、workerId、leaseId，且未过期。
     */
    private Task requireLease(UUID taskId, UUID workerId, UUID leaseId) {        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> BizException.notFound("任务不存在: " + taskId));
        if (!Objects.equals(task.getLeaseId(), leaseId)) {
            throw BizException.leaseInvalid("租约不匹配：任务可能已被重新分配");
        }
        if (!Objects.equals(task.getWorkerId(), workerId)) {
            throw BizException.leaseInvalid("任务当前不属于该节点");
        }
        if (task.getLeaseExpiresAt() == null || !task.getLeaseExpiresAt().isAfter(Instant.now())) {
            throw BizException.leaseInvalid("租约已过期，请等待平台重新分配");
        }
        return task;
    }

    /** AT-07：文件必须真实存在，且大小/校验值一致才允许置为 SUCCEEDED。 */
    private void verifyResultFile(WorkerTaskDtos.CompleteRequest request) {
        long maxSize = properties.getTask().getMaxFileSizeBytes();
        if (request.fileSize() > maxSize) {
            throw new BizException(ErrorCode.FILE_INVALID,
                    "结果文件超过平台上限 " + maxSize + " 字节");
        }
        if (!storageService.exists(request.fileKey())) {
            throw new BizException(ErrorCode.FILE_INVALID,
                    "结果文件不存在: " + request.fileKey());
        }
        Long actualSize = storageService.size(request.fileKey());
        if (actualSize != null && request.fileSize() > 0 && actualSize != request.fileSize()) {
            throw new BizException(ErrorCode.FILE_INVALID,
                    "结果文件大小不一致：声明 " + request.fileSize() + "，实际 " + actualSize);
        }
        String storedChecksum = storageService.checksum(request.fileKey());
        if (request.checksum() != null && !request.checksum().isBlank()
                && storedChecksum != null && !storedChecksum.equalsIgnoreCase(request.checksum())) {
            throw new BizException(ErrorCode.FILE_INVALID, "结果文件校验值不一致");
        }
    }
}
