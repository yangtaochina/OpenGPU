package com.zhiyou.opengpu.web;

import com.zhiyou.opengpu.common.ApiResponse;
import com.zhiyou.opengpu.common.PageResult;
import com.zhiyou.opengpu.domain.WorkerStatus;
import com.zhiyou.opengpu.service.WorkerService;
import com.zhiyou.opengpu.web.dto.WorkerDtos;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端节点接口（FR-A02 / FR-A04）。
 */
@RestController
@RequestMapping("/api/admin/workers")
public class AdminWorkerController {

    private final WorkerService workerService;

    public AdminWorkerController(WorkerService workerService) {
        this.workerService = workerService;
    }

    @GetMapping
    public ApiResponse<PageResult<WorkerDtos.WorkerView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        WorkerStatus parsed = RequestParams.parseEnum(WorkerStatus.class, status, "status");
        return ApiResponse.ok(workerService.list(parsed, RequestParams.pageable(page, size, "createdAt")));
    }

    /** 创建节点，返回一次性 token。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WorkerDtos.WorkerCreatedResponse> create(
            @Valid @RequestBody WorkerDtos.CreateWorkerRequest request) {
        return ApiResponse.ok(workerService.create(request));
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<WorkerDtos.WorkerView> disable(@PathVariable UUID id) {
        return ApiResponse.ok(workerService.setEnabled(id, false));
    }

    @PostMapping("/{id}/enable")
    public ApiResponse<WorkerDtos.WorkerView> enable(@PathVariable UUID id) {
        return ApiResponse.ok(workerService.setEnabled(id, true));
    }

    @GetMapping("/overview")
    public ApiResponse<WorkerDtos.OverviewResponse> overview() {
        return ApiResponse.ok(workerService.overview());
    }
}
