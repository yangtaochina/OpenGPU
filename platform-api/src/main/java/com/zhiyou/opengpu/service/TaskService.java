package com.zhiyou.opengpu.service;

import com.zhiyou.opengpu.common.AfterCommit;
import com.zhiyou.opengpu.common.BizException;
import com.zhiyou.opengpu.common.ErrorCode;
import com.zhiyou.opengpu.common.PageResult;
import com.zhiyou.opengpu.config.OpenGpuProperties;
import com.zhiyou.opengpu.domain.Task;
import com.zhiyou.opengpu.domain.TaskAttempt;
import com.zhiyou.opengpu.domain.TaskResult;
import com.zhiyou.opengpu.domain.TaskStatus;
import com.zhiyou.opengpu.domain.VideoRequirements;
import com.zhiyou.opengpu.domain.VideoResolution;
import com.zhiyou.opengpu.domain.GpuTier;
import com.zhiyou.opengpu.queue.TaskQueue;
import com.zhiyou.opengpu.repository.TaskAttemptRepository;
import com.zhiyou.opengpu.repository.TaskRepository;
import com.zhiyou.opengpu.repository.TaskResultRepository;
import com.zhiyou.opengpu.statemachine.TaskStateMachine;
import com.zhiyou.opengpu.web.dto.TaskDtos;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务的创建、查询、取消与人工重试。
 */
@Slf4j
@Service
public class TaskService {

    private static final int MAX_MANUAL_RETRY = 5;

    private final TaskRepository taskRepository;
    private final TaskAttemptRepository attemptRepository;
    private final TaskResultRepository resultRepository;
    private final TaskStateMachine stateMachine;
    private final TaskQueue taskQueue;
    private final AfterCommit afterCommit;
    private final ViewAssembler assembler;
    private final OpenGpuProperties properties;

    public TaskService(TaskRepository taskRepository,
                       TaskAttemptRepository attemptRepository,
                       TaskResultRepository resultRepository,
                       TaskStateMachine stateMachine,
                       TaskQueue taskQueue,
                       AfterCommit afterCommit,
                       ViewAssembler assembler,
                       OpenGpuProperties properties) {
        this.taskRepository = taskRepository;
        this.attemptRepository = attemptRepository;
        this.resultRepository = resultRepository;
        this.stateMachine = stateMachine;
        this.taskQueue = taskQueue;
        this.afterCommit = afterCommit;
        this.assembler = assembler;
        this.properties = properties;
    }

    /** FR-U01 / AT-01 / AT-10 */
    @Transactional
    public TaskDtos.TaskView create(TaskDtos.CreateTaskRequest request) {
        String prompt = request.prompt() == null ? "" : request.prompt().trim();
        int maxLength = properties.getTask().getMaxPromptLength();
        if (prompt.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_PROMPT, "提示词不能为空");
        }
        if (prompt.length() > maxLength) {
            throw new BizException(ErrorCode.INVALID_PROMPT, "提示词长度不能超过 " + maxLength + " 字符");
        }

        int maxRetry = request.maxRetry() == null ? properties.getTask().getMaxRetry() : request.maxRetry();
        if (maxRetry < 0 || maxRetry > MAX_MANUAL_RETRY) {
            throw BizException.badRequest("maxRetry 取值范围 0-" + MAX_MANUAL_RETRY);
        }

        ResolvedRequirement requirement = resolveRequirement(request.requirement());

        Task task = Task.builder()
                .prompt(prompt)
                .status(TaskStatus.QUEUED)
                .progress(0)
                .retryCount(0)
                .maxRetry(maxRetry)
                .resolution(requirement == null ? null : requirement.resolutionCode())
                .durationSeconds(requirement == null ? null : requirement.durationSeconds())
                .fps(requirement == null ? null : requirement.fps())
                .requiredVramMb(requirement == null ? null : requirement.requiredVramMb())
                .minGpuTier(requirement == null ? null : requirement.minGpuTierRank())
                .requirementSummary(requirement == null ? null : requirement.summary())
                .build();
        task = taskRepository.save(task);
        log.info("任务已创建: taskId={}, maxRetry={}, 视频要求={}",
                task.getId(), maxRetry, requirement == null ? "不限" : requirement.summary());

