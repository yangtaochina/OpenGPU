package com.zhiyou.opengpu.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zhiyou.opengpu.common.BizException;
import com.zhiyou.opengpu.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 视频要求 → 硬件约束的推导规则单测。
 *
 * <p>这些断言同时起到「系数变更哨兵」的作用：M0 之后校准显存系数时，
 * 需要同步更新期望值，从而强制开发者重新审视整条匹配链路。
 */
class VideoRequirementsTest {

    @Test
    @DisplayName("1080P / 5秒 / 30fps 为基准档：12GB 显存，STANDARD 档")
    void baselineProfile() {
        int vram = VideoRequirements.requiredVramMb(VideoResolution.FHD_1080P, 5, 30);
        assertThat(vram).isEqualTo(12288);
        assertThat(GpuTier.fromVramMb(vram)).isEqualTo(GpuTier.STANDARD);
    }

    @Test
    @DisplayName("720P / 5秒 / 30fps 只需 8GB，属入门档")
    void hdProfile() {
        int vram = VideoRequirements.requiredVramMb(VideoResolution.HD_720P, 5, 30);
        assertThat(vram).isEqualTo(8192);
        assertThat(GpuTier.fromVramMb(vram)).isEqualTo(GpuTier.ENTRY);
    }

    @Test
    @DisplayName("480P 低帧率向下取整仍不低于 4GB 且向上对齐 1GB")
    void lowProfileIsRoundedUp() {
        // 4096 × 1.0 × 0.9 = 3686.4 → 向上取整到 1GB 的倍数 → 4096
        int vram = VideoRequirements.requiredVramMb(VideoResolution.SD_480P, 3, 24);
        assertThat(vram).isEqualTo(4096);
        assertThat(vram % 1024).isZero();
    }

    @Test
    @DisplayName("4K / 15秒 / 60fps 是最重档，落进 ULTRA")
    void heaviestProfile() {
        // 24576 × 1.5 × 1.6 = 58982.4 → 59392
        int vram = VideoRequirements.requiredVramMb(VideoResolution.UHD_4K, 15, 60);
        assertThat(vram).isEqualTo(59392);
        assertThat(GpuTier.fromVramMb(vram)).isEqualTo(GpuTier.ULTRA);
    }

    @Test
    @DisplayName("时长与帧率系数单调不减")
    void factorsAreMonotonic() {
        assertThat(VideoRequirements.durationFactor(3))
                .isLessThanOrEqualTo(VideoRequirements.durationFactor(10));
        assertThat(VideoRequirements.durationFactor(10))
                .isLessThanOrEqualTo(VideoRequirements.durationFactor(15));

        assertThat(VideoRequirements.fpsFactor(24))
                .isLessThanOrEqualTo(VideoRequirements.fpsFactor(30));
        assertThat(VideoRequirements.fpsFactor(30))
                .isLessThanOrEqualTo(VideoRequirements.fpsFactor(60));
    }

    @Test
    @DisplayName("显卡档位边界：<12GB 入门 / 12-19GB 标准 / 20-39GB 专业 / >=40GB 旗舰")
    void tierBoundaries() {
        assertThat(GpuTier.fromVramMb(8192)).isEqualTo(GpuTier.ENTRY);
        assertThat(GpuTier.fromVramMb(12288)).isEqualTo(GpuTier.STANDARD);
        assertThat(GpuTier.fromVramMb(20479)).isEqualTo(GpuTier.STANDARD);
        assertThat(GpuTier.fromVramMb(20480)).isEqualTo(GpuTier.PRO);
        assertThat(GpuTier.fromVramMb(24576)).isEqualTo(GpuTier.PRO);
        assertThat(GpuTier.fromVramMb(40960)).isEqualTo(GpuTier.ULTRA);
        // 未上报显存 = 能力未知
        assertThat(GpuTier.fromVramMb(null)).isNull();
        assertThat(GpuTier.fromVramMb(0)).isNull();
    }

    @Test
    @DisplayName("非法要求参数抛 40002")
    void invalidRequirementIsRejected() {
        assertThatThrownBy(() -> VideoResolution.fromCode("8K"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUIREMENT);

        assertThatThrownBy(() -> VideoRequirements.validateDuration(7))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUIREMENT);

        assertThatThrownBy(() -> VideoRequirements.validateFps(120))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUIREMENT);
    }

    @Test
    @DisplayName("节点能力判定：无显存信息的节点只能领取不限硬件的任务")
    void unknownCapabilityWorkerOnlyTakesUnconstrainedTasks() {
        Worker unknown = Worker.builder().name("unknown").tokenHash("x").build();

        assertThat(NodeCapability.satisfies(unknown, null, null, null)).isTrue();
        assertThat(NodeCapability.satisfies(unknown, 12288, GpuTier.STANDARD.getRank(), 5)).isFalse();
    }

    @Test
    @DisplayName("节点能力判定：显存与档位不足或时长超限均不通过")
    void capabilityMatching() {
        Worker node = Worker.builder()
                .name("node").tokenHash("x")
                .vramMb(24576).gpuTier(GpuTier.PRO.getRank()).maxDurationSeconds(15)
                .build();

        // 1080P/5s/30fps → 12288 / STANDARD
        assertThat(NodeCapability.satisfies(node, 12288, GpuTier.STANDARD.getRank(), 5)).isTrue();
        // 4K/5s/30fps → 24576 / PRO，刚好卡在显存上限
        assertThat(NodeCapability.satisfies(node, 24576, GpuTier.PRO.getRank(), 5)).isTrue();
        // 4K/15s/60fps → 59392 / ULTRA，显存不足
        assertThat(NodeCapability.satisfies(node, 59392, GpuTier.ULTRA.getRank(), 15)).isFalse();
        // 时长超过节点声明的 15 秒上限
        assertThat(NodeCapability.satisfies(node, 12288, GpuTier.STANDARD.getRank(), 20)).isFalse();
    }

    @Test
    @DisplayName("节点未显式上报档位时按显存推导")
    void tierFallsBackToVram() {
        Worker node = Worker.builder().name("n").tokenHash("x").vramMb(24576).build();
        assertThat(NodeCapability.tierOf(node)).isEqualTo(GpuTier.PRO);
    }
}
