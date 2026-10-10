package com.print.payment.gateway.alipay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.print.common.BizException;
import com.print.common.ResultCode;
import com.print.config.PrintProperties;
import com.print.payment.gateway.NonceStore;
import com.print.payment.gateway.PaymentGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 支付宝网关（当面付 {@code alipay.trade.precreate} 扫码）。
 *
 * <p>不引入支付宝官方 SDK：用 {@link HttpClient} 向网关做表单 POST，签名算法为
 * RSA2（SHA256withRSA）——公共参数 + 业务参数按 key 升序拼接 {@code k=v&...} 后签名并 Base64。
 *
 * <p>覆盖能力：
 * <ul>
 *   <li>预下单：{@code alipay.trade.precreate} → 返回 {@code qr_code}</li>
 *   <li>查询：{@code alipay.trade.query} → {@code trade_status}</li>
 *   <li>退款：{@code alipay.trade.refund}</li>
 *   <li>异步回调：表单参数用支付宝公钥验签</li>
 * </ul>
 *
 * <p>注册条件：{@code print.payment.alipay.enabled=true} 时才注册（默认不注册），
 * 配置缺失只在调用时抛 {@link BizException}，不影响应用启动。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "print.payment.alipay.enabled", havingValue = "true")
public class AlipayPaymentGateway implements PaymentGateway {

    private static final String GATEWAY_CODE = "alipay";
    private static final String SANDBOX_GATEWAY = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";
    private static final String PROD_GATEWAY = "https://openapi.alipay.com/gateway.do";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PrintProperties props;
    private final ObjectMapper objectMapper;
    private final NonceStore nonceStore;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();

