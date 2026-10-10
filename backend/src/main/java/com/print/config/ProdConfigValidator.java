package com.print.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 生产环境启动前置校验（fail-fast）。
 *
 * <p>以下配置在生产被误设会造成直接的资金/越权风险，因此在启动阶段即拒绝，
 * 而不是等到运行时才暴露：
 * <ul>
 *   <li>JWT 密钥仍为默认/弱值 → 可伪造任意用户（含管理员）令牌</li>
 *   <li>Mock 支付端点开启 → 可绕过真实支付免费出纸</li>
 *   <li>管理端 allow-all 开启 → 任意登录用户即管理员</li>
 * </ul>
 */
@Component
@Profile("prod")
@RequiredArgsConstructor
public class ProdConfigValidator implements InitializingBean {

    private static final int MIN_SECRET_LENGTH = 32;

    private final PrintProperties props;

    @Override
    public void afterPropertiesSet() {
        String secret = props.getJwt().getSecret();
        if (secret == null || secret.length() < MIN_SECRET_LENGTH
                || secret.startsWith("please-set") || secret.startsWith("dev-only")) {
            throw new IllegalStateException(
                    "生产环境必须通过 JWT_SECRET 配置 ≥" + MIN_SECRET_LENGTH + " 字节的随机密钥（当前为默认/弱密钥）");
        }
        if (props.getPayment().isMockEnabled()) {
            throw new IllegalStateException(
                    "生产环境禁止开启 print.payment.mock-enabled（否则可绕过支付免费出纸）");
        }
        if (props.getAdmin().isAllowAll()) {
            throw new IllegalStateException(
                    "生产环境禁止开启 print.admin.allow-all（否则任意登录用户即管理员）");
        }
    }
}
