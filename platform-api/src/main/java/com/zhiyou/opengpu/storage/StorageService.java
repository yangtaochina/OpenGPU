package com.zhiyou.opengpu.storage;

import java.io.InputStream;
import org.springframework.core.io.Resource;

/**
 * 结果文件存储抽象。V1 支持本地磁盘与 MinIO 两种实现，
 * 由 {@code opengpu.storage.type} 选择。
 */
public interface StorageService {

    /** 实现类型标识：local / minio。 */
    String type();

    /** 为一次上传分配唯一 key，形如 {@code videos/20260922/<uuid>.mp4}。 */
    String allocateKey(String originalFilename);

    /** 落盘/上传并返回实际存储元数据（含 sha256）。 */
    StoredObject store(String key, InputStream in, long declaredSize, String contentType);

    /** 读取文件内容用于下载/播放。 */
    Resource load(String key);

    boolean exists(String key);

    /** 返回字节数，不存在返回 null。 */
    Long size(String key);

    /** 返回已存储文件的 sha256（可能为 null，取决于实现）。 */
    String checksum(String key);

    /** 对外访问地址，可能是相对路径 {@code /api/files/...}。 */
    String publicUrl(String key);
}
