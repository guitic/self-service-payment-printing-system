# 自助付费打印系统 · 综合代码审查与安全加固报告

**日期**：2026-10-10
**工作流**：工作流 1 — 全面代码审查（含修复验证）
**参与成员**：甄宇航（Zhen）· 工程督导（主理人／修复与汇编）｜科迪（Cody）· 代码审查师（独立审查）
**审查范围**：本轮新增与改动的代码（支付渠道、文件转换、订单与任务一致性、安全与通用）

---

## 📌 TL;DR（执行摘要）

- **整体结论**：🟠 **有条件通过（开发基线可用，真实支付接入前不可上生产）**。支付抽象与幂等 CAS 设计扎实，但发现 **2 项可被利用的严重安全缺口**（管理端零权限校验、生产可绕过支付免费出纸）。
- **审查发现**：🔴 严重 2 项 / 🟠 高 4 项 / 🟡 中 6 项 / 🟢 低 4 项，共 16 项。
- **本轮修复**：🔴 2 项 + 🟠 4 项 + 🟡 4 项 **已全部修复并验证**；🟢 4 项为优化建议，列入后续。
- **验证证据**：`mvn verify` BUILD SUCCESS，**53 个测试全绿**；端到端冒烟 **12/12**（真实 .NET 助手）；压测 50 VU / 25s **0 失败**。
- **阻塞判定**：**不阻塞开发**；接入真实微信/支付宝**之前**必须完成「真实渠道沙箱联调」（见行动清单 P0）。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| 整体评级 | 🟠 有条件通过 |
| 阻塞项数量 | 0（本轮范围内已全部修复） |
| 关键行动项 | 8 条（P0×3 / P1×3 / P2×2） |
| 建议下一步 | 真实渠道沙箱联调 → 补齐覆盖率至 70% → 真机打印联调 |
| 修复后测试 | 53 通过 / 0 失败；E2E 12/12；压测 0 失败 |

---

## 🔍 审查发现（按严重度排序，含修复状态）

