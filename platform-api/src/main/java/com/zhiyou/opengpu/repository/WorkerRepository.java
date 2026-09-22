package com.zhiyou.opengpu.repository;

import com.zhiyou.opengpu.domain.Worker;
import com.zhiyou.opengpu.domain.WorkerStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerRepository extends JpaRepository<Worker, UUID> {

    Optional<Worker> findByName(String name);

    Page<Worker> findByStatus(WorkerStatus status, Pageable pageable);

    /** 供能力矩阵统计使用（不分页）。 */
    List<Worker> findByStatus(WorkerStatus status);

    long countByStatus(WorkerStatus status);

    /** 心跳超时检测：排除已停用节点。 */
    List<Worker> findByStatusNotAndLastHeartbeatAtBefore(WorkerStatus status, Instant before);

    List<Worker> findByIdIn(List<UUID> ids);
}
