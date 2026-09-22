package com.zhiyou.opengpu.web.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/**
 * 认证相关 DTO。
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password) {
    }

    public record LoginResponse(
            String token,
            String username,
            String role,
            long expiresInSeconds) {
    }

    public record MeResponse(UUID id, String username, String role) {
    }

    /** 自助注册请求。角色固定为 USER，请求体中的 role 会被忽略。 */
    public record RegisterRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password) {
    }

    /** 平台账号策略，供登录/注册页读取，避免前端硬编码校验规则。 */
    public record AuthConfigView(
            boolean registrationEnabled,
            int passwordMinLength,
            int passwordMaxLength,
            int usernameMinLength,
            int usernameMaxLength) {
    }
}
