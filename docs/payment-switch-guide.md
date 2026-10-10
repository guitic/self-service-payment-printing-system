# 支付渠道切换指南（Mock → 微信支付 / 支付宝）

> 适用范围：自助付费打印系统后端 `PaymentGateway` 可插拔支付网关。
> 相关代码：`backend/src/main/java/com/print/payment/gateway/`（`PaymentGateway` 抽象、
> `MockPaymentGateway`、`wechat/WechatPaymentGateway`、`alipay/AlipayPaymentGateway`）。
> 回归清单对齐设计文档 `deliverables/engineering-assurance/chapters/07-测试策略与测试计划.md` §7.5.5。

---

## 0. 当前状态（诚实说明）

- 本仓库**当前未接入任何真实支付渠道**，默认运行档位（`demo`）与 `prod` 默认值均为 `mock`。
- 微信 / 支付宝两个网关实现（`WechatPaymentGateway` / `AlipayPaymentGateway`）已按真实 V3 / OpenAPI 协议实现，
  但**只有商户实际配置密钥、证书并启用后才能真正收款**。
- `backend/src/main/resources/application-prod.yml` 中 `print.payment.wechat.*` 与 `print.payment.alipay.*`
  下的值**全部为环境变量占位**（如 `${WECHAT_MCH_ID:}`），默认空字符串，**不代表已具备收款能力**。
- 未配置时对应网关 Bean **不会注册**（`@ConditionalOnProperty(...enabled=true)`），
  且任何缺失配置只会在**调用支付方法时**抛业务异常 `BizException(5001, "…未配置：<缺什么>")`，**不会导致应用启动失败**。

---

## 1. 前置条件：资质与商户号申请

### 1.1 微信支付（Native 扫码 / APIv3）

| 项 | 说明 |
|----|------|
| 主体资质 | 企业 / 个体工商户营业执照；个体户亦可申请 |
| 商户号 | 微信支付商户平台（pay.weixin.qq.com）注册，取得 `mchid`（10 位） |
| 应用 | 关联一个 AppID（公众号 / 小程序 / 开放平台），取得 `appid` |
| APIv3 密钥 | 商户平台「账户中心 → API 安全」设置 32 位 `apiV3Key` |
| 商户 API 证书 | 申请后得到 `apiclient_cert.pem` / `apiclient_key.pem`；`apiclient_key.pem` 即**商户 API 私钥**（PKCS8） |
| 证书序列号 | 商户证书序列号 `certSerialNo`（商户平台可见，或 `openssl x509 -in apiclient_cert.pem -noout -serial`） |
| 平台证书 | 「API 安全 → 平台证书」下载微信支付平台证书（用于回调验签） |

### 1.2 支付宝（当面付 precreate 扫码）

| 项 | 说明 |
|----|------|
| 主体资质 | 企业 / 个体工商户（个人开发者可用沙箱联调） |
| 应用 | 开放平台（open.alipay.com）创建「网页/移动应用」，取得 `appId`，签约**当面付**产品 |
| 应用私钥 | 生成 RSA2 密钥对，应用私钥（PKCS8）配置为 `ALIPAY_PRIVATE_KEY` |
| 支付宝公钥 | 将应用公钥上传后，平台返回**支付宝公钥**，配置为 `ALIPAY_PUBLIC_KEY`（回调验签用） |
| 沙箱 | 开发期用沙箱环境（`ALIPAY_SANDBOX=true`），使用沙箱 AppID / 密钥 |

> 密钥生成：`openssl genrsa -out app_private.pem 2048`，
> `openssl pkcs8 -topk8 -inform PEM -in app_private.pem -outform PEM -nocrypt -out app_private_pkcs8.pem`。

---

## 2. 证书 / 密钥放置

**原则：密钥一律不入 Git**（`.gitignore` 已忽略 `*.env`、`deploy/env/*.env`）。推荐做法：

1. 在服务器上建立受控目录并收紧权限：

   ```bash
   sudo mkdir -p /etc/print/certs
   sudo chmod 700 /etc/print/certs
   # 微信：商户私钥 + 平台证书
   sudo cp apiclient_key.pem      /etc/print/certs/wechat_apiclient_key.pem
   sudo cp wechatpay_platform.pem /etc/print/certs/wechat_platform_cert.pem
   sudo chmod 600 /etc/print/certs/*
   sudo chown -R print:print /etc/print/certs
   ```

