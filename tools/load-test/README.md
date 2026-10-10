# 负载 / 压测工具（tools/load-test）

自助付费打印系统的容量摸底工具集，覆盖设计文档第 3 章 §7.6 的**非功能测试目标**：
单机 200 并发下单、下单接口 P95 < 500ms、错误率 < 1%。

| 文件 | 用途 | 依赖 |
|------|------|------|
| `k6-booking.js` | 标准压测脚本（200 VU，quote+orders 混合 3 分钟），带阈值门禁，可进 CI | 需安装 k6 |
| `load-test.mjs` | 纯 Node 零依赖压测脚本，无 k6 环境下的快速容量摸底 | 仅需 Node ≥ 18 |

两者都**自动完成登录与文件上传**（demo 档），无需手工准备数据。

---

## 一、k6 版（`k6-booking.js`）

### 1.1 安装 k6

```bash
# macOS
brew install k6

# Windows（winget）
winget install k6 --source winget
# 或 choco install k6

# Linux（Debian/Ubuntu）
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg \
  --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" \
  | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update && sudo apt-get install k6

# Docker（免安装）
docker run --rm -i --network host grafana/k6 run - < tools/load-test/k6-booking.js
```

### 1.2 运行

```bash
# 默认：http://127.0.0.1:8080，200 VU，3 分钟（demo 档需后端已启动）
k6 run tools/load-test/k6-booking.js

# 自定义目标与规模
k6 run -e BASE=http://127.0.0.1:8080 -e VUS=200 -e DURATION=3m tools/load-test/k6-booking.js

# 快速冒烟（10 VU / 15 秒），验证脚本可用
k6 run -e VUS=10 -e DURATION=15s tools/load-test/k6-booking.js

# staging / prod 无 debugCode：注入已签发的 JWT 与文件 ID
k6 run -e BASE=https://print.example.com \
       -e TOKEN="eyJhbGciOi..." -e FILE_ID=123 tools/load-test/k6-booking.js
```

可用环境变量（`-e KEY=VALUE`）：

| 变量 | 默认 | 说明 |
|------|------|------|
| `BASE` | `http://127.0.0.1:8080` | 后端基址（不带 `/api/v1`） |
| `VUS` | `200` | 并发虚拟用户数 |
| `DURATION` | `3m` | 持续时长 |
| `THINK_TIME` | `0.3` | 每次迭代后的思考时间（秒） |
| `TOKEN` / `FILE_ID` | — | 外部注入的 JWT 与文件 ID（staging/prod 用） |

### 1.3 结果解读

运行结束会输出摘要，重点看这几行：

```
http_req_duration........: avg=... min=... med=... max=... p(90)=... p(95)=...
http_req_failed..........: 0.32%  ✓ 234/72000
checks...................: 99.87% ✓ 143812 ✗ 187
booking_quote_duration...: avg=... p(95)=...
booking_order_duration...: avg=... p(95)=...
booking_orders_created...: 18000   (成功下单数)
```

| 指标 | 达标线 | 含义 |
|------|--------|------|
| `http_req_duration{scenario:booking} p(95)` | **< 500ms** | 下单链路 95 分位响应时间（阈值不达标 k6 退出码非 0） |
| `http_req_failed` | **< 1%** | HTTP 层失败率（连接错误 / 4xx / 5xx） |
| `booking_business_error_rate` | < 1% | 业务层错误率（`code≠0` 或缺少字段） |
| `checks rate` | > 99% | 断言通过率 |
| `booking_order_duration p(95)` | 参考 | 仅下单接口的 P95，更聚焦 |
| `booking_orders_created` | — | 压测期间成功创建订单总数（用于核对吞吐） |

> **阈值失败**：k6 进程以非 0 退出，CI 可直接据此判定性能不达标。

### 1.4 常见问题

- **`未取得 debugCode`**：目标不是 demo 档（未返回验证码）。改用 `-e TOKEN=... -e FILE_ID=...`，或在 staging 打开 `print.sms.debug=true`。
- **大量 429**：命中 Nginx 限流（通用 API `20r/s`）。压测应直接打后端 `127.0.0.1:8080` 或在压测环境临时放宽 `api_zone`。
- **P95 偏高但 CPU 空闲**：多为数据库连接池（`MYSQL_POOL_MAX=20`）或 Hikari `connection-timeout` 成为瓶颈，检查 `hikari.connections.active`。
- **想复现真实扫码支付链路**：本脚本只压「下单」，不含支付/打印；支付与打印链路另见 `smoke-test.mjs`。

---

## 二、Node 零依赖版（`load-test.mjs`）

无 k6 环境（如刚装好的机器、Windows 开发机）时的快速容量摸底，**只用 Node 内置能力**。

```bash
# 默认：50 VU / 30 秒 / 混合 quote+order，打 http://127.0.0.1:8080
node tools/load-test/load-test.mjs

# 自定义
node tools/load-test/load-test.mjs --base http://127.0.0.1:8080 --vus 50 --duration 30 --mix quote,order

# 带权重（quote 出现 3 次、order 出现 1 次）
node tools/load-test/load-test.mjs --vus 100 --duration 60 --mix quote:3,order:1

# 仅试算 / 仅下单
node tools/load-test/load-test.mjs --mix quote
node tools/load-test/load-test.mjs --mix order
```

参数：

| 参数 | 默认 | 说明 |
|------|------|------|
| `--base` | `http://127.0.0.1:8080` | 后端基址 |
| `--vus` | `50` | 并发数 |
| `--duration` | `30` | 持续秒数 |
| `--mix` | `quote,order` | 操作混合，支持 `name` 或 `name:weight` |
| `--token` | — | 复用外部 JWT（无 debugCode 时） |
| `--file-id` | — | 复用外部文件 ID |

输出示例：

```
=== 自助付费打印系统 · Node 压测 ===
目标: http://127.0.0.1:8080   并发: 50   时长: 30s   混合: quote,order

[setup] 登录成功 phone=138xxxxxxxx fileId=1
[进度] 12s  已完成 4821 请求  当前 QPS ≈ 401

=== 结果 ===
总请求数 : 12043
成功     : 11980 (99.48%)
失败     : 63 (0.52%)
QPS      : 401.4
延迟(ms) : P50=8.2  P95=41.6  P99=132.0  max=980.5
错误分类 : HTTP 500 × 12 | HTTP 429 × 48 | 业务错误 × 3
```

> 与 k6 的差异：Node 版为单进程异步并发，受 Node 事件循环与单核限制，
> **在极高并发下其自身可能先成为瓶颈**（表现为所有请求延迟同步抬高）。
> 200 VU 级正式验收请用 k6；Node 版用于「有没有明显性能问题」的快速判断。

---

## 三、压测前置与安全提醒

1. **只在 staging / 本地压测**，禁止直接压生产（会污染订单库、触发真实短信/支付）。
2. demo 档零依赖即可压测；staging 档需先按 `docs/staging-deployment.md` 起好 MySQL/Redis/后端。
3. 压测会产生大量订单记录，压测后建议重置 staging 库（`docs/staging-deployment.md` §7）。
4. 阈值不达标时，先排除环境干扰（压测机与被测机同机、CPU 抢占、限流），再判定容量问题。
