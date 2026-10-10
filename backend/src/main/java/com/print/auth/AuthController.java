package com.print.auth;

import com.print.common.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（CONTRACT.md §3.1）。
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String PHONE_REGEX = "^1[3-9]\\d{9}$";

    private final AuthService authService;

    public record SmsSendReq(
            @NotBlank(message = "手机号不能为空")
            @Pattern(regexp = PHONE_REGEX, message = "手机号格式不正确") String phone) {
    }

    public record LoginReq(
            @NotBlank(message = "手机号不能为空")
            @Pattern(regexp = PHONE_REGEX, message = "手机号格式不正确") String phone,
            @NotBlank(message = "验证码不能为空") String code) {
    }

    @PostMapping("/sms/send")
    public Result<AuthService.SmsSendResult> send(@Valid @RequestBody SmsSendReq req) {
        return Result.ok(authService.sendCode(req.phone()));
    }

    @PostMapping("/login")
    public Result<AuthService.LoginResult> login(@Valid @RequestBody LoginReq req) {
        return Result.ok(authService.login(req.phone(), req.code()));
    }

    @GetMapping("/me")
    public Result<AuthService.UserInfo> me() {
        return Result.ok(authService.currentUser());
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        // 无状态 JWT：前端清除 token 即可；如需强制失效可引入 Redis 黑名单
        return Result.ok();
    }
}
