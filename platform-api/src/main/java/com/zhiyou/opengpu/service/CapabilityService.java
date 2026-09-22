package com.zhiyou.opengpu.service;

import com.zhiyou.opengpu.domain.GpuTier;
import com.zhiyou.opengpu.domain.NodeCapability;
import com.zhiyou.opengpu.domain.VideoRequirements;
import com.zhiyou.opengpu.domain.VideoResolution;
import com.zhiyou.opengpu.domain.Worker;
import com.zhiyou.opengpu.domain.WorkerStatus;
import com.zhiyou.opengpu.repository.WorkerRepository;
import com.zhiyou.opengpu.web.dto.CapabilityDtos;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 视频要求能力矩阵（docs/API.md §3 的 {@code GET /api/capabilities}）。
 *
 * <p>把「全部 48 种可选组合 → 推导出的硬件约束 → 当前在线节点能否承接」
 * 一次性算好交给前端，前端只需查表，不需要重复实现推导公式。
 */
@Service
public class CapabilityService {

    private final WorkerRepository workerRepository;

    public CapabilityService(WorkerRepository workerRepository) {
        this.workerRepository = workerRepository;
    }

    @Transactional(readOnly = true)
    public CapabilityDtos.CapabilityView describe() {
        List<Worker> online = workerRepository.findByStatus(WorkerStatus.ONLINE).stream()
                .filter(Worker::isEnabled)
                .toList();

        return new CapabilityDtos.CapabilityView(
                buildFleet(online),
                buildOptions(),
                buildMatrix(online));
    }

    private CapabilityDtos.FleetView buildFleet(List<Worker> online) {
        Integer maxVramMb = online.stream()
                .map(Worker::getVramMb)
                .filter(value -> value != null && value > 0)
                .max(Integer::compareTo)
                .orElse(null);

        Integer maxDurationSeconds = online.stream()
                .map(Worker::getMaxDurationSeconds)
                .filter(value -> value != null && value > 0)
                .max(Integer::compareTo)
                .orElse(null);

        GpuTier maxTier = online.stream()
                .map(NodeCapability::tierOf)
                .filter(Objects::nonNull)
                .max(Comparator.comparingInt(GpuTier::getRank))
                .orElse(null);

        return new CapabilityDtos.FleetView(
                online.size(),
                maxVramMb,
                maxTier == null ? null : maxTier.name(),
                maxDurationSeconds);
    }

    private CapabilityDtos.OptionsView buildOptions() {
        List<CapabilityDtos.ResolutionOption> resolutions = Arrays.stream(VideoResolution.values())
                .map(r -> new CapabilityDtos.ResolutionOption(
                        r.getCode(), r.getCode(), r.getWidth(), r.getHeight()))
                .toList();
        return new CapabilityDtos.OptionsView(
                resolutions,
                VideoRequirements.ALLOWED_DURATIONS,
                VideoRequirements.ALLOWED_FPS);
    }

    private List<CapabilityDtos.MatrixEntry> buildMatrix(List<Worker> online) {
        List<CapabilityDtos.MatrixEntry> matrix = new ArrayList<>();
        for (VideoResolution resolution : VideoResolution.values()) {
            for (int durationSeconds : VideoRequirements.ALLOWED_DURATIONS) {
                for (int fps : VideoRequirements.ALLOWED_FPS) {
                    int requiredVramMb = VideoRequirements.requiredVramMb(resolution, durationSeconds, fps);
                    GpuTier tier = GpuTier.fromVramMb(requiredVramMb);
                    Integer tierRank = tier == null ? null : tier.getRank();

                    // 与 ClaimService 的领取 SQL 使用同一套判定（NodeCapability.satisfies）
                    boolean serviceable = online.stream()
                            .anyMatch(worker -> NodeCapability.satisfies(
                                    worker, requiredVramMb, tierRank, durationSeconds));

                    matrix.add(new CapabilityDtos.MatrixEntry(
                            resolution.getCode(),
                            durationSeconds,
                            fps,
                            requiredVramMb,
                            tier == null ? null : tier.name(),
                            VideoRequirements.summary(
                                    resolution.getCode(), durationSeconds, fps, requiredVramMb, tier),
                            serviceable));
                }
            }
        }
        return matrix;
    }
}
