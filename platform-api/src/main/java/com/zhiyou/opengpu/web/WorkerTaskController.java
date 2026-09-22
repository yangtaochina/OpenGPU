package com.zhiyou.opengpu.web;

import com.zhiyou.opengpu.common.ApiResponse;
import com.zhiyou.opengpu.domain.Worker;
import com.zhiyou.opengpu.security.SecurityUtils;
import com.zhiyou.opengpu.service.ClaimService;
import com.zhiyou.opengpu.service.TaskExecutionService;
import com.zhiyou.opengpu.service.WorkerService;
import com.zhiyou.opengpu.web.dto.WorkerTaskDtos;
import jakarta.validation.Valid;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Worker 任务接口（FR-W03 ~ FR-W06）。
 */
@RestController
@RequestMapping("/api/worker/tasks")
public class WorkerTaskController {

    private final ClaimService claimService;
    private final TaskExecutionService executionService;
    private final WorkerService workerService;

    public WorkerTaskController(ClaimService claimService,
                                TaskExecutionService executionService,
                                WorkerService workerService) {
        this.claimService = claimService;
        this.executionService = executionService;
        this.workerService = workerService;
    }

    /** 原子领取 + 长轮询。无任务时返回 204，不返回错误。 */
    @PostMapping("/claim")
    public ResponseEntity<ApiResponse<WorkerTaskDtos.TaskAssignment>> claim(
            @Valid @RequestBody(required = false) WorkerTaskDtos.ClaimRequest request) {
        UUID workerId = SecurityUtils.currentWorkerId();
        // 领取前先确认节点可用，并把它的显卡能力带进匹配条件
        Worker worker = workerService.requireClaimableWorker(workerId);

        int waitSeconds = (request == null || request.waitSeconds() == null) ? 0 : request.waitSeconds();
        Optional<ClaimService.ClaimResult> claimed = claimService.claim(worker, waitSeconds);
        if (claimed.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        ClaimService.ClaimResult result = claimed.get();
        WorkerTaskDtos.TaskAssignment assignment = new WorkerTaskDtos.TaskAssignment(
                result.task().getId(),
                result.attempt().getId(),
                result.attempt().getAttemptNo(),
                result.task().getPrompt(),
                result.task().getLeaseId(),
                result.task().getLeaseExpiresAt());
        return ResponseEntity.ok(ApiResponse.ok(assignment));
    }

    @PostMapping("/{taskId}/progress")
    public ApiResponse<WorkerTaskDtos.ProgressResponse> progress(
            @PathVariable UUID taskId,
            @Valid @RequestBody WorkerTaskDtos.ProgressRequest request) {
        return ApiResponse.ok(executionService.progress(SecurityUtils.currentWorkerId(), taskId, request));
    }

    @PostMapping("/{taskId}/complete")
    public ApiResponse<WorkerTaskDtos.CompleteResponse> complete(
            @PathVariable UUID taskId,
            @Valid @RequestBody WorkerTaskDtos.CompleteRequest request) {
        return ApiResponse.ok(executionService.complete(SecurityUtils.currentWorkerId(), taskId, request));
    }

    @PostMapping("/{taskId}/fail")
    public ApiResponse<WorkerTaskDtos.FailResponse> fail(
            @PathVariable UUID taskId,
            @Valid @RequestBody WorkerTaskDtos.FailRequest request) {
        return ApiResponse.ok(executionService.fail(SecurityUtils.currentWorkerId(), taskId, request));
    }
}
