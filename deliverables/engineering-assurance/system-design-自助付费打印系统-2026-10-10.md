# 自助付费打印系统 · 系统详细设计文档

**日期**：2026-10-10
**工作流**：工作流 2 — 系统设计（System Design）
**参与成员**：Archi（系统架构师）· Rex（SRE 工程师）· Tessa（测试专家）· Docu（技术文档师）
**版本**：v1.0 ｜ 状态：Proposed ｜ 主理人：甄宇航（Zhen）· 工程督导

---

## 📌 TL;DR（执行摘要）

- **整体结论**：🟡 **有条件通过**。架构分层清晰，数据 / 接口 / 可靠性 / 测试四条线齐备，可直接指导开发；但存在 **4 项需产品拍板的口径**，且**支付渠道本期仅 Mock（接口已预留）**。
- **交付内容**：一套「Vue3 SPA + Java 17/Spring Boot 3 + .NET 8 打印助手 + MySQL 8/Redis 7 + Nginx/Linux」的自助付费打印系统详细设计，覆盖 需求 → 架构 → 数据模型 → API → 可运维性 → 测试策略 全链路。
- **核心机制**：手机号验证码登录；可配置计价（彩/黑、单双面，按实际数量 × 单价直接计算）；**可插拔支付网关**（`PaymentGateway` 抽象 + `MockPaymentGateway`）；打印助手主动拉取任务；订单 / 任务双状态机 + 幂等 CAS + 超时回收。
- **严重度分布**：🔴 严重 4 项 / 🟠 高 5 项 / 🟡 中 6 项（见「风险汇总」）。
- **阻塞判定**：**不阻塞设计定稿**；进入开发前必须闭环 —— ① 支付渠道接入方案 ② 0 页文件与双面奇数页计费口径 ③ OpenAPI 契约冻结。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| 整体评级 | 🟡 有条件通过 |
| 阻塞项数量 | 4（2 项产品口径 + 支付渠道未接 + 契约未冻结） |
| 关键行动项 | 12 条（P0×4 / P1×5 / P2×3，见文末行动清单） |
| 建议下一步 | 产品拍板口径 → 冻结 OpenAPI → 搭三端骨架 → 建 CI 门禁 |
| 本次交付形态 | 系统详细设计文档（对应交付物③）；可运行系统 / 源码 / 测试报告为后续阶段 |
| 技术栈符合性 | ✅ 完全符合约束（Vue / Java / .NET / Git / Nginx / Linux） |

---

## 🧭 文档导读

| 章节 | 内容 | 作者 |
|------|------|------|
| 第 1 章 | 需求分析与高层架构设计（含数据模型、API、ADR、时序、部署拓扑） | Archi |
| 第 2 章 | 可运维性与可靠性设计（部署 / 助手可靠性 / 支付一致性 / 监控 / 备份 / Runbook） | Rex |
| 第 3 章 | 测试策略与测试计划（分层 / 用例矩阵 / Mock 方案 / 覆盖率 / CI） | Tessa |
| 附录 A | 术语表、图表清单与写作规范 | Docu |

> 全文遵循「结论先行 → 表格化 → 图示」原则；术语与命名规范见附录 A。


---

## 第 1 章 需求分析与高层架构设计

> 文档版本：v1.0 ｜ 状态：Proposed ｜ 作者：系统架构师 阿奇（Archi）
> 范围：系统详细设计文档之「需求分析与高层架构」章节，不含实现代码。

---

### 0. 一句话概述

一套部署在商家门店（网吧 / 打印店 / 校园 / 政务大厅）的自助打印系统：公共电脑浏览器登录 → 上传文件 → 选打印参数 → 生成付费二维码 → 用户扫码支付 → 支付成功后由本地打印助手自动驱动打印机出纸，全程无需人工干预。

---

### 1. 需求分解与用例清单

#### 1.1 角色定义

| 角色 | 描述 | 主要诉求 |
|------|------|----------|
| 终端用户（End User） | 到店使用公共电脑打印的顾客 | 快速登录、上传、付费、拿到纸 |
| 商家/管理员（Admin） | 门店经营者 | 配置计价规则、管理终端与打印机、对账、看营收 |
| 打印助手（Print Agent） | 部署在终端机上的 .NET 常驻程序 | 拉取已支付任务、调用打印机、回传状态 |
| 支付网关（外部系统） | 微信/支付宝（本期 Mock/Sandbox） | 收款、回调通知 |

#### 1.2 功能需求分解（FR）

| 编号 | 需求 | 说明 | 优先级 |
|------|------|------|--------|
| FR-1 | 手机号验证码登录 | 短信验证码，无密码；验证码有效期 5 min，同号限频 | P0 |
| FR-2 | 文件上传 | Word/Excel/PDF/图片；大小/类型白名单校验 | P0 |
| FR-3 | 打印参数选择 | 份数、彩色/黑白、单面/双面、纸张规格(A4/A3)、页码范围 | P0 |
| FR-4 | 计价与下单 | 按规则实时计算金额，生成订单 | P0 |
| FR-5 | 付费二维码 | 展示微信/支付宝收款码（本期 Mock 二维码） | P0 |
| FR-6 | 支付状态确认 | 轮询/回调确认支付结果 | P0 |
| FR-7 | 自动打印 | 支付成功后打印助手自动执行打印 | P0 |
| FR-8 | 计价规则配置 | 按页/按张、彩色与黑白单价、双面折扣等，管理员可配 | P1 |
| FR-9 | 打印任务状态查询 | 用户可见「待支付/已支付/打印中/已完成/失败」 | P0 |
| FR-10 | 打印机/终端管理 | 注册终端、绑定打印机、在线状态 | P1 |
| FR-11 | 对账与流水 | 支付流水、订单流水，支持导出 | P2 |
| FR-12 | 异常处理 | 支付成功但打印失败 → 退款/补打工单 | P1 |

#### 1.3 非功能需求（NFR）

| 维度 | 目标值 | 说明 |
|------|--------|------|
| 性能 | 单机并发 ≤ 200；下单接口 P95 < 500 ms | 单店规模，非互联网级 |
| 可用性 | 营业时间可用性 ≥ 99.5% | 打印助手需容忍后端短暂不可用 |
| 一致性 | 订单-支付-打印任务状态最终一致 | 通过状态机 + 幂等 + 补偿 |
| 安全 | 文件隔离、防越权下载、支付回调验签 | 关键 |
| 可扩展 | 支付网关可插拔；支持多门店多终端 | 硬性约束 |
| 成本 | 单店可跑在 1 台 Linux 服务器 | 务实优先 |

#### 1.4 用例清单（Use Case）

**终端用户**
- UC-01 手机号登录 / 退出
- UC-02 上传打印文件
- UC-03 选择打印参数并试算价格
- UC-04 提交订单并获取付费二维码
- UC-05 扫码支付并等待打印
- UC-06 查询打印任务状态 / 重新打印

**商家/管理员**
- UC-07 配置计价规则
- UC-08 注册/停用终端与打印机
- UC-09 查看订单与营收对账
- UC-10 处理异常工单（退款/补打）

**打印助手**
- UC-11 心跳注册与在线状态上报
- UC-12 拉取/接收已支付打印任务
- UC-13 调用打印机执行打印
- UC-14 回传打印结果与失败原因

#### 1.5 用例图（文本示意）

```
        ┌──────────────┐          ┌──────────────────┐
        │  终端用户     │          │  商家/管理员      │
        └──────┬───────┘          └────────┬─────────┘
               │ UC01-06                   │ UC07-10
               ▼                           ▼
        ┌────────────────────────────────────────────┐
        │            自助付费打印系统（Backend）        │
        └────────────────────────────────────────────┘
               ▲                           ▲
               │ UC11-14                   │ 回调/对账
        ┌──────┴───────┐          ┌────────┴─────────┐
        │  打印助手     │          │  支付网关(外部)   │
        │ (.NET Agent) │          │ 微信/支付宝/Mock  │
        └──────────────┘          └──────────────────┘
```

---

### 2. 系统上下文图与组件架构

#### 2.1 系统上下文图（C4-L1）

```
 ┌──────────┐   HTTP(S)   ┌───────────────────────────────┐
 │ 终端用户  │────────────▶│                               │
 └──────────┘             │      自助付费打印系统          │
 ┌──────────┐   HTTP(S)   │   (Vue SPA + Java Backend)     │
 │ 管理员    │────────────▶│                               │
 └──────────┘             └───────┬───────────────┬───────┘
                                  │               │
              短信验证码           │               │ 支付下单/回调
           ┌──────────────┐       │               ▼
           │ 短信服务(可选)│◀──────┘        ┌──────────────┐
           └──────────────┘                │  支付网关     │
                                           │ 微信/支付宝   │
 ┌──────────────┐   HTTPS 轮询/WS           │  (Mock本期)  │
 │  打印助手     │◀─────────────────────────└──────────────┘
 │ (.NET Agent) │──────────────────────────▶  Backend
 └──────┬───────┘
        │ 调用打印
        ▼
 ┌──────────────┐
 │  打印机       │
 └──────────────┘
```

#### 2.2 组件架构图（C4-L2）

```
                         ┌─────────────────────────────┐
   浏览器(公共电脑)       │           Nginx (Linux)      │
   ┌──────────────┐       │  静态资源 / 反向代理 / TLS    │
   │  Vue3 SPA    │──────▶│  /            → SPA dist     │
   │  Vite+Pinia  │       │  /api/**      → Backend:8080 │
   └──────────────┘       │  /ws/**       → Backend(WS)  │
                          └──────────────┬──────────────┘
                                         │
                        ┌────────────────▼─────────────────────────┐
                        │        Java Backend (Spring Boot 3)        │
                        │ ┌──────────┬──────────┬──────────────────┐ │
                        │ │ Auth 模块 │ Order 模块│ Pricing 模块      │ │
                        │ ├──────────┼──────────┼──────────────────┤ │
                        │ │ File 模块 │ Payment  │ Task/Agent 模块   │ │
                        │ │          │ 模块(网关)│                   │ │
                        │ └──────────┴────┬─────┴──────────────────┘ │
                        │   PaymentGateway 抽象接口 (可插拔)          │
                        └───┬────────┬────────┬──────────┬───────────┘
                            │        │        │          │
                  ┌─────────▼─┐ ┌────▼────┐ ┌─▼───────┐ ┌▼──────────────┐
                  │ MySQL 8   │ │ Redis   │ │文件存储  │ │ 支付网关适配器  │
                  │ 业务数据   │ │会话/缓存│ │本地/MinIO│ │Mock/WeChat/Ali│
                  └───────────┘ │/任务队列│ └─────────┘ └───────────────┘
                                └─────────┘
                                         ▲
                                         │ 拉取任务(HTTP轮询)/心跳
                                ┌────────┴─────────┐
                                │ .NET 8 Worker     │
                                │ Service 打印助手   │
                                │  ├ 任务轮询器      │
                                │  ├ 文件下载器      │
                                │  ├ 打印调度器      │
                                │  └ 打印机驱动适配   │
                                └────────┬──────────┘
                                         ▼
                                  ┌──────────────┐
                                  │ 物理打印机    │
                                  └──────────────┘
```

#### 2.3 组件职责

| 组件 | 技术 | 职责 |
|------|------|------|
| Web SPA | Vue3 + Vite + Pinia | 登录、上传、参数选择、二维码展示、状态轮询 |
| API 网关层 | Nginx | 静态托管、反向代理、TLS、限流 |
| Auth 模块 | Spring Boot | 验证码、Token(JWT) 签发校验 |
| File 模块 | Spring Boot | 上传、格式校验、元数据、访问鉴权 |
| Pricing 模块 | Spring Boot | 规则解析与价格试算 |
| Order 模块 | Spring Boot | 订单状态机、幂等 |
| Payment 模块 | Spring Boot | PaymentGateway 抽象 + 适配器 + 回调处理 |
| Task/Agent 模块 | Spring Boot | 打印任务队列、终端心跳、任务下发与状态回传 |
| 打印助手 | .NET 8 Worker Service | 拉任务、下文件、调打印机、回传结果 |
| MySQL | 8.x | 业务持久化 |
| Redis | 7.x | 会话/验证码、任务队列、分布式锁、缓存 |
| 文件存储 | 本地卷 / MinIO | 上传文件与转换后文件 |

---

### 3. 技术栈落地选型与理由

| 层 | 选型 | 理由 |
|----|------|------|
| 前端框架 | **Vue 3 + Vite + Pinia + Vue Router + Element Plus** | 团队栈约束；Vite 秒级热更；Pinia 轻量状态；Element Plus 快速搭中后台风格界面 |
| HTTP 客户端 | Axios | 拦截器统一处理 Token、错误、loading |
| 后端语言 | **Java 17** | LTS，硬性约束，Spring Boot 3 基线 |
| 后端框架 | **Spring Boot 3.x** | 生态成熟、Web/WS/Security/Actuator 开箱即用 |
| ORM | **MyBatis-Plus** | 硬性约束；CRUD 高效、分页插件、代码生成，贴合国内团队习惯 |
| 鉴权 | **Spring Security + JWT** | 无状态、适配 SPA |
| 打印助手 | **.NET 8 Worker Service** | 硬性约束；常驻后台服务模型天然适配；跨平台可上 Linux/Windows 终端 |
| 数据库 | **MySQL 8** | 事务、行锁、成熟运维；单店规模足够 |
| 缓存/队列 | **Redis 7** | 验证码、会话、任务队列(List/Stream)、分布式锁 |
| 文件存储 | **本地卷（默认）/ MinIO（可选）** | 单店可零依赖；多店可切对象存储 |
| Web 服务器 | **Nginx** | 静态托管 + 反代 + TLS + 限流，硬性约束 |
| 部署 | **Linux (CentOS/Ubuntu) + systemd + Docker(可选)** | 硬性约束 |
| 代码管理 | **Git**（GitLab/Gitee/自建） | 硬性约束；分支模型见 §8 |

---

### 4. 核心数据模型

> 约定：主键 `bigint` 雪花 ID；所有表含 `created_at/updated_at`；金额字段用 `DECIMAL(10,2)` 或「分」整数；软删除 `deleted`。

#### 4.1 表设计

**t_user（用户）**
| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK | |
| phone | varchar(20) UNIQUE | 手机号 |
| nickname | varchar(64) | |
| status | tinyint | 1 正常 0 禁用 |
| last_login_at | datetime | |

**t_sms_code（验证码，可放 Redis）**
| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK | |
| phone | varchar(20) | |
| code | varchar(6) | |
| scene | varchar(20) | login |
| expire_at | datetime | |
| used | tinyint | |

**t_file（文件）**
| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK | |
| user_id | bigint | 上传者 |
| original_name | varchar(255) | 原始文件名 |
| storage_path | varchar(512) | 存储路径/对象 key |
| content_type | varchar(64) | MIME |
| size_bytes | bigint | |
| page_count | int | 解析页数（计价依据） |
| status | tinyint | 0 已上传 1 已转换 |

**t_price_rule（计价规则）**
| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK | |
| merchant_id | bigint | 商家 |
| name | varchar(64) | 规则名 |
| paper_size | varchar(8) | A4/A3 |
| color_price | decimal(10,2) | 彩色单价/页 |
| mono_price | decimal(10,2) | 黑白单价/页 |
| duplex_discount | decimal(5,2) | 双面折扣(如 0.90) |
| effective_from | datetime | |
| status | tinyint | 启用 |

