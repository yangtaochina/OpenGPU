package com.zhiyou.opengpu.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 远程 GPU 节点，对应需求文档 5.1 的 Worker 对象。
 *
 * <p>token 只保存 SHA-256 哈希，明文仅在创建节点时返回一次。
 */
@Entity
@Table(name = "worker")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Worker {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "token_hash", nullable = false, length = 128)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private WorkerStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "runtime_status", nullable = false, length = 16)
    private WorkerRuntimeStatus runtimeStatus;

    @Column(name = "current_task_id")
    private UUID currentTaskId;

    @Column(name = "gpu_model", length = 128)
    private String gpuModel;

    @Column(name = "vram_mb")
    private Integer vramMb;

    // ---- 显卡能力（供任务匹配使用）----

    /** 显卡档位 rank：1=ENTRY 2=STANDARD 3=PRO 4=ULTRA；为空则按 vramMb 推导 */
    @Column(name = "gpu_tier")
    private Integer gpuTier;

    /** 可承受的最长时长（秒）；NULL 或 <=0 表示不限制 */
    @Column(name = "max_duration_seconds")
    private Integer maxDurationSeconds;

    /** 支持的分辨率列表（逗号分隔），仅用于展示 */
    @Column(name = "supported_resolutions", length = 128)
    private String supportedResolutions;

    @Column(name = "worker_version", length = 64)
    private String workerVersion;

    @Column(name = "model_version", length = 128)
    private String modelVersion;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (status == null) {
            status = enabled ? WorkerStatus.OFFLINE : WorkerStatus.DISABLED;
        }
        if (runtimeStatus == null) {
            runtimeStatus = WorkerRuntimeStatus.IDLE;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** 节点当前是否可以被分配任务。 */
    public boolean isClaimable() {
        return enabled && status == WorkerStatus.ONLINE;
    }
}
