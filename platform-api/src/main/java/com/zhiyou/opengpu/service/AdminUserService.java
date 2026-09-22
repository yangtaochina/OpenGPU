package com.zhiyou.opengpu.service;

import com.zhiyou.opengpu.common.BizException;
import com.zhiyou.opengpu.common.ErrorCode;
import com.zhiyou.opengpu.common.PageResult;
import com.zhiyou.opengpu.domain.AppUser;
import com.zhiyou.opengpu.domain.UserRole;
import com.zhiyou.opengpu.repository.AppUserRepository;
import com.zhiyou.opengpu.repository.AppUserSearchRepository;
import com.zhiyou.opengpu.web.dto.UserDtos;
import java.util.Locale;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理端用户管理（docs/API.md §4.5）。
 *
 * <p><b>保护性规则</b>（命中返回 40302）：
 * <ul>
 *   <li>不能停用自己</li>
 *   <li>不能修改自己的角色</li>
 *   <li>不能停用或降级「最后一个处于启用状态的管理员」</li>
 * </ul>
 * 这些规则必须放在服务端：前端可以提前把按钮置灰提升体验，但绝对不能依赖前端拦截。
 */
@Slf4j
@Service
public class AdminUserService {

    private final AppUserRepository userRepository;
    private final AppUserSearchRepository userSearchRepository;
    private final PasswordEncoder passwordEncoder;
    private final CredentialPolicy credentialPolicy;
    private final ViewAssembler assembler;

    public AdminUserService(AppUserRepository userRepository,
                            AppUserSearchRepository userSearchRepository,
                            PasswordEncoder passwordEncoder,
                            CredentialPolicy credentialPolicy,
                            ViewAssembler assembler) {
        this.userRepository = userRepository;
        this.userSearchRepository = userSearchRepository;
        this.passwordEncoder = passwordEncoder;
        this.credentialPolicy = credentialPolicy;
        this.assembler = assembler;
    }

    @Transactional(readOnly = true)
    public PageResult<UserDtos.UserView> list(String keyword, UserRole role, Boolean enabled, Pageable pageable) {
        String normalizedKeyword = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        Page<AppUser> page = userSearchRepository.search(normalizedKeyword, role, enabled, pageable);
        return PageResult.of(page, assembler::toUserView);
    }

    @Transactional(readOnly = true)
    public UserDtos.UserView get(UUID userId) {
        return assembler.toUserView(requireUser(userId));
    }

    /** 管理员直接创建账号，可以指定角色。 */
    @Transactional
    public UserDtos.UserView create(UserDtos.CreateUserRequest request) {
        String username = credentialPolicy.normalizeAndValidateUsername(request.username());
        credentialPolicy.validatePassword(request.password());
        UserRole role = parseRole(request.role(), UserRole.USER);

        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new BizException(ErrorCode.USERNAME_TAKEN, "用户名已被占用: " + username);
        }

        AppUser user = AppUser.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(role)
                .enabled(true)
                .build();
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new BizException(ErrorCode.USERNAME_TAKEN, "用户名已被占用: " + username);
        }
        log.info("管理员创建账号: id={}, username={}, role={}", user.getId(), user.getUsername(), role);
        return assembler.toUserView(user);
    }

    @Transactional
    public UserDtos.UserView setEnabled(UUID userId, boolean enabled, UUID operatorId) {
        AppUser user = requireUser(userId);

        if (!enabled) {
            if (user.getId().equals(operatorId)) {
                throw new BizException(ErrorCode.PROTECTED_ADMIN_OPERATION, "不能停用你自己的账号");
            }
            if (isLastEnabledAdmin(user)) {
                throw new BizException(ErrorCode.PROTECTED_ADMIN_OPERATION,
                        "不能停用最后一个处于启用状态的管理员，否则平台将无人可管理");
            }
        }

        user.setEnabled(enabled);
        log.info("账号{}: id={}, username={}, 操作人={}",
                enabled ? "已启用" : "已停用", user.getId(), user.getUsername(), operatorId);
        return assembler.toUserView(user);
    }

    @Transactional
    public UserDtos.UserView updateRole(UUID userId, String rawRole, UUID operatorId) {
        AppUser user = requireUser(userId);
        UserRole newRole = parseRole(rawRole, null);

        if (user.getId().equals(operatorId)) {
            throw new BizException(ErrorCode.PROTECTED_ADMIN_OPERATION, "不能修改你自己的角色");
        }
        if (newRole != UserRole.ADMIN && isLastEnabledAdmin(user)) {
            throw new BizException(ErrorCode.PROTECTED_ADMIN_OPERATION,
                    "不能把最后一个处于启用状态的管理员降级，否则平台将无人可管理");
        }

        UserRole previousRole = user.getRole();
        user.setRole(newRole);
        log.info("账号角色变更: id={}, username={}, {} -> {}, 操作人={}",
                user.getId(), user.getUsername(), previousRole, newRole, operatorId);
        return assembler.toUserView(user);
    }

    /** 管理端重置密码：不校验旧密码，这是管理员代操作而非用户自助改密。 */
    @Transactional
    public UserDtos.UserView resetPassword(UUID userId, String newPassword) {
        AppUser user = requireUser(userId);
        credentialPolicy.validatePassword(newPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        log.info("管理员重置密码: id={}, username={}", user.getId(), user.getUsername());
        return assembler.toUserView(user);
    }

    // ------------------------------------------------------------------

    private AppUser requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> BizException.notFound("账号不存在: " + userId));
    }

    /** 该账号是否为「最后一个处于启用状态的管理员」。 */
    private boolean isLastEnabledAdmin(AppUser user) {
        if (user.getRole() != UserRole.ADMIN || !user.isEnabled()) {
            return false;
        }
        return userRepository.countByRoleAndEnabledTrue(UserRole.ADMIN) <= 1;
    }

    private UserRole parseRole(String raw, UserRole fallback) {
        if (raw == null || raw.isBlank()) {
            if (fallback == null) {
                throw BizException.badRequest("role 不能为空");
            }
            return fallback;
        }
        try {
            return UserRole.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("role 取值非法: " + raw + "，允许值: USER, ADMIN");
        }
    }
}
