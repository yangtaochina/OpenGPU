package com.zhiyou.opengpu.domain;

/**
 * 平台账号角色。V1 不做租户隔离，仅区分普通用户与管理员。
 */
public enum UserRole {

    USER,
    ADMIN
}
