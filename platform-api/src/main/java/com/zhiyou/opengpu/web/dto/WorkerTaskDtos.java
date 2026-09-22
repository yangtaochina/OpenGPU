package com.zhiyou.opengpu.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.UUID;

/**
 * Worker 侧任务交互 DTO（领取 / 进度 / 完成 / 失败）。
 */
public final class WorkerTaskDtos {

    private WorkerTaskDtos() {
    }

    public record ClaimRequest(
            @Min(0) @Max(30) Integer waitSeconds) {
    }

    public record TaskAssignment(
            UUID taskId,
            UUID attemptId,
            int attemptNo,
            String prompt,
            UUID leaseId,
            Instant leaseExpiresAt) {
    }

    public record ProgressRequest(
            @NotNull(message = "leaseId 不能为空") UUID leaseId,
            @Min(0) @Max(100) int progress) {
    }

    public record ProgressResponse(
            UUID taskId,
            String status,
            int progress,
            Instant leaseExpiresAt) {
    }

    public record CompleteRequest(
            @NotNull(message = "leaseId 不能为空") UUID leaseId,
            @NotBlank(message = "fileKey 不能为空") String fileKey,
            @PositiveOrZero long fileSize,
            String checksum,
            Double durationSeconds,
            Integer width,
            Integer height) {
    }

    public record CompleteResponse(
            UUID taskId,
            String status,
            UUID resultId,
            String fileUrl) {
    }

    public record FailRequest(
            @NotNull(message = "leaseId 不能为空") UUID leaseId,
            @NotBlank(message = "errorCode 不能为空") String errorCode,
            String errorMessage,
            Boolean retryable) {
    }

    public record FailResponse(
            UUID taskId,
            String status,
            int retryCount,
            String errorCode) {
    }

    /** 进度上报达到该值时任务由 RUNNING 进入 UPLOADING。 */
    public static final int PROGRESS_UPLOADING_THRESHOLD = 100;
}
