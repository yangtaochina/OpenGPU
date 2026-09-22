package com.zhiyou.opengpu.service;

import com.zhiyou.opengpu.common.BizException;
import com.zhiyou.opengpu.common.Hashing;
import com.zhiyou.opengpu.common.PageResult;
import com.zhiyou.opengpu.config.OpenGpuProperties;
import com.zhiyou.opengpu.domain.GpuTier;
import com.zhiyou.opengpu.domain.NodeCapability;
import com.zhiyou.opengpu.domain.Worker;
import com.zhiyou.opengpu.domain.WorkerRuntimeStatus;
import com.zhiyou.opengpu.domain.WorkerStatus;
import com.zhiyou.opengpu.repository.TaskRepository;
import com.zhiyou.opengpu.repository.WorkerRepository;
import com.zhiyou.opengpu.web.dto.WorkerDtos;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 节点生命周期：创建凭证、注册、心跳、启停与离线判定。
 */
@Slf4j
@Service
public class WorkerService {

    private final WorkerRepository workerRepository;
    private final TaskRepository taskRepository;
    private final ViewAssembler assembler;
    private final OpenGpuProperties properties;

    public WorkerService(WorkerRepository workerRepository,
                         TaskRepository taskRepository,
                         ViewAssembler assembler,
                         OpenGpuProperties properties) {
        this.workerRepository = workerRepository;
        this.taskRepository = taskRepository;
        this.assembler = assembler;
        this.properties = properties;
    }

    // ------------------------------------------------------------------
    // 管理端
    // ------------------------------------------------------------------

