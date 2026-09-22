package com.zhiyou.opengpu.web;

import com.zhiyou.opengpu.common.ApiResponse;
import com.zhiyou.opengpu.security.SecurityUtils;
import com.zhiyou.opengpu.service.WorkerService;
import com.zhiyou.opengpu.web.dto.WorkerDtos;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Worker 身份与心跳接口（FR-W01 / FR-W02）。
 */
@RestController
@RequestMapping("/api/workers")
public class WorkerController {

    private final WorkerService workerService;

    public WorkerController(WorkerService workerService) {
        this.workerService = workerService;
    }

    @PostMapping("/register")
    public ApiResponse<WorkerDtos.WorkerView> register(
            @Valid @RequestBody WorkerDtos.WorkerRegisterRequest request) {
        return ApiResponse.ok(workerService.register(SecurityUtils.currentWorkerId(), request));
    }

    @PostMapping("/heartbeat")
    public ApiResponse<WorkerDtos.HeartbeatResponse> heartbeat(
            @Valid @RequestBody WorkerDtos.HeartbeatRequest request) {
        return ApiResponse.ok(workerService.heartbeat(SecurityUtils.currentWorkerId(), request));
    }
}
