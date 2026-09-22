package com.zhiyou.opengpu.service;

import com.zhiyou.opengpu.common.BizException;
import com.zhiyou.opengpu.common.ErrorCode;
import com.zhiyou.opengpu.config.OpenGpuProperties;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 账号格式策略的唯一实现，注册与管理端创建/重置密码共用，
 * 保证「前端看到的规则」与「服务端实际执行的规则」来自同一处配置。
 */
@Component
public class CredentialPolicy {

    /** 用户名允许：字母、数字、下划线、连字符 */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");

    private final OpenGpuProperties properties;

    public CredentialPolicy(OpenGpuProperties properties) {
        this.properties = properties;
    }

    /** 去空格并校验用户名，返回规范化后的用户名。 */
    public String normalizeAndValidateUsername(String raw) {
        OpenGpuProperties.Security security = properties.getSecurity();
        String username = raw == null ? "" : raw.trim();

        if (username.length() < security.getUsernameMinLength()
                || username.length() > security.getUsernameMaxLength()) {
            throw new BizException(ErrorCode.INVALID_CREDENTIALS_FORMAT,
                    "用户名长度需在 %d-%d 位之间".formatted(
                            security.getUsernameMinLength(), security.getUsernameMaxLength()));
        }
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new BizException(ErrorCode.INVALID_CREDENTIALS_FORMAT,
                    "用户名只能包含字母、数字、下划线、连字符");
        }
        return username;
    }

    /**
     * 校验密码强度。
     *
     * <p>原型阶段只校验长度，未做复杂度（大小写/数字/符号）与常见弱口令校验。
     * 对外开放前必须补充（见 README 已知限制）。
     */
    public void validatePassword(String raw) {
        OpenGpuProperties.Security security = properties.getSecurity();
        String password = raw == null ? "" : raw;

        if (password.length() < security.getPasswordMinLength()
                || password.length() > security.getPasswordMaxLength()) {
            throw new BizException(ErrorCode.INVALID_CREDENTIALS_FORMAT,
                    "密码长度需在 %d-%d 位之间".formatted(
                            security.getPasswordMinLength(), security.getPasswordMaxLength()));
        }
    }
}
