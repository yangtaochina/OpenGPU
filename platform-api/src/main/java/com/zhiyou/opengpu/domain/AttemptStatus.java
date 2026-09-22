package com.zhiyou.opengpu.domain;

/**
 * 单次执行尝试的状态，仅追加不覆盖，用于保留失败历史。
 */
public enum AttemptStatus {

    /** 已分配租约，等待 Worker 开始执行 */
    ASSIGNED,
    /** Worker 已开始推理 */
    RUNNING,
    /** Worker 正在上传结果 */
    UPLOADING,
    /** 本次尝试成功返回结果 */
    SUCCEEDED,
    /** 本次尝试失败 */
    FAILED,
    /** 租约到期被平台回收 */
    LEASE_EXPIRED;

    public boolean isEnded() {
        return this == SUCCEEDED || this == FAILED || this == LEASE_EXPIRED;
    }
}
