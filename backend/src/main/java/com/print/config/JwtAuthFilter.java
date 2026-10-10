package com.print.config;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 鉴权过滤器：解析 Authorization 头，写入 {@link UserContext} 与 SecurityContext。
 * 解析失败不直接拒绝（由 Security 的入口点决定是否需要登录）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final PrintProperties props;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            try {
                Claims claims = jwtUtils.parse(token);
                Long userId = Long.valueOf(claims.getSubject());
                String phone = claims.get("phone", String.class);
                UserContext.set(userId, phone);
                var auth = new UsernamePasswordAuthenticationToken(
                        userId, null, resolveAuthorities(claims, phone));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception e) {
                log.debug("Token 解析失败: {}", e.getMessage());
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 解析授权角色。
     *
     * <p>ADMIN 来源（任一满足）：JWT 的 role claim、管理员手机号白名单、
     * 或 demo 档的 {@code print.admin.allow-all}（生产必须为 false）。
     */
    private List<GrantedAuthority> resolveAuthorities(Claims claims, String phone) {
        boolean admin = "ADMIN".equals(claims.get("role", String.class))
                || props.getAdmin().isAllowAll()
                || (phone != null && props.getAdmin().getPhones().contains(phone));
        return admin
                ? AuthorityUtils.createAuthorityList("ROLE_USER", "ROLE_ADMIN")
                : AuthorityUtils.createAuthorityList("ROLE_USER");
    }
}
