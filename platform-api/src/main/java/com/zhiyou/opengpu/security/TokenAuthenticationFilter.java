package com.zhiyou.opengpu.security;

import com.zhiyou.opengpu.common.Hashing;
import com.zhiyou.opengpu.domain.Worker;
import com.zhiyou.opengpu.repository.WorkerRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 单入口认证过滤器：
 * <ul>
 *   <li>{@code Bearer wkr_...} → Worker 静态 token 校验（SHA-256 比对）</li>
 *   <li>其它 {@code Bearer ...} → 用户 JWT 校验</li>
 * </ul>
 * 认证失败时不直接返回错误，而是清空上下文，由 SecurityConfig 的
 * AuthenticationEntryPoint 统一输出 401 信封。
 */
@Slf4j
@Component
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String BEARER = "Bearer ";
    private static final String WORKER_PREFIX = "wkr_";

    private final JwtService jwtService;
    private final WorkerRepository workerRepository;

    public TokenAuthenticationFilter(JwtService jwtService, WorkerRepository workerRepository) {
        this.jwtService = jwtService;
        this.workerRepository = workerRepository;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                if (token.startsWith(WORKER_PREFIX)) {
                    authenticateWorker(token, request);
                } else {
                    authenticateUser(token, request);
                }
            } catch (Exception e) {
                SecurityContextHolder.clearContext();
                log.debug("令牌校验失败 {} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }

    private void authenticateWorker(String token, HttpServletRequest request) {
        // 格式: wkr_<workerId>_<secret>
        String[] parts = token.split("_", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Worker token 格式非法");
        }
        UUID workerId;
        try {
            workerId = UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Worker token 中的节点 ID 非法");
        }
        Worker worker = workerRepository.findById(workerId)
                .orElseThrow(() -> new IllegalArgumentException("节点不存在: " + workerId));
        if (!Hashing.constantTimeEquals(worker.getTokenHash(), Hashing.sha256Hex(token))) {
            throw new IllegalArgumentException("Worker token 校验失败");
        }
        // 注意：停用节点仍可认证，以便上报心跳（AT-09）；领取任务时再拒绝。
        setAuthentication(new AuthPrincipal(worker.getId(), worker.getName(), AuthPrincipal.ROLE_WORKER), request);
    }

    private void authenticateUser(String token, HttpServletRequest request) {
        JwtService.JwtPayload payload = jwtService.parse(token);
        setAuthentication(new AuthPrincipal(payload.userId(), payload.username(), payload.role()), request);
    }

    private void setAuthentication(AuthPrincipal principal, HttpServletRequest request) {
        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(BEARER)) {
            return null;
        }
        String token = header.substring(BEARER.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
