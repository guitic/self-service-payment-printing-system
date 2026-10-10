# 接口契约 CONTRACT.md（v1.0 · 冻结）

> 本文件是前端 / 后端 / 打印助手并行开发的**唯一接口契约**，与设计文档
> `deliverables/engineering-assurance/system-design-自助付费打印系统-2026-10-10.md` 第 1 章 §5 一致。
> 任何一方改动接口，必须先改本文件。

## 1. 通用约定

| 项 | 约定 |
|----|------|
| BasePath | `/api/v1` |
| 用户鉴权 | `Authorization: Bearer <JWT>` |
| 助手鉴权 | `X-Agent-Token: <token>` |
| 请求体 | `application/json`（上传除外：`multipart/form-data`） |
| 时间格式 | ISO-8601 字符串，如 `2026-10-10T10:30:00` |
| 金额 | 字符串/数字，单位**元**，两位小数（后端 BigDecimal） |
| 分页 | `?page=1&size=10`，响应 `data: { list, total, page, size }` |

### 1.1 统一响应体

```json
{ "code": 0, "message": "ok", "data": {} }
```

`code=0` 表示成功；非 0 为业务错误码（见 §6）。

## 2. 枚举

| 枚举 | 值 |
|------|-----|
| `role` | `ADMIN` / `USER`（登录时由后端签发，随 `/auth/login` 与 `/auth/me` 下发） |
| `colorMode` | `COLOR` / `MONO` |
| `duplexMode` | `SIMPLEX` / `DUPLEX` |
| `paperSize` | `A4` / `A3` |
| `payStatus` | `0` 待支付 / `1` 已支付 / `2` 已关闭 / `3` 已退款 |
| `printStatus` | `0` 待打印 / `1` 打印中 / `2` 已完成 / `3` 失败 |
| `taskStatus` | `0` 待领取 / `1` 已领取 / `2` 打印中 / `3` 成功 / `4` 失败 / `5` 重试中 |

## 3. 接口清单

### 3.1 认证 Auth

| 方法 | 路径 | 鉴权 | 请求 | 响应 data |
|------|------|------|------|-----------|
| POST | `/auth/sms/send` | 无 | `{phone}` | `{expireSeconds, debugCode?}`（demo 档返回 `debugCode` 便于本地联调） |
| POST | `/auth/login` | 无 | `{phone, code}` | `{token, tokenType:"Bearer", expiresIn, user:{id,phone,nickname,role}}` |
| GET | `/auth/me` | 用户 | — | `{id, phone, nickname, role}` |
| POST | `/auth/logout` | 用户 | — | `null` |

### 3.2 文件 File

| 方法 | 路径 | 鉴权 | 请求 | 响应 data |
|------|------|------|------|-----------|
| POST | `/files` | 用户 | multipart，字段名 `file` | `{id, originalName, contentType, sizeBytes, pageCount}` |
| GET | `/files/{id}` | 用户 | — | 同上（越权返回 403/404） |
| GET | `/files/{id}/download` | 用户 | — | 二进制流 |

### 3.3 计价 Pricing

`POST /pricing/quote`（用户）

```json
// 请求
{ "fileId": 1, "copies": 2, "colorMode": "MONO", "duplexMode": "SIMPLEX",
  "paperSize": "A4", "pageRange": { "from": 1, "to": 5 } }
// 响应 data
{ "pageCount": 10, "billablePages": 5, "unitPrice": 0.20, "duplexDiscount": 1.00,
  "amount": 2.00, "currency": "CNY", "ruleId": 1 }
```

计价公式：`amount = billablePages × copies × unitPrice × duplexDiscount`（按实际数量与单价直接计算，**不设起印价/最低消费**）。
`billablePages` = 页范围页数（无范围则为 `pageCount`）；`duplexDiscount`：DUPLEX 取规则折扣，SIMPLEX 取 1.00。

### 3.4 订单 Order

| 方法 | 路径 | 鉴权 | 请求 | 响应 data |
|------|------|------|------|-----------|
| POST | `/orders` | 用户 | 同 quote（可带 `terminalCode`） | `{orderNo, amount, payStatus, printStatus, expireAt, fileId, params}` |
| GET | `/orders/{orderNo}` | 用户 | — | 订单详情（含 `payStatusText/printStatusText`） |
| GET | `/orders` | 用户 | `?page&size` | 分页订单列表 |
| POST | `/orders/{orderNo}/cancel` | 用户 | — | `null`（仅未支付可取消） |

### 3.5 支付 Payment

| 方法 | 路径 | 鉴权 | 请求 | 响应 data |
|------|------|------|------|-----------|
| POST | `/payments/{orderNo}/pay` | 用户 | — | `{orderNo, gateway, tradeNo, qrCodeContent, amount, expireAt}` |
| GET | `/payments/{orderNo}/status` | 用户 | — | `{orderNo, payStatus, payStatusText}` |
| POST | `/payments/notify/{gateway}` | 网关 | 网关报文 | **非统一响应体**：应答格式由渠道决定 —— 支付宝要求 HTTP 200 + 纯文本 `success`；微信 V3 要求 HTTP 200 + JSON。失败返回 5xx + `failure` |
| POST | `/payments/mock/confirm` | **用户 JWT + 订单归属**（且仅 `print.payment.mock-enabled=true` 时注册） | `{orderNo, result?}`，`result` ∈ `SUCCESS`(默认)/`FAIL` | `{orderNo, payStatus}` |

