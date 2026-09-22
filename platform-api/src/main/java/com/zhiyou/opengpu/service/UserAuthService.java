package com.zhiyou.opengpu.service;

import com.zhiyou.opengpu.common.BizException;
import com.zhiyou.opengpu.common.ErrorCode;
import com.zhiyou.opengpu.config.OpenGpuProperties;
import com.zhiyou.opengpu.domain.AppUser;
import com.zhiyou.opengpu.domain.UserRole;
import com.zhiyou.opengpu.repository.AppUserRepository;
import com.zhiyou.opengpu.security.JwtService;
import com.zhiyou.opengpu.web.dto.AuthDtos;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台账号认证与自助注册。
 */
@Slf4j
@Service
public class UserAuthService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final CredentialPolicy credentialPolicy;
    private final OpenGpuProperties properties;

    public UserAuthService(AppUserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           JwtService jwtService,
                           CredentialPolicy credentialPolicy,
                           OpenGpuProperties properties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.credentialPolicy = credentialPolicy;
        this.properties = properties;
    }

    /** 平台账号策略，供登录/注册页读取。免认证。 */
    public AuthDtos.AuthConfigView authConfig() {
        OpenGpuProperties.Security security = properties.getSecurity();
        return new AuthDtos.AuthConfigView(
                security.isRegistrationEnabled(),
                security.getPasswordMinLength(),
                security.getPasswordMaxLength(),
                security.getUsernameMinLength(),
                security.getUsernameMaxLength());
    }

    @Transactional
    public AuthDtos.LoginResponse login(AuthDtos.LoginRequest request) {
        // 用户名不区分大小写
        AppUser user = userRepository.findByUsernameIgnoreCase(request.username() == null ? "" : request.username().trim())
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误"));
        if (!user.isEnabled() || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            // 不区分「账号不存在 / 密码错误 / 账号被停用」，避免账号枚举
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        user.setLastLoginAt(Instant.now());
        return toLoginResponse(user);
    }

    /**
     * 开放注册。
     *
     * <p>安全要点：注册出来的账号**角色固定为 USER**，请求体里即便带了 role 也不会被采纳，
     * 避免通过注册接口提权。
     */
    @Transactional
    public AuthDtos.LoginResponse register(AuthDtos.RegisterRequest request) {
        if (!properties.getSecurity().isRegistrationEnabled()) {
            throw new BizException(ErrorCode.REGISTRATION_CLOSED, "平台当前未开放注册");
        }
        String username = credentialPolicy.normalizeAndValidateUsername(request.username());
        credentialPolicy.validatePassword(request.password());

        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new BizException(ErrorCode.USERNAME_TAKEN, "用户名已被占用: " + username);
        }

        AppUser user = AppUser.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(UserRole.USER)
                .enabled(true)
                .lastLoginAt(Instant.now())
                .build();
        try {
            // saveAndFlush 让 uk_app_user_username_lower 的冲突在这里暴露：
            // 两个并发请求同时注册同名的不同大小写写法时，只有一个能成功。
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new BizException(ErrorCode.USERNAME_TAKEN, "用户名已被占用: " + username);
        }

        log.info("新用户注册: id={}, username={}", user.getId(), user.getUsername());
        return toLoginResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthDtos.MeResponse me(UUID userId) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED, "账号不存在"));
        return new AuthDtos.MeResponse(user.getId(), user.getUsername(), user.getRole().name());
    }

    private AuthDtos.LoginResponse toLoginResponse(AppUser user) {
        return new AuthDtos.LoginResponse(
                jwtService.issue(user), user.getUsername(), user.getRole().name(), jwtService.expireSeconds());
    }
}
