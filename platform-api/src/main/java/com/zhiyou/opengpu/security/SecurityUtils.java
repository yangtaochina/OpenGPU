package com.zhiyou.opengpu.security;

import com.zhiyou.opengpu.common.BizException;
import com.zhiyou.opengpu.common.ErrorCode;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 从 SecurityContext 读取当前调用方。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Optional<AuthPrincipal> principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        if (authentication.getPrincipal() instanceof AuthPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }

    public static AuthPrincipal require() {
        return principal().orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED));
    }

    public static UUID currentUserId() {
        return require().id();
    }

    /** Worker 专用端点使用；非 Worker 调用直接 403。 */
    public static UUID currentWorkerId() {
        AuthPrincipal principal = require();
        if (!principal.isWorker()) {
            throw new BizException(ErrorCode.FORBIDDEN, "该接口仅限 GPU Worker 调用");
        }
        return principal.id();
    }
}
