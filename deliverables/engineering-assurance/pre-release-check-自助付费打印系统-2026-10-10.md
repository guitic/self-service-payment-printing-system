# 自助付费打印系统 · 可运行骨架构建与联调验证报告

**日期**：2026-10-10
**工作流**：工作流 4 — 部署前检查（Go / No-Go）
**参与成员**：甄宇航（Zhen）· 工程督导（主理人，后端与汇编）／前端工程代理／助手与 DevOps 代理／后端测试代理
**上游依据**：`deliverables/engineering-assurance/system-design-自助付费打印系统-2026-10-10.md`、`CONTRACT.md`

---

## 📌 TL;DR（执行摘要）

- **整体结论**：🟢 **通过（骨架阶段）**。三端骨架全部可构建，后端 **37 个单元/集成测试全绿**，**端到端冒烟 12/12 全通过**，前端可构建并成功代理后端。
- **交付内容**：`backend/`（Java 54 个源文件）+ `frontend/`（Vue3 全量页面）+ `print-agent/`（.NET 8 助手）+ `tools/agent-sim/`（Node 仿真器）+ `deploy/`（Nginx/systemd/env/脚本）+ CI。
- **验证方式**：真实启动后端（demo 档 H2）→ 真实跑通「发码 → 登录 → 上传 → 试算 → 下单 → 发起支付 → Mock 支付 → 助手领取并打印 → 状态已完成」全链路。
- **严重度分布**：🔴 严重 0 项 / 🟠 高 1 项 / 🟡 中 4 项。
- **Go/No-Go**：**Go（可交付开发基线）**；**No-Go 项**：真实支付渠道、文件转换引擎、生产环境部署（均为本期范围外）。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| 整体评级 | 🟢 通过（骨架阶段） |
| 阻塞项数量 | 0（本期范围内） |
| 关键行动项 | 7 条（见文末） |
| 建议下一步 | 接入真实支付渠道 → 替换文件转换引擎 → 补硬件联调 |
| 构建产物 | `backend/target/print-backend-1.0.0.jar`（48.6 MB，可执行） |
| 端到端验证 | 12 / 12 通过 |

---

## 🔍 检查清单（逐项打勾）

| # | 检查项 | 结果 | 证据 |
|---|--------|------|------|
| 1 | 后端可编译 | ✅ | `mvn -B package` → BUILD SUCCESS |
| 2 | 后端测试全绿 | ✅ | Tests run: 37, Failures: 0, Errors: 0, Skipped: 0 |
| 3 | 后端可启动（demo 档零外部依赖） | ✅ | 14.4s 启动，`/actuator/health` = `{"status":"UP"}` |
| 4 | 数据库表结构自动初始化 | ✅ | H2 内存库按 `db/schema.sql` + `db/data.sql` 建表并注入种子数据 |
| 5 | 生产库建表脚本 | ✅ | `db/mysql/schema.sql`（MySQL 8，含索引与种子） |
| 6 | 前端可构建 | ✅ | `npm run build` 通过（TypeScript 类型检查 + Vite 产物） |
| 7 | 前端可运行 | ✅ | Vite dev server 返回 200，页面标题「自助付费打印系统」 |
| 8 | 前端 → 后端代理联通 | ✅ | `GET /api/v1/auth/me` 经 Vite 代理返回 `{"code":401,...}` |
| 9 | 打印助手源码完整 | ✅ | .NET 8 Worker：心跳/长轮询/下载/打印/回传/退避/spool |
| 10 | 助手可运行验证 | ✅ | Node 仿真器真实领取任务并回传成功 |
| 11 | 全链路端到端 | ✅ | 见下方「端到端验证证据」 |
| 12 | 幂等性 | ✅ | 重复确认支付 3 次，订单仍 1 笔、任务仍 1 条 |
| 13 | 限频 | ✅ | 同号 60s 内重复发验证码被拒（code=1003） |
| 14 | 计价正确性 | ✅ | 3页×2份×0.20=1.20；3页×3份×0.20×0.9=1.62；1页取起印价 1.00 |
| 15 | 部署配置就绪 | ✅ | Nginx 站点+proxy_common、systemd units、env 模板、备份/巡检脚本 |
| 16 | CI 流水线就绪 | ✅ | `.github/workflows/ci.yml`（三端构建） |

