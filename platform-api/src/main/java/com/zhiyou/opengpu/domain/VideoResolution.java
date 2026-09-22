package com.zhiyou.opengpu.domain;

import com.zhiyou.opengpu.common.BizException;
import com.zhiyou.opengpu.common.ErrorCode;

/**
 * 分辨率档位。<b>baseVramMb 是在「5 秒 / 30fps」基准下跑通 MiniMax H3 所需的显存占位值</b>，
 * 必须等 M0 单机验证完成后用实测峰值显存校准（见 docs/API.md §7.2）。
 */
public enum VideoResolution {

    SD_480P("480P", 854, 480, 4096),
    HD_720P("720P", 1280, 720, 8192),
    FHD_1080P("1080P", 1920, 1080, 12288),
    UHD_4K("4K", 3840, 2160, 24576);

    private final String code;
    private final int width;
    private final int height;
    private final int baseVramMb;

    VideoResolution(String code, int width, int height, int baseVramMb) {
        this.code = code;
        this.width = width;
        this.height = height;
        this.baseVramMb = baseVramMb;
    }

    public String getCode() {
        return code;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getBaseVramMb() {
        return baseVramMb;
    }

    /** 解析用户传入的分辨率编码，非法值抛 40002。 */
    public static VideoResolution fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.INVALID_REQUIREMENT, "resolution 不能为空");
        }
        String normalized = code.trim().toUpperCase(java.util.Locale.ROOT);
        for (VideoResolution resolution : values()) {
            if (resolution.code.equals(normalized)) {
                return resolution;
            }
        }
        throw new BizException(ErrorCode.INVALID_REQUIREMENT,
                "不支持的 resolution: " + code + "，允许值: 480P, 720P, 1080P, 4K");
    }
}