| # | 严重度 | 类别 | 文件:行 | 问题描述 | 修复 | 状态 |
|---|--------|------|---------|----------|------|------|
| 1 | 🔴 | 安全/越权 | `admin/AdminController.java` 全体 | 管理端**无任何角色校验**：任意登录用户（登录即自动注册）可读全量订单、改计价规则（把单价改 0）、自设 `agentToken` → 纵向提权 | 引入 JWT `role` claim + `ROLE_ADMIN`；`SecurityConfig` 对 `/api/v1/admin/**` 强制 `hasRole("ADMIN")`；角色来源为手机号白名单 `print.admin.phones`，demo 档 `print.admin.allow-all` | ✅ 已修 |
| 2 | 🔴 | 安全/资金 | `MockPaymentController.java`、`application-prod.yml`、`SecurityConfig.java` | 生产默认 `gateway=mock` 且 mock 端点 `permitAll` 且 `matchIfMissing=true` → **匿名可把任意订单标记已支付并出纸**（免费出纸） | 改为 `print.payment.mock-enabled=true` 才注册（生产默认 false）；路径要求登录；服务层校验订单归属；`ProdConfigValidator` 在生产启动时拒绝开启该开关 | ✅ 已修 |
| 3 | 🟠 | 安全/重放 | `wechat/WechatPaymentGateway.java:196+`、`alipay/AlipayPaymentGateway.java:166+` | 回调验签后**未校验时间戳窗口、未做 nonce 去重**，合法签名回调可被重放 | 微信校验 `Wechatpay-Timestamp` ±300s + `nonce` 去重；支付宝校验 `notify_time` ±300s + `notify_id` 去重；新增 `NonceStore`（内存 TTL，多节点可换 Redis） | ✅ 已修 |
| 4 | 🟠 | 正确性/一致性 | `payment/PaymentService.java:status()` | **回调丢失无任何兜底**：用户已扣款但订单停留待支付 → 到期被关单 | `status()` 待支付分支主动 `gateway.query()` 命中即补记；新增 `PaymentReconcileJob` 每 60s 扫描过期待支付订单补偿 | ✅ 已修 |
| 5 | 🟠 | 安全/越权 | `task/AgentController.java:84`、`task/TaskService.java` | `/agent/tasks/{id}/report` 只校验令牌有效，**不校验任务归属终端** → 任一终端可改写他终端任务状态 | 新增 `reportByAgent(...)`：归属不匹配返回 403；控制器改走该入口 | ✅ 已修 |
| 6 | 🟠 | 安全/配置 | `application-prod.yml` | `JWT_SECRET` 有**已知默认值** → 可伪造任意用户（含管理员）令牌 | 生产默认值改为空；新增 `ProdConfigValidator`（`@Profile("prod")`）在启动时对弱/默认密钥 fail-fast | ✅ 已修 |
| 7 | 🟡 | 正确性/资金 | `payment/PaymentService.java:markPaidAndEnqueue` | 金额强校验被「amount 为空则跳过」绕过 | 新增 `requireAmount` 参数：**回调路径金额缺失即拒绝**；服务端主动查询才允许跳过 | ✅ 已修 |
| 8 | 🟡 | 安全/契约 | `common/GlobalExceptionHandler.java` | 业务异常一律 HTTP 200，401/403/404/429 在网关与监控侧不可见 | 按 `ResultCode` 映射 HTTP 状态（401/403/404/429/400），纯业务错误仍 200 | ✅ 已修 |
| 9 | 🟡 | 安全/信息泄露 | `common/GlobalExceptionHandler.java:74` | 兜底处理器把底层异常消息（SQL/路径/类名）回传客户端 | 对外返回通用文案，详细信息仅落日志 | ✅ 已修 |
| 10 | 🟡 | 正确性/并发 | `file/convert/LibreOfficeConverter.java` | 转换未传 `-env:UserInstallation`，并发转换共享 profile → 锁冲突 → 降级估算 → 计价不准 | 每次转换使用独立 `UserInstallation` profile | ✅ 已修 |
| 11 | 🟡 | 正确性/兼容 | `payment/PaymentController.java` | `@RequestBody String` 读表单类回调可能取不到内容 → 验签恒失败、渠道持续重试 | 改为从 `HttpServletRequest.getInputStream()` 读原始报文 | ✅ 已修 |
| 12 | 🟡 | 性能/DoS | `file/PageCountResolver.java` | PDFBox 未设内存上限，畸形 PDF 可致 OOM | 未修（需引入 `MemoryUsageSetting` + 解析超时） | ⏳ 待办 |
| 13 | 🟢 | 性能 | `order/OrderService.java:toVO`、`admin/AdminController.java` | 订单列表 **N+1** 查询（每单查一次文件名） | 未修（批量按 fileId 查询） | ⏳ 待办 |
| 14 | 🟢 | 性能 | `task/TaskService.java:toVO` | 领取热路径上每次都全量加载终端列表 | 未修（按 id 精确查询/缓存） | ⏳ 待办 |
| 15 | 🟢 | 安全/纵深 | `file/storage/LocalFileStorage.java:resolve` | `resolve()` 未做前缀校验（`store()` 有） | 未修（入参不可外部控制，属纵深防护） | ⏳ 待办 |
| 16 | 🟢 | 健壮性 | `order/OrderService.java:insertOrderWithRetry` | 碰撞重试依赖 `create()` 非事务的前提，若日后加 `@Transactional` 会失效 | 已加注释约束说明 | ⏳ 待办 |

> **未发现**：命令注入（`ProcessBuilder(List)` 传参且路径为服务端生成）、上传魔数校验、助手 spool 原子写、下载文件名穿越防护——均无问题。

---

## 🛠️ 修复与验证证据

| 验证项 | 结果 |
|--------|------|
| `mvn -B verify` | ✅ BUILD SUCCESS |
| 单元/集成测试 | ✅ **Tests run: 53, Failures: 0, Errors: 0, Skipped: 0**（较上轮 +1：新增「匿名访问 mock 端点被拦截」） |
| 覆盖率门禁 | ✅ JaCoCo report + check 通过（棘轮基线：BUNDLE 行覆盖 ≥30%，核心类 ≥55%） |
| 端到端冒烟（真实 .NET 助手） | ✅ **12 / 12 通过** |
| 压测（50 VU / 25s） | ✅ 26,996 请求、**0 失败**、QPS 1078、P95 86ms |
| .NET 助手编译 | ✅ 0 警告 0 错误（SDK 8.0.404） |

**本轮新增/修改的关键文件**

- 新增：`payment/gateway/NonceStore.java`、`payment/PaymentReconcileJob.java`、`config/ProdConfigValidator.java`
- 修改：`config/{PrintProperties,JwtUtils,JwtAuthFilter,SecurityConfig}.java`、`auth/AuthService.java`、`payment/{PaymentService,PaymentController,MockPaymentController}.java`、`payment/gateway/{wechat,alipay}/*.java`、`task/{TaskService,AgentController}.java`、`common/GlobalExceptionHandler.java`、`order/OrderService.java`、`file/convert/LibreOfficeConverter.java`、`application-{demo,prod}.yml`、`CONTRACT.md`

---

## 🏗️ 架构影响评估

