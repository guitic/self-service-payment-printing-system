package com.print.payment.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mock 支付网关纯单元测试：验签正向 / 反向 + 二维码格式（§7.5.3）。
 *
 * <p>验签由测试侧用独立 HMAC-SHA256 实现重算，确保与网关实现同构、可交叉验证。
 */
class MockPaymentGatewayTest {

    /** 与 MockPaymentGateway.MOCK_SECRET 一致（测试侧独立重算签名）。 */
    private static final String SECRET = "mock-gateway-secret-please-rotate";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new MockPaymentGateway(objectMapper);
    }

    private String hmac(String orderNo, String tradeNo, String amount) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String content = orderNo + "|" + tradeNo + "|" + amount;
        return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    }

    private String body(String orderNo, String tradeNo, String amount, String sign) {
        return "{\"orderNo\":\"" + orderNo + "\",\"tradeNo\":\"" + tradeNo
                + "\",\"amount\":\"" + amount + "\",\"sign\":\"" + sign + "\"}";
    }

    private PaymentGateway.NotifyResult notify(String rawBody) {
        return gateway.handleNotify(new PaymentGateway.NotifyRequest("mock", rawBody, Map.of()));
    }

    @Test
    @DisplayName("签名与独立 HMAC-SHA256 实现一致")
    void signMatchesIndependentHmac() throws Exception {
        assertThat(gateway.sign("P1", "MOCKP1", "2.00"))
                .isEqualTo(hmac("P1", "MOCKP1", "2.00"));
    }

    @Test
    @DisplayName("二维码内容为可解析格式 mockpay://order/{orderNo}?amount={amount}")
    void prepayQrFormat() {
        var result = gateway.prepay(new PaymentGateway.PrepayRequest(
                "P1", new BigDecimal("2.00"), "打印订单 P1", LocalDateTime.now().plusMinutes(15)));
        assertThat(result.gateway()).isEqualTo("mock");
        assertThat(result.tradeNo()).isEqualTo("MOCKP1");
        assertThat(result.qrCodeContent()).isEqualTo("mockpay://order/P1?amount=2.00");
    }

    @Test
    @DisplayName("验签正向：正确签名 -> 通过，状态置 SUCCESS")
    void notifyPositive() throws Exception {
        String sign = hmac("P1", "MOCKP1", "2.00");
        var r = notify(body("P1", "MOCKP1", "2.00", sign));
        assertThat(r.success()).isTrue();
        assertThat(r.orderNo()).isEqualTo("P1");
        assertThat(gateway.query("P1").state()).isEqualTo(PaymentGateway.PayState.SUCCESS);
    }

    @Test
    @DisplayName("验签反向：错误密钥/伪造签名 -> 拒绝，状态不变")
    void notifyWrongSign() {
        String fake = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef";
        var r = notify(body("P2", "MOCKP2", "2.00", fake));
        assertThat(r.success()).isFalse();
        assertThat(gateway.query("P2").state()).isEqualTo(PaymentGateway.PayState.PENDING);
    }

    @Test
    @DisplayName("验签反向：金额被篡改（签名对旧金额有效）-> 拒绝")
    void notifyTamperedAmount() throws Exception {
        String signForOriginal = hmac("P3", "MOCKP3", "2.00");
        // 报文金额改成 0.01，但沿用对 2.00 的签名
        var r = notify(body("P3", "MOCKP3", "0.01", signForOriginal));
        assertThat(r.success()).isFalse();
    }

    @Test
    @DisplayName("验签反向：空签名 -> 拒绝")
    void notifyEmptySign() {
        var r = notify(body("P4", "MOCKP4", "2.00", ""));
        assertThat(r.success()).isFalse();
    }

    @Test
    @DisplayName("验签反向：报文非法 JSON -> 拒绝且不抛异常")
    void notifyBadJson() {
        var r = notify("{not-a-json");
        assertThat(r.success()).isFalse();
        assertThat(r.orderNo()).isNull();
    }
}
