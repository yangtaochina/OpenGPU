package com.zhiyou.opengpu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.zhiyou.opengpu.common.BizException;
import com.zhiyou.opengpu.common.ErrorCode;
import com.zhiyou.opengpu.domain.AppUser;
import com.zhiyou.opengpu.domain.UserRole;
import com.zhiyou.opengpu.repository.AppUserRepository;
import com.zhiyou.opengpu.repository.AppUserSearchRepository;
import com.zhiyou.opengpu.web.dto.UserDtos;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 管理端「把自己/最后一个管理员锁死」的保护规则单测。
 *
 * <p>这是本轮改动里风险最高的逻辑：一旦判断写错，平台会进入「没有任何人能管理」的状态，
 * 而且只能靠改数据库救回来，所以必须有测试钉住。
 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock
    private AppUserRepository userRepository;
    @Mock
    private AppUserSearchRepository userSearchRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private CredentialPolicy credentialPolicy;
    @Mock
    private ViewAssembler assembler;

    private AdminUserService service;

    private final UUID selfId = UUID.randomUUID();
    private final UUID otherId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AdminUserService(
                userRepository, userSearchRepository, passwordEncoder, credentialPolicy, assembler);
        lenient().when(assembler.toUserView(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser user = invocation.getArgument(0);
            return new UserDtos.UserView(
                    user.getId(), user.getUsername(), user.getRole().name(),
                    user.isEnabled(), null, null);
        });
    }

    private AppUser user(UUID id, UserRole role, boolean enabled) {
        return AppUser.builder().id(id).username("u-" + id).passwordHash("x").role(role).enabled(enabled).build();
    }

    // ---- 停用 ----

    @Test
    @DisplayName("不能停用自己")
    void cannotDisableSelf() {
        when(userRepository.findById(selfId)).thenReturn(Optional.of(user(selfId, UserRole.ADMIN, true)));

        assertThatThrownBy(() -> service.setEnabled(selfId, false, selfId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PROTECTED_ADMIN_OPERATION);
    }

    @Test
    @DisplayName("不能停用最后一个启用的管理员")
    void cannotDisableLastEnabledAdmin() {
        when(userRepository.findById(otherId)).thenReturn(Optional.of(user(otherId, UserRole.ADMIN, true)));
        when(userRepository.countByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> service.setEnabled(otherId, false, selfId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PROTECTED_ADMIN_OPERATION);
    }

    @Test
    @DisplayName("还有其他管理员时可以停用其中一个")
    void canDisableWhenOtherAdminsRemain() {
        AppUser target = user(otherId, UserRole.ADMIN, true);
        when(userRepository.findById(otherId)).thenReturn(Optional.of(target));
        when(userRepository.countByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(2L);

        UserDtos.UserView view = service.setEnabled(otherId, false, selfId);
        assertThat(view.enabled()).isFalse();
    }

    @Test
    @DisplayName("停用普通用户不受管理员数量限制")
    void canDisableRegularUser() {
        AppUser target = user(otherId, UserRole.USER, true);
        when(userRepository.findById(otherId)).thenReturn(Optional.of(target));

        UserDtos.UserView view = service.setEnabled(otherId, false, selfId);
        assertThat(view.enabled()).isFalse();
    }

    // ---- 改角色 ----

    @Test
    @DisplayName("不能修改自己的角色")
    void cannotChangeOwnRole() {
        when(userRepository.findById(selfId)).thenReturn(Optional.of(user(selfId, UserRole.ADMIN, true)));

        assertThatThrownBy(() -> service.updateRole(selfId, "USER", selfId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PROTECTED_ADMIN_OPERATION);
    }

    @Test
    @DisplayName("不能把最后一个启用的管理员降级")
    void cannotDemoteLastEnabledAdmin() {
        when(userRepository.findById(otherId)).thenReturn(Optional.of(user(otherId, UserRole.ADMIN, true)));
        when(userRepository.countByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> service.updateRole(otherId, "USER", selfId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PROTECTED_ADMIN_OPERATION);
    }

    @Test
    @DisplayName("可以把普通用户提升为管理员")
    void canPromoteToAdmin() {
        AppUser target = user(otherId, UserRole.USER, true);
        when(userRepository.findById(otherId)).thenReturn(Optional.of(target));

        UserDtos.UserView view = service.updateRole(otherId, "admin", selfId);
        assertThat(view.role()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("非法角色取值返回 40000")
    void invalidRole() {
        when(userRepository.findById(otherId)).thenReturn(Optional.of(user(otherId, UserRole.USER, true)));

        assertThatThrownBy(() -> service.updateRole(otherId, "SUPERUSER", selfId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    // ---- 重置密码 ----

    @Test
    @DisplayName("重置密码会走密码策略校验并重新哈希")
    void resetPassword() {
        AppUser target = user(otherId, UserRole.USER, true);
        when(userRepository.findById(otherId)).thenReturn(Optional.of(target));
        when(passwordEncoder.encode("newsecret123")).thenReturn("hashed");

        UserDtos.UserView view = service.resetPassword(otherId, "newsecret123");

        assertThat(target.getPasswordHash()).isEqualTo("hashed");
        assertThat(view.username()).isEqualTo(target.getUsername());
    }
}
