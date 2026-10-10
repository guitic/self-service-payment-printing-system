package com.print.payment.gateway;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 支付网关抽象接口（ADR-002）——新增渠道只需实现本接口并注册为 Spring Bean。
 *
 * <p>业务层只依赖本接口，通过 {@link PaymentGatewayFactory} 按 gatewayCode 路由。
 * 本期提供 {@link MockPaymentGateway}，后续新增微信 / 支付宝实现同一接口即可，业务代码零改动。
 */
public interface PaymentGateway {

    /** 网关标识：mock / wechat / alipay */
    String getGatewayCode();

    /** 创建支付，返回二维码内容与网关流水号。 */
    PrepayResult prepay(PrepayRequest request);

    /** 主动查询支付结果（用于轮询 / 对账兜底）。 */
    PayQueryResult query(String orderNo);

    /** 处理网关异步回调：验签 + 解析 + 幂等。 */
    NotifyResult handleNotify(NotifyRequest request);

    /** 退款。 */
    RefundResult refund(RefundRequest request);

    enum PayState {
        PENDING, SUCCESS, FAILED
    }

    record PrepayRequest(String orderNo, BigDecimal amount, String subject, LocalDateTime expireAt) {
    }

    record PrepayResult(String gateway, String tradeNo, String qrCodeContent, LocalDateTime expireAt) {
    }

    record PayQueryResult(String orderNo, PayState state, String tradeNo) {
    }

    record NotifyRequest(String gateway, String rawBody, Map<String, String> headers) {
    }

    /**
     * 回调处理结果。
     *
     * @param amount 回调声明的金额（单位：元，字符串）。由网关实现从各自报文结构中解析后回传，
     *               业务侧据此与订单金额做强校验，避免依赖各渠道报文结构去原始报文里捞金额
     */
    record NotifyResult(boolean success, String orderNo, String tradeNo, String amount) {
        public NotifyResult(boolean success, String orderNo, String tradeNo) {
            this(success, orderNo, tradeNo, null);
        }
    }

    /**
     * 回调应答体。各渠道对回调应答的要求不同：
     * 支付宝要求 HTTP 200 + 纯文本 {@code success}；微信支付 V3 要求 HTTP 200 + JSON。
     */
    record NotifyAck(int httpStatus, String contentType, String body) {
    }

    /** 默认应答：HTTP 200 + 纯文本 {@code success}（兼容支付宝）。渠道实现可覆盖。 */
    default NotifyAck ack(boolean success) {
        return new NotifyAck(success ? 200 : 500, "text/plain;charset=UTF-8",
                success ? "success" : "failure");
    }

    record RefundRequest(String orderNo, BigDecimal amount, String reason) {
    }

    record RefundResult(boolean success, String refundNo) {
    }
}
