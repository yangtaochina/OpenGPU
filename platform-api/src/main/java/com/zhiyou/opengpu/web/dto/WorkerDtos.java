package com.zhiyou.opengpu.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * Worker / 节点相关 DTO。
 */
public final class WorkerDtos {

    private WorkerDtos() {
    }

    /** 管理员创建节点。返回的 token 仅此一次可见。 */
    public record CreateWorkerRequest(
            @NotBlank(message = "节点名称不能为空") @Size(max = 64, message = "节点名称最长 64 字符") String name,
            String gpuModel,
            Integer vramMb,
            String workerVersion,
            String modelVersion,
            String gpuTier,
            Integer maxDurationSeconds,
            String supportedResolutions) {
    }

    public record WorkerCreatedResponse(WorkerView worker, String token) {
    }

    /** Worker 启动注册：上报基础信息与显卡能力。 */
    public record WorkerRegisterRequest(
            String name,
            String gpuModel,
            Integer vramMb,
            String workerVersion,
            String modelVersion,
            String gpuTier,
            Integer maxDurationSeconds,
            String supportedResolutions) {
    }

    public record HeartbeatRequest(
            @NotBlank(message = "status 不能为空") String status,
            UUID currentTaskId) {
    }

    public record HeartbeatResponse(
            Instant serverTime,
            long heartbeatIntervalSeconds,
            UUID currentTaskId) {
    }

    public record WorkerView(
            UUID id,
            String name,
            String status,
            String runtimeStatus,
            String gpuModel,
            Integer vramMb,
            String gpuTier,
            Integer maxDurationSeconds,
            String supportedResolutions,
            String workerVersion,
            String modelVersion,
            UUID currentTaskId,
            Instant lastHeartbeatAt,
            boolean enabled,
            Instant createdAt) {
    }

    public record OverviewResponse(
            long taskTotal,
            long taskQueued,
            long taskRunning,
            long taskSucceeded,
            long taskFailed,
            long workerOnline,
            long workerOffline,
            long workerDisabled) {
    }
}
