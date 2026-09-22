package com.zhiyou.opengpu.web;

import com.zhiyou.opengpu.common.ApiResponse;
import com.zhiyou.opengpu.service.FileService;
import com.zhiyou.opengpu.web.dto.FileDtos;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 结果文件上传（FR-W06）。
 *
 * <p>V1 统一走平台中转，服务端在落库时同步计算 sha256，
 * 供 complete 阶段做一致性校验（AT-07）。
 */
@RestController
@RequestMapping("/api/worker/files")
public class WorkerFileController {

    private final FileService fileService;

    public WorkerFileController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/presign")
    public ApiResponse<FileDtos.PresignResponse> presign(@Valid @RequestBody FileDtos.PresignRequest request) {
        return ApiResponse.ok(fileService.presign(request));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FileDtos.UploadedFileResponse> upload(
            @RequestParam("fileKey") String fileKey,
            @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(fileService.upload(fileKey, file));
    }
}
