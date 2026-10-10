# 打印助手本地仿真器（agent-sim）

纯 Node.js 实现的打印助手仿真器，与 `print-agent/`（.NET 8）**行为等价**，用于本机零依赖端到端联调。
只使用 Node 内置能力（`fetch` / `fs` / `process`），**无任何第三方依赖**，无需 `npm install`。

## 环境要求

- Node.js ≥ 18（本机已用 v22 验证；需要全局 `fetch`）

## 快速开始

```bash
cd tools/agent-sim
node agent-sim.mjs --backend http://127.0.0.1:8080 --terminal TERM-001 --token dev-agent-token
```

`Ctrl+C` 优雅退出（等价 SIGINT/SIGTERM）。

## CLI 参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `--backend <url>` | `http://127.0.0.1:8080` | 后端基址（不带 `/api/v1`） |
| `--terminal <code>` | `TERM-001` | 终端编码 |
| `--token <token>` | `dev-agent-token` | `X-Agent-Token`（demo 档预置） |
| `--fail-rate <0..1>` | `0` | 模拟打印失败概率，用于验证重试与失败上报 |
| `--print-seconds <n>` | `2` | 单次模拟打印耗时（秒），可为小数 |
| `--poll-interval <n>` | `3` | 空闲轮询间隔（秒） |
| `--long-poll <n>` | `25` | 长轮询 hold 秒数，**须 < 65**（Nginx 反代超时） |
| `--heartbeat <n>` | `30` | 心跳间隔（秒） |
| `--max-retry <n>` | `3` | 本地瞬时错误重试上限 |
| `--spool-dir <dir>` | `./spool` | 离线待补报 + 下载文件目录 |
| `--printer <name>` | `Default Printer` | 上报的打印机名 |
| `-h, --help` | — | 显示帮助 |

参数支持 `--key value` 与 `--key=value` 两种写法。

## 行为说明（与 .NET 助手一致）

- **心跳**：每 `--heartbeat` 秒 `POST /api/v1/agent/heartbeat`，上报 `terminalCode / agentVersion / printers`。
- **长轮询领取**：`POST /api/v1/agent/tasks/claim`，`longPollSeconds` 默认 25。
- **下载文件**：`GET /api/v1/agent/tasks/{id}/file` → 落 `spool/files/`。
- **模拟打印**：延时 `--print-seconds`，输出结构化日志；`--fail-rate` 概率注入瞬时失败。
- **本地重试**：瞬时错误退避 `3s/6s/12s`，上限 `--max-retry` 次。
- **回传**：`POST /api/v1/agent/tasks/{id}/report` `{status, failReason}`。
- **断线重连**：指数退避 `1→2→4→8→16→32→60s`，叠加 ±20% 抖动；429 尊重 `Retry-After`；4xx 不重试仅告警；5xx/超时/网络不可达重试。
- **离线 spool 补报**：回传失败时把结果写 `spool/*.json`（临时文件 + 原子 rename），恢复后按完成时间升序补报；补报幂等。
- **优雅退出**：收到 SIGINT/SIGTERM 后中止在途请求；若此时正待回传，先落盘再退出，确保不丢结果。

## 日志

格式：`[HH:MM:SS.mmm] [LEVEL] 消息`，包含任务号与阶段（`CLAIM` / `DOWNLOAD` / `PRINT` / `REPORT` / `SPOOL`）。
`WARN` / `ERROR` 输出到 stderr。

## 与真实后端的联调

后端需提供 `CONTRACT.md §3.6` 的四个助手接口，并预置终端 `TERM-001` / `agentToken=dev-agent-token`（demo 档）。
推荐联调流程：

```bash
# 1. 启动后端（demo 档，端口 8080）
# 2. 启动仿真器
node agent-sim.mjs --backend http://127.0.0.1:8080 --terminal TERM-001 --token dev-agent-token --fail-rate 0.1 --print-seconds 2
# 3. 在 SPA 下单支付，观察仿真器日志出现 CLAIM → DOWNLOAD → PRINT → REPORT
```

## 目录产物

运行时会创建 `spool/`：

```
spool/
├── files/                      # 下载的待打印文件（打印后自动清理）
└── *.json                      # 未成功回传的结果（补报成功后自动清理）
```

`ls spool/` 可快速判断是否有积压；`spool/` 已在根 `.gitignore` 中忽略。
