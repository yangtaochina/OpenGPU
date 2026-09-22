package com.zhiyou.opengpu.domain;

import java.util.Objects;

/**
 * 节点能力判定。
 *
 * <p><b>重要：</b>本类必须与 {@code ClaimService} 中的领取 SQL 过滤条件保持完全一致。
 * SQL 负责在数据库侧筛掉不合格的任务，本类负责在生成「能力矩阵」时给出同样的判断，
 * 两处任何一处改动都必须同步另一处。
 */
public final class NodeCapability {

    private NodeCapability() {
    }

    /** 节点档位：优先取显式上报值，缺失时按显存推导。 */
    public static GpuTier tierOf(Worker worker) {
        GpuTier explicit = GpuTier.fromRank(worker.getGpuTier());
        return explicit != null ? explicit : GpuTier.fromVramMb(worker.getVramMb());
    }

    /**
     * 节点是否满足任务要求。与领取 SQL 语义一一对应：
     * <ul>
     *   <li>任务无显存要求 → 通过</li>
     *   <li>节点未上报显存 → 只能承接无显存要求的任务</li>
     *   <li>节点未声明最长时长（null 或 &lt;= 0）→ 视为不限制</li>
     * </ul>
     */
    public static boolean satisfies(Worker worker,
                                    Integer requiredVramMb,
                                    Integer minGpuTierRank,
                                    Integer durationSeconds) {
        if (requiredVramMb != null) {
            Integer vram = worker.getVramMb();
            if (vram == null || vram <= 0 || requiredVramMb > vram) {
                return false;
            }
        }
        if (minGpuTierRank != null) {
            GpuTier tier = tierOf(worker);
            if (tier == null || tier.getRank() < minGpuTierRank) {
                return false;
            }
        }
        if (durationSeconds != null) {
            Integer maxDuration = worker.getMaxDurationSeconds();
            boolean limited = maxDuration != null && maxDuration > 0;
            if (limited && durationSeconds > maxDuration) {
                return false;
            }
        }
        return true;
    }

    /** 便于日志与展示。 */
    public static String describe(Worker worker) {
        GpuTier tier = tierOf(worker);
        return "%s/%s(%dMB)".formatted(
                Objects.toString(worker.getGpuModel(), "unknown"),
                tier == null ? "UNKNOWN" : tier.name(),
                worker.getVramMb() == null ? 0 : worker.getVramMb());
    }
}
