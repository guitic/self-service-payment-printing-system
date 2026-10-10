package com.print.payment;

import com.print.common.Result;
import com.print.config.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 【仅显式开启时注册】模拟支付确认接口，用于本地 / E2E 联调跳过真实扫码。
 *
 * <p><b>安全红线</b>：注册条件为 {@code print.payment.mock-enabled=true}（demo / staging 专用，
 * 生产必须为 false）；同时该路径在 {@code SecurityConfig} 中要求登录，且服务层校验订单归属。
 * 三重防护避免「匿名把任意订单标记为已支付并免费出纸」。
 */
@RestController
@RequestMapping("/api/v1/payments/mock")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "print.payment.mock-enabled", havingValue = "true")
public class MockPaymentController {

    private final PaymentService paymentService;

    public record MockConfirmReq(String orderNo, String result) {
    }

    @PostMapping("/confirm")
    public Result<PaymentService.PayStatusVO> confirm(@RequestBody MockConfirmReq req) {
        return Result.ok(paymentService.mockConfirm(req.orderNo(), req.result(), UserContext.userId()));
    }
}