2. 环境变量文件（`/etc/print/backend.env`，权限 `600`，属主 `print`）中填写**文件路径**而非密钥内容：

   ```bash
   WECHAT_PRIVATE_KEY_PATH=/etc/print/certs/wechat_apiclient_key.pem
   WECHAT_PLATFORM_CERT_PATH=/etc/print/certs/wechat_platform_cert.pem
   ```

3. 支付宝私钥 / 公钥为 Base64 文本，直接写入环境变量（可带或不带 `-----BEGIN-----` 头，代码两者都兼容）：

   ```bash
   ALIPAY_PRIVATE_KEY="MIIEv…（PKCS8 Base64，单行或用引号包多行）"
   ALIPAY_PUBLIC_KEY="MIIBIjANBgkq…（支付宝公钥 Base64）"
   ```

---

## 3. 环境变量清单

在 `deploy/env/backend.env.example` 基础上追加（或合并进 `/etc/print/backend.env`）：

```bash
# ===== 渠道开关（总开关）=====
PAYMENT_GATEWAY=wechat          # mock / wechat / alipay

# ===== 微信支付 V3 =====
WECHAT_PAY_ENABLED=true
WECHAT_APP_ID=wx________________
WECHAT_MCH_ID=1900000000
WECHAT_API_V3_KEY=____________________________  # 32 位
WECHAT_PRIVATE_KEY_PATH=/etc/print/certs/wechat_apiclient_key.pem
WECHAT_CERT_SERIAL_NO=________________________  # 商户证书序列号（大写十六进制）
WECHAT_PLATFORM_CERT_PATH=/etc/print/certs/wechat_platform_cert.pem
WECHAT_NOTIFY_URL=https://print.example.com/api/v1/payments/notify/wechat
WECHAT_API_BASE=https://api.mch.weixin.qq.com   # 一般不改

# ===== 支付宝当面付 =====
ALIPAY_ENABLED=true
ALIPAY_APP_ID=2021000000000000
ALIPAY_PRIVATE_KEY="…应用私钥 PKCS8 Base64…"
ALIPAY_PUBLIC_KEY="…支付宝公钥 Base64…"
ALIPAY_NOTIFY_URL=https://print.example.com/api/v1/payments/notify/alipay
ALIPAY_GATEWAY=https://openapi.alipay.com/gateway.do
ALIPAY_SANDBOX=false            # 沙箱联调时设 true
```

> 上表变量名与 `application-prod.yml` 中 `print.payment.wechat.*` / `print.payment.alipay.*` 的
> `${…}` 占位一一对应；`spring-relaxed-binding` 会把 `WECHAT_APP_ID` 映射到 `print.payment.wechat.app-id`。

---

## 4. 回调域名与备案要求

| 渠道 | 要求 |
|------|------|
| 通用 | 回调地址必须为**公网可达的 HTTPS**（微信 V3 要求 TLS1.2+；支付宝回调同样要求 https 或 http 公网） |
| 微信 | `notify_url` 域名需在商户平台**「产品中心 → 开发配置 → 支付回调域名」**登记；未登记的回调会被拦截 |
| 支付宝 | 回调地址无需预先登记，但域名须能解析到公网、且不能被鉴权（回调无用户 JWT，靠验签保证安全） |
| 中国大陆服务器 | 域名需完成 **ICP 备案**；Nginx 需放行 `/api/v1/payments/notify/{gateway}` 且**不得**做 JWT 鉴权、不得改动请求体（验签基于原始 body） |
| 反向代理 | Nginx 必须原样透传原始请求体与请求头（微信验签依赖 `Wechatpay-Timestamp/Nonce/Signature` 头与原始 body） |

Nginx 参考（`deploy/nginx/print.conf`）：

```nginx
location /api/v1/payments/notify/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    # 保留原始请求体与头，勿改写
    proxy_request_buffering on;
}
```

---

## 5. 切换步骤

1. **停用 Mock 专用接口**：确认 `PAYMENT_GATEWAY != mock`。`MockPaymentController` 由
   `@ConditionalOnProperty(name="print.payment.gateway", havingValue="mock", matchIfMissing=true)` 控制，
   切到 `wechat`/`alipay` 后 `/api/v1/payments/mock/confirm` 自动**不注册**（返回 404）。
