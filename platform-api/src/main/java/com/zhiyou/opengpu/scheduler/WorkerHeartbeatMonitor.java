package com.zhiyou.opengpu.scheduler;

import com.zhiyou.opengpu.config.OpenGpuProperties;
import com.zhiyou.opengpu.domain.Worker;
import com.zhiyou.opengpu.domain.WorkerRuntimeStatus;
import com.zhiyou.opengpu.domain.WorkerStatus;
import com.zhiyou.opengpu.repository.WorkerRepository;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 节点心跳超时判定（FR-W02）。
 *
 * <p>超时后平台显示离线，但仍保留最后心跳时间，方便管理端定位问题。
 */
@Slf4j
@Component
public class WorkerHeartbeatMonitor {

    private final WorkerRepository workerRepository;
    private final OpenGpuProperties properties;

    public WorkerHeartbeatMonitor(WorkerRepository workerRepository, OpenGpuProperties properties) {
        this.workerRepository = workerRepository;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${opengpu.task.worker-monitor-interval-seconds:30}000",
            initialDelayString = "${opengpu.task.worker-monitor-interval-seconds:30}000")
    @Transactional
    public void markStaleWorkersOffline() {
        Instant threshold = Instant.now().minusSeconds(properties.getTask().getHeartbeatTimeoutSeconds());
        List<Worker> stale = workerRepository.findByStatusNotAndLastHeartbeatAtBefore(WorkerStatus.DISABLED, threshold);
        for (Worker worker : stale) {
            if (worker.getStatus() == WorkerStatus.OFFLINE) {
                continue;
            }
            worker.setStatus(WorkerStatus.OFFLINE);
            worker.setRuntimeStatus(WorkerRuntimeStatus.IDLE);
            worker.setCurrentTaskId(null);
            log.warn("节点心跳超时，已标记离线: id={}, name={}, lastHeartbeatAt={}",
                    worker.getId(), worker.getName(), worker.getLastHeartbeatAt());
        }
    }
}
