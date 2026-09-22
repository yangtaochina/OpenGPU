package com.zhiyou.opengpu.web;

import com.zhiyou.opengpu.common.ApiResponse;
import com.zhiyou.opengpu.common.PageResult;
import com.zhiyou.opengpu.domain.UserRole;
import com.zhiyou.opengpu.security.SecurityUtils;
import com.zhiyou.opengpu.service.AdminUserService;
import com.zhiyou.opengpu.web.dto.UserDtos;
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
 * 管理端用户管理（docs/API.md §4.5）。整个路径由 SecurityConfig 限制为 ADMIN。
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping
    public ApiResponse<PageResult<UserDtos.UserView>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UserRole parsedRole = RequestParams.parseEnum(UserRole.class, role, "role");
        return ApiResponse.ok(adminUserService.list(
                keyword, parsedRole, enabled, RequestParams.pageable(page, size, "createdAt")));
    }

    @GetMapping("/{id}")
    public ApiResponse<UserDtos.UserView> get(@PathVariable UUID id) {
        return ApiResponse.ok(adminUserService.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserDtos.UserView> create(@Valid @RequestBody UserDtos.CreateUserRequest request) {
        return ApiResponse.ok(adminUserService.create(request));
    }

    @PostMapping("/{id}/enable")
    public ApiResponse<UserDtos.UserView> enable(@PathVariable UUID id) {
        return ApiResponse.ok(adminUserService.setEnabled(id, true, SecurityUtils.currentUserId()));
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<UserDtos.UserView> disable(@PathVariable UUID id) {
        return ApiResponse.ok(adminUserService.setEnabled(id, false, SecurityUtils.currentUserId()));
    }

    @PostMapping("/{id}/role")
    public ApiResponse<UserDtos.UserView> updateRole(@PathVariable UUID id,
                                                    @Valid @RequestBody UserDtos.UpdateUserRoleRequest request) {
        return ApiResponse.ok(adminUserService.updateRole(id, request.role(), SecurityUtils.currentUserId()));
    }

    @PostMapping("/{id}/reset-password")
    public ApiResponse<UserDtos.UserView> resetPassword(@PathVariable UUID id,
                                                        @Valid @RequestBody UserDtos.ResetPasswordRequest request) {
        return ApiResponse.ok(adminUserService.resetPassword(id, request.newPassword()));
    }
}
