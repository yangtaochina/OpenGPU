package com.zhiyou.opengpu.web;

import com.zhiyou.opengpu.common.ApiResponse;
import com.zhiyou.opengpu.common.PageResult;
import com.zhiyou.opengpu.domain.TaskStatus;
import com.zhiyou.opengpu.service.TaskService;
import com.zhiyou.opengpu.web.dto.TaskDtos;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端任务接口（FR-A01 / FR-A03）。
 */
@RestController
@RequestMapping("/api/admin/tasks")
public class AdminTaskController {

    private final TaskService taskService;

    public AdminTaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    public ApiResponse<PageResult<TaskDtos.TaskView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        TaskStatus parsed = RequestParams.parseEnum(TaskStatus.class, status, "status");
        return ApiResponse.ok(taskService.list(parsed, RequestParams.pageable(page, size, "createdAt")));
    }

    @GetMapping("/{id}")
    public ApiResponse<TaskDtos.TaskDetailView> detail(@PathVariable UUID id) {
        return ApiResponse.ok(taskService.detail(id));
    }

    /** AT-08：人工重试失败任务，产生新尝试并保留失败历史。 */
    @PostMapping("/{id}/retry")
    public ApiResponse<TaskDtos.TaskView> retry(@PathVariable UUID id) {
        return ApiResponse.ok(taskService.retry(id));
    }
}
