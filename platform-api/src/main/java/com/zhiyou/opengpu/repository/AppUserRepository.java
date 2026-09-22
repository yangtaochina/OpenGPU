package com.zhiyou.opengpu.repository;

import com.zhiyou.opengpu.domain.AppUser;
import com.zhiyou.opengpu.domain.UserRole;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 账号仓储。
 *
 * <p>带可选条件的列表查询见 {@link AppUserSearchRepository}——那里用 Criteria API
 * 而不是 JPQL 的 {@code :param is null or ...} 写法，避免 PostgreSQL 的 null 参数类型推断问题。
 */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /**
     * 用户名唯一性不区分大小写：注册 "Alice" 后不能再注册 "alice"，
     * 且两种写法都能登录。
     */
    Optional<AppUser> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);

    /** 用于「最后一个启用的管理员」保护判断。 */
    long countByRoleAndEnabledTrue(UserRole role);
}
