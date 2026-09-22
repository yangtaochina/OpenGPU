package com.zhiyou.opengpu.repository;

import com.zhiyou.opengpu.domain.AppUser;
import com.zhiyou.opengpu.domain.UserRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * 用户列表的可选条件查询。
 *
 * <p><b>为什么不用 {@code @Query} 写死 JPQL：</b>
 * 「可选条件」最直观的写法是 {@code (:param is null or field = :param)}，
 * 但在 PostgreSQL 上，当参数为 null 时驱动无法推断类型，会产生
 * {@code ERROR: function lower(bytea) does not exist} 这类错误，
 * 而且**是否报错取决于参数组合与调用顺序**——测试很容易漏掉。
 *
 * <p>因此这里改用 Criteria API：只拼接真正需要的条件，完全不产生 null 参数。
 */
@Repository
public class AppUserSearchRepository {

    /** 允许排序的字段白名单，避免客户端传入不存在的属性导致 500。 */
    private static final Set<String> SORTABLE_FIELDS =
            Set.of("createdAt", "username", "lastLoginAt", "role", "enabled");

    private final EntityManager entityManager;

    public AppUserSearchRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Page<AppUser> search(String keyword, UserRole role, Boolean enabled, Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<AppUser> query = cb.createQuery(AppUser.class);
        Root<AppUser> root = query.from(AppUser.class);
        query.where(buildPredicates(cb, root, keyword, role, enabled).toArray(new Predicate[0]));
        applySort(cb, query, root, pageable);

        TypedQuery<AppUser> typedQuery = entityManager.createQuery(query);
        typedQuery.setFirstResult((int) pageable.getOffset());
        typedQuery.setMaxResults(pageable.getPageSize());
        List<AppUser> content = typedQuery.getResultList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<AppUser> countRoot = countQuery.from(AppUser.class);
        countQuery.select(cb.count(countRoot));
        countQuery.where(buildPredicates(cb, countRoot, keyword, role, enabled).toArray(new Predicate[0]));
        long total = entityManager.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }

    /** 只为真正存在的条件生成谓词；参数为空时该条件根本不出现在 SQL 里。 */
    private List<Predicate> buildPredicates(CriteriaBuilder cb,
                                            Root<AppUser> root,
                                            String keyword,
                                            UserRole role,
                                            Boolean enabled) {
        List<Predicate> predicates = new ArrayList<>();

        if (keyword != null && !keyword.isBlank()) {
            String pattern = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
            predicates.add(cb.like(cb.lower(root.get("username")), pattern));
        }
        if (role != null) {
            predicates.add(cb.equal(root.get("role"), role));
        }
        if (enabled != null) {
            predicates.add(cb.equal(root.get("enabled"), enabled));
        }
        return predicates;
    }

    private void applySort(CriteriaBuilder cb,
                           CriteriaQuery<AppUser> query,
                           Root<AppUser> root,
                           Pageable pageable) {
        List<Order> orders = new ArrayList<>();
        if (pageable.getSort().isSorted()) {
            pageable.getSort().forEach(sortOrder -> {
                if (SORTABLE_FIELDS.contains(sortOrder.getProperty())) {
                    orders.add(sortOrder.isAscending()
                            ? cb.asc(root.get(sortOrder.getProperty()))
                            : cb.desc(root.get(sortOrder.getProperty())));
                }
            });
        }
        if (orders.isEmpty()) {
            orders.add(cb.desc(root.get("createdAt")));
        }
        query.orderBy(orders);
    }
}