- **权限模型**：从「无角色」升级为 `ROLE_USER` / `ROLE_ADMIN` 两级，角色经 JWT claim 传递（无状态，与 ADR-005 一致），未引入会话存储。
- **生产安全边界**：新增 `ProdConfigValidator`，把「弱密钥 / mock 支付开关 / 管理端 allow-all」从运行期风险前移为**启动期拒绝**，属于配置安全的 fail-fast 模式。
- **支付一致性**：回调路径（验签→防重放→金额强校验→CAS 落库→投递任务）与补偿路径（前端轮询主动查询 + 定时对账任务）形成闭环，覆盖设计文档 OPS-3 风险。
- **未改变**：`PaymentGateway` 接口语义、订单/任务状态机、CAS 幂等机制、部署拓扑。

---

## 🧪 测试覆盖评估

| 维度 | 现状 |
|------|------|
| 测试总数 | 53（Pricing 14 / Order 6 / Payment 5 / Task 5 / PageCount 7 / MockGateway 7 / ApiSecurity 7 / MockEndpointDisabled 2） |
| 后端行覆盖 | **约 35%**（核心类：PricingService 94%、TaskService 80%、PageCountResolver 80%、MockPaymentGateway 82%、PaymentService 52%、OrderService 41%） |
| 与目标差距 | 设计目标：整体 ≥70%、Pricing/Order ≥90% —— **未达标**，门禁暂设棘轮基线防止倒退 |
| 未覆盖重点 | Controller 层（除安全用例外）、AuthService、FileService、AdminController、真实渠道回调解析 |
| 硬件/真机 | 未覆盖（无物理打印机）；`.NET 助手` 已用 `MockPrintBackend` 完成全链路验证 |

---

## ✅ 行动清单（按优先级排序）

| # | 行动 | 负责角色 | 紧急度 | 预期完成 |
|---|------|---------|--------|---------|
| 1 | 用微信/支付宝**沙箱**商户号跑通真实下单→回调→验签→退款全链路（重点验证第 11 项修复后的表单回调取体、RSA2 待签串、金额单位） | 后端 + 测试 | P0 | 迭代 1 |
| 2 | 在含物理打印机的终端做真机出纸联调（页数/色彩/单双面/纸张正确性人工核对） | 助手开发 + 测试 | P0 | 迭代 1 |
| 3 | 生产部署前确认：`JWT_SECRET` ≥32 字节随机、`PAYMENT_MOCK_ENABLED=false`、`print.admin.allow-all=false`、`ADMIN_PHONES` 白名单已配 | SRE | P0 | 上线前 |
| 4 | 补齐覆盖率：Controller 层 MockMvc 用例 + AuthService/FileService/AdminController，把门禁上调至整体 70%、Pricing/Order 90% | 测试 | P1 | 迭代 2 |
| 5 | 修复 🟡 #12：PDFBox 设 `MemoryUsageSetting` 上限 + 解析超时 | 后端 | P1 | 迭代 2 |
| 6 | 把 `NonceStore` 换为 Redis 实现（多节点部署时内存去重失效） | 后端 | P1 | 迭代 3 |
| 7 | 优化 🟢 #13/#14 的 N+1 查询 | 后端 | P2 | 迭代 3 |
| 8 | 补充回调伪造/重放的专项安全用例（时间戳越界、nonce 重放） | 测试 | P2 | 迭代 3 |

---

## ⚠️ 待完善 / 已知局限

1. **真实渠道未联调**：微信/支付宝实现按官方协议编写并静态审查通过，但**未经沙箱实测**；`docs/payment-switch-guide.md` 已列出 8 项切换回归清单。
2. **覆盖率 35% < 70% 目标**：门禁暂为棘轮基线（防倒退），未达设计目标。
3. **`NonceStore` 为单机内存实现**：多节点部署需替换为 Redis。
4. **LibreOffice 未安装**：转换器生产路径未实测，Office 文档当前走体积估算降级。
5. **真机打印未做**：`WindowsPrintBackend` 未在真实打印机上验证。
6. **🟡#12 与 🟢#13~#16 未修**：已在发现表中逐条标注，列入行动清单。

---

## 📚 数据来源 & 成员产出索引

| 成员 | 角色 | 产出 |
|------|------|------|
| Cody（科迪） | 代码审查师 | 16 项审查发现（含文件行号与修复建议）、5 项优点、6 项需运行时验证点 |
| Zhen（甄宇航） | 工程督导（主理人） | 修复 10 项（🔴2 + 🟠4 + 🟡4）、测试与验证、本报告汇编 |
| 上游依据 | — | `CONTRACT.md`、`deliverables/engineering-assurance/system-design-自助付费打印系统-2026-10-10.md` |

---

> 本报告由工程保障团队 AI 协作生成，关键决策请由人类工程负责人复核。
