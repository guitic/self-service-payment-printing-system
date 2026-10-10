# 监控落地手册（deploy/monitoring/metrics.md）

> 将设计文档第 2 章 §4.1「核心指标」落成**可执行的采集方案**。
> 原则：单店单机不引入 Prometheus 全家桶，用「Spring Actuator + 采集脚本 + cron 巡检 + 告警通道」实现；
> 规模化可平滑升级（见文末 §5）。

采集入口脚本：[`deploy/scripts/metrics_collect.sh`](../scripts/metrics_collect.sh)（输出 `metric=value` 行）。
巡检告警脚本：[`deploy/scripts/health_check.sh`](../scripts/health_check.sh)。

---

## 0. 采集机制与鉴权（先读）

| 数据源 | 是否需要鉴权 | 说明 |
|--------|--------------|------|
| `GET /actuator/health` | 否 | `SecurityConfig` 放行 `/actuator/health/**`，返回整体 `status` |
| `GET /actuator/health`（components 明细） | **是（JWT）** | 配置 `show-details: when-authorized`，带 JWT 才返回 `components.db/redis/diskSpace/ping` |
| `GET /actuator/metrics/*` | **是（JWT）** | `anyRequest().authenticated()`，无 token 返回 `401` |
| MySQL 业务聚合 | MySQL 账号 | 从 `/etc/print/backend.env` 读连接信息 |

> ⚠️ **已知问题（本构建）**：带 JWT 访问 `/actuator/health` 会返回
> `500 NoClassDefFoundError: com/fasterxml/jackson/databind/ser/BasicSerializerFactory$1`，
> 导致 `components` 明细无法获取（health 序列化缺陷）。因此 `metrics_collect.sh` 对
> `backend_health_db/redis/disk/ping` 输出 `NA`，整体可用性以 `backend_health` 为准。
> 该项应在后端修复后自动生效（脚本无需改动）。

**JWT 获取**（`metrics_collect.sh` 内置，按序尝试）：

1. 环境变量 `METRICS_JWT`（**生产推荐**：为巡检建一个固定账号或长期 token）；
2. demo / staging（`print.sms.debug=true`）自动走 `/auth/sms/send` 取 `debugCode` → `/auth/login`；
3. 都不可用 → metrics 段输出 `NA`（health 与 DB 段不受影响）。

**快速自测**：

```bash
# demo 档（后端 8080）：
bash deploy/scripts/metrics_collect.sh

# 指定地址 / 指定 token：
BACKEND_BASE=http://127.0.0.1:8080 bash deploy/scripts/metrics_collect.sh
METRICS_JWT="eyJhbGciOi..." bash deploy/scripts/metrics_collect.sh
```

---

## 1. 核心指标清单（§4.1 落地）

> 表头约定：**正常值**为稳态目标；**告警阈值**分级 🟠 一般 / 🔴 严重；
> 指标名对应 `metrics_collect.sh` 的输出键（可直接被巡检脚本 grep）。

| # | 指标名（脚本输出键） | 含义 | 采集命令 / 接口 | 正常值 | 告警阈值 | 告警动作 |
|---|----------------------|------|-----------------|--------|----------|----------|
| 1 | `db_print_success_rate_1h` | 打印成功率（成功任务 / 终态任务，滚动 1h） | SQL（见 §2.1）或 `metrics_collect.sh` | ≥ 98% | 🟠 < 95%，🔴 < 90% | 查 `journalctl -u print-agent`；核对打印机状态；达重试上限转退款/补打工单（Runbook-1） |
| 2 | `db_pay_success_rate_1h` | 支付成功率（已支付 / 近 1h 创建订单） | SQL（见 §2.2） | ≥ 95% | 🟠 < 90% | 查回调是否丢失、网关连通性（Runbook-2）；必要时切备用渠道 |
| 3 | `db_pending_tasks` | 任务队列积压（待领取任务数） | `SELECT COUNT(*) FROM t_print_task WHERE status=0` | ≤ 5 | 🟠 > 20，🔴 > 50 | 助手是否在线？在线但堆积 → 打印机故障/助手假死（Runbook-1/3） |
| 4 | `db_terminals_online` / `db_terminals_total` | 助手在线率（在线终端 / 总终端） | SQL（见 §2.3） | 100% | 🔴 单店有终端离线 > 90s | 立即查终端网络/进程/token（Runbook-3） |
| 5 | `http_server_requests_p95_ms` | 下单接口响应 P95 | Actuator `http.server.requests`（需开分位，见 §2.4） | < 300ms | 🟠 > 500ms | 查连接池/慢 SQL/GC；参考 `tools/load-test` 复现 |
| 6 | `http_server_requests_avg_ms` + 日志 | 接口错误率（5xx / 总请求，5min） | 日志聚合（见 §2.5）或 Nginx `print.access.log` | < 0.5% | 🟠 > 1%，🔴 > 5% | `journalctl -u print-backend --since "5 min ago" -p err`；定位后按栈处理 |
| 7 | `reconcile_compensations_1d` | 支付兜底命中次数（轮询/对账补状态） | 对账报告 + 日志 grep（见 §2.6） | 0 | 🟠 ≥ 1/天 | 说明回调不稳：排查回调地址/证书/验签 |
| 8 | `disk_used_pct_root` / `disk_used_pct_data` | 磁盘使用率（`/` 与 `/data`） | `df -P <挂载点>` | < 70% | 🟠 > 80%，🔴 > 90% | 清理 binlog/日志/备份/过期文件；扩容（§2.7） |
| 9 | `db_stuck_paid_orders` | 订单超时未终态（已支付但 5min 未打印完成） | SQL（见 §2.8） | 0 | 🟠 ≥ 1 | 查任务是否卡「打印中」→ Runbook-1；必要时人工补打/退款 |

