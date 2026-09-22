package com.zhiyou.opengpu.web.dto;

import java.time.Instant;

/**
 * 文件上传相关 DTO。
 */
public final class FileDtos {

    private FileDtos() {
    }

    public record PresignRequest(
            String filename,
            String contentType,
            Long sizeBytes) {
    }

    /**
     * @param mode {@code multipart} 表示走平台中转上传；{@code presigned-put} 为后续直传扩展
     */
    public record PresignResponse(
            String fileKey,
            String uploadUrl,
            String method,
            String mode,
            long maxSizeBytes,
            Instant expiresAt) {
    }

    public record UploadedFileResponse(
            String fileKey,
            String fileUrl,
            long size,
            String checksum,
            String contentType) {
    }

    public static final String MODE_MULTIPART = "multipart";
}
