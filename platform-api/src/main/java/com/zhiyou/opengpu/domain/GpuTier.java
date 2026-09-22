package com.zhiyou.opengpu.domain;

/**
 * 显卡算力档位。用于把「用户要求的视频规格」与「节点显卡能力」放在同一把尺子上比较。
 *
 * <p>rank 越小越弱，比较规则为 {@code 需求档位 <= 节点档位}。
 * 档位由显存推导（见 {@link #fromVramMb}），因为 V1 只能可靠地从节点拿到显存信息。
 */
public enum GpuTier {

    ENTRY(1, "入门", "< 12 GB"),
    STANDARD(2, "标准", "12 - 19 GB"),
    PRO(3, "专业", "20 - 39 GB"),
    ULTRA(4, "旗舰", ">= 40 GB");

    private final int rank;
    private final String label;
    private final String vramRange;

    GpuTier(int rank, String label, String vramRange) {
        this.rank = rank;
        this.label = label;
        this.vramRange = vramRange;
    }

    public int getRank() {
        return rank;
    }

    public String getLabel() {
        return label;
    }

    public String getVramRange() {
        return vramRange;
    }

    /** 由数据库中的 rank 还原；非法值返回 null 而不是抛异常，避免脏数据导致接口 500。 */
    public static GpuTier fromRank(Integer rank) {
        if (rank == null) {
            return null;
        }
        for (GpuTier tier : values()) {
            if (tier.rank == rank) {
                return tier;
            }
        }
        return null;
    }

    /**
     * 由显存推导档位。未上报显存时返回 null，表示「能力未知」，
     * 此时节点只能领取不限定硬件的任务。
     */
    public static GpuTier fromVramMb(Integer vramMb) {
        if (vramMb == null || vramMb <= 0) {
            return null;
        }
        if (vramMb < 12 * 1024) {
            return ENTRY;
        }
        if (vramMb < 20 * 1024) {
            return STANDARD;
        }
        if (vramMb < 40 * 1024) {
            return PRO;
        }
        return ULTRA;
    }
}