        final UUID taskId = task.getId();
        afterCommit.run(() -> taskQueue.publish(taskId));
        return assembler.toTaskView(task, Map.of(), null);
    }

    /**
     * 把用户选择的视频要求推导成硬件约束（推导规则见 docs/API.md §7.2）。
     *
     * @return {@code null} 表示用户未限定硬件要求，任何节点都可领取
     */
    private ResolvedRequirement resolveRequirement(TaskDtos.VideoRequirementRequest request) {
        if (request == null) {
            return null;
        }
        VideoResolution resolution = VideoResolution.fromCode(request.resolution());
        VideoRequirements.validateDuration(request.durationSeconds());
        VideoRequirements.validateFps(request.fps());

        int durationSeconds = request.durationSeconds();
        int fps = request.fps();
        int requiredVramMb = VideoRequirements.requiredVramMb(resolution, durationSeconds, fps);
        GpuTier tier = GpuTier.fromVramMb(requiredVramMb);

        return new ResolvedRequirement(
                resolution.getCode(),
                durationSeconds,
                fps,
                requiredVramMb,
                tier == null ? null : tier.getRank(),
                VideoRequirements.summary(resolution.getCode(), durationSeconds, fps, requiredVramMb, tier));
    }

    private record ResolvedRequirement(
            String resolutionCode,
            int durationSeconds,
            int fps,
            int requiredVramMb,
            Integer minGpuTierRank,
            String summary) {
    }

    @Transactional(readOnly = true)
    public PageResult<TaskDtos.TaskView> list(TaskStatus status, Pageable pageable) {
        Page<Task> page = status == null
                ? taskRepository.findAll(pageable)
                : taskRepository.findByStatus(status, pageable);

        List<UUID> taskIds = page.getContent().stream().map(Task::getId).toList();
        Map<UUID, TaskDtos.TaskResultView> results = taskIds.isEmpty()
                ? Map.of()
                : resultRepository.findByTaskIdIn(taskIds).stream()
                        .collect(Collectors.toMap(TaskResult::getTaskId, assembler::toResultView));
        Map<UUID, String> workerNames = assembler.workerNames(
                page.getContent().stream().map(Task::getWorkerId).toList());

        return PageResult.of(page, task -> assembler.toTaskView(
                task, workerNames, results.get(task.getId())));
    }

    @Transactional(readOnly = true)
    public TaskDtos.TaskDetailView detail(UUID taskId) {
        Task task = requireTask(taskId);
        TaskResult result = resultRepository.findByTaskId(taskId).orElse(null);
        List<TaskAttempt> attempts = attemptRepository.findByTaskIdOrderByAttemptNoAsc(taskId);
        Map<UUID, String> workerNames = assembler.workerNames(
                attempts.stream().map(TaskAttempt::getWorkerId).collect(Collectors.toList()));

        TaskDtos.TaskResultView resultView = assembler.toResultView(result);
        return new TaskDtos.TaskDetailView(
                assembler.toTaskView(task, workerNames, resultView),
                attempts.stream().map(a -> assembler.toAttemptView(a, workerNames)).toList(),
                resultView);
    }

    /** FR-U05：仅 QUEUED 可取消。 */
    @Transactional
    public TaskDtos.TaskView cancel(UUID taskId) {
        Task task = requireTask(taskId);
        if (task.getStatus() != TaskStatus.QUEUED) {
            throw BizException.stateConflict(
                    "仅 QUEUED 状态的任务可以取消，当前状态: " + task.getStatus());
        }
        stateMachine.assertTransition(task.getStatus(), TaskStatus.CANCELED);
        task.setStatus(TaskStatus.CANCELED);
        task.setFinishedAt(Instant.now());
        task.clearLease();
        log.info("任务已取消: taskId={}", taskId);
        return assembler.toTaskView(task, Map.of(), null);
    }

    /**
     * FR-A03 / AT-08：人工重试失败任务。重置重试预算并回队列，原 TaskAttempt 历史保留。
     */
    @Transactional
    public TaskDtos.TaskView retry(UUID taskId) {
        Task task = requireTask(taskId);
        if (task.getStatus() != TaskStatus.FAILED) {
            throw BizException.stateConflict(
                    "仅 FAILED 状态的任务可以人工重试，当前状态: " + task.getStatus());
        }
        stateMachine.assertTransition(task.getStatus(), TaskStatus.QUEUED);
        task.setStatus(TaskStatus.QUEUED);
        task.setProgress(0);
        task.setRetryCount(0);
        task.setErrorCode(null);
        task.setErrorMessage(null);
        task.clearLease();
        task.setFinishedAt(null);
        log.info("任务已人工重试入队: taskId={}", taskId);

        final UUID id = task.getId();
        afterCommit.run(() -> taskQueue.publish(id));
        return assembler.toTaskView(task, Map.of(), null);
    }

    /** 供管理端概览使用。 */
    @Transactional(readOnly = true)
    public long countByStatus(TaskStatus status) {
        return taskRepository.countByStatus(status);
    }

    @Transactional(readOnly = true)
    public long countAll() {
        return taskRepository.count();
    }

    /** 供调度器/其他服务复用。 */
    @Transactional(readOnly = true)
    public Task requireTask(UUID taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> BizException.notFound("任务不存在: " + taskId));
    }
}
