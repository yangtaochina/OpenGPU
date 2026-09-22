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
 * 视频生成任务，对应需求文档 5.1 的 Task 对象。
 *
 * <p>租约字段（{@code leaseId} / {@code leaseExpiresAt}）用于避免重复执行，
 * 并防止过期 Worker 覆盖新执行的结果。
 */
@Entity
@Table(name = "task")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Task {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "prompt", nullable = false, columnDefinition = "text")
    private String prompt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TaskStatus status;

    @Column(name = "progress", nullable = false)
    private int progress;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "max_retry", nullable = false)
    private int maxRetry;

    // ---- 视频要求（用户选择，平台推导出硬件约束；全为 NULL 表示不限定硬件）----

    /** 分辨率编码：480P / 720P / 1080P / 4K */
    @Column(name = "resolution", length = 16)
    private String resolution;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "fps")
    private Integer fps;

    /** 推导出的显存下限（MB），领取时的硬约束 */
    @Column(name = "required_vram_mb")
    private Integer requiredVramMb;

    /** 所需显卡档位 rank：1=ENTRY 2=STANDARD 3=PRO 4=ULTRA */
    @Column(name = "min_gpu_tier")
    private Integer minGpuTier;

    @Column(name = "requirement_summary", length = 255)
    private String requirementSummary;

    @Column(name = "worker_id")
    private UUID workerId;

    @Column(name = "lease_id")
    private UUID leaseId;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

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
            status = TaskStatus.QUEUED;
        }
        if (maxRetry <= 0) {
            maxRetry = 2;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** 清空租约绑定（回到队列或进入终态时使用）。 */
    public void clearLease() {
        this.leaseId = null;
        this.leaseExpiresAt = null;
        this.workerId = null;
    }
}
