package com.print.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发与校验（HS256）。
 */
@Component
@RequiredArgsConstructor
public class JwtUtils {

    private final PrintProperties props;

    private SecretKey key() {
        return Keys.hmacShaKeyFor(props.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /** 签发 Token（默认 USER 角色）。 */
    public String generate(Long userId, String phone) {
        return generate(userId, phone, "USER");
    }

    /** 签发 Token，携带角色 claim（USER / ADMIN）。 */
    public String generate(Long userId, String phone, String role) {
        long now = System.currentTimeMillis();
        long exp = now + props.getJwt().getExpireMinutes() * 60_000L;
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("phone", phone)
                .claim("role", role)
                .issuedAt(new Date(now))
                .expiration(new Date(exp))
                .signWith(key())
                .compact();
    }

    /** 解析 Token，失败抛异常。 */
    public Claims parse(String token) {
        Jws<Claims> jws = Jwts.parser().verifyWith(key()).build().parseSignedClaims(token);
        return jws.getPayload();
    }

    public long expireSeconds() {
        return props.getJwt().getExpireMinutes() * 60L;
    }
}
