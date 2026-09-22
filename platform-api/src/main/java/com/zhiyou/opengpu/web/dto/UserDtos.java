package com.zhiyou.opengpu.web.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;

/**
 * 管理端用户管理 DTO（docs/API.md §4.5）。
 */
public final class UserDtos {

    private UserDtos() {
    }

    /** 对外的账号视图。**不含 passwordHash**。 */
    public record UserView(
            UUID id,
            String username,
            String role,
            boolean enabled,
            Instant lastLoginAt,
            Instant createdAt) {
    }

    /** 管理员创建账号：与开放注册的区别是可以直接指定角色。 */
    public record CreateUserRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password,
            String role) {
    }

    public record ResetPasswordRequest(
            @NotBlank(message = "新密码不能为空") String newPassword) {
    }

    public record UpdateUserRoleRequest(
            @NotBlank(message = "role 不能为空") String role) {
    }
}
