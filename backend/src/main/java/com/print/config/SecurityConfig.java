package com.print.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.print.common.Result;
import com.print.common.ResultCode;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * Spring Security 配置：无状态 JWT。
 *
 * <p>放行：认证接口、助手接口（走 X-Agent-Token 自行校验）、支付回调、
 * Mock 确认（仅 demo 档注册该控制器）、健康检查、H2 控制台。
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/api/v1/agent/**").permitAll()
                        .requestMatchers("/api/v1/payments/notify/**").permitAll()
                        // 管理端必须 ADMIN 角色（防止任意登录用户改价/读全量订单/签发终端令牌）
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // Mock 支付端点需登录（且仅在 print.payment.mock-enabled=true 时才会注册）
                        .requestMatchers("/api/v1/payments/mock/**").authenticated()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/h2-console/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeJson(res, 401, ResultCode.UNAUTHORIZED))
                        .accessDeniedHandler((req, res, ex) -> writeJson(res, 403, ResultCode.FORBIDDEN)));
        // H2 控制台需要允许 iframe
        http.headers(h -> h.frameOptions(f -> f.disable()));
        return http.build();
    }

    private void writeJson(HttpServletResponse res, int status, ResultCode rc) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write(objectMapper.writeValueAsString(Result.fail(rc)));
    }
}
