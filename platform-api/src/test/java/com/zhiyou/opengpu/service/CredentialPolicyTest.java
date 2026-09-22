package com.zhiyou.opengpu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zhiyou.opengpu.common.BizException;
import com.zhiyou.opengpu.common.ErrorCode;
import com.zhiyou.opengpu.config.OpenGpuProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 账号格式策略单测。注册、管理端建号、重置密码共用同一套规则，
 * 改这里会同时影响三处，所以用测试把边界钉住。
 */
class CredentialPolicyTest {

    private final CredentialPolicy policy = new CredentialPolicy(new OpenGpuProperties());

    @Test
    @DisplayName("合法用户名原样通过，并去除首尾空格")
    void validUsername() {
        assertThat(policy.normalizeAndValidateUsername("  alice-01  ")).isEqualTo("alice-01");
        assertThat(policy.normalizeAndValidateUsername("A_b-9")).isEqualTo("A_b-9");
    }

    @Test
    @DisplayName("用户名长度边界：3 与 32 位通过，2 与 33 位拒绝")
    void usernameLengthBoundaries() {
        assertThat(policy.normalizeAndValidateUsername("abc")).hasSize(3);
        assertThat(policy.normalizeAndValidateUsername("a".repeat(32))).hasSize(32);

        assertThatThrownBy(() -> policy.normalizeAndValidateUsername("ab"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS_FORMAT);

        assertThatThrownBy(() -> policy.normalizeAndValidateUsername("a".repeat(33)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS_FORMAT);
    }

    @Test
    @DisplayName("用户名只允许字母数字下划线连字符")
    void usernameCharacterSet() {
        for (String illegal : new String[]{"张三", "alice@example.com", "a b", "a.b", "a/b"}) {
            assertThatThrownBy(() -> policy.normalizeAndValidateUsername(illegal))
                    .as("应拒绝非法用户名: %s", illegal)
                    .isInstanceOf(BizException.class);
        }
    }

    @Test
    @DisplayName("密码长度边界：6 与 64 位通过，5 与 65 位拒绝")
    void passwordLengthBoundaries() {
        policy.validatePassword("a".repeat(6));
        policy.validatePassword("a".repeat(64));

        assertThatThrownBy(() -> policy.validatePassword("a".repeat(5)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS_FORMAT);

        assertThatThrownBy(() -> policy.validatePassword("a".repeat(65)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS_FORMAT);
    }

    @Test
    @DisplayName("空用户名与空密码被拒绝")
    void blankInputsRejected() {
        assertThatThrownBy(() -> policy.normalizeAndValidateUsername(null)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> policy.normalizeAndValidateUsername("   ")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> policy.validatePassword(null)).isInstanceOf(BizException.class);
    }
}
