package com.zhiyou.opengpu.storage;

/**
 * 存储完成后的元数据。
 *
 * @param key         存储 key
 * @param size        实际字节数
 * @param checksum    sha256 十六进制
 * @param contentType 内容类型
 * @param url         对外访问地址
 */
public record StoredObject(
        String key,
        long size,
        String checksum,
        String contentType,
        String url) {
}