`qrCodeContent` 格式：`mockpay://order/{orderNo}?amount={amount}`（便于 E2E 断言）。
支付成功后：`payStatus=1` 且**投递 1 条打印任务**（幂等，重复确认不重复投递）。

### 3.6 打印任务 Task

| 方法 | 路径 | 鉴权 | 请求 | 响应 data |
|------|------|------|------|-----------|
| GET | `/tasks/{orderNo}/status` | 用户 | — | `{orderNo, payStatus, printStatus, printStatusText, failReason?}` |
| POST | `/agent/heartbeat` | 助手 | `{terminalCode, agentVersion, printers:[{name,status}]}` | `{serverTime, config:{claimTimeoutSeconds,maxRetry,heartbeatSeconds}}` |
| POST | `/agent/tasks/claim` | 助手 | `{terminalCode, longPollSeconds?}` | 任务对象或 `null` |
| POST | `/agent/tasks/{id}/report` | 助手 | `{status:"SUCCESS"\|"FAILED", failReason?}` | `null` |
| GET | `/agent/tasks/{id}/file` | 助手 | — | 二进制流 |

**任务对象**：

```json
{ "taskId": 1, "orderNo": "P20261010xxxx", "terminalCode": "TERM-001",
  "printerName": "Default Printer", "fileName": "doc.pdf", "fileSize": 102400,
  "pageCount": 5, "copies": 2, "colorMode": "MONO", "duplexMode": "SIMPLEX",
  "paperSize": "A4", "retryCount": 0 }
```

### 3.7 管理端 Admin

| 方法 | 路径 | 请求 | 响应 data |
|------|------|------|-----------|
| GET | `/admin/price-rules` | — | 规则列表 |
| POST | `/admin/price-rules` | `{name,paperSize,colorPrice,monoPrice,duplexDiscount}` | 新建规则 |
| PUT | `/admin/price-rules/{id}` | 同上 | 更新 |
| GET | `/admin/orders` | `?page&size` | 分页订单 |
| GET | `/admin/terminals` | — | 终端列表（含在线状态） |
| POST | `/admin/terminals` | `{code,name,agentToken}` | 新建终端 |

> 管理端鉴权：需 `ROLE_ADMIN`。角色来源：手机号白名单 `print.admin.phones`（生产），
> 或 demo 档的 `print.admin.allow-all=true`（**生产必须为 false**，否则任意登录用户即管理员）。
> 前端据 `/auth/me` 下发的 `role` 隐藏「管理端」菜单与路由，但**安全边界只在本节的接口鉴权**。

## 4. 运行档位（Profile）

| 档位 | 数据库 | 队列 | 短信 | 支付 | 用途 |
|------|--------|------|------|------|------|
| `demo`（默认） | H2 内存 | 内存队列 | 控制台打印验证码 + 返回 `debugCode` | Mock 网关 | **本机零依赖直接跑通** |
| `prod` | MySQL 8 | Redis | 短信通道 | Mock/微信/支付宝 | 按设计文档部署 |

**demo 档预置数据**：
- 终端 `TERM-001`，`agentToken = dev-agent-token`
- 计价规则：A4 黑白 0.20 / A4 彩色 0.80 / A3 黑白 0.40，双面折扣 0.90（金额按实际数量 × 单价直接计算，无起印价）
- 验证码有效期 300s，同号 60s 冷却

## 5. 关键行为约束（幂等 / 一致性）

1. `orderNo` 全局唯一；`t_print_task` 与订单 1:1。
2. 支付确认/回调：`payStatus 0→1` 用条件更新（CAS），仅一次生效；重复确认返回成功但不重复投递任务。
3. 助手领取任务：原子更新（`status 0→2` 并记 `claimedAt`），同一任务只能被一个助手领到。
4. 任务领取后超过 `claimTimeoutSeconds`(默认 120s) 未回传 → 回收为待领取并 `retryCount++`。
5. 回传 `SUCCESS` 后状态不可被回收覆盖；`FAILED` 且 `retryCount >= maxRetry(3)` → 终态失败。

## 6. 错误码

| code | HTTP | 含义 |
|------|------|------|
| 0 | 200 | 成功 |
| 400 | 400 | 参数校验失败 |
| 401 | 401 | 未登录 / Token 无效 |
| 403 | 403 | 越权 |
| 404 | 404 | 资源不存在 |
| 429 | 429 | 请求过于频繁 |
| 1001 | 400 | 验证码错误 |
| 1002 | 400 | 验证码已过期 |
| 1003 | 429 | 验证码发送过于频繁 |
| 2001 | 400 | 不支持的文件类型 |
| 2002 | 400 | 文件超过大小限制 |
| 2003 | 400 | 文件解析失败 |
| 3001 | 400 | 暂无可用计价规则 |
| 3002 | 400 | 页码范围超出文件页数 |
| 4001 | 400 | 订单不存在 |
| 4002 | 400 | 订单已支付不可取消 |
| 4003 | 400 | 订单已关闭 |
| 5001 | 400 | 支付网关错误 |
