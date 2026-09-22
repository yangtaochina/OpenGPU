package com.zhiyou.opengpu.service;

import com.zhiyou.opengpu.config.OpenGpuProperties;
import com.zhiyou.opengpu.domain.AttemptStatus;
import com.zhiyou.opengpu.domain.GpuTier;
import com.zhiyou.opengpu.domain.NodeCapability;
import com.zhiyou.opengpu.domain.Task;
import com.zhiyou.opengpu.domain.TaskAttempt;
import com.zhiyou.opengpu.domain.TaskStatus;
import com.zhiyou.opengpu.domain.Worker;
import com.zhiyou.opengpu.queue.TaskQueue;
import com.zhiyou.opengpu.repository.TaskAttemptRepository;
import com.zhiyou.opengpu.statemachine.TaskStateMachine;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 原子领取 + 显卡能力匹配（FR-W03 / AT-02 / AT-04 / AT-11 / AT-12）。
 *
 * <p>PostgreSQL 是事实来源。领取通过
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} 保证同一任务不会被两个有效租约拿到。
 *
 * <p><b>能力匹配：</b>SQL 中额外加入显存 / 显卡档位 / 最长时长的过滤条件，
 * 只有满足任务视频要求的节点才能看到该任务。过滤语义必须与
 * {@link NodeCapability#satisfies} 保持一致（前者管数据库筛选，后者管能力矩阵展示）。
 *
 * <p>为了避开 PostgreSQL 原生查询传 null 参数时的类型推断问题，
 * 这里按节点实际能力动态拼接条件，而不是传 null 占位。
 */
@Slf4j
@Service
public class ClaimService {

    private static final String BASE_SQL = "select id from task where status = 'QUEUED'";

    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final TaskAttemptRepository attemptRepository;
    private final TaskStateMachine stateMachine;
    private final TaskQueue taskQueue;
    private final OpenGpuProperties properties;

    public ClaimService(EntityManager entityManager,
                        PlatformTransactionManager transactionManager,
                        TaskAttemptRepository attemptRepository,
                        TaskStateMachine stateMachine,
                        TaskQueue taskQueue,
                        OpenGpuProperties properties) {
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.attemptRepository = attemptRepository;
        this.stateMachine = stateMachine;
        this.taskQueue = taskQueue;
        this.properties = properties;
    }

    /**
     * 长轮询领取一个「本节点能力可满足」的任务。
     *
     * @param worker      已通过认证且未被停用的节点
     * @param waitSeconds 0 表示只尝试一次；上限由服务端配置截断
     */
    public Optional<ClaimResult> claim(Worker worker, int waitSeconds) {
        int wait = Math.max(0, Math.min(waitSeconds, properties.getTask().getMaxClaimWaitSeconds()));
        Instant deadline = Instant.now().plusSeconds(wait);
        long signalWaitMillis = Math.max(200L, Math.min(1000L, properties.getTask().getLeaseSeconds() * 1000L));

        while (true) {
            Optional<ClaimResult> claimed = tryClaim(worker);
            if (claimed.isPresent()) {
                return claimed;
            }
            if (!Instant.now().isBefore(deadline)) {
                return Optional.empty();
            }
            // 阻塞在唤醒通道上，新任务入队时可立即返回；Redis 未启用或不可用时退化为 sleep
            taskQueue.awaitSignal(Duration.ofMillis(signalWaitMillis));
        }
    }

    /** 单次原子领取尝试，独立事务。 */
    public Optional<ClaimResult> tryClaim(Worker worker) {
        return transactionTemplate.execute(status -> {
            List<?> rows = buildClaimQuery(worker).getResultList();
            if (rows.isEmpty()) {
                return Optional.<ClaimResult>empty();
            }
            UUID taskId = toUuid(rows.get(0));
            Task task = entityManager.find(Task.class, taskId);
            if (task == null || task.getStatus() != TaskStatus.QUEUED) {
                return Optional.<ClaimResult>empty();
            }
            return Optional.of(assign(task, worker));
        });
    }

    /**
     * 构造带能力过滤的领取查询，语义与 {@link NodeCapability#satisfies} 一一对应：
     * <ul>
     *   <li>节点有显存 → 可以领取显存要求不超过自身、以及不限定显存的任务</li>
     *   <li>节点无显存 → 只能领取不限定显存的任务</li>
     *   <li>节点声明了最长时长 → 时长超限的任务对它是不可见的</li>
     * </ul>
     */
    private jakarta.persistence.Query buildClaimQuery(Worker worker) {
        StringBuilder sql = new StringBuilder(BASE_SQL);
        Map<String, Object> params = new HashMap<>();

        Integer vramMb = worker.getVramMb();
        if (vramMb != null && vramMb > 0) {
            sql.append(" and (required_vram_mb is null or required_vram_mb <= :vramMb)");
            params.put("vramMb", vramMb);
        } else {
            sql.append(" and required_vram_mb is null");
        }

        GpuTier tier = NodeCapability.tierOf(worker);
        if (tier != null) {
            sql.append(" and (min_gpu_tier is null or min_gpu_tier <= :gpuTier)");
            params.put("gpuTier", tier.getRank());
        } else {
            sql.append(" and min_gpu_tier is null");
        }

        Integer maxDuration = worker.getMaxDurationSeconds();
        if (maxDuration != null && maxDuration > 0) {
            sql.append(" and (duration_seconds is null or duration_seconds <= :maxDuration)");
            params.put("maxDuration", maxDuration);
        }

        sql.append(" order by created_at asc, id asc for update skip locked limit 1");

        jakarta.persistence.Query query = entityManager.createNativeQuery(sql.toString());
        params.forEach(query::setParameter);
        return query;
    }

    private ClaimResult assign(Task task, Worker worker) {
        stateMachine.assertTransition(task.getStatus(), TaskStatus.ASSIGNED);

        UUID leaseId = UUID.randomUUID();
        Instant leaseExpiresAt = Instant.now().plusSeconds(properties.getTask().getLeaseSeconds());

        task.setStatus(TaskStatus.ASSIGNED);
        task.setWorkerId(worker.getId());
        task.setLeaseId(leaseId);
        task.setLeaseExpiresAt(leaseExpiresAt);
        task.setProgress(0);
        task.setErrorCode(null);
        task.setErrorMessage(null);
        if (task.getStartedAt() == null) {
            task.setStartedAt(Instant.now());
        }

        TaskAttempt attempt = TaskAttempt.builder()
                .taskId(task.getId())
                .workerId(worker.getId())
                .leaseId(leaseId)
                .attemptNo(task.getRetryCount() + 1)
                .status(AttemptStatus.ASSIGNED)
                .build();
        attempt = attemptRepository.save(attempt);

        log.info("任务已分配: taskId={}, worker={}, attemptNo={}, 要求={}, leaseId={}, 租约到期={}",
                task.getId(), NodeCapability.describe(worker), attempt.getAttemptNo(),
                task.getRequirementSummary() == null ? "不限" : task.getRequirementSummary(),
                leaseId, leaseExpiresAt);
        return new ClaimResult(task, attempt);
    }

    private UUID toUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(String.valueOf(value));
    }

    /** 领取结果：任务 + 本次执行尝试。 */
    public record ClaimResult(Task task, TaskAttempt attempt) {
    }
}
