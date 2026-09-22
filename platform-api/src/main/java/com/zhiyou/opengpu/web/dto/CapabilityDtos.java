package com.zhiyou.opengpu.web.dto;

import java.util.List;

/**
 * 视频要求能力矩阵，对应 docs/API.md §3 的 {@code GET /api/capabilities}。
 *
 * <p>该接口把「全部可选组合 → 推导出的硬件约束 → 当前在线节点能否承接」
 * 一次性交给前端，避免前端重复实现推导公式。
 */
public final class CapabilityDtos {

    private CapabilityDtos() {
    }

    /** 当前在线节点（启用且 ONLINE）的能力上界。无在线节点时各字段为 null / 0。 */
    public record FleetView(
            int onlineWorkers,
            Integer maxVramMb,
            String maxGpuTier,
            Integer maxDurationSeconds) {
    }

    public record ResolutionOption(String value, String label, int width, int height) {
    }

    public record OptionsView(
            List<ResolutionOption> resolutions,
            List<Integer> durations,
            List<Integer> fps) {
    }

    /** 一种「分辨率 + 时长 + 帧率」组合的推导结果与可服务性。 */
    public record MatrixEntry(
            String resolution,
            int durationSeconds,
            int fps,
            int requiredVramMb,
            String gpuTier,
            String summary,
            boolean serviceable) {
    }

    public record CapabilityView(
            FleetView fleet,
            OptionsView options,
            List<MatrixEntry> matrix) {
    }
}
