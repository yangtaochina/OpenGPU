package com.zhiyou.opengpu.web;

import com.zhiyou.opengpu.common.ApiResponse;
import com.zhiyou.opengpu.security.SecurityUtils;
import com.zhiyou.opengpu.service.UserAuthService;
import com.zhiyou.opengpu.web.dto.AuthDtos;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。其中 {@code /config} 与 {@code /register} 免认证。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserAuthService userAuthService;

    public AuthController(UserAuthService userAuthService) {
        this.userAuthService = userAuthService;
    }

    /** 平台账号策略（是否开放注册、账号密码长度限制）。 */
    @GetMapping("/config")
    public ApiResponse<AuthDtos.AuthConfigView> config() {
        return ApiResponse.ok(userAuthService.authConfig());
    }

    @PostMapping("/login")
    public ApiResponse<AuthDtos.LoginResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        return ApiResponse.ok(userAuthService.login(request));
    }

    /** 自助注册，成功后直接返回登录态。 */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AuthDtos.LoginResponse> register(@Valid @RequestBody AuthDtos.RegisterRequest request) {
        return ApiResponse.ok(userAuthService.register(request));
    }

    @GetMapping("/me")
    public ApiResponse<AuthDtos.MeResponse> me() {
        return ApiResponse.ok(userAuthService.me(SecurityUtils.currentUserId()));
    }
}