### 端到端验证证据（`tools/smoke-test.mjs`）

```
=== 自助付费打印系统 · 端到端冒烟测试 ===
  ✅ 健康检查返回 UP
  ✅ 发送验证码成功且返回 debugCode  code=043993
  ✅ 验证码登录成功并签发 JWT
  ✅ 上传 PDF 并解析页数 = 3        fileId=2, pageCount=3
  ✅ 试算金额 = 1.20（3页×2份×0.20）
  ✅ 双面折扣金额 = 1.62（3页×3份×0.20×0.9）
  ✅ 起印价生效 = 1.00
  ✅ 创建订单成功                  orderNo=P20261010110941278761
  ✅ 发起支付返回二维码内容          qr=mockpay://order/...?amount=1.20
  ✅ Mock 支付成功 → payStatus=1
  ✅ 重复确认后订单仍只有 1 笔（幂等）
  ✅ 打印任务最终状态 = 已完成
=== 结果：12 通过 / 0 失败 ===
```

### 后端测试明细

| 测试类 | 用例数 | 覆盖重点 |
|--------|--------|---------|
| `PricingServiceTest` | 14 | 彩/黑、单双面折扣、多份、起印价、页码范围、0 页、BigDecimal 精度 |
| `MockPaymentGatewayTest` | 7 | HMAC 验签正向/反向、伪造回调拒绝 |
| `OrderServiceTest` | 6 | CAS 幂等、订单状态迁移 |
| `PaymentServiceTest` | 5 | 重复确认不重复投递任务 |
| `TaskServiceTest` | 5 | 回传幂等、重试上限、超时回收 |
| **合计** | **37** | 全部通过 |

---

## 🔐 安全风险

| 风险 | 等级 | 现状 | 缓解 |
|------|------|------|------|
| `mock/confirm` 端点暴露 | 🟠 | 已用 `@ConditionalOnProperty(gateway=mock)` 限定，仅 mock 档注册；生产档返回 404 | 保持该约束，并在 CI 加断言 |
| 密钥硬编码（JWT secret / Mock 签名密钥） | 🟡 | demo 档为开发默认值 | 生产通过 `deploy/env/backend.env.example` 外置，权限 600、不入 Git |
| 文件类型伪装 | ✅ 已缓解 | 上传按**魔数**校验（PDF/PNG/JPG/ZIP/OLE2），非仅扩展名 | — |
| 越权访问文件/订单 | ✅ 已缓解 | 所有查询强制归属校验，越权返回 403 | — |
| 目录穿越 | ✅ 已缓解 | 存储路径 `normalize()` + 前缀校验 | — |
| H2 控制台暴露 | 🟡 | demo 档开放 `/h2-console` 便于联调 | 生产档未开放；建议生产 profile 显式关闭 |

---

## 🧪 测试覆盖 & CI 状态

| 项 | 状态 |
|----|------|
| 单元/集成测试 | 37 通过（目标：后端整体 ≥70%、Pricing/Order ≥90%，需接入 JaCoCo 出报告核验） |
| 端到端冒烟 | 12/12 通过（真实 HTTP + 真实助手领取） |
| 前端构建 | 通过 |
| 助手构建 | 本机无 .NET SDK，**未能编译验证**（代码按 `net8.0` 编写，需在有 SDK 环境执行 `dotnet build`） |
| CI | 流水线文件已就绪，尚未在 GitHub 上实际触发 |

---

## 🚦 Go / No-Go 决策

| 维度 | 结论 |
|------|------|
| 作为**开发基线**交付 | 🟢 **Go** |
| 作为**生产系统**上线 | 🔴 **No-Go**（原因：支付为 Mock、文件转换为占位实现、未做硬件联调与压测） |
| 遗留阻塞项 | 无（本期范围内） |

