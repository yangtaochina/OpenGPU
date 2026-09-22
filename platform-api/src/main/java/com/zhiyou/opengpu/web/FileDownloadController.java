package com.zhiyou.opengpu.web;

import com.zhiyou.opengpu.storage.StorageService;
import java.util.Locale;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
/**
 * 结果文件下载/播放。
 *
 * <p>免认证：{@code <video src="...">} 无法携带 Authorization 头。
 * key 为随机 UUID 文件名，V1 内测阶段接受该风险；对外开放前需引入签名 URL。
 */
@RestController
public class FileDownloadController {

    private final StorageService storageService;

    public FileDownloadController(StorageService storageService) {
        this.storageService = storageService;
    }

    @GetMapping("/api/files/{*key}")
    public ResponseEntity<Resource> download(@PathVariable String key) {
        String normalized = key.startsWith("/") ? key.substring(1) : key;
        Resource resource = storageService.load(normalized);
        Long size = storageService.size(normalized);

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(mediaTypeOf(normalized))
                .header("Accept-Ranges", "none");
        if (size != null && size >= 0) {
            builder.contentLength(size);
        }
        return builder.body(resource);
    }

    private MediaType mediaTypeOf(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp4")) {
            return MediaType.parseMediaType("video/mp4");
        }
        if (lower.endsWith(".webm")) {
            return MediaType.parseMediaType("video/webm");
        }
        if (lower.endsWith(".mov")) {
            return MediaType.parseMediaType("video/quicktime");
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
