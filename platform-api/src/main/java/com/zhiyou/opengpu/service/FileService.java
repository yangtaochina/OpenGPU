package com.zhiyou.opengpu.service;

import com.zhiyou.opengpu.common.BizException;
import com.zhiyou.opengpu.common.ErrorCode;
import com.zhiyou.opengpu.config.OpenGpuProperties;
import com.zhiyou.opengpu.storage.StorageService;
import com.zhiyou.opengpu.storage.StoredObject;
import com.zhiyou.opengpu.web.dto.FileDtos;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 结果文件上传。V1 统一走平台中转（multipart），
 * 便于在服务端计算校验值并校验文件存在性（AT-07）。
 */
@Slf4j
@Service
public class FileService {

    private static final long PRESIGN_TTL_SECONDS = 1800;

    private final StorageService storageService;
    private final OpenGpuProperties properties;

    public FileService(StorageService storageService, OpenGpuProperties properties) {
        this.storageService = storageService;
        this.properties = properties;
    }

    /** 申请一个存储 key。 */
    public FileDtos.PresignResponse presign(FileDtos.PresignRequest request) {
        long maxSize = properties.getTask().getMaxFileSizeBytes();
        if (request.sizeBytes() != null && request.sizeBytes() > maxSize) {
            throw BizException.badRequest("声明文件大小超过平台上限 " + maxSize + " 字节");
        }
        String fileKey = storageService.allocateKey(request.filename());
        return new FileDtos.PresignResponse(
                fileKey,
                "/api/worker/files",
                "POST",
                FileDtos.MODE_MULTIPART,
                maxSize,
                Instant.now().plusSeconds(PRESIGN_TTL_SECONDS));
    }

    /** 中转上传，返回实际落库的元数据。 */
    public FileDtos.UploadedFileResponse upload(String fileKey, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("上传文件不能为空");
        }
        long maxSize = properties.getTask().getMaxFileSizeBytes();
        if (file.getSize() > maxSize) {
            throw BizException.badRequest("上传文件超过平台上限 " + maxSize + " 字节");
        }
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        try (InputStream in = file.getInputStream()) {
            StoredObject stored = storageService.store(fileKey, in, file.getSize(), contentType);
            return new FileDtos.UploadedFileResponse(
                    stored.key(), stored.url(), stored.size(), stored.checksum(), stored.contentType());
        } catch (IOException e) {
            throw new BizException(ErrorCode.STORAGE_ERROR, "读取上传文件失败: " + e.getMessage());
        }
    }
}
