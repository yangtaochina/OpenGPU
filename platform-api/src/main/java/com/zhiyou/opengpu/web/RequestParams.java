package com.zhiyou.opengpu.web;

import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 请求参数解析与分页构造。
 */
public final class RequestParams {

    private static final int MAX_PAGE_SIZE = 200;

    private RequestParams() {
    }

    public static Pageable pageable(int page, int size, String sortProperty) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, sortProperty));
    }

    /** 解析查询参数中的枚举，非法值返回 40000 而不是 500。 */
    public static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw com.zhiyou.opengpu.common.BizException.badRequest(
                    field + " 取值非法: " + raw + "，允许值: " + java.util.Arrays.toString(type.getEnumConstants()));
        }
    }
}
