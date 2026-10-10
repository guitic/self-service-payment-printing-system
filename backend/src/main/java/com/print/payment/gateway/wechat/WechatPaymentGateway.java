package com.print.payment.gateway.wechat;

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

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;

/**
 * 微信支付网关（APIv3 / Native 扫码）。
 *
 * <p>设计目标：不引入微信官方 SDK，仅用 JDK 自带的 {@link HttpClient} + {@link Signature} +
 * JCA（{@code AES/GCM/NoPadding}）直连 V3 接口，减少依赖面、便于审计。
 *
 * <p>覆盖能力：
 * <ul>
 *   <li>Native 下单：{@code POST /v3/pay/transactions/native}</li>
 *   <li>订单查询：{@code GET /v3/pay/transactions/out-trade-no/{out_trade_no}?mchid=}</li>
 *   <li>退款：{@code POST /v3/refund/domestic/refunds}</li>
 *   <li>异步回调：请求头 RSA 验签（平台证书公钥）+ {@code resource} 密文 AES-256-GCM 解密（apiV3Key）</li>
 * </ul>
 *
 * <p>注册条件：{@code print.payment.wechat.enabled=true} 时才注册为 Spring Bean（默认不注册），
 * 且任何配置缺失都只在**调用时**抛 {@link BizException}，绝不导致应用启动失败。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "print.payment.wechat.enabled", havingValue = "true")
public class WechatPaymentGateway implements PaymentGateway {

    private static final String GATEWAY_CODE = "wechat";

    /** 下单 */
    private static final String PATH_NATIVE = "/v3/pay/transactions/native";
    /** 退款 */
    private static final String PATH_REFUND = "/v3/refund/domestic/refunds";
    /** 查询（需拼接 out-trade-no 与 mchid） */
    private static final String PATH_QUERY_PREFIX = "/v3/pay/transactions/out-trade-no/";

    /** 回调请求头 */
    private static final String HEADER_TIMESTAMP = "Wechatpay-Timestamp";
    private static final String HEADER_NONCE = "Wechatpay-Nonce";
    private static final String HEADER_SIGNATURE = "Wechatpay-Signature";
    private static final String HEADER_SERIAL = "Wechatpay-Serial";

    /** 微信 V3 时间格式（RFC3339，带时区偏移） */
    private static final DateTimeFormatter RFC3339 =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final PrintProperties props;
    private final ObjectMapper objectMapper;
    private final NonceStore nonceStore;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();
    private final SecureRandom secureRandom = new SecureRandom();

    /** 私钥懒加载缓存（配置缺失时保持 null，调用时才抛错） */
    private volatile PrivateKey cachedPrivateKey;

    public WechatPaymentGateway(PrintProperties props, ObjectMapper objectMapper, NonceStore nonceStore) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.nonceStore = nonceStore;
    }

    private PrintProperties.WechatCfg cfg() {
        return props.getPayment().getWechat();
    }

    @Override
    public String getGatewayCode() {
        return GATEWAY_CODE;
    }

    // ==================== 下单 ====================

    /**
     * Native 下单：返回 {@code code_url} 作为二维码内容，前端据此渲染二维码。
     *
     * <p>金额口径：业务用「元」（BigDecimal），微信 V3 用「分」（整数），此处 ×100 取整。
     */
    @Override
    public PrepayResult prepay(PrepayRequest request) {
        PrintProperties.WechatCfg c = cfg();
        requireConfigured(c, true);
        try {
            // 请求体：金额单位为「分」
            int totalFen = toFen(request.amount());
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("appid", c.getAppId());
            body.put("mchid", c.getMchId());
            body.put("description", request.subject());
            body.put("out_trade_no", request.orderNo());
            if (request.expireAt() != null) {
                body.put("time_expire", request.expireAt().atZone(ZONE).format(RFC3339));
            }
            if (isNotBlank(c.getNotifyUrl())) {
                body.put("notify_url", c.getNotifyUrl());
            }
            body.put("amount", Map.of("total", totalFen, "currency", "CNY"));

            String json = objectMapper.writeValueAsString(body);
            JsonNode resp = post(c, PATH_NATIVE, json);
            String codeUrl = text(resp, "code_url");
            if (codeUrl == null) {
                throw new BizException(ResultCode.PAYMENT_GATEWAY_ERROR.code(),
                        "微信下单未返回 code_url: " + resp);
            }
            log.info("[WechatPay] Native 下单成功: orderNo={}, totalFen={}", request.orderNo(), totalFen);
            // Native 下单阶段微信尚未返回 transaction_id，tradeNo 置空，待回调 / 查询回填
            return new PrepayResult(GATEWAY_CODE, null, codeUrl, request.expireAt());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[WechatPay] 下单失败: orderNo={}, err={}", request.orderNo(), e.getMessage());
            throw new BizException(ResultCode.PAYMENT_GATEWAY_ERROR.code(), "微信支付下单失败: " + e.getMessage());
        }
    }

    // ==================== 查询 ====================

    /** 主动查询：{@code trade_state} 映射到统一的 {@link PayState}。 */
    @Override
    public PayQueryResult query(String orderNo) {
        PrintProperties.WechatCfg c = cfg();
        requireConfigured(c, false);
        try {
            String path = PATH_QUERY_PREFIX + orderNo + "?mchid=" + c.getMchId();
            JsonNode resp = get(c, path);
            String tradeState = text(resp, "trade_state");
            String tradeNo = text(resp, "transaction_id");
            return new PayQueryResult(orderNo, mapState(tradeState), tradeNo);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[WechatPay] 查询失败: orderNo={}, err={}", orderNo, e.getMessage());
            throw new BizException(ResultCode.PAYMENT_GATEWAY_ERROR.code(), "微信支付查询失败: " + e.getMessage());
        }
    }

    // ==================== 回调 ====================

    /**
     * 处理微信异步回调。
     *
     * <p>步骤：① 用平台证书公钥对 {@code timestamp\nnonce\nbody\n} 做 RSA-SHA256 验签；
     * ② 用 apiV3Key（32 字节）对 {@code resource} 做 AES-256-GCM 解密；
     * ③ 从明文取 {@code out_trade_no / transaction_id / trade_state}。
     *
     * @return success=false 表示验签失败 / 报文异常 / 非支付成功事件，调用方不应标记支付成功
     */
    @Override
    public NotifyResult handleNotify(NotifyRequest request) {
        PrintProperties.WechatCfg c = cfg();
        requireConfigured(c, false);
        String rawBody = request.rawBody();
        try {
            Map<String, String> headers = request.headers();
            String timestamp = header(headers, HEADER_TIMESTAMP);
            String nonce = header(headers, HEADER_NONCE);
            String signature = header(headers, HEADER_SIGNATURE);
            if (timestamp == null || nonce == null || signature == null) {
                log.warn("[WechatPay] 回调缺少验签头，拒绝");
                return new NotifyResult(false, null, null);
            }
            // ① 验签：message = timestamp + "\n" + nonce + "\n" + body + "\n"
            String message = timestamp + "\n" + nonce + "\n" + rawBody + "\n";
            if (!verifyWithPlatformCert(c, message, signature)) {
                log.warn("[WechatPay] 回调验签失败");
                return new NotifyResult(false, null, null);
            }
            // 防重放（设计 §3.1.2）：时间戳窗口 ±5min + nonce 去重
            if (!withinWindow(timestamp)) {
                log.warn("[WechatPay] 回调时间戳超出 ±5min 窗口，拒绝: ts={}", timestamp);
                return new NotifyResult(false, null, null);
            }
            if (!nonceStore.firstSeen("wechat:" + nonce, 300)) {
                log.warn("[WechatPay] 回调 nonce 重复（重放），拒绝: nonce={}", nonce);
                return new NotifyResult(false, null, null);
            }

            JsonNode root = objectMapper.readTree(rawBody);
            String eventType = text(root, "event_type");
            if (eventType != null && !"TRANSACTION.SUCCESS".equalsIgnoreCase(eventType)) {
                log.info("[WechatPay] 忽略非成功事件: eventType={}", eventType);
                return new NotifyResult(false, null, null);
            }

            // ② 解密 resource
            JsonNode resource = root.get("resource");
            if (resource == null) {
                log.warn("[WechatPay] 回调缺少 resource");
                return new NotifyResult(false, null, null);
            }
            String plain = decryptResource(c, resource);

            // ③ 解析明文
            JsonNode data = objectMapper.readTree(plain);
            String orderNo = text(data, "out_trade_no");
            String tradeNo = text(data, "transaction_id");
            String tradeState = text(data, "trade_state");
            if (!"SUCCESS".equalsIgnoreCase(tradeState)) {
                log.warn("[WechatPay] 回调交易状态非 SUCCESS: orderNo={}, state={}", orderNo, tradeState);
                return new NotifyResult(false, orderNo, tradeNo);
            }
            // 金额：微信以「分」为单位放在 amount.total，转为「元」与订单口径对齐
            JsonNode amountNode = data.path("amount").path("total");
            String amount = amountNode.isMissingNode() || amountNode.isNull()
                    ? null
                    : new BigDecimal(amountNode.asLong()).movePointLeft(2).toPlainString();
            log.info("[WechatPay] 回调验签+解密通过: orderNo={}, tradeNo={}, amount={}", orderNo, tradeNo, amount);
            return new NotifyResult(true, orderNo, tradeNo, amount);
        } catch (Exception e) {
            log.warn("[WechatPay] 回调处理异常: {}", e.getMessage());
            return new NotifyResult(false, null, null);
        }
    }

    // ==================== 退款 ====================

    /** 申请退款：退款单号由业务侧生成（{@code R + orderNo}），幂等由微信以 out_refund_no 保证。 */
    @Override
    public RefundResult refund(RefundRequest request) {
        PrintProperties.WechatCfg c = cfg();
        requireConfigured(c, false);
        try {
            int refundFen = toFen(request.amount());
            String outRefundNo = "R" + request.orderNo();
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("out_trade_no", request.orderNo());
            body.put("out_refund_no", outRefundNo);
            if (isNotBlank(request.reason())) {
                body.put("reason", request.reason());
            }
            body.put("amount", Map.of(
                    "refund", refundFen,
                    "total", refundFen,
                    "currency", "CNY"));

            String json = objectMapper.writeValueAsString(body);
            JsonNode resp = post(c, PATH_REFUND, json);
            String refundId = text(resp, "refund_id");
            String status = text(resp, "status");
            boolean ok = "SUCCESS".equalsIgnoreCase(status) || "PROCESSING".equalsIgnoreCase(status);
            log.info("[WechatPay] 退款受理: orderNo={}, refundId={}, status={}", request.orderNo(), refundId, status);
            return new RefundResult(ok, refundId != null ? refundId : outRefundNo);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[WechatPay] 退款失败: orderNo={}, err={}", request.orderNo(), e.getMessage());
            throw new BizException(ResultCode.PAYMENT_GATEWAY_ERROR.code(), "微信支付退款失败: " + e.getMessage());
        }
    }

    // ==================== HTTP + 签名 ====================

    private JsonNode post(PrintProperties.WechatCfg c, String path, String body) throws Exception {
        HttpRequest req = signedRequest(c, "POST", path, body);
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return parseResponse(resp, path);
    }

    private JsonNode get(PrintProperties.WechatCfg c, String path) throws Exception {
        // GET 无请求体，签名 message 的 body 段为空串
        HttpRequest req = signedRequest(c, "GET", path, "");
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return parseResponse(resp, path);
    }

    /**
     * 构造带 V3 签名的请求。
     *
     * <p>待签串（每行以 \n 结尾）：{@code METHOD\nURL_PATH\nTIMESTAMP\nNONCE\nBODY\n}
     * 其中 URL_PATH 含 query string；签名用商户私钥 SHA256withRSA，结果 Base64。
     */
    private HttpRequest signedRequest(PrintProperties.WechatCfg c, String method, String path, String body) throws Exception {
        long timestamp = System.currentTimeMillis() / 1000;
        String nonce = randomNonce();
        String message = method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + body + "\n";
        String signature = rsaSign(message, privateKey(c));
        String auth = "WECHATPAY2-SHA256-RSA2048 "
                + "mchid=\"" + c.getMchId() + "\","
                + "nonce_str=\"" + nonce + "\","
                + "signature=\"" + signature + "\","
                + "timestamp=\"" + timestamp + "\","
                + "serial_no=\"" + c.getCertSerialNo() + "\"";

        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(c.getApiBase() + path))
                .header("Authorization", auth)
                .header("Accept", "application/json")
                .header("User-Agent", "print-backend/1.0 (wechatpay-v3)");
        if ("POST".equals(method)) {
            b.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            b.GET();
        }
        return b.build();
    }

    /** 解析 V3 响应：HTTP 2xx 解析 JSON；否则抛业务异常并带出微信错误码/信息。 */
    private JsonNode parseResponse(HttpResponse<String> resp, String path) throws Exception {
        String bodyStr = resp.body() == null ? "" : resp.body();
        if (resp.statusCode() / 100 != 2) {
            String code = null;
            String msg = bodyStr;
            try {
                JsonNode err = objectMapper.readTree(bodyStr);
                code = text(err, "code");
                msg = text(err, "message");
            } catch (Exception ignore) {
                // 保持原始文本
            }
            log.error("[WechatPay] 接口返回非 2xx: path={}, status={}, code={}, msg={}",
                    path, resp.statusCode(), code, msg);
            throw new BizException(ResultCode.PAYMENT_GATEWAY_ERROR.code(),
                    "微信接口错误(" + resp.statusCode() + "): " + code + " " + msg);
        }
        return bodyStr.isEmpty() ? objectMapper.createObjectNode() : objectMapper.readTree(bodyStr);
    }

    // ==================== 加解密 / 验签工具 ====================

    /** 商户 API 私钥（PKCS8 PEM），首次使用时从 {@code privateKeyPath} 加载并缓存。 */
    private PrivateKey privateKey(PrintProperties.WechatCfg c) throws Exception {
        PrivateKey key = cachedPrivateKey;
        if (key != null) {
            return key;
        }
        synchronized (this) {
            if (cachedPrivateKey == null) {
                String pem = Files.readString(Path.of(c.getPrivateKeyPath()), StandardCharsets.UTF_8);
                byte[] der = decodePem(pem, "PRIVATE KEY");
                cachedPrivateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
                log.info("[WechatPay] 已加载商户 API 私钥: {}", c.getPrivateKeyPath());
            }
            return cachedPrivateKey;
        }
    }

    /** SHA256withRSA 签名，返回 Base64。 */
    private String rsaSign(String message, PrivateKey privateKey) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    /** 用平台证书公钥验签。 */
    /** 回调时间戳是否在 ±5 分钟窗口内（防重放，设计 §3.1.2）。 */
    private boolean withinWindow(String timestamp) {
        try {
            long ts = Long.parseLong(timestamp.trim());
            return Math.abs(System.currentTimeMillis() / 1000 - ts) <= 300;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean verifyWithPlatformCert(PrintProperties.WechatCfg c, String message, String signatureB64) throws Exception {
        if (isBlank(c.getPlatformCertPath())) {
            throw new BizException(ResultCode.PAYMENT_GATEWAY_ERROR.code(),
                    "微信支付未配置：platformCertPath（回调验签平台证书）");
        }
        X509Certificate cert;
        try (var in = Files.newInputStream(Path.of(c.getPlatformCertPath()))) {
            cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(cert.getPublicKey());
        verifier.update(message.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(signatureB64));
    }

    /**
     * AES-256-GCM 解密回调 {@code resource}。
     *
     * <p>密钥 = apiV3Key（32 字节字符串）；nonce 为 12 字节字符串；associated_data 作为 AAD。
     */
    private String decryptResource(PrintProperties.WechatCfg c, JsonNode resource) throws Exception {
        String ciphertext = text(resource, "ciphertext");
        String nonce = text(resource, "nonce");
        String associatedData = text(resource, "associated_data");
        if (ciphertext == null || nonce == null) {
            throw new BizException(ResultCode.PAYMENT_GATEWAY_ERROR.code(), "微信回调解密参数缺失");
        }
        byte[] key = c.getApiV3Key().getBytes(StandardCharsets.UTF_8);
        if (key.length != 32) {
            throw new BizException(ResultCode.PAYMENT_GATEWAY_ERROR.code(),
                    "微信支付未配置：apiV3Key 必须为 32 字节");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8));
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), spec);
        if (isNotBlank(associatedData)) {
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        }
        byte[] plain = cipher.doFinal(Base64.getDecoder().decode(ciphertext));
        return new String(plain, StandardCharsets.UTF_8);
    }

    // ==================== 配置校验 ====================

    /**
     * 校验配置是否就绪，缺失时抛出可读的 {@link BizException}（不阻断启动）。
     *
     * @param needAppAndNotify 下单场景额外要求 appId / notifyUrl
     */
    private void requireConfigured(PrintProperties.WechatCfg c, boolean needAppAndNotify) {
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (isBlank(c.getMchId())) missing.add("mchId");
        if (isBlank(c.getPrivateKeyPath())) missing.add("privateKeyPath");
        if (isBlank(c.getCertSerialNo())) missing.add("certSerialNo");
        if (isBlank(c.getApiV3Key())) missing.add("apiV3Key");
        if (needAppAndNotify) {
            if (isBlank(c.getAppId())) missing.add("appId");
            if (isBlank(c.getNotifyUrl())) missing.add("notifyUrl");
        }
        if (!missing.isEmpty()) {
            throw new BizException(ResultCode.PAYMENT_GATEWAY_ERROR.code(),
                    "微信支付未配置：" + String.join("、", missing));
        }
    }

    // ==================== 小工具 ====================

    /** 元 → 分（×100 四舍五入取整）。 */
    private int toFen(BigDecimal yuan) {
        if (yuan == null) {
            throw new BizException(ResultCode.PAYMENT_GATEWAY_ERROR.code(), "微信支付金额不能为空");
        }
        return yuan.movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    private PayState mapState(String tradeState) {
        if (tradeState == null) {
            return PayState.PENDING;
        }
        return switch (tradeState.toUpperCase()) {
            case "SUCCESS" -> PayState.SUCCESS;
            case "NOTPAY", "USERPAYING" -> PayState.PENDING;
            case "CLOSED", "REVOKED", "PAYERROR", "REFUND" -> PayState.FAILED;
            default -> PayState.PENDING;
        };
    }

    private String randomNonce() {
        byte[] buf = new byte[16];
        secureRandom.nextBytes(buf);
        return java.util.HexFormat.of().formatHex(buf).toUpperCase();
    }

    private static String decodePemBase64(String pem, String label) {
        return pem.replace("-----BEGIN " + label + "-----", "")
                .replace("-----END " + label + "-----", "")
                .replaceAll("\\s", "");
    }

    private static byte[] decodePem(String pem, String label) {
        return Base64.getDecoder().decode(decodePemBase64(pem, label));
    }

    /** 大小写不敏感地取请求头。 */
    private static String header(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
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
