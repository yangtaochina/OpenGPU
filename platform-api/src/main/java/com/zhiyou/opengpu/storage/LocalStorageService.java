package com.zhiyou.opengpu.storage;

import com.zhiyou.opengpu.common.BizException;
import com.zhiyou.opengpu.common.ErrorCode;
import com.zhiyou.opengpu.config.OpenGpuProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * 本地磁盘存储实现（开发默认）。
 *
 * <p>每个文件旁写一个 {@code <file>.sha256} 边车文件，避免在 complete 阶段
 * 重新读取大文件计算校验值。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "opengpu.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalStorageService implements StorageService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    /** 只接受平台自己生成的 key，杜绝路径穿越。 */
    private static final Pattern KEY_PATTERN =
            Pattern.compile("^videos/\\d{8}/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.[A-Za-z0-9]{1,8}$");

    private final Path root;
    private final String publicBaseUrl;
    private final OpenGpuProperties properties;

    public LocalStorageService(OpenGpuProperties properties) {
        this.properties = properties;
        String configured = properties.getStorage().getLocalRoot();
        this.root = Paths.get(configured == null ? "./data/videos" : configured).toAbsolutePath().normalize();
        String base = properties.getStorage().getPublicBaseUrl();
        this.publicBaseUrl = base == null ? "" : base.replaceAll("/+$", "");
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建本地存储目录: " + root, e);
        }
        log.info("本地存储已启用，根目录: {}", root);
    }

    @Override
    public String type() {
        return "local";
    }

    @Override
    public String allocateKey(String originalFilename) {
        String ext = extensionOf(originalFilename);
        return "videos/" + DAY.format(Instant.now()) + "/" + UUID.randomUUID() + "." + ext;
    }

    @Override
    public StoredObject store(String key, InputStream in, long declaredSize, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long written;
            try (InputStream source = new DigestInputStream(in, digest);
                 OutputStream out = Files.newOutputStream(target,
                         StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                written = source.transferTo(out);
            }
            String checksum = HexFormat.of().formatHex(digest.digest());
            Files.writeString(sidecar(target), checksum, StandardCharsets.UTF_8);
            log.info("结果文件已落盘: key={}, size={}, checksum={}", key, written, checksum);
            return new StoredObject(key, written, checksum, contentType, publicUrl(key));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new BizException(ErrorCode.STORAGE_ERROR, "本地存储写入失败: " + e.getMessage());
        }
    }

    @Override
    public Resource load(String key) {
        Path target = resolve(key);
        if (!Files.isRegularFile(target)) {
            throw BizException.notFound("结果文件不存在: " + key);
        }
        return new FileSystemResource(target);
    }

    @Override
    public boolean exists(String key) {
        try {
            return Files.isRegularFile(resolve(key));
        } catch (BizException e) {
            return false;
        }
    }

    @Override
    public Long size(String key) {
        try {
            Path target = resolve(key);
            return Files.isRegularFile(target) ? Files.size(target) : null;
        } catch (IOException | BizException e) {
            return null;
        }
    }

    @Override
    public String checksum(String key) {
        Path target = resolve(key);
        Path sidecar = sidecar(target);
        try {
            if (Files.isRegularFile(sidecar)) {
                return Files.readString(sidecar, StandardCharsets.UTF_8).trim();
            }
            if (!Files.isRegularFile(target)) {
                return null;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(target)) {
                in.transferTo(new java.io.OutputStream() {
                    @Override
                    public void write(int b) {
                        digest.update((byte) b);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) {
                        digest.update(b, off, len);
                    }
                });
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            log.warn("计算文件校验值失败: key={}, cause={}", key, e.getMessage());
            return null;
        }
    }

    @Override
    public String publicUrl(String key) {
        return publicBaseUrl + "/api/files/" + key;
    }

    private Path sidecar(Path file) {
        return file.resolveSibling(file.getFileName() + ".sha256");
    }

    private Path resolve(String key) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw BizException.notFound("非法的文件 key: " + key);
        }
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw BizException.notFound("非法的文件 key: " + key);
        }
        return target;
    }

    private String extensionOf(String filename) {
        String ext = "mp4";
        if (filename != null && filename.contains(".")) {
            String candidate = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (candidate.matches("[a-z0-9]{1,8}")) {
                ext = candidate;
            }
        }
        if (!properties.getTask().getAllowedVideoExtensions().contains(ext)) {
            throw BizException.badRequest("不支持的视频格式: " + ext
                    + "，允许: " + properties.getTask().getAllowedVideoExtensions());
        }
        return ext;
    }
}
