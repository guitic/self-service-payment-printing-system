package com.print.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.print.auth.entity.User;
import com.print.auth.mapper.UserMapper;
import com.print.auth.store.SmsCodeStore;
import com.print.common.BizException;
import com.print.common.ResultCode;
import com.print.config.JwtUtils;
import com.print.config.PrintProperties;
import com.print.config.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 认证服务：手机号验证码登录（ADR-005）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final SmsCodeStore codeStore;
    private final JwtUtils jwtUtils;
    private final PrintProperties props;

    public record SmsSendResult(int expireSeconds, String debugCode) {
    }

    /** 用户信息；{@code role} 为 {@code ADMIN} / {@code USER}，前端据此控制管理端入口与路由。 */
    public record UserInfo(Long id, String phone, String nickname, String role) {
    }

    public record LoginResult(String token, String tokenType, long expiresIn, UserInfo user) {
    }

    /** 发送验证码（限频 + TTL）。 */
    public SmsSendResult sendCode(String phone) {
        if (codeStore.inCooldown(phone)) {
            throw new BizException(ResultCode.SMS_SEND_TOO_FREQUENT);
        }
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        int ttl = props.getSms().getCodeTtlSeconds();
        codeStore.saveCode(phone, code, ttl);
        codeStore.markCooldown(phone, props.getSms().getSendCooldownSeconds());

        // demo 档：验证码打到控制台（生产档由真实短信通道发送）
        log.info("【短信-Mock】手机号 {} 验证码：{}（{} 秒内有效）", phone, code, ttl);
        return new SmsSendResult(ttl, props.getSms().isDebug() ? code : null);
    }

    /** 验证码登录：校验通过则签发 JWT，用户不存在则自动注册。 */
    public LoginResult login(String phone, String code) {
        String saved = codeStore.getCode(phone);
        if (saved == null) {
            throw new BizException(ResultCode.SMS_CODE_EXPIRED);
        }
        if (!saved.equals(code)) {
            throw new BizException(ResultCode.SMS_CODE_ERROR);
        }
        // 一次性：验证成功即删除
        codeStore.removeCode(phone);

        LocalDateTime now = LocalDateTime.now();
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        if (user == null) {
            user = new User();
            user.setPhone(phone);
            user.setNickname("用户" + phone.substring(phone.length() - 4));
            user.setStatus(1);
            user.setCreatedAt(now);
            user.setUpdatedAt(now);
            userMapper.insert(user);
            log.info("新用户注册: id={}, phone={}", user.getId(), phone);
        }
        user.setLastLoginAt(now);
        user.setUpdatedAt(now);
        userMapper.updateById(user);

        // 角色：管理员白名单命中（或 demo 档 allow-all）→ ADMIN
        String role = resolveRole(phone);
        String token = jwtUtils.generate(user.getId(), phone, role);
        return new LoginResult(token, "Bearer", jwtUtils.expireSeconds(),
                new UserInfo(user.getId(), user.getPhone(), user.getNickname(), role));
    }

    /** 当前登录用户信息。 */
    public UserInfo currentUser() {
        Long uid = UserContext.userId();
        if (uid == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        User u = userMapper.selectById(uid);
        if (u == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        return new UserInfo(u.getId(), u.getPhone(), u.getNickname(), effectiveRole());
    }

    /**
     * 按配置判定角色（登录签发 Token 时使用）。
     *
     * <p>规则与 {@code JwtAuthFilter.resolveAuthorities} 完全一致：管理员手机号白名单
     * 或 demo 档 {@code print.admin.allow-all} 命中即 ADMIN。
     */
    private String resolveRole(String phone) {
        boolean admin = props.getAdmin().isAllowAll()
                || (phone != null && props.getAdmin().getPhones().contains(phone));
        return admin ? "ADMIN" : "USER";
    }

    /**
     * 读取本次请求**实际生效**的角色（查询当前用户时使用）。
     *
     * <p>直接取 SecurityContext 中 {@code JwtAuthFilter} 已解析的授权，避免与过滤器判定
     * 产生分歧（例如运行期调整了白名单或 allow-all，而 Token 仍携带旧角色）。
     */
    private String effectiveRole() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return "USER";
        }
        boolean admin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return admin ? "ADMIN" : "USER";
    }
}
