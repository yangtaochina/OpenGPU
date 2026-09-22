package com.zhiyou.opengpu.domain;

/**
 * 节点运行时忙闲状态，由 Worker 心跳上报。
 */
public enum WorkerRuntimeStatus {

    IDLE,
    BUSY
}
