package com.zhiyou.opengpu.service;

import com.zhiyou.opengpu.domain.AppUser;
import com.zhiyou.opengpu.domain.GpuTier;
import com.zhiyou.opengpu.domain.NodeCapability;
import com.zhiyou.opengpu.domain.Task;
import com.zhiyou.opengpu.domain.TaskAttempt;
import com.zhiyou.opengpu.domain.TaskResult;
import com.zhiyou.opengpu.domain.Worker;
import com.zhiyou.opengpu.repository.WorkerRepository;
import com.zhiyou.opengpu.web.dto.TaskDtos;
import com.zhiyou.opengpu.web.dto.UserDtos;
import com.zhiyou.opengpu.web.dto.WorkerDtos;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 实体 → 视图对象转换。集中在此处，保证 API 字段与 docs/API.md 一致。
 */
@Component
public class ViewAssembler {

    private final WorkerRepository workerRepository;

    public ViewAssembler(WorkerRepository workerRepository) {
        this.workerRepository = workerRepository;
    }

    /** 批量查询节点名称，避免列表接口 N+1。 */
    public Map<UUID, String> workerNames(Collection<UUID> workerIds) {
        Collection<UUID> ids = workerIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        for (Worker worker : workerRepository.findAllById(ids)) {
            names.put(worker.getId(), worker.getName());
        }
        return names;
    }

    public TaskDtos.TaskView toTaskView(Task task, Map<UUID, String> workerNames, TaskDtos.TaskResultView result) {
        String workerName = (workerNames != null && task.getWorkerId() != null)
                ? workerNames.get(task.getWorkerId())
                : null;
        return new TaskDtos.TaskView(
                task.getId(),
                task.getPrompt(),
                task.getStatus().name(),
                task.getProgress(),
                task.getRetryCount(),
                task.getMaxRetry(),
                toRequirementView(task),
                task.getWorkerId(),
                workerName,
                task.getLeaseExpiresAt(),
                task.getErrorCode(),
                task.getErrorMessage(),
                result,
                task.getCreatedAt(),
                task.getStartedAt(),
                task.getFinishedAt(),
                task.getUpdatedAt());
    }

    /** 任务未限定硬件要求时返回 null。 */
    public TaskDtos.TaskRequirementView toRequirementView(Task task) {
        if (task.getResolution() == null) {
            return null;
        }
        return new TaskDtos.TaskRequirementView(
                task.getResolution(),
                task.getDurationSeconds(),
                task.getFps(),
                task.getRequiredVramMb(),
                GpuTier.fromRank(task.getMinGpuTier()) == null
                        ? null
                        : GpuTier.fromRank(task.getMinGpuTier()).name(),
                task.getRequirementSummary());
    }

    public TaskDtos.TaskResultView toResultView(TaskResult result) {
        if (result == null) {
            return null;
        }
        return new TaskDtos.TaskResultView(
                result.getId(),
                result.getTaskId(),
                result.getFileKey(),
                result.getFileUrl(),
                result.getFileSize(),
                result.getChecksum(),
                result.getDurationSeconds(),
                result.getWidth(),
                result.getHeight(),
                result.getCreatedAt());
    }

    public TaskDtos.TaskAttemptView toAttemptView(TaskAttempt attempt, Map<UUID, String> workerNames) {
        String workerName = (workerNames != null && attempt.getWorkerId() != null)
                ? workerNames.get(attempt.getWorkerId())
                : null;
        return new TaskDtos.TaskAttemptView(
                attempt.getId(),
                attempt.getTaskId(),
                attempt.getWorkerId(),
                workerName,
                attempt.getLeaseId(),
                attempt.getAttemptNo(),
                attempt.getStatus().name(),
                attempt.getErrorCode(),
                attempt.getErrorMessage(),
                attempt.getStartedAt(),
                attempt.getEndedAt());
    }

    public WorkerDtos.WorkerView toWorkerView(Worker worker) {        GpuTier tier = NodeCapability.tierOf(worker);
        return new WorkerDtos.WorkerView(
                worker.getId(),
                worker.getName(),
                worker.getStatus().name(),
                worker.getRuntimeStatus().name(),
                worker.getGpuModel(),
                worker.getVramMb(),
                tier == null ? null : tier.name(),
                worker.getMaxDurationSeconds(),
                worker.getSupportedResolutions(),
                worker.getWorkerVersion(),
                worker.getModelVersion(),
                worker.getCurrentTaskId(),
                worker.getLastHeartbeatAt(),
                worker.isEnabled(),
                worker.getCreatedAt());
    }

    /** 账号视图。**绝不能把 passwordHash 放进这里。** */
    public UserDtos.UserView toUserView(AppUser user) {
        return new UserDtos.UserView(
                user.getId(),
                user.getUsername(),
                user.getRole().name(),
                user.isEnabled(),
                user.getLastLoginAt(),
                user.getCreatedAt());
    }
}
