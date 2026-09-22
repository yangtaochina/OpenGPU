package com.zhiyou.opengpu.common;

import org.springframework.http.HttpStatus;

/**
 * 统一业务错误码，与 docs/API.md 1.2 节保持一致。
 */
public enum ErrorCode {

    OK(0, "success", HttpStatus.OK),

    BAD_REQUEST(40000, "参数校验失败", HttpStatus.BAD_REQUEST),
    INVALID_PROMPT(40001, "非法提示词：不能为空且长度不能超过限制", HttpStatus.BAD_REQUEST),
    INVALID_REQUIREMENT(40002, "非法的视频要求参数", HttpStatus.BAD_REQUEST),
    INVALID_CREDENTIALS_FORMAT(40003, "用户名或密码格式不符合要求", HttpStatus.BAD_REQUEST),

    UNAUTHORIZED(40100, "未认证或凭证无效", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(40300, "无权限执行该操作", HttpStatus.FORBIDDEN),
    REGISTRATION_CLOSED(40301, "平台当前未开放注册", HttpStatus.FORBIDDEN),
    PROTECTED_ADMIN_OPERATION(40302, "该管理操作被保护性拒绝", HttpStatus.FORBIDDEN),

    NOT_FOUND(40400, "资源不存在", HttpStatus.NOT_FOUND),

    STATE_CONFLICT(40900, "当前状态不允许该操作", HttpStatus.CONFLICT),
    LEASE_INVALID(40910, "租约已失效：任务已被重新分配或租约已过期", HttpStatus.CONFLICT),
    FILE_INVALID(40920, "结果文件不存在或校验失败", HttpStatus.CONFLICT),
    USERNAME_TAKEN(40930, "用户名已被占用", HttpStatus.CONFLICT),

    INTERNAL_ERROR(50000, "服务内部错误", HttpStatus.INTERNAL_SERVER_ERROR),
    STORAGE_ERROR(50001, "存储服务错误", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String defaultMessage, HttpStatus httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    public int code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
