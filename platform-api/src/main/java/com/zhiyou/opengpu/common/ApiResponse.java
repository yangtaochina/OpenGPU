package com.zhiyou.opengpu.common;

/**
 * 统一响应信封：{ code, message, data }。
 *
 * @param code    0 表示成功，非 0 见 {@link ErrorCode}
 * @param message 可直接展示的错误摘要
 * @param data    业务数据，错误时为 null
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.OK.code(), ErrorCode.OK.defaultMessage(), data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(ErrorCode.OK.code(), ErrorCode.OK.defaultMessage(), null);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.code(), errorCode.defaultMessage(), null);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.code(), message == null ? errorCode.defaultMessage() : message, null);
    }
}
