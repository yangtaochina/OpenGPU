package com.zhiyou.opengpu.domain;

/**
 * 任务状态，取值与 docs/API.md 1.5 一致。
 * 合法迁移由 {@link com.zhiyou.opengpu.statemachine.TaskStateMachine} 统一校验。
 */
public enum TaskStatus {

    /** 等待 Worker 领取 */
    QUEUED,
    /** 已分配，等待 Worker 确认执行 */
    ASSIGNED,
    /** 模型正在生成 */
    RUNNING,
    /** Worker 正在上传生成结果 */
    UPLOADING,
    /** 任务和结果均已完成 */
    SUCCEEDED,
    /** 执行失败且不再自动重试 */
    FAILED,
    /** 任务已取消 */
    CANCELED;

    /** 终态：不再发生任何状态迁移。 */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == CANCELED;
    }

    /** 正在被某个 Worker 持有租约的状态。 */
    public boolean isInFlight() {
        return this == ASSIGNED || this == RUNNING || this == UPLOADING;
    }
}