    /** 创建节点并一次性下发 token（明文只返回这一次）。 */
    @Transactional
    public WorkerDtos.WorkerCreatedResponse create(WorkerDtos.CreateWorkerRequest request) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw BizException.badRequest("节点名称不能为空");
        }
        workerRepository.findByName(name).ifPresent(existing -> {
            throw BizException.badRequest("节点名称已存在: " + name);
        });

        Worker worker = Worker.builder()
                .name(name)
                // token 需要用到节点 ID，先落库拿到 ID 再回填哈希
                .tokenHash("pending")
                .status(WorkerStatus.OFFLINE)
                .runtimeStatus(WorkerRuntimeStatus.IDLE)
                .gpuModel(request.gpuModel())
                .vramMb(request.vramMb())
                .gpuTier(resolveTierRank(request.gpuTier(), request.vramMb()))
                .maxDurationSeconds(request.maxDurationSeconds())
                .supportedResolutions(request.supportedResolutions())
                .workerVersion(request.workerVersion())
                .modelVersion(request.modelVersion())
                .enabled(true)
                .build();
        worker = workerRepository.saveAndFlush(worker);

        String token = "wkr_" + worker.getId() + "_" + Hashing.randomHex(24);
        worker.setTokenHash(Hashing.sha256Hex(token));
        worker = workerRepository.save(worker);

        log.info("节点已创建: id={}, name={}", worker.getId(), worker.getName());
        return new WorkerDtos.WorkerCreatedResponse(assembler.toWorkerView(worker), token);
    }

    @Transactional(readOnly = true)
    public PageResult<WorkerDtos.WorkerView> list(WorkerStatus status, Pageable pageable) {
        Page<Worker> page = status == null
                ? workerRepository.findAll(pageable)
                : workerRepository.findByStatus(status, pageable);
        return PageResult.of(page, assembler::toWorkerView);
    }

    /** FR-A04：停用后节点不能领取新任务，但心跳仍可被记录。 */
    @Transactional
    public WorkerDtos.WorkerView setEnabled(UUID workerId, boolean enabled) {
        Worker worker = requireWorker(workerId);
        worker.setEnabled(enabled);
        if (enabled) {
            worker.setStatus(WorkerStatus.ONLINE);
        } else {
            worker.setStatus(WorkerStatus.DISABLED);
            worker.setRuntimeStatus(WorkerRuntimeStatus.IDLE);
            worker.setCurrentTaskId(null);
        }
        log.info("节点{}: id={}, name={}", enabled ? "已启用" : "已停用", workerId, worker.getName());
        return assembler.toWorkerView(worker);
    }

    @Transactional(readOnly = true)
    public WorkerDtos.OverviewResponse overview() {
        return new WorkerDtos.OverviewResponse(
                taskRepository.count(),
                taskRepository.countByStatus(com.zhiyou.opengpu.domain.TaskStatus.QUEUED),
                taskRepository.countByStatus(com.zhiyou.opengpu.domain.TaskStatus.ASSIGNED)
                        + taskRepository.countByStatus(com.zhiyou.opengpu.domain.TaskStatus.RUNNING)
                        + taskRepository.countByStatus(com.zhiyou.opengpu.domain.TaskStatus.UPLOADING),
                taskRepository.countByStatus(com.zhiyou.opengpu.domain.TaskStatus.SUCCEEDED),
                taskRepository.countByStatus(com.zhiyou.opengpu.domain.TaskStatus.FAILED),
                workerRepository.countByStatus(WorkerStatus.ONLINE),
                workerRepository.countByStatus(WorkerStatus.OFFLINE),
                workerRepository.countByStatus(WorkerStatus.DISABLED));
    }

    // ------------------------------------------------------------------
    // Worker 端
    // ------------------------------------------------------------------

    /** FR-W01 */
    @Transactional
    public WorkerDtos.WorkerView register(UUID workerId, WorkerDtos.WorkerRegisterRequest request) {
        Worker worker = requireWorker(workerId);

        if (request.name() != null && !request.name().isBlank()) {
            String newName = request.name().trim();
            if (!newName.equals(worker.getName())) {
                workerRepository.findByName(newName).ifPresent(existing -> {
                    throw BizException.badRequest("节点名称已被占用: " + newName);
                });
                worker.setName(newName);
            }
        }
        if (request.gpuModel() != null) {
            worker.setGpuModel(request.gpuModel());
        }
        if (request.vramMb() != null) {
            worker.setVramMb(request.vramMb());
        }
        // 档位：显式声明优先；只上报显存时自动推导，保证能力匹配条件始终可用
        if (request.gpuTier() != null && !request.gpuTier().isBlank()) {
            worker.setGpuTier(parseTierRank(request.gpuTier()));
        } else if (request.vramMb() != null) {
            worker.setGpuTier(resolveTierRank(null, request.vramMb()));
        } else if (worker.getGpuTier() == null) {
            worker.setGpuTier(resolveTierRank(null, worker.getVramMb()));
        }
        if (request.maxDurationSeconds() != null) {
            worker.setMaxDurationSeconds(request.maxDurationSeconds());
        }
        if (request.supportedResolutions() != null) {
            worker.setSupportedResolutions(request.supportedResolutions());
        }
        if (request.workerVersion() != null) {
            worker.setWorkerVersion(request.workerVersion());
        }
        if (request.modelVersion() != null) {
            worker.setModelVersion(request.modelVersion());
        }
        worker.setLastHeartbeatAt(Instant.now());
        if (worker.isEnabled()) {
            worker.setStatus(WorkerStatus.ONLINE);
        }
        log.info("节点已注册: id={}, name={}, 能力={}", worker.getId(), worker.getName(),
                NodeCapability.describe(worker));
        return assembler.toWorkerView(worker);
    }

    /** 档位解析：显式名称优先，否则由显存推导；都拿不到则为 null（能力未知）。 */
    private Integer resolveTierRank(String tierName, Integer vramMb) {
        if (tierName != null && !tierName.isBlank()) {
            return parseTierRank(tierName);
        }
        GpuTier derived = GpuTier.fromVramMb(vramMb);
        return derived == null ? null : derived.getRank();
    }

    private Integer parseTierRank(String tierName) {
        try {
            return GpuTier.valueOf(tierName.trim().toUpperCase(Locale.ROOT)).getRank();
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest(
                    "gpuTier 取值非法: " + tierName + "，允许值: ENTRY, STANDARD, PRO, ULTRA");
        }
    }

    /** FR-W02：心跳 + 忙闲上报 + 当前任务续租。 */
    @Transactional
    public WorkerDtos.HeartbeatResponse heartbeat(UUID workerId, WorkerDtos.HeartbeatRequest request) {
        Worker worker = requireWorker(workerId);
        WorkerRuntimeStatus runtimeStatus = parseRuntimeStatus(request.status());

        worker.setRuntimeStatus(runtimeStatus);
        worker.setLastHeartbeatAt(Instant.now());
        if (worker.isEnabled()) {
            worker.setStatus(WorkerStatus.ONLINE);
        }
        worker.setCurrentTaskId(runtimeStatus == WorkerRuntimeStatus.BUSY ? request.currentTaskId() : null);

        if (runtimeStatus == WorkerRuntimeStatus.BUSY && request.currentTaskId() != null) {
            renewLease(request.currentTaskId(), workerId);
        }
        return new WorkerDtos.HeartbeatResponse(
                Instant.now(),
                properties.getTask().getHeartbeatIntervalSeconds(),
                worker.getCurrentTaskId());
    }

    /** 心跳顺带续租：Worker 在推理过程中即使不上报进度也不会被回收。 */
    private void renewLease(UUID taskId, UUID workerId) {
        taskRepository.findById(taskId).ifPresent(task -> {
            boolean sameWorker = Objects.equals(task.getWorkerId(), workerId);
            if (sameWorker && task.getStatus().isInFlight()) {
                task.setLeaseExpiresAt(Instant.now().plusSeconds(properties.getTask().getLeaseSeconds()));
            }
        });
    }

    /** 供 claim 校验：停用节点不得领取新任务。 */
    @Transactional(readOnly = true)
    public Worker requireClaimableWorker(UUID workerId) {
        Worker worker = requireWorker(workerId);
        if (!worker.isEnabled()) {
            throw BizException.forbidden("节点已被停用，不能领取新任务");
        }
        return worker;
    }

    @Transactional(readOnly = true)
    public Worker requireWorker(UUID workerId) {
        return workerRepository.findById(workerId)
                .orElseThrow(() -> BizException.notFound("节点不存在: " + workerId));
    }

    /** 供心跳监控调用。 */
    @Transactional
    public void markOffline(Worker worker) {
        worker.setStatus(WorkerStatus.OFFLINE);
        worker.setRuntimeStatus(WorkerRuntimeStatus.IDLE);
        worker.setCurrentTaskId(null);
    }

    private WorkerRuntimeStatus parseRuntimeStatus(String raw) {
        if (raw == null) {
            throw BizException.badRequest("status 不能为空");
        }
        try {
            return WorkerRuntimeStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("status 只能是 IDLE 或 BUSY，收到: " + raw);
        }
    }
}
