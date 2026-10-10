# 自助付费打印系统（Self-service Payment Printing System）

> 公共电脑 + 打印机 → 用户手机号登录 → 上传文件 → 选打印参数 → 扫码付费 → 支付成功后由打印助手自动出纸，全程无需人工干预。

技术栈严格遵循需求约束：**前端 Vue、后端 Java、打印助手 .NET**，配套 **Git / Nginx / Linux** 部署配置。

---

## 目录结构

```
.
├─ backend/            Java 17 + Spring Boot 3 + MyBatis-Plus（REST API、可插拔支付网关、打印任务调度）
├─ frontend/           Vue 3 + Vite + Pinia + Element Plus（用户端 + 管理端）
├─ print-agent/        .NET 8 Worker Service 打印助手（心跳 / 长轮询领任务 / 调用打印机 / 回传）
├─ tools/agent-sim/    Node.js 打印助手仿真器（无 .NET SDK 环境下跑通全链路）
├─ deploy/             Nginx 站点配置、systemd units、环境变量模板、备份与巡检脚本
├─ docker-compose.yml  本地 MySQL 8 + Redis 7（prod 档联调用）
├─ CONTRACT.md         ★ 接口契约（前后端 / 助手唯一约定）
├─ deliverables/       ★ 系统详细设计文档（工程保障团队产出）
└─ .github/workflows/  CI 流水线（三端构建 + 测试）
```

---

## 快速开始（demo 档 · 零外部依赖）

demo 档使用 **H2 内存库 + 内存队列 + 日志短信 + Mock 支付网关**，无需安装 MySQL / Redis / Docker 即可跑通全链路。

### 1. 启动后端

```bash
cd backend
mvn spring-boot:run
# 或：mvn -DskipTests package && java -jar target/print-backend-1.0.0.jar
```

启动后：`http://127.0.0.1:8080`（健康检查 `/actuator/health`，H2 控制台 `/h2-console`）。

### 2. 启动前端

```bash
cd frontend
npm install --registry=https://registry.npmmirror.com
npm run dev
```

浏览器打开 `http://127.0.0.1:5173`。

### 3. 启动打印助手

**方式 A：Node 仿真器（推荐，本机即可跑）**

```bash
node tools/agent-sim/agent-sim.mjs \
  --backend http://127.0.0.1:8080 \
  --terminal TERM-001 \
  --token dev-agent-token
```

**方式 B：.NET 8 打印助手（需安装 .NET 8 SDK）**

```bash
cd print-agent
dotnet run
```

### 4. 全链路演示

1. 打开前端 `/login`，输入手机号（如 `13800000001`）→ 点「发送验证码」
   - demo 档验证码会打印在后端控制台，并直接回填到输入框
2. 登录 → `/upload` 上传一个 PDF / 图片
3. `/params` 选择份数、彩色/黑白、单面/双面、纸张、页码范围 → 实时试算 → 提交下单
4. `/pay/:orderNo` 页面展示收款二维码 → 点「模拟支付成功」
5. 助手仿真器随即领取任务并「打印」，前端 `/status/:orderNo` 显示 **已完成**

> 预置数据：终端 `TERM-001`（token `dev-agent-token`）；计价规则 A4 黑白 0.20/页、A4 彩色 0.80/页、A3 黑白 0.40/页，双面折扣 0.90，起印价 1.00。

---

## 生产部署（prod 档）

```bash
# 1. 启动 MySQL + Redis
docker compose up -d

# 2. 初始化数据库表结构
mysql -h127.0.0.1 -uprint -pprint123 print_db < backend/src/main/resources/db/mysql/schema.sql

# 3. 配置环境变量并启动后端（systemd）
sudo cp deploy/systemd/print-backend.service /etc/systemd/system/
sudo cp deploy/env/backend.env.example /etc/print/backend.env   # 修改其中的密钥
sudo systemctl daemon-reload && sudo systemctl enable --now print-backend

# 4. Nginx
sudo cp deploy/nginx/print.conf /etc/nginx/conf.d/print.conf
sudo nginx -t && sudo systemctl reload nginx
```

详细步骤见 `deploy/README.md` 与设计文档第 2 章。

---

## 测试

```bash
cd backend && mvn test        # 单元 + 集成（demo 档 H2）
cd frontend && npm run test   # 前端单测（如已配置）
```

CI 流水线见 `.github/workflows/ci.yml`；测试策略与用例矩阵见设计文档第 3 章。

---

## 相关文档

| 文档 | 说明 |
|------|------|
| `deliverables/engineering-assurance/system-design-自助付费打印系统-2026-10-10.md` | 系统详细设计文档（需求 / 架构 / ADR / 数据模型 / API / 可运维性 / 测试策略） |
| `CONTRACT.md` | 接口契约（唯一约定，改动需先改此文件） |
| `deploy/README.md` | 部署手册 |

---

## 已知边界

- **支付为接口预留**：本期 `MockPaymentGateway` 可跑通全链路，真实微信 / 支付宝接入需商户资质，切换时业务代码零改动（实现同一 `PaymentGateway` 接口即可）。
- **文件转换引擎为占位实现**：页数解析当前为轻量策略（PDF 统计页面对象 / Office 按体积估算），生产需替换为 LibreOffice headless 等统一转换引擎。
- **demo 档数据不持久**：H2 内存库随进程退出清空。
