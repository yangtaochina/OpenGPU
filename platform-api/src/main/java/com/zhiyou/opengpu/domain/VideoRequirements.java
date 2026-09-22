package com.zhiyou.opengpu.domain;

import com.zhiyou.opengpu.common.BizException;
import com.zhiyou.opengpu.common.ErrorCode;
import java.util.List;
import java.util.Set;

/**
 * 视频要求 → 硬件约束的推导规则。
 *
 * <p><b>⚠️ 本类中的全部系数均为工程占位值。</b>
 * 它们的作用是把「用户选的视频规格」稳定地映射成「显存下限 + 显卡档位」，
 * 从而让能力匹配的框架可以先跑起来。
 * <b>M0（MiniMax H3 单机部署验证）完成后，必须用实测峰值显存重新校准这些数字</b>，
 * 在此之前不得把推导结果作为对外承诺。校准只需改动本类常量。
 *
 * <p>推导公式：
 * <pre>
 *   requiredVramMb = ceil1024( baseVramMb × durationFactor × fpsFactor )
 *   minGpuTier     = GpuTier.fromVramMb(requiredVramMb)
 * </pre>
 */
public final class VideoRequirements {

    /** 允许的时长取值（秒） */
    public static final List<Integer> ALLOWED_DURATIONS = List.of(3, 5, 10, 15);

    /** 允许的帧率取值 */
    public static final List<Integer> ALLOWED_FPS = List.of(24, 30, 60);

    private static final Set<Integer> DURATION_SET = Set.copyOf(ALLOWED_DURATIONS);
    private static final Set<Integer> FPS_SET = Set.copyOf(ALLOWED_FPS);

    private static final long VRAM_STEP_MB = 1024L;
    private static final int MIN_VRAM_MB = 1024;

    private VideoRequirements() {
    }

    /** 时长系数：3~5 秒为基准，10 秒、15 秒逐级上调。 */
    public static double durationFactor(int durationSeconds) {
        if (durationSeconds <= 5) {
            return 1.0;
        }
        if (durationSeconds <= 10) {
            return 1.25;
        }
        return 1.5;
    }

    /** 帧率系数：30fps 为基准，24fps 略降，60fps 显著上调。 */
    public static double fpsFactor(int fps) {
        if (fps <= 24) {
            return 0.9;
        }
        if (fps <= 30) {
            return 1.0;
        }
        return 1.6;
    }

    /** 推导显存下限（MB），向上取整到 1GB 的整数倍。 */
    public static int requiredVramMb(VideoResolution resolution, int durationSeconds, int fps) {
        double raw = resolution.getBaseVramMb() * durationFactor(durationSeconds) * fpsFactor(fps);
        long rounded = (long) Math.ceil(raw / VRAM_STEP_MB) * VRAM_STEP_MB;
        return (int) Math.max(MIN_VRAM_MB, rounded);
    }

    /** 面向用户展示的要求摘要。 */
    public static String summary(String resolutionCode, int durationSeconds, int fps,
                                 int requiredVramMb, GpuTier tier) {
        return "%s · %d秒 · %dfps → 需要 ≥%dGB 显存（%s 级 GPU）".formatted(
                resolutionCode, durationSeconds, fps,
                requiredVramMb / 1024, tier == null ? "未知" : tier.name());
    }

    public static void validateDuration(Integer durationSeconds) {
        if (durationSeconds == null || !DURATION_SET.contains(durationSeconds)) {
            throw new BizException(ErrorCode.INVALID_REQUIREMENT,
                    "不支持的 durationSeconds: " + durationSeconds + "，允许值: " + ALLOWED_DURATIONS);
        }
    }

    public static void validateFps(Integer fps) {
        if (fps == null || !FPS_SET.contains(fps)) {
            throw new BizException(ErrorCode.INVALID_REQUIREMENT,
                    "不支持的 fps: " + fps + "，允许值: " + ALLOWED_FPS);
        }
    }
}