**t_print_order（订单）**
| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK | |
| order_no | varchar(32) UNIQUE | 业务单号 |
| user_id | bigint | |
| file_id | bigint | |
| terminal_id | bigint | 下单终端 |
| params_json | json | 份数/色彩/单双面/纸张/页码 |
| page_count | int | 计费页数 |
| amount | decimal(10,2) | 应付金额 |
| pay_status | tinyint | 0 待支付 1 已支付 2 已关闭 3 已退款 |
| print_status | tinyint | 0 待打印 1 打印中 2 已完成 3 失败 |
| price_rule_id | bigint | 计价规则快照引用 |
| expire_at | datetime | 支付超时 |

**t_payment_record（支付流水）**
| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK | |
| order_no | varchar(32) | |
| gateway | varchar(20) | mock/wechat/alipay |
| trade_no | varchar(64) | 网关流水号 |
| amount | decimal(10,2) | |
| status | tinyint | 0 待支付 1 成功 2 失败 3 退款 |
| pay_time | datetime | |
| raw_notify | text | 原始回调（审计） |

**t_print_task（打印任务）**
| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK | |
| order_no | varchar(32) | |
| terminal_id | bigint | |
| printer_id | bigint | |
| file_path | varchar(512) | 助手可取的文件地址 |
| params_json | json | 打印参数 |
| status | tinyint | 0 待领取 1 已领取 2 打印中 3 成功 4 失败 5 重试中 |
| retry_count | int | |
| fail_reason | varchar(255) | |
| claimed_at | datetime | 领取时间(防死锁) |
| finished_at | datetime | |

**t_terminal（终端）**
| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK | |
| merchant_id | bigint | |
| code | varchar(32) UNIQUE | 终端编码 |
| name | varchar(64) | |
| agent_token | varchar(64) | 助手鉴权令牌 |
| online_status | tinyint | 0 离线 1 在线 |
| last_heartbeat | datetime | |

**t_printer（打印机）**
| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK | |
| terminal_id | bigint | |
| name | varchar(64) | 系统打印机名 |
| driver | varchar(64) | 驱动标识 |
| paper_size | varchar(8) | 默认纸张 |
| status | tinyint | 0 离线 1 就绪 2 故障 |

#### 4.2 ER 关系（简化）

```
t_user 1──* t_file 1──* t_print_order 1──* t_payment_record
                              │ 1
                              │
                              └──1 t_print_task *──1 t_terminal 1──* t_printer
t_price_rule *──1 t_print_order (快照引用)
```

#### 4.3 订单状态机

```
pay_status:  待支付 ──支付成功──▶ 已支付
                 │                  │
                 ├─超时/取消─▶ 已关闭  └─退款─▶ 已退款

print_status: 待打印 ──助手领取──▶ 打印中 ──成功──▶ 已完成
                                       └─失败─▶ 失败 ─(重试/人工)─▶ ...
```

---

### 5. API 设计

#### 5.1 通用约定
- 前缀 `/api/v1`；响应统一 `{ code, message, data }`；鉴权 `Authorization: Bearer <JWT>`。
- 助手侧接口使用 `X-Agent-Token` 头鉴权。

#### 5.2 REST 接口清单

**认证（Auth）**
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/auth/sms/send` | 发送验证码（限频） |
| POST | `/api/v1/auth/login` | 验证码登录，返回 JWT |
| POST | `/api/v1/auth/logout` | 退出 |
| GET | `/api/v1/auth/me` | 当前用户信息 |

**文件（File）**
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/files` | 上传文件（multipart），返回 fileId/pageCount |
| GET | `/api/v1/files/{id}` | 元数据（越权校验） |
| GET | `/api/v1/files/{id}/download` | 鉴权下载 |

**计价与订单（Pricing / Order）**
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/pricing/quote` | 传参数试算价格 |
| POST | `/api/v1/orders` | 创建订单，返回 orderNo+金额 |
| GET | `/api/v1/orders/{orderNo}` | 订单详情与状态 |
| GET | `/api/v1/orders` | 用户订单列表 |
| POST | `/api/v1/orders/{orderNo}/cancel` | 取消未支付订单 |

**支付（Payment）**
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/payments/{orderNo}/pay` | 发起支付，返回二维码内容 |
| GET | `/api/v1/payments/{orderNo}/status` | 轮询支付状态 |
| POST | `/api/v1/payments/notify/{gateway}` | 网关回调（验签，幂等） |
| POST | `/api/v1/payments/mock/confirm` | 【仅 Mock】模拟支付成功 |

**打印任务（Task / Agent）**
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/tasks/{orderNo}/status` | 用户查打印状态 |
| POST | `/api/v1/agent/heartbeat` | 助手心跳 |
| POST | `/api/v1/agent/tasks/claim` | 助手领取任务（按终端） |
| POST | `/api/v1/agent/tasks/{id}/report` | 回传打印结果 |
| GET | `/api/v1/agent/tasks/{id}/file` | 助手下载打印文件 |

**管理端（Admin）**
| 方法 | 路径 | 说明 |
|------|------|------|
| GET/POST/PUT | `/api/v1/admin/price-rules` | 计价规则 CRUD |
| GET/POST | `/api/v1/admin/terminals` | 终端管理 |
| GET/POST | `/api/v1/admin/printers` | 打印机管理 |
| GET | `/api/v1/admin/orders` | 订单/对账查询 |

#### 5.3 PaymentGateway 抽象接口定义（可插拔核心）

```java
/**
 * 支付网关抽象接口 —— 新增支付渠道只需实现本接口并注册为 Spring Bean。
 * 通过 @ConditionalOnProperty(gateway=xxx) 或 SPI 选择实现。
 */
public interface PaymentGateway {

    /** 网关标识：mock / wechat / alipay */
    String getGatewayCode();

    /**
     * 创建支付，返回二维码内容与网关流水号。
     * @param request 含 orderNo、amount、subject、expireAt
     */
    PrepayResult prepay(PrepayRequest request);

    /** 主动查询支付结果（用于轮询/对账兜底） */
    PayQueryResult query(String orderNo);

    /** 处理网关异步回调：验签 + 解析 + 幂等，返回处理结果 */
    NotifyResult handleNotify(NotifyRequest request);

    /** 退款（异常补打/取消场景） */
    RefundResult refund(RefundRequest request);
}

// —— DTO 片段 ——
record PrepayRequest(String orderNo, BigDecimal amount, String subject, LocalDateTime expireAt) {}
record PrepayResult(String gateway, String tradeNo, String qrCodeContent, LocalDateTime expireAt) {}
record PayQueryResult(String orderNo, PayState state, String tradeNo) {}  // state: PENDING/SUCCESS/FAILED
record NotifyRequest(String gateway, String rawBody, Map<String,String> headers) {}
record NotifyResult(boolean success, String orderNo, String tradeNo) {}
record RefundRequest(String orderNo, BigDecimal amount, String reason) {}
record RefundResult(boolean success, String refundNo) {}
```

**设计要点**
- 业务层只依赖 `PaymentGateway`，通过 `PaymentGatewayFactory` 按 `gatewayCode` 路由。
- 本期提供 `MockPaymentGateway`（本地生成二维码，`/payments/mock/confirm` 模拟成功回调），无需真实商户资质即可全链路跑通。
- 后续新增 `WechatPaymentGateway` / `AlipayPaymentGateway`，仅需实现接口 + 配置密钥，业务代码零改动。
- 回调统一走 `/payments/notify/{gateway}`，验签与幂等由各实现负责，落 `t_payment_record` 审计。

---

### 6. 架构决策记录（ADR）

#### ADR-001：打印任务下发采用「助手主动拉取」而非「后端推送」
**状态：** Accepted
| 维度 | 评估 |
|------|------|
| 复杂度 | 低（助手轮询 + Redis 队列） |
| 成本 | 低 |
| 可扩展性 | 中（终端多时轮询压力可控，可加长轮询/WS 优化） |

**背景**：终端在门店内网，后端在服务器，网络方向与 NAT 不确定。
**选项分析**
- A 后端主动推送（WebSocket/gRPC 双向）：实时性好，但需助手暴露可达端点或维持长连接，NAT/防火墙复杂。
- B 助手轮询拉取（HTTP + 长轮询）：实现简单、天然穿透 NAT、断线自动重连。
- C 消息队列（MQTT）：能力强但引入额外中间件，单店过重。
**决策**：选 B。助手每 2~3 s 拉取；空闲时用长轮询（hold 25 s）降低空转。后续实时性要求高可平滑升级为 WebSocket（保留同一任务模型）。
**影响**：打印实时性略降（秒级），换取部署鲁棒性与简单性。

#### ADR-002：支付网关采用抽象接口 + 可插拔适配器
**状态：** Accepted
| 维度 | 评估 |
|------|------|
| 复杂度 | 中 |
| 成本 | 低（本期 Mock 零成本） |
| 可扩展性 | 高 |

**背景**：本期无真实商户资质，但后续需接微信/支付宝。
**选项**：A 直接写死微信 SDK；B 抽象 `PaymentGateway` 接口 + 工厂路由。
**决策**：选 B。业务只依赖接口，Mock 实现本期跑通，真实渠道后续实现同一接口。
**影响**：新增少量抽象层代码，换取渠道零耦合、可测试、可无缝替换。

#### ADR-003：计价采用「规则快照」而非实时关联
**状态：** Accepted
| 维度 | 评估 |
|------|------|
| 复杂度 | 低 |
| 成本 | 低 |
| 可扩展性 | 高（规则变更不影响历史订单） |

**背景**：管理员可改价，但历史订单金额不可变。
**选项**：A 订单仅存 `rule_id` 实时关联；B 下单时快照规则与金额。
**决策**：选 B。订单保存 `amount` + `params_json` + 规则快照，规则后续改动不回填历史。
**影响**：可审计、可对账；代价是少量冗余字段。

#### ADR-004：文件存储默认本地卷，可选 MinIO
**状态：** Accepted
| 维度 | 评估 |
|------|------|
| 复杂度 | 低（本地）/ 中（MinIO） |
| 成本 | 本地零成本 |
| 可扩展性 | 本地受限，MinIO 可横向 |

**背景**：单店文件量不大；多店/多节点需共享存储。
**决策**：抽象 `FileStorage` 接口，默认 `LocalFileStorage`（挂载卷），多店部署切换 `MinioFileStorage`。
**影响**：单机部署零依赖，规模化可平滑切换。

#### ADR-005：登录采用短信验证码 + JWT，会话辅助存 Redis
**状态：** Accepted
| 维度 | 评估 |
|------|------|
| 复杂度 | 低 |
| 成本 | 低 |
| 安全 | 中高（限频 + 有效期 + 黑名单） |

**背景**：公共电脑，用户不注册密码。
**选项**：A 密码登录；B 验证码登录 + JWT；C Session 服务端存储。
**决策**：选 B。JWT 无状态适配 SPA；验证码存 Redis 设 TTL；退出/风控用 Redis 黑名单。
**影响**：需处理短信通道（本期可日志/Mock 通道），公共电脑需提供「用完即退」引导。

#### ADR-006：打印任务状态回传采用「状态机 + 超时回收 + 幂等」
**状态：** Accepted
| 维度 | 评估 |
|------|------|
| 复杂度 | 中 |
| 可靠性 | 高 |
| 可维护性 | 高 |

**背景**：助手可能崩溃/断电，导致任务卡在「打印中」。
**选项**：A 简单状态字段；B 状态机 + `claimed_at` 超时回收 + 重试计数 + 幂等键。
**决策**：选 B。助手领取即置「打印中」并记 `claimed_at`，超时（如 2 min）未回传则回收为「待领取」重试；同一订单回传幂等。
**影响**：避免任务死锁，需定时任务兜底扫描。

---

### 7. 关键流程时序

#### 7.1 登录

```
用户         SPA          Backend         Redis/短信
 │  输手机号  │              │                │
 │──────────▶│ POST sms/send│                │
 │           │─────────────▶│ 校验限频        │
 │           │              │───────────────▶│ 存 code(TTL 5m)
 │           │              │  发送验证码(短信/Mock日志)
 │  输验证码  │              │                │
 │──────────▶│ POST login   │                │
 │           │─────────────▶│ 校验 code      │
 │           │              │◀──────────────▶│
 │           │  返回 JWT    │                │
 │◀──────────│◀─────────────│                │
```

#### 7.2 上传 → 下单 → 支付 → 打印（全链路）

```
用户    SPA       Backend        Redis/MySQL      支付网关     打印助手      打印机
 │上传文件│           │              │               │            │            │
 │──────▶│ POST /files│              │               │            │            │
 │       │──────────▶│ 存储+解析页数 │              │            │            │
 │       │◀──────────│ fileId/pageCount             │            │            │
 │选参数  │           │              │               │            │            │
 │──────▶│ quote/orders             │               │            │            │
 │       │──────────▶│ 计价+建订单   │               │            │            │
 │       │◀──orderNo+amount          │               │            │            │
 │       │ POST pay  │              │               │            │            │
 │       │──────────▶│ prepay()      │               │            │            │
 │       │           │──────────────┼──────────────▶│            │            │
 │       │           │◀──qrCode─────┼───────────────│            │            │
 │◀─显示二维码────────│              │               │            │            │
 │扫码支付 │           │              │               │            │            │
 │───────┼───────────┼──────────────┼──────────────▶│            │            │
 │       │ 轮询status │              │               │            │            │
 │       │──────────▶│              │               │            │            │
 │       │           │◀─notify(验签幂等)─────────────│            │            │
 │       │           │ 更新pay=已支付, 投递打印任务→Redis Queue │            │
 │       │           │              │               │            │            │
 │       │           │◀─────────────助手claim────────┼────────────│            │
 │       │           │─────任务+文件地址─────────────┼───────────▶│            │
 │       │           │              │               │            │ 调打印机    │
 │       │           │              │               │            │───────────▶│
 │       │           │◀─────────────report(成功/失败)┼────────────│            │
 │       │           │ 更新print=已完成              │            │            │
 │◀─显示完成/取纸─────│              │               │            │            │
```

#### 7.3 打印助手拉取/回传任务（含容错）

```
打印助手                         Backend                    Redis
 │ 心跳(每30s) ───────────────▶ │ 更新 online_status         │
 │ 轮询 claim(长轮询25s) ──────▶ │ 从队列取终端待打印任务      │◀─ LPOP/BLPOP
 │ ◀──── 任务+fileUrl ────────── │ 置 status=打印中,claimed_at │
 │ 下载文件 ───────────────────▶ │                            │
 │ 调打印机执行                   │                            │
 │ report(成功) ───────────────▶ │ 置 status=成功(幂等)        │
 │                              │                            │
 │  ── 若崩溃/超时 ──            │                            │
 │                              │ 定时任务扫描 claimed_at 超时 │
 │                              │ → 置回 待领取/重试(≤N)      │
```

---

### 8. 部署拓扑

#### 8.1 单店拓扑

```
                         ┌─────────────────────────────┐
   互联网/内网用户 ──────▶│        Nginx (443/80)        │
                         │  TLS + 静态SPA + /api 反代    │
                         └──────────────┬──────────────┘
                                        │ 127.0.0.1:8080
                         ┌──────────────▼──────────────┐
                         │  Java Backend (systemd)      │
                         │  spring-boot jar :8080       │
                         └───┬───────────────┬─────────┘
                             │               │
                   ┌─────────▼──┐      ┌─────▼──────┐      ┌──────────────┐
                   │  MySQL 8   │      │  Redis 7   │      │ 文件卷 /data │
                   │  :3306     │      │  :6379     │      │ uploads/     │
                   └────────────┘      └────────────┘      └──────────────┘

   ── 门店内网（公共电脑终端）──
   ┌──────────────────────────────────────────────────────┐
   │ 终端机 Windows/Linux                                   │
   │  ├ 浏览器 → 访问 Nginx 上的 SPA                         │
   │  ├ .NET 8 Worker Service 打印助手 (systemd/Windows服务) │
   │  └ USB/网络 打印机                                      │
   └──────────────────────────────────────────────────────┘
