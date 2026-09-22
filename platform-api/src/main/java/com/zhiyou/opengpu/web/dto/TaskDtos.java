package com.zhiyou.opengpu.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 任务相关 DTO。字段与 docs/API.md 第 6 节严格对齐。
 */
public final class TaskDtos {

    private TaskDtos() {
    }

    /**
     * 创建任务请求。
     *
     * <p>提示词的「非空 + 长度上限」校验放在 Service 内完成，
     * 以便按契约返回 40001（非法提示词）而不是通用的 40000。
     *
     * @param requirement 视频要求，可为 null（表示不限定硬件，任何节点都可领取）
     */
    public record CreateTaskRequest(String prompt, Integer maxRetry, VideoRequirementRequest requirement) {
    }

    /** 用户可选的视频要求。合法取值见 docs/API.md §7.1。 */
    public record VideoRequirementRequest(String resolution, Integer durationSeconds, Integer fps) {
    }

    /** 平台根据用户要求推导出的硬件约束。 */
    public record TaskRequirementView(
            String resolution,
            Integer durationSeconds,
            Integer fps,
            Integer requiredVramMb,
            String gpuTier,
            String summary) {
    }

    public record TaskView(
            UUID id,
            String prompt,
            String status,
            int progress,
            int retryCount,
            int maxRetry,
            TaskRequirementView requirement,
            UUID workerId,
            String workerName,
            Instant leaseExpiresAt,
            String errorCode,
            String errorMessage,
            TaskResultView result,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            Instant updatedAt) {
    }

    public record TaskDetailView(
            TaskView task,
            List<TaskAttemptView> attempts,
            TaskResultView result) {
    }

    public record TaskResultView(
            UUID id,
            UUID taskId,
            String fileKey,
            String fileUrl,
            long fileSize,
            String checksum,
            Double durationSeconds,
            Integer width,
            Integer height,
            Instant createdAt) {
    }

    public record TaskAttemptView(
            UUID id,
            UUID taskId,
            UUID workerId,
            String workerName,
            UUID leaseId,
            int attemptNo,
            String status,
            String errorCode,
            String errorMessage,
            Instant startedAt,
            Instant endedAt) {
    }
}
