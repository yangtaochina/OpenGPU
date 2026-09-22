package com.zhiyou.opengpu.common;

/**
 * 业务异常：携带 {@link ErrorCode}，由 GlobalExceptionHandler 统一转成响应信封。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    // ---- 常用快捷方法 ----

    public static BizException notFound(String message) {
        return new BizException(ErrorCode.NOT_FOUND, message);
    }

    public static BizException badRequest(String message) {
        return new BizException(ErrorCode.BAD_REQUEST, message);
    }

    public static BizException stateConflict(String message) {
        return new BizException(ErrorCode.STATE_CONFLICT, message);
    }

    public static BizException leaseInvalid(String message) {
        return new BizException(ErrorCode.LEASE_INVALID, message);
    }

    public static BizException forbidden(String message) {
        return new BizException(ErrorCode.FORBIDDEN, message);
    }
}
