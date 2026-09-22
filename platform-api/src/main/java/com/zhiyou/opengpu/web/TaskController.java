package com.zhiyou.opengpu.web;

import com.zhiyou.opengpu.common.ApiResponse;
import com.zhiyou.opengpu.common.PageResult;
import com.zhiyou.opengpu.domain.TaskStatus;
import com.zhiyou.opengpu.service.TaskService;
import com.zhiyou.opengpu.web.dto.TaskDtos;
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
 * 用户端任务接口（FR-U01 ~ FR-U05）。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TaskDtos.TaskView> create(@Valid @RequestBody TaskDtos.CreateTaskRequest request) {
        return ApiResponse.ok(taskService.create(request));
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

    @PostMapping("/{id}/cancel")
    public ApiResponse<TaskDtos.TaskView> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(taskService.cancel(id));
    }
}
