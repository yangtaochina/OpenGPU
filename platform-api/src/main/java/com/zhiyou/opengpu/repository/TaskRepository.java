package com.zhiyou.opengpu.repository;

import com.zhiyou.opengpu.domain.Task;
import com.zhiyou.opengpu.domain.TaskStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, java.util.UUID> {

    Page<Task> findByStatus(TaskStatus status, Pageable pageable);

    long countByStatus(TaskStatus status);

    /**
     * 租约回收：找出处于执行中状态但租约已经过期的任务。
     */
    List<Task> findByStatusInAndLeaseExpiresAtBefore(Collection<TaskStatus> statuses, Instant before);
}
