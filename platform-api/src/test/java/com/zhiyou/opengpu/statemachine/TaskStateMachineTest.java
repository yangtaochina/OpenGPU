package com.zhiyou.opengpu.statemachine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zhiyou.opengpu.common.BizException;
import com.zhiyou.opengpu.common.ErrorCode;
import com.zhiyou.opengpu.domain.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 任务状态机守卫的单测。覆盖需求文档 3.1 的迁移表与两处放宽点。
 */
class TaskStateMachineTest {

    private final TaskStateMachine stateMachine = new TaskStateMachine();

    @Test
    @DisplayName("合法迁移：正常闭环 QUEUED -> ASSIGNED -> RUNNING -> UPLOADING -> SUCCEEDED")
    void happyPath() {
        assertThat(stateMachine.canTransition(TaskStatus.QUEUED, TaskStatus.ASSIGNED)).isTrue();
        assertThat(stateMachine.canTransition(TaskStatus.ASSIGNED, TaskStatus.RUNNING)).isTrue();
        assertThat(stateMachine.canTransition(TaskStatus.RUNNING, TaskStatus.UPLOADING)).isTrue();
        assertThat(stateMachine.canTransition(TaskStatus.UPLOADING, TaskStatus.SUCCEEDED)).isTrue();
    }

    @Test
    @DisplayName("取消：仅 QUEUED 可直接取消")
    void cancelOnlyFromQueued() {
        assertThat(stateMachine.canTransition(TaskStatus.QUEUED, TaskStatus.CANCELED)).isTrue();
        assertThat(stateMachine.canTransition(TaskStatus.RUNNING, TaskStatus.CANCELED)).isFalse();
        assertThat(stateMachine.canTransition(TaskStatus.SUCCEEDED, TaskStatus.CANCELED)).isFalse();
    }

    @Test
    @DisplayName("租约回收：执行中状态均可回退到 QUEUED")
    void leaseRecycleBackToQueued() {
        assertThat(stateMachine.canTransition(TaskStatus.ASSIGNED, TaskStatus.QUEUED)).isTrue();
        assertThat(stateMachine.canTransition(TaskStatus.RUNNING, TaskStatus.QUEUED)).isTrue();
        assertThat(stateMachine.canTransition(TaskStatus.UPLOADING, TaskStatus.QUEUED)).isTrue();
    }

    @Test
    @DisplayName("终态不可再迁移")
    void terminalStatesAreFinal() {
        for (TaskStatus target : TaskStatus.values()) {
            assertThat(stateMachine.canTransition(TaskStatus.SUCCEEDED, target)).isFalse();
            assertThat(stateMachine.canTransition(TaskStatus.CANCELED, target)).isFalse();
        }
    }

    @Test
    @DisplayName("非法迁移抛出 40900")
    void illegalTransitionThrows409() {
        assertThatThrownBy(() -> stateMachine.assertTransition(TaskStatus.QUEUED, TaskStatus.SUCCEEDED))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STATE_CONFLICT);
    }

    @Test
    @DisplayName("人工重试：FAILED -> QUEUED")
    void manualRetry() {
        assertThat(stateMachine.canTransition(TaskStatus.FAILED, TaskStatus.QUEUED)).isTrue();
    }

    @Test
    @DisplayName("ASSIGNED 阶段即可上报失败（Worker 领取后发现环境不可用）")
    void assignedCanFail() {
        assertThat(stateMachine.canTransition(TaskStatus.ASSIGNED, TaskStatus.FAILED)).isTrue();
    }
}
