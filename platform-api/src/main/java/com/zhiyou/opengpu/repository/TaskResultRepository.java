package com.zhiyou.opengpu.repository;

import com.zhiyou.opengpu.domain.TaskResult;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskResultRepository extends JpaRepository<TaskResult, UUID> {

    Optional<TaskResult> findByTaskId(UUID taskId);

    List<TaskResult> findByTaskIdIn(Collection<UUID> taskIds);
}