**辅助指标（脚本附带，便于诊断）**

| 输出键 | 含义 | 正常 |
|--------|------|------|
| `backend_health` | 后端整体健康 | `UP` |
| `jvm_memory_used_bytes` | JVM 堆使用（配合 `-Xmx1g`） | < 800MB |
| `process_cpu_usage_pct` / `system_cpu_usage_pct` | 进程 / 系统 CPU | < 70% |
| `hikaricp_active_connections` / `hikaricp_max_connections` | 连接池占用 / 上限 | active < max（默认 20） |
| `hikaricp_pending_connections` | 等待连接的请求数 | 0 |
| `db_printing_tasks` / `db_retry_tasks` / `db_failed_tasks` | 打印中 / 重试中 / 失败任务 | 打印中稳态趋 0 |
| `db_orders_created_1h` / `db_orders_paid_1h` | 近 1h 下单 / 支付成功数 | 业务参考 |

---

## 2. 每项指标的具体采集命令

> 以下 SQL 直接可跑（生产库 `print_db`）；`mysql -h127.0.0.1 -uprint -p print_db -e "..."`。
> 建议统一通过 `metrics_collect.sh` 采集，避免密码出现在命令行历史。

### 2.1 打印成功率（滚动 1h）

```sql
SELECT ROUND(
  SUM(status = 3) / NULLIF(SUM(status IN (3,4)), 0) * 100, 2
) AS print_success_rate_1h
FROM t_print_task
WHERE deleted = 0 AND updated_at >= NOW() - INTERVAL 1 HOUR;
```

### 2.2 支付成功率（滚动 1h）

```sql
SELECT ROUND(
  SUM(pay_status = 1) / NULLIF(COUNT(*), 0) * 100, 2
) AS pay_success_rate_1h
FROM t_print_order
WHERE deleted = 0 AND created_at >= NOW() - INTERVAL 1 HOUR;
```

### 2.3 任务队列积压 + 助手在线率

```sql
-- 队列积压（待领取）
SELECT COUNT(*) FROM t_print_task WHERE deleted = 0 AND status = 0;

-- 助手在线率
SELECT
  SUM(online_status = 1) AS online,
  COUNT(*)              AS total,
  ROUND(SUM(online_status = 1) / NULLIF(COUNT(*), 0) * 100, 2) AS online_pct
FROM t_terminal WHERE deleted = 0;

-- 最近心跳（判断单店是否掉线）
SELECT code, online_status, last_heartbeat,
       TIMESTAMPDIFF(SECOND, last_heartbeat, NOW()) AS silent_seconds
FROM t_terminal WHERE deleted = 0;
```

### 2.4 下单 P95（Actuator）

Actuator 的 `http.server.requests` 默认只有 `COUNT/TOTAL_TIME/MAX`，**不含分位**。开启分位后再采集：

```yaml
# application-prod.yml（后端配置，需重启生效；属性能监控配置，非业务代码）
management:
  metrics:
    distribution:
      percentiles:
        http.server.requests: 0.5, 0.95, 0.99
      percentiles-histogram:
        http.server.requests: true
```

```bash
# 取 P95（需 JWT；下例假设已有 token）
curl -s -H "Authorization: Bearer $METRICS_JWT" \
  "http://127.0.0.1:8080/actuator/metrics/http.server.requests" \
  | grep -o '"statistic":"0.95","value":[^,}]*'
```

未开启分位时的替代手段：

```bash
# 用负载脚本现测 P95（推荐，最直观）
node tools/load-test/load-test.mjs --base http://127.0.0.1:8080 --vus 50 --duration 30 --mix order
# 或从 Nginx 访问日志统计（request_time 为秒）
awk '{print $NF}' /var/log/nginx/print.access.log | sort -n | awk '{a[NR]=$1} END{print "P95=" a[int(NR*0.95)] "s"}'
```

### 2.5 接口错误率（5xx，5min）

