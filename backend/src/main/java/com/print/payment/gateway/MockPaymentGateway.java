package com.print.payment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock 支付网关：本地生成可解析二维码，支持模拟成功 / 失败 / 查询，
 * 并实现与真实渠道同构的 HMAC-SHA256 验签，便于后续无缝切换（见设计文档 §7.5.5）。
 *
 * <p>无需真实商户资质即可跑通「下单 → 二维码 → 支付 → 回调 → 打印」全链路。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MockPaymentGateway implements PaymentGateway {

    /** Mock 签名密钥（真实渠道由配置注入，不入 Git） */
    private static final String MOCK_SECRET = "mock-gateway-secret-please-rotate";

    private final ObjectMapper objectMapper;

    /** orderNo -> 支付状态（骨架用内存；生产可换 Redis） */
    private final Map<String, PayState> states = new ConcurrentHashMap<>();
    private final Map<String, String> tradeNos = new ConcurrentHashMap<>();

    @Override
    public String getGatewayCode() {
        return "mock";
    }

    @Override
    public PrepayResult prepay(PrepayRequest request) {
        String tradeNo = "MOCK" + request.orderNo();
        states.put(request.orderNo(), PayState.PENDING);
        tradeNos.put(request.orderNo(), tradeNo);
        // 可解析格式，便于 E2E 断言
        String qr = "mockpay://order/" + request.orderNo() + "?amount=" + request.amount().toPlainString();
        log.info("[MockPay] 创建支付: orderNo={}, amount={}", request.orderNo(), request.amount());
        return new PrepayResult(getGatewayCode(), tradeNo, qr, request.expireAt());
    }

    @Override
    public PayQueryResult query(String orderNo) {
        PayState state = states.getOrDefault(orderNo, PayState.PENDING);
        return new PayQueryResult(orderNo, state, tradeNos.get(orderNo));
    }

    @Override
    public NotifyResult handleNotify(NotifyRequest request) {
        try {
            JsonNode node = objectMapper.readTree(request.rawBody());
            String orderNo = text(node, "orderNo");
            String tradeNo = text(node, "tradeNo");
            String amount = text(node, "amount");
            String sign = text(node, "sign");
            String expect = sign(orderNo, tradeNo, amount);
            if (sign == null || !expect.equalsIgnoreCase(sign)) {
                log.warn("[MockPay] 回调验签失败: orderNo={}", orderNo);
                return new NotifyResult(false, orderNo, tradeNo);
            }
            states.put(orderNo, PayState.SUCCESS);
            tradeNos.put(orderNo, tradeNo);
            log.info("[MockPay] 回调验签通过: orderNo={}, tradeNo={}", orderNo, tradeNo);
            return new NotifyResult(true, orderNo, tradeNo, amount);
        } catch (Exception e) {
            log.warn("[MockPay] 回调解析失败: {}", e.getMessage());
            return new NotifyResult(false, null, null);
        }
    }

    @Override
    public RefundResult refund(RefundRequest request) {
        states.put(request.orderNo(), PayState.FAILED);
        String refundNo = "REFUND" + request.orderNo();
        log.info("[MockPay] 退款: orderNo={}, amount={}, reason={}", request.orderNo(), request.amount(), request.reason());
        return new RefundResult(true, refundNo);
    }

    // ===== 供 Mock 专用接口调用 =====

    /** 模拟支付结果（SUCCESS / FAIL）。 */
    public void confirm(String orderNo, boolean success) {
        states.put(orderNo, success ? PayState.SUCCESS : PayState.FAILED);
        log.info("[MockPay] 模拟支付结果: orderNo={}, result={}", orderNo, success ? "SUCCESS" : "FAIL");
    }

    public String tradeNoOf(String orderNo) {
        return tradeNos.getOrDefault(orderNo, "MOCK" + orderNo);
    }

    /** 生成回调签名（供测试与 Mock 回调使用）。 */
    public String sign(String orderNo, String tradeNo, String amount) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(MOCK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String content = orderNo + "|" + tradeNo + "|" + amount;
            return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("签名失败", e);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** 金额格式化（与签名口径一致）。 */
    public static String amountOf(BigDecimal amount) {
        return amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
