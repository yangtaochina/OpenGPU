package com.zhiyou.opengpu.statemachine;

import com.zhiyou.opengpu.common.BizException;
import com.zhiyou.opengpu.domain.TaskStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 任务状态机唯一入口。任何修改 {@code task.status} 的代码都必须先经过这里，
 * 避免状态在业务代码各处被随意改写。
 *
 * <p>迁移表依据需求文档 3.1，另做两处工程化放宽（已在下面注明）。
 */
@Component
public class TaskStateMachine {

    private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED = new EnumMap<>(TaskStatus.class);

    static {
        ALLOWED.put(TaskStatus.QUEUED, EnumSet.of(TaskStatus.ASSIGNED, TaskStatus.CANCELED));
        // 放宽点 1：ASSIGNED -> FAILED，用于 Worker 刚领取就发现环境不可用（如模型加载失败）的场景。
        ALLOWED.put(TaskStatus.ASSIGNED, EnumSet.of(TaskStatus.RUNNING, TaskStatus.QUEUED, TaskStatus.FAILED));
        // RUNNING -> QUEUED 与 UPLOADING -> QUEUED 由租约到期回收触发。
        ALLOWED.put(TaskStatus.RUNNING, EnumSet.of(TaskStatus.UPLOADING, TaskStatus.FAILED, TaskStatus.QUEUED));
        ALLOWED.put(TaskStatus.UPLOADING, EnumSet.of(TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.QUEUED));
        ALLOWED.put(TaskStatus.FAILED, EnumSet.of(TaskStatus.QUEUED));
        ALLOWED.put(TaskStatus.SUCCEEDED, EnumSet.noneOf(TaskStatus.class));
        ALLOWED.put(TaskStatus.CANCELED, EnumSet.noneOf(TaskStatus.class));
    }

    public boolean canTransition(TaskStatus from, TaskStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * 校验迁移合法性，非法则抛出 40900。
     */
    public void assertTransition(TaskStatus from, TaskStatus to) {
        if (!canTransition(from, to)) {
            throw BizException.stateConflict(
                    "非法状态迁移：%s -> %s".formatted(from, to));
        }
    }
}