2. **启用目标渠道**：设置 `WECHAT_PAY_ENABLED=true`（或 `ALIPAY_ENABLED=true`），
   并填写第 3 节全部变量。
3. **放置证书/密钥**（第 2 节），确认后端进程用户可读。
4. **切换总开关**：`PAYMENT_GATEWAY=wechat`（或 `alipay`）。
5. **重启后端**：`sudo systemctl restart print-backend`（`PrintProperties` 为启动时绑定，改渠道需重启）。
6. **验证启动日志**：应看到 `已加载商户 API 私钥: /etc/print/certs/…`（首次调用时打印），且无异常堆栈。
7. **冒烟**：发起一笔小额真实/沙箱支付，走完「下单 → 扫码 → 回调 → 出纸」。
8. **灰度**：建议先在小范围终端切 `wechat`，观察回调命中率与对账后再全量。

---

## 6. 验证清单（对齐 §7.5.5 八项回归）

> 切换到真实渠道时业务代码应**零改动**。切换前必须逐项回归：

- [ ] **① 契约测试重跑**：`PaymentGateway` 的 `prepay / query / handleNotify / refund` 契约测试在
      真实实现（Sandbox 商户号）上全部通过。
- [ ] **② 验签正/反用例**：微信平台证书验签、支付宝 RSA2 验签的**正例与篡改用例**均通过
      （篡改 body / 签名 → 返回 `success=false`，不更新任何业务状态）。
- [ ] **③ 字段映射一致**：真实回调解析出的 `orderNo`（`out_trade_no`）、`tradeNo`（`transaction_id` / `trade_no`）
      与 Mock 语义一致，能正确落 `t_payment_record`。
- [ ] **④ 回调重试与幂等兼容**：同一 `orderNo` 重复回调（渠道会重试）**不重复出纸**；
      `t_print_task` 中该订单仅 1 条记录，`pay_status` 仅迁移一次（CAS）。
- [ ] **⑤ 金额单位一致性**：微信 V3 用「分」（实现内 ×100 转换）、支付宝用「元」字符串；
      重点回归 `TC-PRICE-14` 与回调金额比对（`PaymentService.markPaidAndEnqueue` 的金额校验）。
- [ ] **⑥ 退款链路真实跑通**：Sandbox 发起退款，`refund()` 返回成功，`pay_status` 迁移为 `3 已退款`。
- [ ] **⑦ 对账一致**：定时对账任务调用 `query()` 的结果与渠道账单一致（渠道成功 ↔ 本地成功）。
- [ ] **⑧ Mock 端点已关闭**：生产 profile 下 `POST /api/v1/payments/mock/confirm` 返回 404（断言）。

---

## 7. 已知限制 / 后续待办（切真实渠道前需闭环）

1. **支付宝回调应答格式**：`PaymentController.notify` 统一返回 JSON `{"success":true}`，
   而支付宝要求回包**纯文本 `success`**（否则会持续重试）。当前 `PaymentController` 属冻结契约、未改动，
   **接入支付宝前需产品/架构确认是否允许为该渠道单独返回 `success` 文本**（建议后续由控制器按渠道分支返回）。
2. **回调金额落库口径**：`PaymentService.handleNotify` 通过 `rawBody` 根节点的 `amount` 字段取金额；
   真实渠道金额为嵌套结构（微信 `resource` 解密后 `amount.total`；支付宝表单 `total_amount`），
   因此真实回调的**金额强校验目前会被跳过**（`amount==null` 时跳过）。建议后续扩展
   `NotifyResult` 携带金额，或在 `PaymentService` 内按渠道解析（本任务未改动冻结文件）。
3. **防重放**：设计文档 §3.1 要求「时间戳窗口 + nonce 去重」，当前实现已完成**验签**，
   时间窗/nonce 去重建议在网关层或统一拦截器中补齐。
4. **平台证书自动下载**：微信平台证书会轮换，当前需手工放置 `platformCertPath`；后续可接
   `/v3/certificates` 自动下载更新。

---

## 8. 快速回退

将 `PAYMENT_GATEWAY` 改回 `mock` 并重启即可（Mock 网关始终注册，`/payments/mock/confirm` 恢复可用），
不影响历史订单与流水数据。
