package com.zhiyou.opengpu.security;

import com.zhiyou.opengpu.config.OpenGpuProperties;
import com.zhiyou.opengpu.domain.AppUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * 用户侧 JWT 签发与校验。Worker 不使用 JWT，走独立的静态 token。
 */
@Component
public class JwtService {

    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_ROLE = "role";

    private final SecretKey key;
    private final long expireMinutes;

    public JwtService(OpenGpuProperties properties) {
        String secret = properties.getSecurity().getJwtSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("opengpu.security.jwt-secret 长度不足 32 字节，拒绝启动");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMinutes = properties.getSecurity().getJwtExpireMinutes();
    }

    public String issue(AppUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim(CLAIM_USERNAME, user.getUsername())
                .claim(CLAIM_ROLE, user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expireSeconds())))
                .signWith(key)
                .compact();
    }

    public JwtPayload parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return new JwtPayload(
                UUID.fromString(claims.getSubject()),
                claims.get(CLAIM_USERNAME, String.class),
                claims.get(CLAIM_ROLE, String.class));
    }

    public long expireSeconds() {
        return expireMinutes * 60;
    }

    public record JwtPayload(UUID userId, String username, String role) {
    }
}