```bash
# 后端日志（含 5xx 结构化日志；按实际日志格式调整 grep 关键词）
journalctl -u print-backend --since "5 min ago" --no-pager \
  | grep -cE 'ERROR|status=5[0-9]{2}'

# 或从 Nginx 访问日志：5xx 占比
total=$(awk 'END{print NR}' /var/log/nginx/print.access.log)
e5xx=$(awk '$9 ~ /^5/ {c++} END{print c+0}' /var/log/nginx/print.access.log)
awk -v e="$e5xx" -v t="$total" 'BEGIN{printf "5xx_rate=%.3f%%\n", (t>0? e/t*100:0)}'
```

### 2.6 支付兜底命中（轮询/对账补状态）

```bash
# 对账报告目录（每日产出，差异数非 0 即告警）
ls -t docs/reconcile-report/ 2>/dev/null | head
grep -c '回调丢失\|渠道有.*本地无' docs/reconcile-report/*.log 2>/dev/null

# 日志中补偿打点（按实际埋点关键词）
journalctl -u print-backend --since "1 day ago" --no-pager | grep -c '补偿\|compensat\|query.*SUCCESS'
```

### 2.7 磁盘水位

```bash
df -P / /data | awk 'NR>1 {gsub("%","",$5); print $6"="$5"%"}'
# 明细定位（哪个目录吃满）
du -sh /data/uploads /data/backup /var/log/print /var/lib/mysql 2>/dev/null | sort -h
```

### 2.8 订单超时未终态

```sql
SELECT COUNT(*) AS stuck_paid_orders
FROM t_print_order
WHERE deleted = 0
  AND pay_status = 1
  AND print_status <> 2
  AND updated_at < NOW() - INTERVAL 5 MINUTE;
```

---

## 3. 定时采集与告警接入

### 3.1 cron

```cron
# 每 1 分钟采集指标，落 textfile（供 Telegraf / 自研看板消费）
* * * * * /opt/print/deploy/scripts/metrics_collect.sh > /var/lib/print/metrics.prom 2>/dev/null
# 每 5 分钟巡检 + 告警（退出码 0/1/2）
*/5 * * * * /opt/print/deploy/scripts/health_check.sh
```

> `metrics_collect.sh` 输出为 `metric=value`，与 Prometheus textfile 格式兼容；
> 若需完整 Prometheus 文本格式，在 `emit()` 中追加 `# TYPE` 行即可。

### 3.2 告警判定示例（消费 `metric=value`）

```bash
# 读取采集结果并判定（示例）
M=$(bash deploy/scripts/metrics_collect.sh)
val() { echo "$M" | grep "^$1=" | cut -d= -f2; }

rate=$(val db_print_success_rate_1h)
[[ "$rate" != NA ]] && awk -v r="$rate" 'BEGIN{exit !(r<95)}' && echo "🟠 打印成功率 ${rate}% < 95%"
[[ "$(val db_stuck_paid_orders)" != "0" && "$(val db_stuck_paid_orders)" != "NA" ]] && echo "🟠 存在超时未终态订单"
```

### 3.3 告警通道与降噪（对齐 §4.4）

- 通道：门店运维群 Webhook（企业微信/钉钉）+ 关键项短信；分级 🔴 立即 / 🟠 15min / 🟡 日报。
- 降噪：同告警 10min 去重；维护窗口静默；短抖动（单次心跳丢失）不告警，连续 2 周期才触发。

---

## 4. 指标不可得时的排查顺序

| 现象 | 排查 |
|------|------|
| `backend_health=DOWN` / `NA` | 后端进程是否存活：`systemctl status print-backend`；端口：`ss -lntp | grep 8080` |
| metrics 段全 `NA` | 未取得 JWT：设 `METRICS_JWT` 或确认目标为 demo/staging（`sms.debug=true`）；确认 `curl` 可达 `127.0.0.1:8080` |
| DB 段全 `NA` | 本机无 mysql 客户端或 `MYSQL_PASSWORD` 为空；确认 `/etc/print/backend.env` 可读（权限 600，属主 `print`，巡检以 root 运行） |
| `backend_health_*=NA` 但整体 UP | 见 §0 已知问题（health 明细序列化缺陷），属预期 |
| 指标值明显异常 | 交叉验证：直接跑 §2 的 SQL；对照 `journalctl` 与 `tools/smoke-test.mjs` |

---

## 5. 平滑升级到 Prometheus（规模化）

当前方案的所有指标都可一一映射，无需改造业务代码：

1. 后端加依赖 `micrometer-registry-prometheus`，暴露 `/actuator/prometheus`（`exposure.include` 追加 `prometheus`）。
2. `metrics_collect.sh` 改为被 Telegraf `inputs.exec` 或 `node_exporter textfile` 采集，指标名自动转为 Prometheus 规范。
3. MySQL 业务指标用 `mysqld_exporter` 的 custom query（`t_print_task`/`t_print_order` 聚合 SQL 照搬 §2）。
4. 告警规则迁移到 Alertmanager，阈值沿用 §1 表格。