    public AlipayPaymentGateway(PrintProperties props, ObjectMapper objectMapper, NonceStore nonceStore) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.nonceStore = nonceStore;
    }

    /** 回调 notify_time 是否在 ±5 分钟窗口内（防重放，设计 §3.1.2）。 */
    private boolean withinWindow(String notifyTime) {
        if (notifyTime == null || notifyTime.isBlank()) {
            return false;
        }
        try {
            java.time.LocalDateTime t = java.time.LocalDateTime.parse(notifyTime.trim(), TS);
            return Math.abs(java.time.Duration.between(t, java.time.LocalDateTime.now()).getSeconds()) <= 300;
        } catch (Exception e) {
            return false;
        }
    }

    private PrintProperties.AlipayCfg cfg() {
        return props.getPayment().getAlipay();
    }

    @Override
    public String getGatewayCode() {
        return GATEWAY_CODE;
    }

    // ==================== 下单 ====================

    /**
     * 预下单：{@code alipay.trade.precreate}，返回 {@code qr_code} 作为二维码内容。
     *
     * <p>金额口径：业务用「元」，支付宝同样用「元」字符串（保留 2 位小数）。
     */
    @Override
    public PrepayResult prepay(PrepayRequest request) {
        PrintProperties.AlipayCfg c = cfg();
        requireConfigured(c, false);
        try {
            Map<String, Object> biz = new LinkedHashMap<>();
            biz.put("out_trade_no", request.orderNo());
            biz.put("total_amount", yuan(request.amount()));
            biz.put("subject", request.subject());
            if (request.expireAt() != null) {
                // 支付宝 time_expire 相对下单时间超时，此处给出剩余分钟数（最小 1 分钟）
                long minutes = java.time.Duration.between(LocalDateTime.now(), request.expireAt()).toMinutes();
                biz.put("timeout_express", Math.max(1, minutes) + "m");
            }

            JsonNode resp = invoke(c, "alipay.trade.precreate", biz);
            String qrCode = text(resp, "qr_code");
            if (qrCode == null) {
                throw new BizException(ResultCode.PAYMENT_GATEWAY_ERROR.code(),
                        "支付宝预下单未返回 qr_code: " + resp);
            }
            log.info("[Alipay] 预下单成功: orderNo={}, amount={}", request.orderNo(), yuan(request.amount()));
            // precreate 阶段无 trade_no，置空待回调 / 查询回填
            return new PrepayResult(GATEWAY_CODE, null, qrCode, request.expireAt());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Alipay] 预下单失败: orderNo={}, err={}", request.orderNo(), e.getMessage());
            throw new BizException(ResultCode.PAYMENT_GATEWAY_ERROR.code(), "支付宝下单失败: " + e.getMessage());
        }
    }

    // ==================== 查询 ====================

    /** 查询：{@code alipay.trade.query}，{@code trade_status} 映射统一状态。 */
    @Override
    public PayQueryResult query(String orderNo) {
        PrintProperties.AlipayCfg c = cfg();
        requireConfigured(c, false);
        try {
            Map<String, Object> biz = Map.of("out_trade_no", orderNo);
            JsonNode resp = invoke(c, "alipay.trade.query", biz);
            String tradeStatus = text(resp, "trade_status");
            String tradeNo = text(resp, "trade_no");
            return new PayQueryResult(orderNo, mapState(tradeStatus), tradeNo);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Alipay] 查询失败: orderNo={}, err={}", orderNo, e.getMessage());
            throw new BizException(ResultCode.PAYMENT_GATEWAY_ERROR.code(), "支付宝查询失败: " + e.getMessage());
        }
    }

    // ==================== 回调 ====================

    /**
     * 处理支付宝异步回调（{@code application/x-www-form-urlencoded}）。
     *
     * <p>步骤：① 解析表单参数；② 剔除 sign / sign_type 后按 key 升序拼接 {@code k=v&...}；
     * ③ 用支付宝公钥 RSA-SHA256 验签；④ 取 {@code out_trade_no / trade_no / trade_status}。
     */
    @Override
    public NotifyResult handleNotify(NotifyRequest request) {
        PrintProperties.AlipayCfg c = cfg();
        requireConfigured(c, true);
        try {
            Map<String, String> params = parseForm(request.rawBody());
            String sign = params.get("sign");
            if (sign == null || sign.isBlank()) {
                log.warn("[Alipay] 回调缺少 sign，拒绝");
                return new NotifyResult(false, null, null);
            }
            // 验签：剔除 sign 与 sign_type 后重建待签串
            Map<String, String> forVerify = new TreeMap<>(params);
            forVerify.remove("sign");
            forVerify.remove("sign_type");
            String content = buildSignContent(forVerify);
            if (!verify(content, sign, publicKey(c))) {
                log.warn("[Alipay] 回调验签失败");
                return new NotifyResult(false, null, null);
            }
            // 防重放（设计 §3.1.2）：notify_time 窗口 ±5min + notify_id 去重
            if (!withinWindow(params.get("notify_time"))) {
                log.warn("[Alipay] 回调 notify_time 超出 ±5min 窗口，拒绝: {}", params.get("notify_time"));
                return new NotifyResult(false, null, null);
            }
            String notifyId = params.get("notify_id");
            if (notifyId != null && !notifyId.isBlank() && !nonceStore.firstSeen("alipay:" + notifyId, 300)) {
                log.warn("[Alipay] 回调 notify_id 重复（重放），拒绝: {}", notifyId);
                return new NotifyResult(false, null, null);
            }

            String orderNo = params.get("out_trade_no");
            String tradeNo = params.get("trade_no");
            String tradeStatus = params.get("trade_status");
            boolean paid = "TRADE_SUCCESS".equalsIgnoreCase(tradeStatus)
                    || "TRADE_FINISHED".equalsIgnoreCase(tradeStatus);
            if (!paid) {
                log.warn("[Alipay] 回调交易状态非成功: orderNo={}, status={}", orderNo, tradeStatus);
                return new NotifyResult(false, orderNo, tradeNo);
            }
            String amount = params.get("total_amount");
            log.info("[Alipay] 回调验签通过: orderNo={}, tradeNo={}, amount={}", orderNo, tradeNo, amount);
            return new NotifyResult(true, orderNo, tradeNo, amount);
        } catch (Exception e) {
            log.warn("[Alipay] 回调处理异常: {}", e.getMessage());
            return new NotifyResult(false, null, null);
        }
    }

    // ==================== 退款 ====================

    /** 退款：{@code alipay.trade.refund}，以 out_request_no 保证幂等。 */
    @Override
    public RefundResult refund(RefundRequest request) {
        PrintProperties.AlipayCfg c = cfg();
        requireConfigured(c, false);
        try {
            String outRequestNo = "R" + request.orderNo();
            Map<String, Object> biz = new LinkedHashMap<>();
            biz.put("out_trade_no", request.orderNo());
            biz.put("refund_amount", yuan(request.amount()));
            biz.put("out_request_no", outRequestNo);
            if (request.reason() != null && !request.reason().isBlank()) {
                biz.put("refund_reason", request.reason());
            }
            JsonNode resp = invoke(c, "alipay.trade.refund", biz);
            String tradeNo = text(resp, "trade_no");
            boolean ok = resp.path("code").asText("").equals("10000");
            log.info("[Alipay] 退款受理: orderNo={}, tradeNo={}, ok={}", request.orderNo(), tradeNo, ok);
            return new RefundResult(ok, tradeNo != null ? tradeNo : outRequestNo);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Alipay] 退款失败: orderNo={}, err={}", request.orderNo(), e.getMessage());
            throw new BizException(ResultCode.PAYMENT_GATEWAY_ERROR.code(), "支付宝退款失败: " + e.getMessage());
        }
    }

    // ==================== 请求 / 签名 ====================

    /**
     * 组装公共参数 + 业务参数，签名后以表单 POST 调用网关，解析 {@code <method>_response}。
     */
    private JsonNode invoke(PrintProperties.AlipayCfg c, String method, Map<String, Object> biz) throws Exception {
        Map<String, String> params = new TreeMap<>();
        params.put("app_id", c.getAppId());
        params.put("method", method);
        params.put("format", "JSON");
        params.put("charset", "utf-8");
        params.put("sign_type", "RSA2");
        params.put("timestamp", LocalDateTime.now().format(TS));
        params.put("version", "1.0");
        if (isNotBlank(c.getNotifyUrl())) {
            params.put("notify_url", c.getNotifyUrl());
        }
        params.put("biz_content", objectMapper.writeValueAsString(biz));

        // 签名：剔除 sign 后按 key 升序拼接（值不做 URL 编码），SHA256withRSA → Base64
        String signContent = buildSignContent(params);
        String sign = sign(signContent, privateKey(c));

        // 请求体：所有参数（含 sign）逐个 URL 编码后拼接
        String form = urlEncode(params) + "&sign=" + enc(sign);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(resolveGateway(c)))
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .header("User-Agent", "print-backend/1.0 (alipay-openapi)")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String bodyStr = resp.body() == null ? "" : resp.body();
        if (resp.statusCode() / 100 != 2) {
            throw new BizException(ResultCode.PAYMENT_GATEWAY_ERROR.code(),
                    "支付宝网关 HTTP " + resp.statusCode() + ": " + bodyStr);
        }
        JsonNode root = objectMapper.readTree(bodyStr);
        // 响应体键名：method 中的 '.' 换成 '_'，再加 _response
        String respKey = method.replace('.', '_') + "_response";
        JsonNode data = root.get(respKey);
        if (data == null) {
            // 兼容错误响应 error_response
            JsonNode err = root.get("error_response");
            throw new BizException(ResultCode.PAYMENT_GATEWAY_ERROR.code(),
                    "支付宝返回异常: " + (err != null ? err : root));
        }
        String code = text(data, "code");
        if (!"10000".equals(code)) {
            log.error("[Alipay] 业务失败: method={}, code={}, subMsg={}, msg={}",
                    method, code, text(data, "sub_msg"), text(data, "msg"));
            throw new BizException(ResultCode.PAYMENT_GATEWAY_ERROR.code(),
                    "支付宝业务错误(" + code + "): " + text(data, "sub_msg") + " " + text(data, "msg"));
        }
        return data;
    }

    /** 待签名字符串：按 key ASCII 升序，跳过空值，拼接 {@code k=v&...}（末尾无 &）。 */
    private static String buildSignContent(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(params).entrySet()) {
            String v = e.getValue();
            if (v == null || v.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(v);
        }
        return sb.toString();
    }

    /** 表单整体 URL 编码（含所有参数，按 key 升序）。 */
    private static String urlEncode(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(params).entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return sb.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** 解析回调表单参数（application/x-www-form-urlencoded）。 */
    private static Map<String, String> parseForm(String rawBody) throws Exception {
        Map<String, String> map = new LinkedHashMap<>();
        if (rawBody == null || rawBody.isBlank()) {
            return map;
        }
        for (String pair : rawBody.split("&")) {
            int idx = pair.indexOf('=');
            if (idx < 0) {
                continue;
            }
            String k = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
            String v = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            map.put(k, v);
        }
        return map;
    }

    // ==================== 密钥工具 ====================

    private java.security.PrivateKey privateKey(PrintProperties.AlipayCfg c) throws Exception {
        byte[] der = decodeKey(c.getPrivateKey(), "PRIVATE KEY");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private PublicKey publicKey(PrintProperties.AlipayCfg c) throws Exception {
        byte[] der = decodeKey(c.getAlipayPublicKey(), "PUBLIC KEY");
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    /** 签名内容 → Base64 签名。 */
    private static String sign(String content, java.security.PrivateKey privateKey) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(content.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    /** 用公钥验签。 */
    private static boolean verify(String content, String signBase64, PublicKey publicKey) throws Exception {
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(content.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(signBase64));
    }

    /** 兼容纯 Base64 与 PEM（带 -----BEGIN----- 头）两种写法。 */
    private static byte[] decodeKey(String key, String label) {
        if (key == null) {
            throw new BizException(ResultCode.PAYMENT_GATEWAY_ERROR.code(), "支付宝未配置：" + label);
        }
        String cleaned = key
                .replace("-----BEGIN " + label + "-----", "")
                .replace("-----END " + label + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(cleaned);
    }

    // ==================== 配置校验 ====================

    /**
     * 校验配置是否就绪，缺失时抛出可读的 {@link BizException}（不阻断启动）。
     *
     * @param needPublicKey 回调验签场景额外要求支付宝公钥
     */
    private void requireConfigured(PrintProperties.AlipayCfg c, boolean needPublicKey) {
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (isBlank(c.getAppId())) missing.add("appId");
        if (isBlank(c.getPrivateKey())) missing.add("privateKey");
        if (needPublicKey && isBlank(c.getAlipayPublicKey())) missing.add("alipayPublicKey");
        if (!missing.isEmpty()) {
            throw new BizException(ResultCode.PAYMENT_GATEWAY_ERROR.code(),
                    "支付宝未配置：" + String.join("、", missing));
        }
    }

    // ==================== 小工具 ====================

    /** 元 → 支付宝金额字符串（2 位小数）。 */
    private static String yuan(BigDecimal amount) {
        if (amount == null) {
            throw new BizException(ResultCode.PAYMENT_GATEWAY_ERROR.code(), "支付宝金额不能为空");
        }
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String resolveGateway(PrintProperties.AlipayCfg c) {
        if (c.isSandbox()) {
            return SANDBOX_GATEWAY;
        }
        return isNotBlank(c.getGateway()) ? c.getGateway() : PROD_GATEWAY;
    }

    private static PayState mapState(String tradeStatus) {
        if (tradeStatus == null) {
            return PayState.PENDING;
        }
        return switch (tradeStatus.toUpperCase()) {
            case "TRADE_SUCCESS", "TRADE_FINISHED" -> PayState.SUCCESS;
            case "WAIT_BUYER_PAY" -> PayState.PENDING;
            case "TRADE_CLOSED" -> PayState.FAILED;
            default -> PayState.PENDING;
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean isNotBlank(String s) {
        return !isBlank(s);
    }
}
