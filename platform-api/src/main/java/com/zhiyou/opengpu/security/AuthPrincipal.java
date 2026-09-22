package com.zhiyou.opengpu.security;

import java.util.UUID;

/**
 * 统一认证主体。用户/管理员与 Worker 共用同一结构，用 {@code role} 区分，
 * role 取值：{@code USER} | {@code ADMIN} | {@code WORKER}。
 */
public record AuthPrincipal(UUID id, String name, String role) {

    public static final String ROLE_WORKER = "WORKER";

    public boolean isWorker() {
        return ROLE_WORKER.equals(role);
    }
}