---

## ↩️ 回滚方案

- **代码**：骨架为新增目录，回滚即删除 `backend/`、`frontend/`、`print-agent/`、`tools/`、`deploy/`。
- **数据**：demo 档为 H2 内存库，进程退出即清空；生产库变更均以 `db/mysql/schema.sql` 幂等脚本承载（`CREATE TABLE IF NOT EXISTS`）。
- **服务**：systemd unit 支持 `systemctl stop print-backend` 快速回退；Nginx 配置版本化，`nginx -t` 通过后 reload。

---

## ✅ 行动清单（按优先级排序）

| # | 行动 | 负责角色 | 紧急度 | 预期完成 |
|---|------|---------|--------|---------|
| 1 | 在具备 .NET 8 SDK 的环境执行 `dotnet build` 验证助手，并做真机出纸联调 | 助手开发 + 测试 | P0 | 迭代 1 |
| 2 | 接入真实支付渠道（实现 `WechatPaymentGateway`/`AlipayPaymentGateway` + 密钥），按设计文档 §7.5.5 回归 | 后端 | P0 | 迭代 2 |
| 3 | 用统一转换引擎（LibreOffice headless 等）替换占位页数解析，并补样本集矩阵测试 | 后端 + 测试 | P0 | 迭代 2 |
| 4 | 接入 JaCoCo 覆盖率门禁（后端 ≥70%、Pricing/Order ≥90%）并接入 CI | 测试 | P1 | 迭代 2 |
| 5 | 补齐安全用例：`mock/confirm` 生产 profile 404 断言、越权下载、限频 | 测试 | P1 | 迭代 2 |
| 6 | staging 环境跑 k6 压测（200 并发下单，P95<500ms）并产出容量结论 | SRE + 测试 | P1 | 迭代 3 |
| 7 | 落地监控指标 + 巡检脚本 + 4 份 Runbook 到 staging | SRE | P2 | 迭代 3 |

---

## ⚠️ 待完善 / 已知局限

1. **本机无 .NET 8 SDK**：助手源码无法在本环境编译验证；已提供行为等价的 Node 仿真器完成端到端验证（非替代品，仅联调工具）。
2. **支付为 Mock**：真实微信/支付宝需商户资质；接口已按 `PaymentGateway` 抽象预留，切换时业务代码零改动。
3. **文件转换/页数解析为占位实现**：PDF 按页面对象计数、Office 按体积估算，生产需替换。
4. **Mock 支付状态存内存**：重启丢失（仅影响 Mock 渠道，不影响业务幂等）。
5. **覆盖率未出报告**：37 个用例通过，但尚未接入 JaCoCo 统计实际行/分支覆盖率。
6. **本机联调端口**：本环境存在 `SERVER__PORT` 环境变量覆盖 `server.port`，联调时通过 `--server.port=18080` 显式指定；用户本机无此变量，按 README 默认 8080 即可。

---

## 📚 数据来源 & 成员产出索引

| 产出 | 路径 |
|------|------|
| 系统详细设计文档 | `deliverables/engineering-assurance/system-design-自助付费打印系统-2026-10-10.md` |
| 接口契约 | `CONTRACT.md` |
| 后端服务 | `backend/`（54 个源文件 + 6 个测试类 + 3 档配置 + H2/MySQL 建表脚本） |
| 前端应用 | `frontend/`（Vue3 + Element Plus，7 个页面 + 管理端） |
| .NET 打印助手 | `print-agent/`（Worker Service，心跳/长轮询/打印/回传/spool） |
| Node 助手仿真器 | `tools/agent-sim/` |
| 端到端冒烟脚本 | `tools/smoke-test.mjs` |
| 部署与 CI | `deploy/`、`docker-compose.yml`、`.github/workflows/ci.yml` |
| 运行说明 | `README.md` |

---

> 本报告由工程保障团队 AI 协作生成，关键决策请由人类工程负责人复核。
