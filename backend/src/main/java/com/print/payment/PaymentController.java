package com.print.payment;

import com.print.common.Result;
import com.print.config.UserContext;
import com.print.payment.gateway.PaymentGateway;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 支付接口（CONTRACT.md §3.5）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/{orderNo}/pay")
    public Result<PaymentService.PrepayVO> pay(@PathVariable String orderNo) {
        return Result.ok(paymentService.prepay(orderNo, UserContext.userId()));
    }

    @GetMapping("/{orderNo}/status")
    public Result<PaymentService.PayStatusVO> status(@PathVariable String orderNo) {
        return Result.ok(paymentService.status(orderNo, UserContext.userId()));
    }

    /**
     * 网关异步回调（无鉴权，靠验签保证安全）。
     *
     * <p>应答格式由各渠道决定：支付宝要求 HTTP 200 + 纯文本 {@code success}，
     * 微信支付 V3 要求 HTTP 200 + JSON，因此这里不再返回统一响应体。
     */
    @PostMapping("/notify/{gateway}")
    public ResponseEntity<String> notify(@PathVariable String gateway,
                                         HttpServletRequest request,
                                         @RequestHeader Map<String, String> headers) throws IOException {
        // 直接读取原始报文：表单类回调（支付宝）用 @RequestBody String 可能取不到内容，
        // 会导致验签恒失败、渠道持续重试而订单永不更新
        String rawBody = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean ok;
        try {
            ok = paymentService.handleNotify(gateway, rawBody.isBlank() ? "{}" : rawBody, headers);
        } catch (Exception e) {
            log.error("回调处理异常: gateway={}", gateway, e);
            ok = false;
        }
        PaymentGateway.NotifyAck ack = paymentService.ackOf(gateway, ok);
        return ResponseEntity.status(ack.httpStatus())
                .contentType(MediaType.parseMediaType(ack.contentType()))
                .body(ack.body());
    }
}
