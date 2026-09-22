package com.zhiyou.opengpu.storage;

import com.zhiyou.opengpu.common.BizException;
import com.zhiyou.opengpu.common.ErrorCode;
import com.zhiyou.opengpu.config.OpenGpuProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
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
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * MinIO / S3 兼容对象存储实现。
 *
 * <p>启用方式：{@code opengpu.storage.type=minio}。
 * 上传统一走平台中转（{@code opengpu.storage.minio} 指向内网地址即可），
 * 后续可扩展为 Worker 直传预签名 URL。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "opengpu.storage.type", havingValue = "minio")
public class MinioStorageService implements StorageService {

    private static final Pattern KEY_PATTERN =
            Pattern.compile("^videos/\\d{8}/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.[A-Za-z0-9]{1,8}$");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final long PART_SIZE = 10L * 1024 * 1024;

    private final MinioClient client;
    private final OpenGpuProperties properties;
    private final String bucket;
    private final String publicBase;
    private volatile boolean bucketReady = false;

    public MinioStorageService(OpenGpuProperties properties) {
        OpenGpuProperties.Storage.Minio minio = properties.getStorage().getMinio();
        this.properties = properties;
        this.bucket = minio.getBucket();
        this.client = MinioClient.builder()
                .endpoint(minio.getEndpoint())
                .credentials(minio.getAccessKey(), minio.getSecretKey())
                .build();
        String endpoint = minio.getPublicEndpoint() == null || minio.getPublicEndpoint().isBlank()
                ? minio.getEndpoint()
                : minio.getPublicEndpoint();
        this.publicBase = endpoint.replaceAll("/+$", "");
    }

    @PostConstruct
    void init() {
        try {
            ensureBucket();
        } catch (Exception e) {
            log.warn("MinIO 初始化失败（将在首次上传时重试）: {}", e.getMessage());
        }
    }

    @Override
    public String type() {
        return "minio";
    }

    @Override
    public String allocateKey(String originalFilename) {
        String ext = "mp4";
        if (originalFilename != null && originalFilename.contains(".")) {
            String candidate = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (candidate.matches("[a-z0-9]{1,8}")) {
                ext = candidate;
            }
        }
        if (!properties.getTask().getAllowedVideoExtensions().contains(ext)) {
            throw BizException.badRequest("不支持的视频格式: " + ext
                    + "，允许: " + properties.getTask().getAllowedVideoExtensions());
        }
        return "videos/" + DAY.format(Instant.now()) + "/" + UUID.randomUUID() + "." + ext;
    }

    @Override
    public StoredObject store(String key, InputStream in, long declaredSize, String contentType) {
        requireValidKey(key);
        try {
            ensureBucket();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream source = new DigestInputStream(in, digest)) {
                PutObjectArgs.Builder builder = PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(key)
                        .contentType(contentType == null ? "application/octet-stream" : contentType);
                if (declaredSize > 0) {
                    builder.stream(source, declaredSize, -1);
                } else {
                    builder.stream(source, -1, PART_SIZE);
                }
                client.putObject(builder.build());
            }
            String checksum = HexFormat.of().formatHex(digest.digest());
            Long actualSize = size(key);
            long written = actualSize != null ? actualSize : Math.max(declaredSize, 0L);
            log.info("结果文件已上传 MinIO: bucket={}, key={}, size={}, checksum={}", bucket, key, written, checksum);
            return new StoredObject(key, written, checksum, contentType, publicUrl(key));
        } catch (Exception e) {
            throw new BizException(ErrorCode.STORAGE_ERROR, "MinIO 上传失败: " + e.getMessage());
        }
    }

    @Override
    public Resource load(String key) {
        requireValidKey(key);
        try {
            InputStream stream = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
            return new InputStreamResource(stream);
        } catch (Exception e) {
            throw BizException.notFound("结果文件不存在: " + key);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            return stat(key) != null;
        } catch (BizException e) {
            return false;
        }
    }

    @Override
    public Long size(String key) {
        try {
            StatObjectResponse stat = stat(key);
            return stat == null ? null : stat.size();
        } catch (BizException e) {
            return null;
        }
    }

    @Override
    public String checksum(String key) {
        // MinIO 不保存业务 sha256；complete 阶段以 Worker 上报值为准，
        // 并额外校验文件存在性与字节数。如需强校验，可改为读取对象重算。
        return null;
    }

    @Override
    public String publicUrl(String key) {
        String base = (properties.getStorage().getPublicBaseUrl() == null || properties.getStorage().getPublicBaseUrl().isBlank())
                ? publicBase + "/" + bucket
                : properties.getStorage().getPublicBaseUrl().replaceAll("/+$", "");
        return base + "/" + key;
    }

    private StatObjectResponse stat(String key) {
        requireValidKey(key);
        try {
            return client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return null;
            }
            throw new BizException(ErrorCode.STORAGE_ERROR, "MinIO 查询失败: " + e.getMessage());
        } catch (Exception e) {
            throw new BizException(ErrorCode.STORAGE_ERROR, "MinIO 查询失败: " + e.getMessage());
        }
    }

    private void ensureBucket() throws Exception {
        if (bucketReady) {
            return;
        }
        synchronized (this) {
            if (bucketReady) {
                return;
            }
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("已创建 MinIO 存储桶: {}", bucket);
            }
            bucketReady = true;
        }
    }

    private void requireValidKey(String key) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw BizException.notFound("非法的文件 key: " + key);
        }
    }
}