```

#### 8.2 部署要点
- **Nginx**：托管 `dist/`；`/api`、`/ws` 反代后端；开启 gzip、TLS、上传大小限制（`client_max_body_size 50m`）、限流。
- **后端**：`systemd` 管理 jar；配置外置 `application-prod.yml`（数据库、Redis、网关、文件路径）。
- **打印助手**：Windows 用服务方式、Linux 用 `systemd`；配置项含 `backendUrl`、`terminalCode`、`agentToken`、轮询间隔。
- **MySQL/Redis**：单机部署；数据卷持久化；定时备份。
- **代码管理**：Git 主干 `main` + 特性分支 `feature/*` + `release/*`；PR 评审；后端/前端/助手可分仓或 monorepo。
- **可观测**：Spring Actuator + 日志文件；助手本地日志轮转。

---

### 9. 风险与权衡

| 编号 | 风险 | 影响 | 缓解措施 |
|------|------|------|----------|
| R-1 | 支付成功但打印失败（缺纸/卡纸/断电） | 用户已付费未出纸，投诉 | 状态机 + 失败重试 + 退款/补打工单 + 打印机状态检测 |
| R-2 | 助手崩溃导致任务卡死 | 订单不完成 | `claimed_at` 超时回收 + 定时兜底扫描 + 重试上限 |
| R-3 | 支付回调丢失/重复 | 状态不一致 | 主动轮询兜底 + 回调幂等（order_no 唯一）+ 对账任务 |
| R-4 | 公共电脑隐私泄露（前用户文件/会话） | 隐私事故 | 登出清 Token + 文件访问鉴权 + 前端用完即清 + 会话超时 |
| R-5 | 文件格式/页数解析不准 | 计价偏差 | 统一转换（如 LibreOffice/PDF 工具）+ 上传后确认页数 + 兜底按大小估算 |
| R-6 | 短信通道成本/可达性 | 登录失败 | 通道可配置 + Mock 日志通道 + 备用通道 |
| R-7 | 单机部署单点故障 | 全店不可用 | 数据备份 + 快速重启脚本；规模化再上集群 |
| R-8 | 二维码被他人扫付/误付 | 资金纠纷 | 订单与终端/用户绑定 + 金额校验 + 支付后归属校验 |
| R-9 | 恶意大文件/超量上传 | 存储与带宽 | 大小/类型白名单 + 限流 + 定期清理 |
| R-10 | 多店扩展时数据隔离 | 越权 | 所有表带 `merchant_id`，接口层强制租户过滤 |

#### 关键权衡取舍
- **实时性 vs 简单性**：选轮询换取部署鲁棒性（ADR-001）。
- **抽象度 vs 交付速度**：仅对支付/存储做抽象，其余保持简单（务实优先）。
- **单机 vs 分布式**：本期单店单机，预留多店租户与对象存储切换点。

---

### 附录：模块与目录建议（供后续详细设计）

```
self-service-print/
├─ frontend/            # Vue3 + Vite + Pinia
│  └─ src/{views,components,api,stores,router}
├─ backend/             # Java 17 + Spring Boot 3 + MyBatis-Plus
│  └─ src/main/java/.../{auth,file,pricing,order,payment,task,admin}
│     └─ payment/gateway/{PaymentGateway,MockPaymentGateway,...}
├─ print-agent/         # .NET 8 Worker Service
│  └─ src/{Polling,Downloader,Printer,Reporter}
├─ deploy/              # Nginx conf, systemd units, docker-compose(可选)
└─ docs/                # 设计文档
```


---

## 第 2 章 可运维性与可靠性设计

> 文档版本：v1.0 ｜ 状态：Proposed ｜ 作者：SRE 工程师 雷克斯（Rex）
> 范围：系统详细设计文档之「可运维性与可靠性」章节，不含实现代码。
> 上游依赖：《需求分析与高层架构设计》（第 1 章）。本章严格遵循既定技术栈与架构，不改动组件边界。

---

### 0. 设计目标与原则

| 目标 | 量化指标 | 落地手段 |
|------|----------|----------|
| 营业时段可用性 | ≥ 99.5%（单店单机） | systemd 守护 + 快速重启 + 降级 |
| 打印任务最终一致 | 已付费订单 100% 收敛（成功或退款） | 状态机 + 超时回收 + 重试上限 + 人工工单兜底 |
| 支付资金零错账 | 日对账差异 = 0（T+1） | 验签 + 幂等 + 轮询兜底 + 对账 |
| 故障可诊断 | MTTR < 15 min | 结构化日志 + traceId + Runbook |
| 数据可恢复 | RPO ≤ 1 天（binlog 级可达分钟），RTO ≤ 1 h | 每日全量 + binlog + 恢复演练 |

**运维原则**
- **务实优先**：单店单机，不引入 Prometheus/K8s 等重型依赖；指标以「日志 + 轻量暴露端点 + 脚本巡检」实现，规模化再升级。
- **失败可自愈**：助手、后端、任务状态均设计为「崩溃后可自动恢复」，减少人工介入。
- **资金谨慎**：支付路径默认「宁可重试查询，不可重复出纸/重复扣款」，所有写操作幂等。
- **无责复盘**：所有故障按时间线 + 5 Why 复盘，聚焦系统与流程，不追责个人。

---

### 1. 部署方案

#### 1.1 部署拓扑（复核第 1 章 §8，补充运维细节）

```
                         ┌───────────────────────────────────────┐
   用户/终端浏览器 ─────▶│  Nginx 443/80  (systemd: nginx)         │
                         │  TLS终止 / 静态SPA / /api反代 / 限流     │
                         └────────────────┬──────────────────────┘
                                          │ 127.0.0.1:8080
                         ┌────────────────▼──────────────────────┐
                         │  Java Backend (systemd: print-backend) │
                         │  -Xmx1g · Spring Boot 3 jar            │
                         └───┬────────────┬────────────┬──────────┘
                             │            │            │
                   ┌─────────▼──┐  ┌──────▼─────┐  ┌───▼────────────┐
                   │ MySQL 8    │  │ Redis 7    │  │ /data/uploads  │
                   │ :3306      │  │ :6379      │  │ (本地卷 200G)   │
                   │ (仅本机)    │  │ (仅本机)    │  └────────────────┘
                   └────────────┘  └────────────┘
   ── 门店内网终端 ──
   ┌────────────────────────────────────────────────────────────┐
   │ 终端机: 浏览器 + .NET 打印助手(print-agent) + 物理打印机      │
   └────────────────────────────────────────────────────────────┘
```

**安全边界**：MySQL/Redis/Backend 只监听 `127.0.0.1`，不对外暴露端口；仅 Nginx 监听公网 443/80。所有运维 SSH 走密钥登录 + 非 22 端口 + fail2ban。

#### 1.2 Nginx 关键配置要点

配置落于 `/etc/nginx/conf.d/print.conf`，核心片段（要点说明，非完整文件）：

```nginx
# —— 限流区定义（http 块）——
limit_req_zone  $binary_remote_addr zone=api_zone:10m  rate=20r/s;
limit_req_zone  $binary_remote_addr zone=sms_zone:10m  rate=1r/m;   # 验证码严格限频
limit_req_zone  $binary_remote_addr zone=upload_zone:10m rate=30r/m;
limit_conn_zone $binary_remote_addr zone=conn_zone:10m;

server {
    listen 443 ssl http2;
    server_name print.example.com;

    # —— TLS ——
    ssl_certificate     /etc/letsencrypt/live/print.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/print.example.com/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:...;
    ssl_prefer_server_ciphers on;
    ssl_session_cache   shared:SSL:10m;
    ssl_session_timeout 1h;
    ssl_stapling on;  ssl_stapling_verify on;
    add_header Strict-Transport-Security "max-age=31536000" always;

    # —— 全局请求体 & 超时 ——
    client_max_body_size 50m;          # 与后端 multipart 上限一致
    client_body_timeout  60s;
    client_conn_zone     10m;

    # —— 静态 SPA ——
    root /var/www/print/dist;
    location / {
        try_files $uri $uri/ /index.html;   # SPA history 路由回退
    }
    # 带 hash 的静态资源长缓存；index.html 不缓存
    location ~* \.(js|css|woff2?|png|jpg|svg)$ {
        expires 30d; add_header Cache-Control "public, immutable";
    }
    location = /index.html { add_header Cache-Control "no-cache, must-revalidate"; }

    gzip on; gzip_types text/css application/javascript application/json image/svg+xml;
    gzip_min_length 1024;

    # —— 上传接口（放宽 body 与超时）——
    location /api/v1/files {
        limit_req zone=upload_zone burst=10 nodelay;
        proxy_pass http://127.0.0.1:8080;
        proxy_read_timeout 120s;
        proxy_request_buffering off;     # 大文件边收边传，降低内存占用
        include /etc/nginx/conf.d/proxy_common.conf;
    }

    # —— 验证码接口（严格限频，防刷）——
    location /api/v1/auth/sms/send {
        limit_req zone=sms_zone burst=2 nodelay;
        proxy_pass http://127.0.0.1:8080;
        include /etc/nginx/conf.d/proxy_common.conf;
    }

    # —— 通用 API 反代 ——
    location /api/ {
        limit_req  zone=api_zone burst=40 nodelay;
        limit_conn conn_zone 50;
        proxy_pass http://127.0.0.1:8080;
        proxy_read_timeout 65s;          # > 助手 claim 长轮询 25s，留足余量
        proxy_send_timeout 30s;
        include /etc/nginx/conf.d/proxy_common.conf;
    }

    # —— WebSocket 升级（保留给未来实时推送）——
    location /ws/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 300s;         # WS 长连接，勿用默认 60s
        proxy_send_timeout 300s;
        include /etc/nginx/conf.d/proxy_common.conf;
    }
}

# 80 端口仅做跳转 + ACME 校验
server {
    listen 80;
    server_name print.example.com;
    location /.well-known/acme-challenge/ { root /var/www/certbot; }
    location / { return 301 https://$host$request_uri; }
}
```

`proxy_common.conf`（复用片段）：
```nginx
proxy_set_header Host              $host;
proxy_set_header X-Real-IP         $remote_addr;
proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Trace-Id        $request_id;   # 生成 traceId 透传后端
proxy_http_version 1.1;
proxy_buffering on;
```

**配置要点小结**
| 项 | 建议值 | 原因 |
|----|--------|------|
| `client_max_body_size` | `50m` | 覆盖最大打印文件；与后端 `spring.servlet.multipart.max-file-size` 对齐 |
| 验证码限流 | `1r/m` + burst 2 | 防短信轰炸与成本失控 |
| 通用 API 限流 | `20r/s` | 单店并发 ≤ 200 足够，超限返回 429 |
| claim 长轮询超时 | `proxy_read_timeout 65s` | 必须 > 助手长轮询 25s，否则连接被 Nginx 提前断开 |
| WS 超时 | `300s` | 避免空闲长连接被回收 |
| TLS | 1.2/1.3 + HSTS | 支付场景合规底线 |

#### 1.3 systemd 服务定义要点

**后端 `/etc/systemd/system/print-backend.service`**
```ini
[Unit]
Description=Self-service Print Backend
After=network-online.target mysql.service redis.service
Wants=network-online.target
Requires=mysql.service redis.service

[Service]
Type=simple
User=print
Group=print
WorkingDirectory=/opt/print/backend
EnvironmentFile=/etc/print/backend.env      # 密钥外置，勿写死在 unit
ExecStart=/usr/bin/java -Xms512m -Xmx1024m -XX:+UseG1GC \
          -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/log/print/ \
          -Dspring.profiles.active=prod \
          -jar /opt/print/backend/app.jar
SuccessExitStatus=143                         # SIGTERM 正常退出
Restart=always
RestartSec=5
TimeoutStopSec=30                             # 给足优雅停机时间
LimitNOFILE=65535
# 日志交 journald
StandardOutput=journal
StandardError=journal
SyslogIdentifier=print-backend

[Install]
WantedBy=multi-user.target
```

**打印助手（Linux 终端）`/etc/systemd/system/print-agent.service`**
```ini
[Unit]
Description=Self-service Print Agent
After=network-online.target
Wants=network-online.target

[Service]
Type=notify                                  # .NET Worker 用 systemd notify 上报就绪
User=printagent
WorkingDirectory=/opt/print/agent
EnvironmentFile=/etc/print/agent.env
ExecStart=/opt/print/agent/PrintAgent
Restart=always
RestartSec=10
WatchdogSec=90                               # 助手需定期 sd_notify，卡死则被重启
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
```
> Windows 终端：将助手注册为 Windows Service（`sc create` / `--install`），在「服务属性 → 恢复」中设置「第一次/第二次/后续失败」均为「重新启动服务」，重置计数 1 天。`WatchdogSec` 由 Windows SCM 的失败恢复策略等价替代。

**关键 systemd 运维命令（写入 Runbook）**
```bash
systemctl daemon-reload
systemctl restart print-backend && journalctl -u print-backend -f
systemctl status print-agent
journalctl -u print-backend --since "30 min ago" -p err
```

#### 1.4 环境变量 / 配置清单

**后端（`/etc/print/backend.env`，key=value）**

| Key | 默认值 | 取值范围 | 生效方式 | 说明 |
|-----|--------|----------|----------|------|
| `SPRING_PROFILES_ACTIVE` | `prod` | prod/dev | 重启 | 环境 |
| `SERVER_PORT` | `8080` | 1024-65535 | 重启 | 仅监听 127.0.0.1 |
| `MYSQL_HOST` / `MYSQL_PORT` | `127.0.0.1` / `3306` | — | 重启 | |
| `MYSQL_DB` | `print_db` | — | 重启 | |
| `MYSQL_USER` / `MYSQL_PASSWORD` | — | — | 重启 | 密码入密钥库 |
| `MYSQL_POOL_MAX` | `20` | 5-50 | 重启 | HikariCP 最大连接 |
| `REDIS_HOST` / `REDIS_PORT` | `127.0.0.1` / `6379` | — | 重启 | |
| `REDIS_PASSWORD` | — | — | 重启 | 必填 |
| `JWT_SECRET` | — | ≥32 字节随机 | 重启 | 泄露需轮换 |
| `JWT_EXPIRE_MINUTES` | `120` | 15-1440 | 重启 | Token 有效期 |
| `SMS_PROVIDER` | `mock` | mock/aliyun/tencent | 重启 | 通道选择 |
| `SMS_CODE_TTL_SECONDS` | `300` | 60-600 | 重启 | 验证码有效期 |
| `SMS_SEND_COOLDOWN_SECONDS` | `60` | 30-300 | 重启 | 同号发送冷却 |
| `PAYMENT_GATEWAY` | `mock` | mock/wechat/alipay | 重启 | 渠道选择 |
| `ORDER_PAY_EXPIRE_MINUTES` | `15` | 5-60 | 重启 | 未支付订单超时关闭 |
| `FILE_STORAGE_TYPE` | `local` | local/minio | 重启 | 存储后端 |
| `FILE_ROOT` | `/data/uploads` | — | 重启 | 本地卷路径 |
| `FILE_MAX_SIZE_MB` | `50` | 1-100 | 重启 | 与 Nginx 对齐 |
| `FILE_RETENTION_DAYS` | `30` | 7-365 | 热加载 | 文件保留天数 |
| `TASK_CLAIM_TIMEOUT_SECONDS` | `120` | 60-600 | 热加载 | 打印中任务超时回收阈值 |
| `TASK_MAX_RETRY` | `3` | 1-5 | 热加载 | 任务最大重试次数 |
| `AGENT_OFFLINE_SECONDS` | `90` | 60-300 | 热加载 | 超时未心跳判离线 |
| `RECONCILE_CRON` | `0 30 3 * * ?` | cron | 热加载 | 每日对账任务 |

> 支持「热加载」的项建议由配置中心/数据库配置表驱动（改动免重启）；其余需重启。密钥类（`MYSQL_PASSWORD`/`REDIS_PASSWORD`/`JWT_SECRET`/渠道密钥）统一从 `/etc/print/backend.env`（权限 `600`，属主 `print`）加载，**不入 Git**。

**打印助手（`/etc/print/agent.env`）**

| Key | 默认值 | 取值范围 | 说明 |
|-----|--------|----------|------|
| `PRINT_BACKEND_URL` | `https://print.example.com` | — | 后端基址 |
| `PRINT_TERMINAL_CODE` | — | 终端编码 | 唯一标识 |
| `PRINT_AGENT_TOKEN` | — | ≥32 字符 | `X-Agent-Token` |
| `PRINT_POLL_INTERVAL_SECONDS` | `3` | 1-10 | 空闲轮询间隔 |
| `PRINT_LONGPOLL_SECONDS` | `25` | 10-50 | 长轮询 hold 时长（须 < Nginx 65s） |
| `PRINT_HEARTBEAT_SECONDS` | `30` | 10-60 | 心跳间隔 |
| `PRINT_MAX_RETRY` | `3` | 1-5 | 本地瞬时重试次数 |
| `PRINT_RETRY_BACKOFF_SECONDS` | `3,6,12` | — | 重试退避序列 |
| `PRINT_SPOOL_DIR` | `/var/lib/print-agent/spool` | — | 离线待上报结果本地持久化 |
| `PRINT_LOG_DIR` | `/var/log/print-agent` | — | 日志目录 |

---

### 2. 打印助手（.NET 8 常驻服务）可靠性设计

打印助手是「最后一公里」的关键，直接决定出纸成败。设计遵循：**本地可自愈、断网不丢单、崩溃可恢复**。

#### 2.1 自启与守护

- **进程守护**：Linux 用 systemd（`Restart=always` + `WatchdogSec=90`）；Windows 用 SCM 失败恢复策略。进程异常退出 10s 内自动拉起。
- **开机自启**：`systemctl enable print-agent` / Windows Service 自动启动。
- **启动自检**（Worker `ExecuteAsync` 启动阶段，失败不阻断启动、仅告警）：
  1. 校验配置完整性（BackendUrl / TerminalCode / AgentToken 非空）；
  2. 探测后端 `/api/v1/agent/heartbeat` 可达性；
  3. 枚举系统打印机，确认配置的 `printer_id` 存在且就绪；
  4. 恢复 `spool` 目录中未上报的离线结果，优先补报。

#### 2.2 心跳与在线状态

- 助手每 **30s** 调用 `POST /agent/heartbeat`，上报 `terminalCode`、打印机列表与状态、本地队列深度、版本号。
- 后端维护 `t_terminal.last_heartbeat`；巡检任务每 30s 扫描，`now - last_heartbeat > AGENT_OFFLINE_SECONDS(90s)` 则置 `online_status=离线`。
- 心跳失败不阻塞主循环：连续失败进入断线重连（见 2.3），恢复后立即补发一次心跳。
- 心跳携带 `agentVersion` 与 `printerStatus[]`，供管理端展示「终端在线 + 打印机就绪」两个维度。

#### 2.3 断线重连（指数退避 + 抖动）

- 网络错误（超时 / 5xx / 连接拒绝）触发重连，退避序列：
  `1s → 2s → 4s → 8s → 16s → 32s → 60s(封顶)`，叠加 ±20% 抖动（避免多终端同时重连打爆后端）。
- 任一次成功请求立即重置退避计数。
- **区分错误类型**：
  - 4xx（如 401 token 失效）：不重试，记录错误并告警（需人工换 token）；
  - 5xx / 超时 / 网络不可达：按退避重试；
  - 后端返回 429：尊重 `Retry-After`。
- **断网期间**：已完成打印但未上报的结果写入本地 `spool`（JSON 文件 + 原子 rename），恢复后按时间顺序补报（补报幂等，见 §3）。

#### 2.4 任务超时回收

- 助手 `claim` 成功即被后端置 `打印中` 并记 `claimed_at`。
- 后端定时扫描：`status=打印中 且 now - claimed_at > TASK_CLAIM_TIMEOUT_SECONDS(120s)` → 回收为 `待领取`，`retry_count++`。
- 回收时**幂等保护**：若助手随后补报成功，以「成功」为准（成功状态不可被回收覆盖），仅当仍为「打印中」才回收。
- 超时阈值需 > 单次打印最长耗时（含文件下载 + 打印）。若门店存在大文件/大幅面，可将 `TASK_CLAIM_TIMEOUT_SECONDS` 上调至 300s。

#### 2.5 失败重试上限与退避

分两层重试，职责清晰：

| 层级 | 触发 | 策略 | 上限 |
|------|------|------|------|
| 助手本地重试 | 打印机瞬时错误（spooler busy、临时离线） | 立即重试，退避 `3s/6s/12s` | 3 次 |
| 后端任务级重试 | 任务被回收 / 助手明确上报失败 | 重新入队，换终端或稍后重试 | `TASK_MAX_RETRY=3` |

- 本地重试耗尽后，助手 `report(failed, reason)`，后端据 `retry_count` 决定：`< max` 重新入队；`≥ max` 置 `失败` 并**生成异常工单**（触发退款/人工补打，见 §3.5）。
- **不可重试错误**：文件损坏、格式不支持、打印机型号不匹配 → 直接上报失败，不占用重试次数，转人工工单。

#### 2.6 打印机离线检测

- 助手每 **30s** 枚举系统打印机状态（就绪 / 缺纸 / 缺墨 / 卡纸 / 离线 / 队列忙），随心跳上报。
- 后端在**派发任务前**校验目标打印机状态：非就绪则不派发，避免任务空转消耗重试次数。
- 打印机离线时：
  - 新任务**保持待领取**，不消耗重试；
  - 若离线持续 > 5 min，触发告警（管理端 + 门店联系人）。
- 出纸前助手做**二次就绪检查**，减少「已领取但打不出」的假失败。

#### 2.7 日志与本地诊断

- **日志库**：Serilog，结构化 JSON 输出到滚动文件 + 控制台（交 journald）。
- **滚动策略**：按天 + 单文件 50MB，保留 7 天，总占用封顶 500MB。
- **日志字段**：`timestamp, level, traceId, terminalCode, taskId, orderNo, printer, event, message, durationMs`。
- **本地诊断**：
  - `--selfcheck` 命令：输出后端连通性、打印机枚举、spool 待补报数量、配置校验结果，供现场排障；
  - spool 目录同时是「断网缓冲」，运维可通过 `ls spool/` 快速判断是否有积压；
  - 关键事件（领取/开始打印/成功/失败/重连）单独打点，便于 grep 定位。

---

### 3. 支付一致性与幂等

#### 3.1 回调验签

- 回调入口统一 `POST /api/v1/payments/notify/{gateway}`，由对应 `PaymentGateway.handleNotify()` 实现：
  1. **验签**：按渠道规则（微信 V3 平台证书 / 支付宝 RSA2）校验签名，失败直接返回失败并记录，**不更新任何业务状态**；
  2. **防重放**：校验时间戳窗口（±5 min）+ nonce 去重；
  3. **解析**：提取 `orderNo`、`tradeNo`、`amount`、`payTime`；
  4. **金额校验**：回调金额必须等于订单金额，不符则拒绝并告警（防篡改）；
  5. 原始报文落 `t_payment_record.raw_notify`（审计）。
- Mock 网关：本地签名占位，`/payments/mock/confirm` 走同一套幂等逻辑，确保沙箱与真实渠道行为一致。

#### 3.2 幂等键设计

| 层级 | 幂等键 | 约束 | 作用 |
|------|--------|------|------|
| 订单 | `t_print_order.order_no` | 唯一索引 | 防重复下单 |
| 支付流水 | `(gateway, trade_no)` | 唯一索引 | 防重复回调 |
| 回调处理 | `order_no` + 状态机 CAS | `UPDATE ... WHERE pay_status=0` | 同一订单多次回调只生效一次 |
| 打印任务 | `t_print_task.order_no` | 唯一（一单一任务） | 防重复投递 |
| 结果上报 | `taskId` + 状态机 CAS | 仅「打印中→成功/失败」 | 重复上报只生效一次 |

- **核心手法**：状态流转用**条件更新（CAS）**——`UPDATE t_print_order SET pay_status=1 WHERE order_no=? AND pay_status=0`，影响行数=1 才算本次生效，=0 视为重复/非法流转，直接返回成功（幂等响应）。
- 回调与轮询**可能并发**：二者都走同一状态机 CAS + 唯一约束，天然互斥，不会重复置状态或重复投递任务。

#### 3.3 轮询兜底

三层兜底，确保「回调丢失」不致订单卡死：

1. **前端轮询**：SPA 展示二维码后每 2~3s 调 `GET /payments/{orderNo}/status`。该接口在本地状态为「待支付」时，**触发一次网关主动查询** `PaymentGateway.query()`，命中成功即就地补状态。
2. **后端定时兜底**：每 1 min 扫描「待支付 且 `expire_at` 已过」订单，主动 `query()` 网关：若渠道已成功则补记成功（补偿回调丢失）；否则关闭订单。
3. **对账兜底**：T+1 对账发现「渠道成功本地无」的订单，人工/自动补状态（见 3.4）。

#### 3.4 对账方案（订单 vs 渠道流水）

- **频率**：每日 `RECONCILE_CRON`（默认 03:30）执行 T-1 对账。
- **数据源**：本地 `t_payment_record`（status=成功）+ 渠道对账文件/查询接口流水。
- **比对维度**：`trade_no`、`order_no`、`amount`、`pay_time`。
- **差异分类与处置**：

| 差异类型 | 含义 | 处置 |
|----------|------|------|
| 平账 | 双方一致 | — |
| 渠道有、本地无 | 回调丢失 | 自动补记成功并投递打印任务；无法打印则转退款工单 |
| 本地有、渠道无 | 本地误标（极少） | 冻结订单，人工核查，必要时回滚 |
| 金额不符 | 疑似篡改/异常 | 阻断 + 高优告警 + 人工介入 |

- **产出**：对账报告（每日落文件 + 管理端可查），差异数非 0 即告警。

#### 3.5 退款流程

触发场景：支付成功但打印失败且重试耗尽（R-1）、用户取消、对账异常。

```
异常/失败任务 ─▶ 生成退款工单 ─▶ 人工确认 ─▶ PaymentGateway.refund()
                                             │ 幂等键 refund_no（= orderNo+seq）
                                             ▼
                                   更新 pay_status=已退款
                                   落 t_payment_record(status=退款)
```
- **幂等**：`refund_no` 唯一，重复调用返回同一结果，不重复退。
- **状态机**：`已支付 → 已退款`，与打印状态解耦（打印失败不影响退款）。
- **对账**：退款流水纳入日对账（渠道退款单 vs 本地退款记录）。
- **补打优先于退款**：若为可修复的打印故障（缺纸/卡纸），优先重试/人工补打，退款为兜底。

---

### 4. 监控与告警

> 单店规模不引入 Prometheus 全家桶，采用**「Spring Actuator 暴露指标 + 轻量采集脚本 + 告警通道」**。规模化可平滑接入 Prometheus/Grafana。

#### 4.1 核心指标

| 指标 | 定义 | 采集方式 | 正常 | 告警阈值 |
|------|------|----------|------|----------|
| 打印成功率 | 成功任务 / 总任务（滚动 1h） | 订单表聚合 | ≥ 98% | 🟠 < 95%，🔴 < 90% |
| 支付成功率 | 支付成功订单 / 发起支付订单（1h） | 订单+流水聚合 | ≥ 95% | 🟠 < 90% |
| 任务队列积压 | `待领取` 任务数 / Redis 队列长度 | Redis `LLEN` + DB count | ≤ 5 | 🟠 > 20，🔴 > 50 |
| 助手在线率 | 在线终端 / 总终端 | `t_terminal.online_status` | 100% | 🔴 单店离线 > 90s |
| 下单 P95 | 下单接口响应 P95 | Actuator `http.server.requests` | < 300ms | 🟠 > 500ms |
| 接口错误率 | 5xx / 总请求（5min） | 日志聚合 | < 0.5% | 🟠 > 1%，🔴 > 5% |
| 支付兜底命中 | 轮询/对账补状态次数 | 补偿逻辑计数 | 0 | 🟠 ≥ 1/天（说明回调不稳） |
| 磁盘使用率 | `/data` 与 `/` 使用率 | `df` 巡检 | < 70% | 🟠 > 80%，🔴 > 90% |
| 订单超时未终态 | 支付成功但 5min 未打印完成 | 订单扫描 | 0 | 🟠 ≥ 1 |

#### 4.2 结构化日志字段与 traceId

- **日志格式**：JSON 单行，字段：`timestamp, level, logger, traceId, spanId?, userId?, orderNo?, terminalCode?, taskId?, event, message, durationMs, error?`。
- **traceId 贯穿**：
  - Nginx 注入 `X-Trace-Id: $request_id`；
  - 后端过滤器读取该头（缺失则生成 UUID），写入 MDC，贯穿 Controller→Service→DAO→异步任务；
  - 助手请求携带自身 `traceId`，后端透传并回写响应头；
  - 支付回调、对账、定时任务等无入站请求的场景，自生成 traceId 并落日志。
- **价值**：一次故障可用 `grep traceId` 从 Nginx→后端→助手串起全链路；订单维度用 `orderNo` 关联支付与打印。

#### 4.3 健康检查 / 就绪探针

- **`GET /actuator/health`**（仅 127.0.0.1 可达）：聚合 MySQL、Redis、磁盘、自定义组件健康。
- **存活 vs 就绪**：
  - Liveness `/actuator/health/liveness`：进程是否活着（不查依赖，避免依赖抖动误杀）；
  - Readiness `/actuator/health/readiness`：是否可接流量（查 MySQL/Redis 连通）。
- **自定义健康指示器**：`agentHealth`（最近有终端心跳）、`paymentGatewayHealth`（网关连通/密钥有效）、`storageHealth`（卷可写）。
- **运维巡检脚本**（cron 每分钟）：调 health + 关键指标，异常推告警；健康端点不暴露公网。

#### 4.4 告警通道与降噪

- **通道**：门店运维群（企业微信/钉钉 Webhook）+ 关键项短信。分级：🔴 立即、🟠 15min 内、🟡 日报汇总。
- **降噪**：
  - 同一告警 10 min 内去重合并；
  - 维护窗口/重启期间静默；
  - 短抖动（单次心跳丢失）不告警，需连续 2 个周期才触发。

---

### 5. 备份与容量

#### 5.1 MySQL 备份策略

| 项 | 策略 |
|----|------|
| 全量备份 | 每日 02:00 `mysqldump --single-transaction --routines --triggers`，gzip 压缩 |
| 增量备份 | 开启 binlog（`ROW` 格式），保留 7 天，支持 PITR 到分钟级 |
| 保留 | 全量保留 7 天（周备份保留 4 周）；binlog 7 天 |
| 存储位置 | 备份写本地 `/data/backup` + **每日同步一份到异机/对象存储**（防磁盘损坏连带丢失） |
| 恢复演练 | 每月一次，用最近备份在测试库恢复并校验行数 |
| RPO/RTO | RPO ≤ 1 天（binlog 可到分钟级）；RTO ≤ 1 h |

```bash
# 每日备份（cron）
mysqldump -u print -p"$MYSQL_PWD" --single-transaction --routines --triggers \
  --databases print_db | gzip > /data/backup/print_db_$(date +%F).sql.gz
find /data/backup -name 'print_db_*.sql.gz' -mtime +7 -delete
```

#### 5.2 文件存储清理与保留

- **保留策略**：上传文件默认保留 `FILE_RETENTION_DAYS=30` 天。
- **清理任务**：每日 03:00 扫描 `t_file.created_at < now-30d` **且** 关联订单均已终态（已完成/已退款/已关闭）的文件 → 删除物理文件 + 软删记录。
- **孤儿清理**：上传成功但从未下单的文件，保留 7 天后清理。
- **磁盘水位联动**：`/data` 使用率 > 85% 时，清理任务改为「先删最旧的已终态文件」，并告警。
- **容量估算**：按单店日均 100 单、平均文件 2MB 计，日增约 200MB，30 天约 6GB；`/data` 卷 200G 可支撑数年，预留充足。

#### 5.3 Redis 持久化

- **RDB**：`save 900 1` / `save 300 10` / `save 60 10000`（默认节奏），快照到 `/data/redis/dump.rdb`。
- **AOF**：`appendonly yes`，`appendfsync everysec`（兼顾性能与最多丢 1s 数据）。
- **定位**：Redis 存验证码、会话、任务队列、分布式锁。**业务真值以 MySQL 为准**：
  - 验证码丢失 → 用户重发即可；
  - 任务队列丢失 → 启动时/定时从 `t_print_task(status=待领取)` 重建队列；
  - 因此 Redis 可容忍数据丢失，AOF+RDB 仅为加速恢复。
- **内存**：`maxmemory 512mb` + `maxmemory-policy noeviction`（队列数据不可被淘汰），超限告警。

#### 5.4 单机资源估算

**基线配置：4 vCPU / 8 GB 内存 / 200 GB SSD（系统盘 40G + 数据卷 160G）**

| 组件 | CPU | 内存 | 磁盘 | 说明 |
|------|-----|------|------|------|
| Nginx | 0.5 核 | 128 MB | — | 静态+反代，轻量 |
| Java Backend | 1.5 核 | 1.5 GB（`-Xmx1g` + 堆外） | — | 峰值下单并发 |
| MySQL 8 | 1 核 | 1.5 GB（`innodb_buffer_pool=1g`） | 数据+binlog 约 10-50G | |
| Redis 7 | 0.5 核 | 512 MB | 少量 | `maxmemory 512mb` |
| OS + 余量 | — | 1 GB | — | 预留 |
| 合计 | ~4 核 | ~5 GB | 200G 充裕 | 留 30% 余量应对峰值 |

- **压力上限**：单机并发 ≤ 200、下单 P95 < 500ms 的目标下，4C8G 有余量。若多店复用同一台服务器，按「每店 +1 核 / +1 GB」线性预留。
- **文件卷**：SSD 优先（上传/下载/打印文件读取 IO 敏感）。
- **扩容触发**：CPU 持续 > 70%、内存 > 80%、磁盘 > 80% → 升级配置或拆分 MySQL/Redis 到独立机。

---

### 6. 单点故障与降级

#### 6.1 单点风险清单

| 单点 | 影响面 | 概率 | 影响 | 缓解 |
|------|--------|------|------|------|
| 单机（Nginx+Backend+MySQL+Redis 同机） | 全店不可用 | 中 | 🔴 高 | 备份+快速重启+降级+（规模化）双机 |
| MySQL | 无法下单/支付/查询 | 低 | 🔴 高 | 每日备份+binlog+快速恢复 |
| Redis | 验证码/队列不可用 | 中 | 🟠 中 | 可重建，降级为 DB 队列 |
| Nginx | 全站不可达 | 低 | 🔴 高 | 配置版本化，秒级重启 |
| 打印助手/打印机 | 无法出纸 | 中 | 🟠 中 | 守护自启+任务回收+人工补打 |
| 短信通道 | 无法登录 | 中 | 🟠 中 | 多通道降级 |
| 支付网关 | 无法支付 | 低 | 🔴 高 | 轮询兜底+切换通道 |

#### 6.2 降级策略

**① 短信通道降级**
```
主通道（aliyun）失败/超时 → 备用通道（tencent）→ 全部失败 → 本地 Mock/日志通道
                                                       （仅内部测试环境；生产则告警+引导人工）
```
- 通道健康检查：连续 N 次失败自动熔断该通道 5 min，切备用。
- 生产环境禁用「日志验证码」，仅降级为「告警 + 提示用户稍后重试」。

**② 支付轮询降级**
- 回调链路异常（回调命中率下降/验签批量失败）→ 提高主动查询频率（从 1min 到 15s）；
- 极端情况（渠道查询也不可用）→ 订单进入「待确认」，恢复后靠对账补状态，绝不重复扣款/出纸。

**③ 打印降级**
- 助手离线：后端将任务保留在「待领取」，助手恢复后自动消费，用户侧提示「打印中，请稍候」；
- 后端短暂不可用：助手用本地 spool + 退避重连，恢复后补拉任务、补报结果；
- 打印机故障：任务不消耗重试次数保持待领取，或转人工补打/退款工单。

**④ 数据库降级（只读兜底）**
- MySQL 短时不可用：后端返回友好错误，前端展示「系统维护中」；
- 恢复后，补偿任务重放未完成的状态流转（幂等，可安全重放）。

#### 6.3 故障 Runbook

##### Runbook-1：打印卡死（任务长期「打印中」）
1. **确认**：管理端查订单 `print_status=打印中` 且 `claimed_at` 超 2 min。
2. **定位**：`journalctl -u print-agent -n 200` 看助手日志；检查打印机面板（缺纸/卡纸/离线）。
3. **处置**：
   - 打印机物理问题 → 现场处理后，助手下次心跳恢复；等待后端超时回收自动重试；
   - 助手进程假死 → `systemctl restart print-agent`，观察 spool 补报；
   - 任务已回收但反复失败 → 达到 `TASK_MAX_RETRY` 后转异常工单，人工补打或退款。
4. **验证**：订单最终收敛为「已完成」或生成退款工单。

##### Runbook-2：支付回调丢失（用户已付但订单仍待支付）
1. **确认**：用户反馈已扣款但订单未支付；查 `t_payment_record` 有无该 `order_no`。
2. **处置**：
   - 前端轮询会触发主动查询，通常秒级自愈；
   - 未自愈 → 手动触发 `PaymentGateway.query(orderNo)` 或等定时兜底（1 min）；
   - 仍无 → 查渠道商户后台确认到账，确认后人工补记 + 补投打印任务；
   - 记录为「回调丢失」事件，若频发（≥1/天）排查回调地址/证书/验签。
3. **验证**：`pay_status=已支付` 且打印任务已投递。

##### Runbook-3：打印助手掉线
1. **确认**：管理端终端显示「离线」，`last_heartbeat` 超 90s。
2. **定位**：终端机是否开机/联网；`systemctl status print-agent`；助手日志；token 是否过期（401）。
3. **处置**：
   - 进程停止 → `systemctl start print-agent`；
   - 网络不通 → 检查终端机到后端 443 连通性；
   - 401 → 更新 `PRINT_AGENT_TOKEN` 并重启；
   - 期间新任务堆积在「待领取」，恢复后自动消费，无需人工干预。
4. **验证**：终端恢复「在线」，队列积压回落。

##### Runbook-4：MySQL 不可用
1. **确认**：`systemctl status mysql`；`journalctl -u mysql`；健康检查 readiness 失败。
2. **定位**：磁盘满（最常见）→ `df -h`；连接数打满 → 查慢查询/连接泄漏；进程崩溃 → 看错误日志。
3. **处置**：
   - 磁盘满 → 清理 binlog/日志/备份，扩容；
   - 连接打满 → 重启后端释放连接池，排查慢 SQL；
   - 数据损坏 → 用最近全量备份 + binlog 做 PITR 恢复（见 5.1）；
   - 恢复期间后端返回维护提示，恢复后补偿任务重放（幂等）。
4. **验证**：readiness 通过；订单/支付状态无丢失；对账无新增差异。

---

### 7. 风险表（可运维性视角）

| 编号 | 风险 | 等级 | 影响 | 缓解措施 |
|------|------|------|------|----------|
| OPS-1 | 单机单点，宕机导致全店停摆 | 🔴 | 营业中断 | 备份+快速重启脚本+降级；规模化上双机/容器编排 |
| OPS-2 | MySQL 数据丢失 | 🔴 | 资金/订单错乱 | 每日全量+binlog+异机备份+月度恢复演练 |
| OPS-3 | 支付回调丢失致订单卡死 | 🔴 | 用户已付费未出纸 | 三层兜底（轮询+定时+对账）+ 幂等 CAS |
| OPS-4 | 打印助手崩溃/断电致任务卡死 | 🟠 | 订单不完成 | systemd 守护+WatchdogSec+超时回收+spool 补报 |
| OPS-5 | 打印机缺纸/卡纸/离线 | 🟠 | 出纸失败 | 状态检测+派发前校验+不消耗重试+告警+人工补打 |
| OPS-6 | 磁盘写满（日志/文件/binlog） | 🟠 | 服务不可用 | 日志轮转+文件保留清理+binlog 清理+水位告警 |
| OPS-7 | 短信通道不可用 | 🟠 | 无法登录 | 多通道熔断切换+冷却限频 |
| OPS-8 | 密钥泄露（JWT/渠道密钥） | 🟠 | 安全/资金风险 | 密钥外置 600 权限+不入 Git+轮换预案 |
| OPS-9 | 回调被伪造/重放 | 🟠 | 资金损失 | 验签+时间窗+nonce 去重+金额校验 |
| OPS-10 | Redis 不可用 | 🟡 | 验证码/队列短暂不可用 | AOF+RDB+队列可由 DB 重建+noeviction |
| OPS-11 | 任务重复投递/重复打印 | 🟡 | 重复出纸浪费 | 幂等键+CAS 状态机+任务唯一约束 |
| OPS-12 | 缺乏可观测，故障定位慢 | 🟡 | MTTR 高 | traceId 全链路+结构化日志+健康检查+Runbook |
| OPS-13 | 备份未验证，恢复失败 | 🟡 | 灾难时不可用 | 月度恢复演练+校验行数 |
| OPS-14 | 多终端同时重连打爆后端 | 🟡 | 短时抖动 | 退避+抖动+429 尊重 Retry-After |

---

### 附录：运维交付物清单

| 交付物 | 说明 |
|--------|------|
| `deploy/nginx/print.conf` | Nginx 站点配置（TLS/反代/限流/WS） |
| `deploy/systemd/print-backend.service` | 后端 unit |
| `deploy/systemd/print-agent.service` | 助手 unit |
| `deploy/env/backend.env.example` | 后端环境变量模板（不含密钥） |
| `deploy/env/agent.env.example` | 助手环境变量模板 |
| `deploy/scripts/backup_mysql.sh` | MySQL 每日备份脚本 |
| `deploy/scripts/health_check.sh` | 巡检 + 告警脚本 |
| `deploy/scripts/reconcile.sh` | 对账触发脚本 |
| `docs/runbook.md` | 故障 Runbook（本章 §6.3 落地） |
| `docs/reconcile-report/` | 每日对账报告归档目录 |


---

## 第 3 章 测试策略与测试计划

> 文档版本：v1.0 ｜ 状态：Proposed ｜ 作者：测试专家 泰莎（Tessa）
> 范围：自助付费打印系统详细设计文档之「测试策略与测试计划」章节，不含实现代码。
> 上游依据：`docs/01-需求分析与高层架构设计.md`（FR-1~12、NFR、API 清单、状态机、ADR-001/002/006）。

---

### 7.1 测试目标与质量属性映射

测试围绕系统最脆弱的三个接缝设计：**钱（计价/支付）、纸（打印任务）、隐私（文件/会话）**。这三个环节任一失败都直接产生客诉或资金纠纷，因此投入最高测试强度。

| 质量属性 | 关联需求/风险 | 主要验证手段 | 目标 |
|----------|---------------|--------------|------|
| 正确性 | FR-3/4/8 计价；ADR-003 规则快照 | 单元测试（计价算法）+ 参数化边界用例 | 核心计价 100% 分支覆盖 |
| 一致性 | FR-6/9；ADR-006 状态机 | 单元（状态机）+ 集成（并发回调/领取） | 状态机非法迁移 0 容忍 |
| 幂等/防重 | R-3 回调重复；ADR-006 | 集成 + 并发测试 | 重复回调/领取不产生重复副作用 |
| 可靠性 | R-1/R-2 打印失败、助手掉线 | 硬件联调 + 混沌（断网/杀进程） | 任务最终收敛，不卡死 |
| 性能 | NFR 200 并发、P95<500ms | 负载测试（k6） | 达标或给出容量结论 |
| 安全 | R-4/R-8/R-9 隐私、越权、支付篡改 | 集成（越权）+ 专项安全用例 | 越权 100% 拦截 |
| 兼容性 | FR-2 文件格式；R-5 页数解析 | 文件样本集矩阵测试 | 白名单格式全部可解析计费 |

---

### 7.2 测试分层策略

采用经典测试金字塔，但**按组件差异调整配比**：后端以单元+集成为主；打印助手因涉及真实硬件，硬件联调层权重高于常规项目。

```
                  ┌─────────────────────────┐
                  │  硬件联调 (助手+真机)     │  ~20 用例  慢，真机/真驱动
                  ├─────────────────────────┤
                  │  E2E 端到端 (Playwright) │  ~15 场景  中速，浏览器全链路
                  ├─────────────────────────┤
                  │  集成 (API+MySQL+Redis)  │  ~120 用例 中速，Testcontainers
                  ├─────────────────────────┤
                  │  单元 (service/计价/状态机)│  ~400 用例 快，每 PR 必跑
                  └─────────────────────────┘
```

#### 7.2.1 单元测试层

| 项 | 说明 |
|----|------|
| 目标 | 计价算法、订单状态机、权限/租户过滤逻辑、回调验签解析、任务超时回收判定等纯逻辑 |
| 工具 | JUnit 5 + Mockito + AssertJ；参数化用 `@ParameterizedTest`/`@CsvSource` |
| 覆盖重点 | Pricing 模块、Order 状态机、PaymentGateway 抽象的路由（Factory）、Task 状态流转与重试上限 |
| 运行时机 | 每次 `git push` / PR，CI 必过，< 2 min |
| 不测 | 简单 getter/setter、MyBatis-Plus 生成的 CRUD、框架样板 |

**单元测试重点对象（白盒）**
- `PricingService.calculate(rule, params, pageCount)`：所有分支。
- `OrderStateMachine.transition(current, event)`：合法/非法迁移矩阵。
- `TaskRecycleJob.shouldRecycle(task, now)`：超时阈值边界。
- `MockPaymentGateway.handleNotify()`：验签通过/失败、重复单号。
- `PaymentGatewayFactory.get(code)`：未知渠道抛错。

#### 7.2.2 集成测试层

| 项 | 说明 |
|----|------|
| 目标 | 验证 API + 持久化 + 缓存的真实交互，覆盖事务、唯一约束、并发锁、Redis 队列语义 |
| 工具 | Spring Boot Test (`@SpringBootTest` + `MockMvc`/`RestAssured`) + **Testcontainers**（MySQL 8、Redis 7 真实容器，禁止用 H2 替 MySQL，避免方言/锁行为失真） |
| 覆盖重点 | 认证限频、文件越权、下单事务、支付回调幂等、任务领取（`BLPOP`/LPOP 竞争）、超时回收 |
| 运行时机 | 每次 PR，< 10 min |
| 数据 | 每用例事务回滚或容器重置；关键用例显式提交以测唯一约束 |

#### 7.2.3 端到端测试层（E2E）

| 项 | 说明 |
|----|------|
| 目标 | 从浏览器视角验证「登录→上传→选参→计价→下单→二维码→（Mock）支付→状态变已完成」全链路 |
| 工具 | **Playwright**（推荐，多浏览器、自带 trace、等待智能）或 Cypress |
| 覆盖重点 | 5 条主链路 + 3 条关键异常链路（见 7.4.5） |
| 环境 | staging 环境 + MockPaymentGateway；通过 `/payments/mock/confirm` 触发支付成功，跳过真实扫码 |
| 运行时机 | 合并到 main / 每日夜间；PR 可跑冒烟子集（登录+下单） |

#### 7.2.4 硬件联调层（打印助手 + 真实打印机）

| 项 | 说明 |
|----|------|
| 目标 | 验证 .NET 助手真实拉取任务、下载文件、调用打印驱动、回传结果；验证断线重连、超时回收 |
| 工具 | **.NET xUnit**（助手单测）+ 真实终端机 + 打印机（激光/喷墨各一，Windows/Linux 各一） |
| 覆盖重点 | 心跳、长轮询 claim、打印成功/失败/缺纸、进程被杀后任务回收、真实出纸效果（页数/色彩/单双面正确） |
| 运行时机 | 迭代验收前集中执行；无法进 CI，作为「发布门禁清单」人工执行 |
| 关键约束 | 出纸正确性必须人工核对（页数、彩色/黑白、单双面、A4/A3 尺寸），不可仅看接口返回 |

#### 7.2.5 性能测试层

| 项 | 说明 |
|----|------|
| 目标 | 验证 NFR：单机 200 并发下单、下单接口 P95<500ms、助手长轮询空转不拖垮后端 |
| 工具 | **k6**（脚本化、易进 CI）或 JMeter；压测库直接打 staging |
| 场景 | 见 7.5 非功能测试 |
| 运行时机 | 版本发布前 / 重大变更后 |

---

### 7.3 测试环境与测试数据

#### 7.3.1 环境分层

| 环境 | 用途 | 数据 | 支付 | 打印机 |
|------|------|------|------|--------|
| **local** | 开发者单元/集成 | Testcontainers 临时 | Mock | 无（助手 mock printer） |
| **dev** | 联调 | 共享，可清库 | MockPaymentGateway | 1 台测试机 + 虚拟打印机 |
| **staging** | E2E / 性能 / UAT | 脱敏+可重置 | MockPaymentGateway | 真实测试打印机（隔离网络） |
| **prod** | 生产 | 真实 | 真实渠道（本期未接） | 门店真实打印机 |

#### 7.3.2 配置隔离

- 后端：`application-{local,dev,staging,prod}.yml`，网关实现用 `@ConditionalOnProperty(gateway=xxx)` 切换。
- **安全红线**：`/payments/mock/confirm` 端点仅在 `gateway=mock` 的 profile 下注册；prod 强制关闭，并加测试用例断言该端点在 prod profile 返回 404/403。

#### 7.3.3 测试文件样本集（`test-fixtures/files/`）

覆盖 FR-2 白名单与 R-5 页数解析，每个样本标注预期 `pageCount`：

| 文件 | 类型 | 大小 | 预期页数 | 用途 |
|------|------|------|----------|------|
| `doc_1p.docx` | Word | 20 KB | 1 | 正常最小 |
| `doc_10p.docx` | Word | 200 KB | 10 | 多页计价 |
| `xls_3sheet.xlsx` | Excel | 50 KB | 3 | 多 Sheet 页数 |
| `pdf_5p.pdf` | PDF | 1 MB | 5 | 标准 PDF |
| `pdf_scan_2p.pdf` | PDF（扫描图） | 5 MB | 2 | 图片型 PDF |
| `img.png` / `img.jpg` | 图片 | 2 MB | 1 | 图片按 1 页 |
| `a3_1p.pdf` | PDF | 1 MB | 1 | A3 纸张 |
| `empty_0p.pdf` | PDF | 5 KB | 0 | **边界：0 页** |
| `corrupt.pdf` | 损坏文件 | 10 KB | — | **异常：解析失败** |
| `oversize_60m.pdf` | PDF | 60 MB | — | **异常：超限** |
| `evil.exe`（改名 .pdf） | 伪装 | 10 KB | — | **安全：类型伪装** |

#### 7.3.4 测试数据构造

- 用户/订单/规则：SQL seed 脚本 + 工厂方法（`TestDataFactory`），订单号可预测（测试模式用递增序列而非雪花，便于断言）。
- 计价规则基线（seed）：A4 黑白 0.20/页、A4 彩色 0.80/页、A3 黑白 0.40/页、双面折扣 0.90。金额按实际数量 × 单价直接计算，不设起印价。
- Redis 每次用例前 `FLUSHDB`（隔离）或使用独立 DB index。

---

### 7.4 关键用例矩阵

> 约定：所有 API 前缀 `/api/v1`；预期响应体 `{code,message,data}`，`code=0` 成功。用例编号 `TC-<模块>-<序号>`。

#### 7.4.1 登录 / 认证（Auth）

| 编号 | 用例 | 输入 | 预期输出 |
|------|------|------|----------|
| TC-AUTH-01 | 正常发送验证码 | POST `/auth/sms/send` body `{phone:"13800000001"}` | code=0；Redis 存在 `sms:code:13800000001`，TTL≈300s |
| TC-AUTH-02 | 验证码登录成功 | POST `/auth/login` `{phone, code:"123456"}`（与 Redis 一致） | code=0；返回 JWT；`t_user.last_login_at` 更新 |
| TC-AUTH-03 | 验证码错误 | login，code 输入错误值 | code≠0，`message=验证码错误`；不签发 Token |
| TC-AUTH-04 | 验证码过期 | 发送后 `TTL` 到期再 login | code≠0，`message=验证码已过期` |
| TC-AUTH-05 | 验证码一次性 | 同一 code 连续 login 两次 | 第一次成功；第二次 code≠0（Redis 已删除） |
| TC-AUTH-06 | 发送限频 | 60s 内同号连续请求 sms/send 3 次 | 第 2、3 次 code≠0，`message=发送过于频繁`，返回 `Retry-After` |
| TC-AUTH-07 | 手机号格式非法 | `{phone:"abc"}` | code≠0，参数校验失败（400） |
| TC-AUTH-08 | 无 Token 访问受保护接口 | GET `/auth/me` 无 Authorization | 401 |
| TC-AUTH-09 | 过期/伪造 JWT | 携带过期或篡改签名的 Token | 401，`message=Token 无效` |
| TC-AUTH-10 | 登出后 Token 失效 | login → logout → 用旧 Token 访问 `/auth/me` | 401（Redis 黑名单生效） |

#### 7.4.2 文件上传（File）

| 编号 | 用例 | 输入 | 预期输出 |
|------|------|------|----------|
| TC-FILE-01 | 正常上传 PDF | multipart `pdf_5p.pdf` | code=0；返回 `fileId`、`pageCount=5`；文件落存储，`t_file` 记录 user_id |
| TC-FILE-02 | 白名单格式全通过 | 依次上传 docx/xlsx/pdf/png/jpg | 全部 code=0，pageCount 与样本集一致 |
| TC-FILE-03 | 超大小拒绝 | `oversize_60m.pdf`（>50MB） | code≠0，`message=文件超过大小限制`（Nginx `client_max_body_size` 或后端双重拦截） |
| TC-FILE-04 | 非白名单类型 | `.exe` / `.zip` | code≠0，`message=不支持的文件类型` |
| TC-FILE-05 | 类型伪装 | `evil.exe` 改名 `evil.pdf`，MIME 伪造 | 服务端按**魔数**校验，拒绝（不能只看扩展名/Content-Type） |
| TC-FILE-06 | 损坏文件 | `corrupt.pdf` | code≠0，`message=文件解析失败`；不产生脏记录 |
| TC-FILE-07 | 越权访问他人文件元数据 | 用户 A 上传得 fileId；用户 B（另一 JWT）GET `/files/{A的id}` | 403/404，不泄露文件存在性 |
| TC-FILE-08 | 越权下载他人文件 | 用户 B GET `/files/{A的id}/download` | 403/404，且不返回任何文件字节 |
| TC-FILE-09 | 未登录上传 | 无 Token POST `/files` | 401 |
| TC-FILE-10 | 0 页文件 | `empty_0p.pdf` | 允许上传但 `pageCount=0`；下单时金额为 0.00（见 TC-PRICE-07） |

#### 7.4.3 计价（Pricing）— 核心，需 90%+ 覆盖

计价公式（基线规则）：`amount = pageCount × 份数 × 单价 × 双面折扣`（按实际数量与单价直接计算，不设起印价），其中单价按「色彩 × 纸张」查规则。

| 编号 | 用例 | 输入（页数/份数/色彩/单双面/纸张） | 预期金额（基线规则） |
|------|------|-----------------------------------|----------------------|
| TC-PRICE-01 | 黑白单面 A4 | 5 页 × 1 份 | 5 × 0.20 = **1.00** |
| TC-PRICE-02 | 彩色单面 A4 | 5 页 × 1 份 | 5 × 0.80 = **4.00** |
| TC-PRICE-03 | 黑白双面 A4 | 5 页 × 1 份 × 折扣0.9 | 5 × 0.20 × 0.9 = **0.90** |
| TC-PRICE-04 | 多份 | 3 页 × 4 份 黑白 | 3 × 4 × 0.20 = **2.40** |
| TC-PRICE-05 | A3 黑白 | 2 页 × 1 份 | 2 × 0.40 = **0.80** |
| TC-PRICE-06 | 单页小额不托底 | 1 页 黑白单面 = 0.20 | **0.20**（无起印价） |
| TC-PRICE-07 | 边界 0 页 | 0 页 × 1 份 | **0.00**（无起印价托底） |
| TC-PRICE-08 | 页码范围 | 10 页文件，选第 3~5 页 | 计费页数=3，金额按 3 页算 |
| TC-PRICE-09 | 页码越界 | 选第 8~15 页（文件仅 10 页） | code≠0，`message=页码范围超出文件页数` |
| TC-PRICE-10 | 起止倒置 | 选第 5~3 页 | code≠0，参数校验失败 |
| TC-PRICE-11 | 双面奇数页 | 5 页 双面 | 页数仍按 5 计（或按 ceil(5/2) 张——**按规则定义锁定**），金额=5×单价×0.9 |
| TC-PRICE-12 | 规则快照不回填 | 下单后管理员改价，再查订单 | 订单金额不变（ADR-003） |
| TC-PRICE-13 | 无匹配规则 | 请求 A3 彩色但无该规则 | code≠0，`message=暂无可用计价规则` |
| TC-PRICE-14 | 金额精度 | 0.20 × 3 份 × 0.9 | **0.54**，`BigDecimal` 两位，无浮点误差（断言 `0.54` 非 `0.5400000001`） |
| TC-PRICE-15 | 试算与下单一致 | `/pricing/quote` 与 `/orders` 同参数 | 两者金额严格相等（防试算/下单算法分叉） |

#### 7.4.4 下单（Order）

| 编号 | 用例 | 输入 | 预期输出 |
|------|------|------|----------|
| TC-ORD-01 | 正常下单 | 已上传 fileId + 合法参数 | code=0；返回 `orderNo`、`amount`；`pay_status=待支付`，`print_status=待打印`（或未生成），`expire_at` 已设 |
| TC-ORD-02 | 未上传文件下单 | 不存在的 fileId | code≠0，404/参数错误 |
| TC-ORD-03 | 越权用他人 fileId 下单 | 用户 B 用用户 A 的 fileId | code≠0，403 |
| TC-ORD-04 | 参数非法 | 份数=0 或负数 | code≠0，参数校验失败 |
| TC-ORD-05 | orderNo 唯一 | 并发创建多单 | orderNo 无重复（DB 唯一约束兜底） |
| TC-ORD-06 | 取消未支付订单 | 下单后 POST `/orders/{no}/cancel` | `pay_status=已关闭` |
| TC-ORD-07 | 已支付订单不可取消 | 支付成功后 cancel | code≠0，`message=订单已支付不可取消` |
| TC-ORD-08 | 支付超时自动关闭 | 下单后 `expire_at` 到期（可用短 TTL 或时间注入） | 定时任务置 `pay_status=已关闭` |

#### 7.4.5 支付（Payment）

| 编号 | 用例 | 输入 | 预期输出 |
|------|------|------|----------|
| TC-PAY-01 | 发起支付生成二维码 | POST `/payments/{no}/pay` | code=0；返回 `qrCodeContent`；`t_payment_record` 落 `待支付` |
| TC-PAY-02 | 支付成功（Mock 回调） | POST `/payments/mock/confirm` `{orderNo}` | `pay_status=已支付`；支付流水 `成功`；**投递打印任务** |
| TC-PAY-03 | 支付超时 | `expire_at` 到期未支付 | `pay_status=已关闭`；`status` 接口返回已关闭；不投递打印任务 |
| TC-PAY-04 | 重复回调幂等 | 同一回调/confirm 调用 3 次 | `pay_status` 仅变更一次；`t_payment_record` 无重复成功记录；**仅生成 1 个打印任务** |
| TC-PAY-05 | 篡改金额回调 | 回调 body 中金额=0.01（订单应 1.00） | 验签/金额比对失败，拒绝；`pay_status` 不变；记录审计 |
| TC-PAY-06 | 回调验签失败 | 伪造签名 header | 拒绝（401/400），不更新订单 |
| TC-PAY-07 | 未知订单回调 | 回调 orderNo 不存在 | 返回失败但**不抛 500**（网关可重试），记录异常 |
| TC-PAY-08 | 轮询状态 | GET `/payments/{no}/status` 待支付→已支付 | 状态与 DB 一致，最终一致 |
| TC-PAY-09 | 退款 | 打印失败后触发退款 | `pay_status=已退款`；流水 `退款`；调用 `PaymentGateway.refund` |
| TC-PAY-10 | 未支付查询状态 | 待支付订单查 status | 返回 `待支付`，不误报成功 |
| TC-PAY-11 | 主动查询兜底 | 回调丢失，`query()` 查网关返回成功 | 订单补正为已支付（对账兜底 R-3） |

#### 7.4.6 打印任务（Task / Agent）

| 编号 | 用例 | 输入 | 预期输出 |
|------|------|------|----------|
| TC-TASK-01 | 助手心跳 | POST `/agent/heartbeat`（X-Agent-Token） | `t_terminal.online_status=1`，`last_heartbeat` 刷新 |
| TC-TASK-02 | 心跳鉴权失败 | 错误 agentToken | 401 |
| TC-TASK-03 | 领取任务 | POST `/agent/tasks/claim`（终端有已支付任务） | 返回任务+fileUrl；任务 `status=已领取/打印中`，记 `claimed_at` |
| TC-TASK-04 | 无任务长轮询 | claim 且队列空 | 挂起约 25s 后返回空（长轮询语义），不报错 |
| TC-TASK-05 | 回传成功 | POST `/agent/tasks/{id}/report` `{status:success}` | 任务 `成功`，`finished_at` 落；订单 `print_status=已完成` |
| TC-TASK-06 | 回传失败+重试 | report `{status:failed, reason:"缺纸"}`，retry<上限 | 任务 `重试中`，`retry_count+1`，重新入队 |
| TC-TASK-07 | 重试超上限 | 连续失败达上限（如 3 次） | 任务终态 `失败`；生成异常工单（FR-12），不再重试 |
| TC-TASK-08 | 超时回收 | claim 后助手崩溃，`claimed_at` 超时（如 2 min） | 定时任务置回 `待领取`，可被重新领取（ADR-006） |
| TC-TASK-09 | 回传幂等 | 同一任务 report success 两次 | 状态仅置成功一次，无重复副作用 |
| TC-TASK-10 | 重复领取不重复打印 | 两个助手/两次 claim 竞争同一任务 | 分布式锁/原子更新保证**仅一个**领取成功（另一返回空） |
| TC-TASK-11 | 越权领取他人终端任务 | 终端 A 的 token 领终端 B 的任务 | 返回空/403，不泄露 |
| TC-TASK-12 | 助手掉线 | 心跳停止 > 阈值（如 90s） | `online_status=0`；管理端可见离线告警 |
| TC-TASK-13 | 打印机离线 | 助手检测打印机不可用 | 上报失败原因；任务按 TC-TASK-06/07 处理 |
| TC-TASK-14 | 文件下载鉴权 | 助手 GET `/agent/tasks/{id}/file` 无 token | 401；带 token 成功下载 |

#### 7.4.7 任务状态查询（用户侧）

| 编号 | 用例 | 输入 | 预期输出 |
|------|------|------|----------|
| TC-STAT-01 | 查询全流程状态 | GET `/tasks/{orderNo}/status` 各阶段 | 待支付→已支付→打印中→已完成 依次正确 |
| TC-STAT-02 | 越权查他人订单 | 用户 B 查用户 A 的 orderNo | 403/404 |
| TC-STAT-03 | 不存在订单 | 随机 orderNo | 404 |
| TC-STAT-04 | 失败态展示 | 打印失败订单 | 返回 `失败` + 可读原因 + 「重新打印/退款」入口标识 |

---

### 7.5 支付网关 Mock / Sandbox 测试方案

本期无真实商户资质，全部支付路径通过 `MockPaymentGateway` 验证；测试核心是**回调验签 + 幂等 + 金额一致性**，这三项直接决定切换真实渠道时是否需要改动业务代码。

#### 7.5.1 Mock 能力清单

| 能力 | 触发方式 | 用途 |
|------|----------|------|
| 生成二维码 | `POST /payments/{no}/pay` → `MockPaymentGateway.prepay()` 返回固定/可解析的 `qrCodeContent` | 展示层验证 |
| 模拟支付成功 | `POST /payments/mock/confirm {orderNo}` | 主链路推进 |
| 模拟支付失败 | confirm 带 `{result:"FAIL"}`（建议扩展参数） | 失败链路 |
| 模拟超时 | 不 confirm，等待 `expire_at` | 超时链路 |
| 主动查询 | `query(orderNo)` 按内存/Redis 状态返回 PENDING/SUCCESS/FAILED | 兜底对账 |
| 模拟重复回调 | 对同一 orderNo 多次 confirm / 多次 notify | 幂等验证 |

> 实现建议（供开发参考，非本测试章节强制）：`MockPaymentGateway` 的确认状态存 Redis（`mock:pay:{orderNo}`），便于测试脚本读写与重置；`qrCodeContent` 采用可解析格式如 `mockpay://order/{orderNo}?amount={amount}`，便于 E2E 断言。

#### 7.5.2 场景构造矩阵

| 场景 | 构造步骤 | 断言点 |
|------|----------|--------|
| **支付成功** | 下单 → pay → `mock/confirm` | 订单已支付；生成 1 打印任务；流水成功 |
| **支付失败** | 下单 → pay → `confirm{result:FAIL}` | 订单仍待支付或转已关闭；无打印任务 |
| **支付超时** | 下单 → pay → 等待 expire_at | 订单已关闭；`status` 接口返回已关闭 |
| **重复回调** | 下单 → pay → `confirm` ×3（或 notify ×3） | pay_status 只变一次；1 个打印任务；流水无重复 |
| **篡改金额** | 构造 notify body 金额≠订单金额 | 验签/金额校验拒绝；订单不变 |
| **验签失败** | notify 带错误签名 | 拒绝；记审计；订单不变 |
| **回调乱序** | 先发「失败」再发「成功」回调 | 终态以成功为准（或按状态机规则，锁定并断言） |
| **回调丢失兜底** | 只调 `query()` 返回成功，不发 notify | 对账任务将订单补正为已支付 |

#### 7.5.3 验签验证方法

1. **签名算法一致性**：Mock 实现使用与真实渠道同构的签名（如 HMAC-SHA256 over 排序参数 + 密钥）。测试用同一算法在测试侧独立计算签名，比对服务端接受/拒绝结果。
2. **正向**：正确签名 → 通过。
3. **反向用例集**：错误密钥签名、篡改任一字段后不更新签名、空签名、超长签名、重放旧签名（带时间戳窗口校验）→ 全部拒绝。
4. **审计**：每次回调（无论成败）原始报文写入 `t_payment_record.raw_notify`，测试断言落库。

#### 7.5.4 幂等验证方法

1. **回调幂等键**：以 `orderNo` 为业务幂等键。测试对同一 orderNo 并发/顺序多次 notify。
2. **断言**：
   - `t_print_order.pay_status` 的**状态迁移只发生一次**（可查状态变更日志或通过 `updated_at`/版本号验证）。
   - `t_print_task` 中该 orderNo **仅有 1 条记录**。
   - `t_payment_record` 中成功记录**不重复**。
3. **并发幂等**：用 k6/Gatling 并发 50 次同一回调，断言最终仅 1 个打印任务。

#### 7.5.5 切换到真实渠道的回归清单

接入 `WechatPaymentGateway`/`AlipayPaymentGateway` 时，业务代码应零改动。切换前必须回归：

- [ ] 所有 `PaymentGateway` 契约测试（`prepay/query/handleNotify/refund`）在真实实现上重跑（用 Sandbox 商户号）。
- [ ] 真实渠道**验签**正/反用例全过。
- [ ] 真实回调的**字段映射**与 Mock 一致（订单号、金额、流水号解析正确）。
- [ ] 真实渠道的**回调重试策略**与幂等键兼容（不重复出纸）。
- [ ] **金额单位一致性**：真实渠道用「分」，业务用「元」——重点回归 TC-PRICE-14 与回调金额比对。
- [ ] 退款链路真实跑通（Sandbox）。
- [ ] 对账任务与真实渠道 `query()` 结果一致。
- [ ] `mock/confirm` 端点在生产 profile 已关闭（断言 404/403）。

---

### 7.6 非功能测试

#### 7.6.1 并发测试

| 场景 | 方法 | 通过标准 |
|------|------|----------|
| 200 并发下单 | k6，200 VU，持续 3 min，混合「quote+orders」 | 下单接口 P95<500ms，错误率<1%，无超卖/重复订单 |
| 并发回调 | k6，50 VU 打同一 orderNo 回调 | 仅 1 打印任务；无死锁 |
| 并发领取 | 多助手实例竞争 claim | 每任务仅被领取一次（分布式锁正确） |
| 队列积压 | 灌入 500 任务，观察助手消费 | 任务全部收敛到终态，无卡死 |

#### 7.6.2 幂等测试

- 重复回调（见 7.5.4）；重复领取（TC-TASK-10）；重复 report（TC-TASK-09）；重复下单提交（前端防抖 + 后端幂等键）。
- 判定：任何重复请求**不得产生第二个副作用**（第二张纸、第二条成功流水、第二次扣款）。

#### 7.6.3 断网 / 故障重试

| 场景 | 方法 | 通过标准 |
|------|------|----------|
| 后端短暂不可用 | 助手运行中重启后端 / 断网 30s | 助手持续重试，恢复后任务继续，不丢任务 |
| 助手崩溃 | kill -9 助手进程，任务已 claim | `claimed_at` 超时回收，任务被重新领取（TC-TASK-08） |
| 打印机离线 | 断开打印机 | 上报失败，进入重试/工单，不静默丢失 |
| Redis 抖动 | 重启 Redis | 会话可重登；队列任务不丢（持久化策略需验证） |

#### 7.6.4 长稳测试（Soak）

- 助手连续运行 **24h**：心跳不断、长轮询连接稳定、内存无泄漏增长（监控 RSS 曲线）、无句柄泄漏。
- 后端连续运行 24h + 周期性下单/支付/打印：观察 GC、连接池、线程池、Redis 内存稳定。
- 通过标准：无崩溃、内存增长 < 20%、无任务卡死。

#### 7.6.5 安全测试

| 编号 | 用例 | 预期 |
|------|------|------|
| TC-SEC-01 | 用户 A 下载用户 B 文件 | 403/404 |
| TC-SEC-02 | 伪造/过期 JWT 访问 | 401 |
| TC-SEC-03 | 篡改回调金额 | 拒绝（见 TC-PAY-05） |
| TC-SEC-04 | 越权查他人订单/任务状态 | 403/404 |
| TC-SEC-05 | 用他人 fileId 下单 | 403 |
| TC-SEC-06 | 助手 token 越权领他终端任务 | 拒绝（TC-TASK-11） |
| TC-SEC-07 | `mock/confirm` 在生产 profile 可用性 | 404/403（必须关闭） |
| TC-SEC-08 | 上传超大/伪装文件 | 拒绝（TC-FILE-03/05） |
| TC-SEC-09 | 登出后旧 Token 可用性 | 401（黑名单） |
| TC-SEC-10 | 公共电脑会话超时 | 空闲超时后需重新登录 |
| TC-SEC-11 | SQL 注入（登录/查询参数） | 参数化查询，无注入（扫描 + 手工） |
| TC-SEC-12 | 目录穿越（文件名 `../../etc`） | 存储路径净化，拒绝 |

---

### 7.7 覆盖率目标与 CI 集成

#### 7.7.1 覆盖率目标

| 模块 | 行覆盖 | 分支覆盖 | 说明 |
|------|--------|----------|------|
| 后端整体 | **≥70%** | ≥60% | 门禁下限 |
| Pricing 计价 | **≥90%** | ≥90% | 资金相关，核心 |
| Order 状态机 | **≥90%** | ≥90% | 一致性核心 |
| Payment 回调/幂等 | ≥85% | ≥80% | 资金相关 |
| Task 状态/回收 | ≥85% | ≥80% | 可靠性核心 |
| 前端 Vue | ≥60% | — | 组件 + 关键交互 |
| 助手 .NET | ≥70% | ≥60% | 核心调度逻辑 |
| **不纳入覆盖统计** | — | — | DTO/getter/setter、配置类、生成的 mapper、一次性脚本 |

工具：JaCoCo（后端）、`c8`/Vitest（前端）、`coverlet`（.NET）。覆盖率报告上传 CI 并设门禁。

#### 7.7.2 CI 流水线（Git 触发）

```
push / PR
  │
  ├─ Stage 1: 静态检查      (≤2min)  Checkstyle/SpotBugs + ESLint + dotnet format
  ├─ Stage 2: 单元测试      (≤3min)  JUnit5 / Vitest / xUnit  ← 门禁：全绿 + 覆盖率
  ├─ Stage 3: 集成测试      (≤10min) Testcontainers(MySQL+Redis)  ← 门禁：全绿
  ├─ Stage 4: 构建镜像/产物 (≤5min)  backend jar + frontend dist + agent 发布包
  ├─ Stage 5: E2E 冒烟      (≤8min)  Playwright 主链路（仅 main/ nightly 全量）
  └─ Stage 6: 部署 staging  (手动/自动) + 性能测试（nightly）
```

#### 7.7.3 质量门禁（Quality Gate）

合并到 `main` 必须满足：

- [ ] Stage 1-3 全绿；单元+集成 0 失败。
- [ ] 覆盖率达标：后端整体 ≥70%、Pricing/Order ≥90%（低于阈值**阻断合并**）。
- [ ] 无 P0/P1 未关闭缺陷（见 7.8.3）。
- [ ] 新增代码覆盖率不低于阈值（增量门禁）。
- [ ] 关键 E2E 冒烟（登录+下单+Mock 支付）通过。

#### 7.7.4 分支与触发策略

- `feature/*` PR：Stage 1-3 + 覆盖率门禁 + E2E 冒烟子集。
- 合并 `main`：全量 E2E。
- `release/*`：加硬件联调清单（人工）+ 性能测试。
- nightly：全量 E2E + 24h 长稳（可选，周末）。

---

### 7.8 进入 / 退出准则与缺陷分级

#### 7.8.1 测试进入准则（Entry Criteria）

- 需求与架构冻结（本文档上游 `01-需求分析与高层架构设计.md` 已 Accepted）。
- 目标构建产物可部署到 staging，接口契约稳定（OpenAPI 冻结）。
- 测试环境（MySQL/Redis/Mock 网关/测试终端）就绪；样本文件集就位。
- 单元/集成测试用例已评审通过。

#### 7.8.2 测试退出准则（Exit Criteria）

| 维度 | 退出标准 |
|------|----------|
| 功能 | 所有 P0 用例 100% 执行通过；P1 通过率 ≥95% |
| 覆盖率 | 后端整体 ≥70%，Pricing/Order 状态机 ≥90% |
| 缺陷 | P0/P1 缺陷 0 个未关闭；P2 有明确处理计划 |
| 性能 | 200 并发下单达标（P95<500ms，错误率<1%） |
| 可靠性 | 断网重试/超时回收/长稳 24h 通过 |
| 安全 | 越权、篡改、验签、mock 端点关闭全部通过 |
| 硬件 | 真机出纸正确（页数/色彩/单双面/纸张），失败重试与回收通过 |
| 回归 | 全量回归套件通过；切换真实渠道回归清单已备 |

#### 7.8.3 缺陷分级

| 级别 | 定义 | 示例 | 处理要求 |
|------|------|------|----------|
| **P0 阻断** | 主链路不可用 / 资金或隐私事故 | 支付后不出纸且无补偿；越权下载他人文件；重复扣款 | 立即修复，阻塞发布 |
| **P1 严重** | 核心功能错误但有绕行 | 计价算错金额；状态机非法迁移；回调幂等失效 | 发布前必修 |
| **P2 一般** | 非核心功能异常/体验问题 | 长轮询偶发超时提示不准；错误文案不清 | 计划内修复 |
| **P3 轻微** | 文案/样式/优化建议 | UI 对齐、日志冗余 | 可延后 |

#### 7.8.4 缺陷报告要素

复现步骤、期望 vs 实际、环境与版本、订单号/终端号、日志与回调原始报文（`raw_notify`）、截图/出纸照片、影响级别。

---

### 7.9 测试风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| 真机/驱动差异导致联调不可复现 | 出纸问题漏测 | 固定机型清单 + 出纸照片存档 + 版本化驱动 |
| 页数解析与真实打印页不一致（R-5） | 计价争议 | 样本集矩阵 + 上传后页数二次确认 + 兜底策略测试 |
| Mock 与真实渠道行为偏差 | 切渠道踩坑 | 契约测试同构 + 7.5.5 回归清单 |
| 长稳测试耗时 | 拖慢发布 | 周末/nightly 执行，不阻塞日常 |
| 测试数据污染 | 结果不可信 | Testcontainers 隔离 + Redis FLUSHDB + 可重置 seed |
| 覆盖率刷量（无效测试） | 虚假信心 | 门禁看**增量**覆盖 + 关键模块分支覆盖 + 人工评审用例质量 |

---

## 🧯 风险汇总（架构 + 运维 + 测试合并视图）

| # | 风险 | 等级 | 影响 | 缓解措施 | 来源 |
|---|------|------|------|----------|------|
| 1 | 支付成功但打印失败（缺纸 / 卡纸 / 断电） | 🔴 | 用户付费未出纸，投诉 | 状态机 + 失败重试 + 退款 / 补打工单 + 打印机状态检测 | Archi R-1 / Rex OPS-5 |
| 2 | 单机单点，宕机导致全店停摆 | 🔴 | 营业中断 | 备份 + 快速重启脚本 + 降级；规模化上双机 | Rex OPS-1 |
| 3 | 支付回调丢失 / 重复 | 🔴 | 资金错乱、重复出纸 | 三层兜底（轮询 + 定时 + 对账）+ 幂等 CAS + 唯一约束 | Archi R-3 / Rex OPS-3 |
| 4 | MySQL 数据丢失 | 🔴 | 资金 / 订单错乱 | 每日全量 + binlog + 异机备份 + 月度恢复演练 | Rex OPS-2 |
| 5 | 打印助手崩溃 / 断电致任务卡死 | 🟠 | 订单不完成 | systemd 守护 + WatchdogSec + 超时回收 + spool 补报 | Archi R-2 / Rex OPS-4 |
| 6 | 公共电脑隐私泄露（前用户文件 / 会话） | 🟠 | 隐私事故 | 登出清 Token + 文件访问鉴权 + 会话超时 | Archi R-4 |
| 7 | 文件格式 / 页数解析不准 | 🟠 | 计价偏差、争议 | 统一转换 + 上传后确认页数 + 兜底估算 + 样本集矩阵 | Archi R-5 / Tessa 7.3.3 |
| 8 | 密钥泄露（JWT / 渠道密钥） | 🟠 | 安全 / 资金风险 | 密钥外置 600 权限 + 不入 Git + 轮换预案 | Rex OPS-8 |
| 9 | 回调被伪造 / 重放 | 🟠 | 资金损失 | 验签 + 时间窗 + nonce 去重 + 金额校验 | Rex OPS-9 |
| 10 | 恶意大文件 / 超量上传 | 🟡 | 存储 / 带宽 | 大小 / 类型白名单（魔数校验）+ 限流 + 定期清理 | Archi R-9 |
| 11 | 短信通道成本 / 可达性 | 🟡 | 登录失败 | 通道可配 + 熔断切换 + Mock 日志通道 | Archi R-6 / Rex OPS-7 |
| 12 | 多店扩展时数据隔离 | 🟡 | 越权 | 全表 `merchant_id`，接口层强制租户过滤 | Archi R-10 |
| 13 | 磁盘写满（日志 / 文件 / binlog） | 🟡 | 服务不可用 | 日志轮转 + 文件保留清理 + binlog 清理 + 水位告警 | Rex OPS-6 |
| 14 | 缺乏可观测，故障定位慢 | 🟡 | MTTR 高 | traceId 全链路 + 结构化日志 + 健康检查 + Runbook | Rex OPS-12 |
| 15 | Mock 与真实渠道行为偏差 | 🟡 | 切渠道踩坑 | 契约测试同构 + 切换真实渠道回归清单 | Tessa 7.5.5 / 7.9 |

---

## ✅ 行动清单（按优先级排序）

| # | 行动 | 负责角色 | 紧急度 | 预期完成 |
|---|------|---------|--------|---------|
| 1 | 产品拍板「0 页文件」行为（允许上传并计 0.00 元 / 直接拒绝） | 产品 + 后端 | P0 | 开发启动前 |
| 2 | 产品拍板「双面奇数页」计费口径（按页 5 计 / 按张 ceil(5/2)=3 计） | 产品 + 后端 | P0 | 开发启动前 |
| 3 | 确定支付渠道接入方案与资质路径（微信 / 支付宝商户申请），明确 Mock→真实的切换计划 | 产品 + 后端 | P0 | 设计定稿前 |
| 4 | 冻结 OpenAPI 契约（将第 1 章 §5 接口清单落地为 `openapi.yaml`） | 后端 | P0 | 开发启动前 |
| 5 | 搭建三端工程骨架（frontend / backend / print-agent）+ 确定 Git 分支模型 | 前端 + 后端 + 助手 | P1 | 第 1 迭代 |
| 6 | 实现 `PaymentGateway` 抽象 + `MockPaymentGateway`（含 `{result:"FAIL"}` 参数、Redis 状态存储、可解析二维码内容） | 后端 | P1 | 第 1 迭代 |
| 7 | 按第 2 章落地 Nginx 站点 / systemd unit / 环境变量模板（`deploy/` 目录） | SRE / 运维 | P1 | 第 1 迭代 |
| 8 | 建立 CI 六阶段流水线 + 覆盖率门禁（后端 ≥70%、Pricing/Order ≥90%） | 后端 + 测试 | P1 | 第 2 迭代 |
| 9 | 准备测试文件样本集（11 个样本，含 0 页 / 损坏 / 伪装 / 超限）与计价规则 seed | 测试 | P1 | 第 2 迭代 |
| 10 | 实现打印助手可靠性机制（心跳 / 长轮询 / 超时回收 / spool 补报 / 打印机离线检测） | 助手开发 | P2 | 第 2 迭代 |
| 11 | 落地监控指标 + 巡检脚本 + 4 份故障 Runbook | SRE / 运维 | P2 | 第 3 迭代 |
| 12 | 硬件联调（真机出纸正确性人工核对）+ 24h 长稳测试 | 测试 + 助手 | P2 | 验收前 |

---

## ⚠️ 待完善 / 已知局限

1. **支付仅接口预留**：本期 `MockPaymentGateway` 可跑通全链路，但**未接入真实微信 / 支付宝**；真实接入需商户资质、证书、回调域名备案。切换回归清单见第 3 章 §7.5.5。
2. **4 项产品 / 工程口径待拍板**：0 页文件计费、双面奇数页口径（产品侧）；Mock 支付失败参数、`mock/confirm` 生产 profile 关闭门禁（工程侧，已给建议）。
3. **单机单点**：本设计面向单店单机，未含高可用集群与多店租户完整实现（仅预留 `merchant_id` 与对象存储切换点）。
4. **文件转换 / 渲染引擎未定型**：Word/Excel → 可打印格式的转换方案（LibreOffice headless / 第三方）需在实现阶段做 PoC，并评估页数解析准确率（R-5）。
5. **无实测容量数据**：200 并发 / 下单 P95<500ms 为**目标值**，需在 staging 用 k6 实测确认。
6. **短信通道未选定**：默认 `mock`（日志输出），生产需选定阿里云 / 腾讯云并完成签名报备。
7. **本次为设计阶段产出**：可运行系统、完整源码、系统测试报告（交付物①②④）属后续开发阶段，本设计已为其提供全部依据。

---

## 📚 数据来源 & 成员产出索引

| 成员 | 角色 | 原始产出文件 | 对应章节 |
|------|------|-------------|---------|
| Archi（阿奇） | 系统架构师 | `deliverables/engineering-assurance/chapters/01-需求分析与高层架构设计.md` | 第 1 章 |
| Rex（雷克斯） | SRE 工程师 | `deliverables/engineering-assurance/chapters/06-可运维性与可靠性.md` | 第 2 章 |
| Tessa（泰莎） | 测试专家 | `deliverables/engineering-assurance/chapters/07-测试策略与测试计划.md` | 第 3 章 |
| Docu（多库） | 技术文档师 | 术语表 / 图表清单 / 写作规范 | 附录 A |
| Zhen（甄宇航） | 工程督导（主理人） | TL;DR、核心结论卡片、风险汇总、行动清单、全文汇编 | 收口部分 |

**输入依据**：用户提供的项目需求（项目背景与目标 / 具体功能需求 / 技术栈要求 / 交付物要求 / 其他注意事项）。

---

## 附录 A 术语表、图表清单与写作规范（Docu 产出）

### A.1 术语表（中英文对照）

| 中文 | 英文 | 缩写 / 标识 | 定义 |
|------|------|-----------|------|
| 打印任务 | Print Job | `print_job` | 一次「文件 + 参数 + 计价 + 支付」的完整业务单元，是计费与打印的核心对象 |
| 计价规则 | Pricing Rule | `pricing_rule` | 可配置的计费配置（按页单价、彩色 / 黑白、单双面、纸张尺寸等） |
| 支付网关 | Payment Gateway | `PaymentGateway` | 支付渠道抽象接口，本期提供 Mock/Sandbox 实现，预留微信 / 支付宝接入 |
| 打印助手 | Print Agent | `PrintAgent` | 部署在公共电脑上的 .NET 客户端，负责领取任务、渲染并驱动打印机 |
| 终端 | Terminal | `terminal` | 一台公共电脑（含打印机）的注册实体，打印任务绑定到具体终端 |
| 会话 | Session | `session` | 用户登录后的临时状态，存于 Redis，含 token 与过期时间 |
| 验证码 | OTP / Verification Code | `otp` | 发送至用户手机号的一次性登录码 |
| 文件上传 | File Upload | `print_file` | 用户上传的原始文件记录（Word/Excel/PDF/图片） |
| 打印参数 | Print Options | `print_options` | 份数、彩色 / 黑白、单面 / 双面、纸张尺寸等打印选项集合 |
| 收款二维码 | Payment QR Code | `pay_qr` | 依据订单金额生成的收款码，供用户微信 / 支付宝扫码支付 |
| 支付订单 | Payment Order | `payment_order` | 与打印任务关联的支付单据，记录金额、状态、渠道流水号 |
| 支付回调 | Payment Callback | `callback` | 支付渠道异步通知支付结果的请求，需验签与幂等处理 |
| 幂等 | Idempotency | `idempotent` | 同一请求（如回调、领任务）重复执行结果一致，防止重复扣费 / 重复打印 |
| 打印队列 | Print Queue | `print_queue` | 待打印任务的排队结构，用于调度与限流 |
| 取件码 | Pickup Code / Print Ticket | `pickup_code` | 支付成功后生成、用户在打印机侧核对取件的短码 |
| 页数计量 | Page Count | `page_count` | 文件转换后实际需打印的页数，是计价的数量基准 |
| 单价 | Unit Price | `unit_price` | 单位页在特定参数下的价格，由计价规则给出 |
| 彩色 / 黑白 | Color / Monochrome | `color_mode` | 打印色彩模式枚举：COLOR / MONO |
| 单面 / 双面 | Simplex / Duplex | `duplex_mode` | 打印面模式枚举：SIMPLEX / DUPLEX |
| 文件转换 / 渲染 | File Conversion / Rendering | `render` | 将上传文件转为打印机可消费格式（如 PDF / 位图）的过程 |
| 打印驱动 | Print Driver | `driver` | 操作系统层与打印机通信的驱动，由打印助手调用 |
| 沙箱 | Sandbox | `sandbox` | 支付渠道的联调测试环境 |
| 对账 | Reconciliation | `reconcile` | 比对系统订单与支付渠道流水，发现差异并处理 |
| 退款 | Refund | `refund` | 打印失败等场景下将已付金额退回用户 |

### A.2 图表清单（建议绘制）

| # | 图名 | 类型 | 应表达的核心内容 | 建议章节 |
|---|------|------|------------------|----------|
| 1 | 系统上下文图 | 上下文 / 边界图 | 系统边界；4 类角色（用户 / 运营 / 打印助手 / 支付渠道）与外部依赖 | 第 1 章 §2.1 |
| 2 | 系统架构图 | 分层架构图 | Nginx→Java→MySQL/Redis 调用方向；.NET 助手在边缘的位置与通信通道 | 第 1 章 §2.2 |
| 3 | 组件图 | 组件 / 模块图 | 各进程内部模块划分（前端 / 后端 / 助手） | 第 1 章 §2.3 |
| 4 | 端到端主链路时序图 | 时序图 | 登录→上传→计价→支付→打印 完整消息序列 | 第 1 章 §7 |
| 5 | 支付时序图 | 时序图 | 下单→二维码→扫码→异步回调→验签幂等→触发打印（含超时 / 重试分支） | 第 1 章 §7.2 |
| 6 | 打印任务拉取时序图 | 时序图 | 助手轮询领任务→下载→渲染→调用驱动→上报（含失败重试与心跳） | 第 1 章 §7.3 |
| 7 | ER 图 | 实体关系图 | 核心实体与基数关系 | 第 1 章 §4.2 |
| 8 | 订单状态机 | 状态机图 | `pay_status` / `print_status` 流转与非法迁移 | 第 1 章 §4.3 |
| 9 | 部署拓扑图 | 部署图 | 主机与容器分布、公共电脑助手与打印机连接、网络分区与端口 | 第 1 章 §8 / 第 2 章 §1.1 |
| 10 | 文件处理流程图 | 流程图 | 上传校验→存储→转换渲染→页数计量→供计价 | 第 1 章 §4 / 第 2 章 §5.2 |

> 绘图规范：统一用 draw.io / Mermaid；节点命名与术语表一致；每图配 1 句「图注」说明它回答什么问题；跨图复用同一图例（颜色=层，实线=同步调用，虚线=异步 / 回调）。

### A.3 写作与命名规范

**标题层级**：全文仅 1 个 H1（文档标题）；章节 H2，小节 H3，最细 H4；禁止跳级。

**状态标记**：🔴 阻塞 / 高危 / 未完成 ｜ 🟠 风险 / 待处理 ｜ 🟡 待确认 / 需关注 ｜ 🟢 已完成 / 正常。

**命名约定**

| 对象 | 约定 | 示例 |
|------|------|------|
| 数据库表 | snake_case、复数 | `print_jobs`、`pricing_rules` |
| 数据库字段 | snake_case | `created_at`、`unit_price` |
| Java 类 / 方法 | PascalCase / camelCase | `PaymentGateway`、`createOrder()` |
| .NET 类 / 方法 | PascalCase | `PrintAgentService.ExecuteJob()` |
| REST 路径 | kebab-case、复数、带版本 | `/api/v1/print-jobs` |
| JSON 字段 | camelCase | `printJobId`、`colorMode` |
| 枚举值 | UPPER_SNAKE_CASE | `PENDING_PAYMENT`、`PRINT_SUCCESS` |
| 配置键 | 点分层级 | `payment.gateway.type` |
| 环境变量 | UPPER_SNAKE_CASE | `DB_HOST`、`PAYMENT_GATEWAY_TYPE` |

**代码块**：所有代码块标注语言（`java`/`sql`/`json`/`bash`/`csharp`）；API 示例成对给出「请求 + 响应」；命令块给出可复制执行的最小步骤与预期输出。

**通用原则**：结论先行；用表格 / 图替代长段落；引用需求 / 契约文档用链接避免多处维护；每章标注最后更新日期。

---

> 本报告由工程保障团队 AI 协作生成，关键决策请由人类工程负责人复核。
