package com.zhiyou.opengpu.domain;

/**
 * 节点对外可见状态。
 */
public enum WorkerStatus {

    /** 心跳正常 */
    ONLINE,
    /** 心跳超时 */
    OFFLINE,
    /** 被管理员停用 */
    DISABLED
}
