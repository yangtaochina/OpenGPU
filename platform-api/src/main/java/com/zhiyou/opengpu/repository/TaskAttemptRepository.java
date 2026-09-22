package com.zhiyou.opengpu.repository;

import com.zhiyou.opengpu.domain.TaskAttempt;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskAttemptRepository extends JpaRepository<TaskAttempt, UUID> {

    List<TaskAttempt> findByTaskIdOrderByAttemptNoAsc(UUID taskId);

    /** 租约唯一，可用于幂等判断与租约回收。 */
    Optional<TaskAttempt> findByLeaseId(UUID leaseId);

    long countByTaskId(UUID taskId);
}
