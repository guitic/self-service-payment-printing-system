# 故障 Runbook（docs/runbook.md）

> 落地设计文档第 2 章 §6.3 的 4 个 Runbook 为**可执行步骤**。
> 目标：MTTR < 15 min。每个 Runbook 统一结构：**确认 → 定位 → 处置 → 验证**。
> 命令均针对本项目实际服务名与路径：后端 `print-backend`、助手 `print-agent`、
> 上传目录 `/data/uploads`、后端环境文件 `/etc/print/backend.env`。

## 通用前提

| 项 | 值 |
|----|----|
| 后端服务 | `systemd: print-backend`（jar：`/opt/print/backend/app.jar`，监听 `127.0.0.1:8080`） |
| 助手服务 | `systemd: print-agent`（Linux）；Windows 终端为服务 `PrintAgent` |
| 数据库 | MySQL 8，库 `print_db`，账号 `print`（连接信息在 `/etc/print/backend.env`） |
| 缓存/队列 | Redis 7（`127.0.0.1:6379`） |
| 上传目录 | `/data/uploads` |
| 助手 spool | `/var/lib/print-agent/spool`（离线待补报积压） |
| 巡检脚本 | `deploy/scripts/health_check.sh`、`deploy/scripts/metrics_collect.sh` |
| 指标键 | `metrics_collect.sh` 输出的 `metric=value`（如 `db_stuck_paid_orders`） |

**载入数据库凭据**（后续 SQL 复用；避免密码进历史，用环境文件）：

```bash
set -a; . /etc/print/backend.env; set +a
mysql() { command mysql -h"${MYSQL_HOST:-127.0.0.1}" -P"${MYSQL_PORT:-3306}" -u"${MYSQL_USER:-print}" -p"${MYSQL_PASSWORD}" "${MYSQL_DB:-print_db}" "$@"; }
```

**一键巡检**（先看全局，再进具体 Runbook）：

```bash
bash /opt/print/deploy/scripts/metrics_collect.sh
bash /opt/print/deploy/scripts/health_check.sh; echo "exit=$?"
```

---

## Runbook-1：打印卡死（任务长期「打印中」）

**现象**：用户已付费，但订单 `print_status=打印中` 长时间不变；`db_stuck_paid_orders > 0`。

### 1. 确认

```bash
# ① 是否存在卡死任务（claimed_at 超 2 分钟仍未回传）
mysql -e "
SELECT t.id, t.order_no, t.status, t.retry_count, t.claimed_at,
       TIMESTAMPDIFF(SECOND, t.claimed_at, NOW()) AS claimed_seconds
FROM t_print_task t
WHERE t.deleted=0 AND t.status IN (1,2)
  AND t.claimed_at < NOW() - INTERVAL 2 MINUTE;"

# ② 指标确认
bash /opt/print/deploy/scripts/metrics_collect.sh | grep -E 'db_printing_tasks|db_stuck_paid_orders'
```

若 ① 有输出 → 进入定位。

### 2. 定位

```bash
# 助手日志（最近 200 行，重点看 CLAIM/DOWNLOAD/PRINT/REPORT 阶段）
journalctl -u print-agent -n 200 --no-pager

# 助手进程状态（是否假死/未运行）
systemctl status print-agent --no-pager

# 离线补报积压（>0 说明断网或回传失败）
ls -l /var/lib/print-agent/spool/ 2>/dev/null

# 终端心跳是否新鲜（silent_seconds 应 < 90）
mysql -e "
SELECT code, online_status, last_heartbeat,
       TIMESTAMPDIFF(SECOND, last_heartbeat, NOW()) AS silent_seconds
FROM t_terminal WHERE deleted=0;"
```

**常见根因判定**：
- 打印机面板显示缺纸/卡纸/离线/缺墨 → 物理故障；
- 日志停在 `DOWNLOAD` → 助手到后端/文件存储的网络问题；
- 日志停在 `PRINT` 且无后续 → 打印机驱动/队列忙；
- `systemctl status` 显示进程卡住（无日志心跳）→ 助手假死。

### 3. 处置

```bash
# 3.1 物理故障（缺纸/卡纸/离线）：现场处理后打印机自愈，等待后端超时回收自动重试。
#     （无需重启；回收阈值 TASK_CLAIM_TIMEOUT_SECONDS=120s）

# 3.2 助手假死/停止：重启助手
sudo systemctl restart print-agent
systemctl status print-agent --no-pager
journalctl -u print-agent -f          # 观察 spool 补报与 CLAIM 恢复

# 3.3 手动回收单个卡死任务（当自动回收未生效、需即时恢复时；谨慎，先确认助手确已失联）
#     把「打印中」重置为「待领取」，可被重新领取：
mysql -e "
UPDATE t_print_task
SET status=0, claimed_at=NULL
WHERE order_no='P2026XXXXXXXXXX' AND status IN (1,2) AND deleted=0;"
# 注意：若助手随后补报成功，仍以成功为准（状态机 CAS 保护，成功不可被覆盖）。

# 3.4 任务已反复失败、达重试上限（TASK_MAX_RETRY=3）：转异常工单，人工补打或退款
mysql -e "
SELECT order_no, retry_count, fail_reason FROM t_print_task
WHERE deleted=0 AND status=4;"
```

### 4. 验证

```bash
# 订单最终收敛为「已完成」（print_status=2）
mysql -e "
SELECT order_no, pay_status, print_status, updated_at
FROM t_print_order WHERE order_no='P2026XXXXXXXXXX';"

# 队列不再有卡死项
mysql -e "
SELECT COUNT(*) AS printing FROM t_print_task
WHERE deleted=0 AND status IN (1,2) AND claimed_at < NOW() - INTERVAL 2 MINUTE;"
# 期望 0；且 db_stuck_paid_orders=0

# 打印成功率回升
bash /opt/print/deploy/scripts/metrics_collect.sh | grep db_print_success_rate_1h
```

> 若确认无法补打，走退款流程（设计文档 §3.5），并记录工单。

---

## Runbook-2：支付回调丢失（用户已付但订单仍待支付）

**现象**：用户反馈已扣款，但订单 `pay_status=0`（待支付）；`db_pay_success_rate_1h` 下降。

### 1. 确认

```bash
# ① 订单与支付流水现状
mysql -e "
SELECT o.order_no, o.pay_status, o.amount, o.created_at,
       p.gateway, p.trade_no, p.status AS pay_record_status, p.pay_time
FROM t_print_order o
LEFT JOIN t_payment_record p ON p.order_no = o.order_no
WHERE o.order_no='P2026XXXXXXXXXX';"

# ② 该订单是否有成功流水（渠道侧/本地）
mysql -e "SELECT * FROM t_payment_record WHERE order_no='P2026XXXXXXXXXX';"

# ③ 近期回调与兜底是否异常
journalctl -u print-backend --since "30 min ago" --no-pager | grep -iE 'notify|回调|verify|验签'
```

**判定**：订单 `pay_status=0` 且本地无成功流水 → 回调丢失（或验签失败）。

### 2. 定位

```bash
# 回调入口是否收到请求（Nginx 访问日志）
grep '/api/v1/payments/notify' /var/log/nginx/print.access.log | tail -20

# 验签失败 / 金额不符 / 时间窗异常
journalctl -u print-backend --since "1 hour ago" --no-pager | grep -iE '验签|signature|金额|replay|nonce'

# 回调地址与证书（微信 V3 / 支付宝）
grep -E 'NOTIFY_URL|CERT|PRIVATE_KEY' /etc/print/backend.env
```

**常见根因**：回调 URL 不可达（Nginx/防火墙）、证书过期或平台证书未更新、验签实现与渠道不一致、时间戳窗口（±5min）漂移。

### 3. 处置

```bash
# 3.1 首选：前端轮询会触发主动查询，通常秒级自愈；也可手动触发状态查询
curl -s -H "Authorization: Bearer $USER_JWT" \
  "http://127.0.0.1:8080/api/v1/payments/P2026XXXXXXXXXX/status"; echo

# 3.2 等定时兜底（每 1 min 扫描「待支付且已过期」订单主动 query 网关），无需干预

# 3.3 仍未自愈：到渠道商户后台确认到账，确认后人工补记成功 + 补投打印任务（mock 档可用 confirm 走同一幂等逻辑）
#     ⚠️ 生产真实渠道请用渠道后台/对账补单，禁止绕过验签直接改库；下面为 mock/应急示例：
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"orderNo":"P2026XXXXXXXXXX","result":"SUCCESS"}' \
  "http://127.0.0.1:8080/api/v1/payments/mock/confirm"; echo

# 3.4 记录为「回调丢失」事件；若频发（≥1/天）→ 排查回调地址/证书/验签（§2）
```

> **幂等保证**：无论走轮询、定时兜底还是人工补记，均经状态机 CAS
> （`UPDATE ... WHERE pay_status=0`）与唯一约束，重复确认**不会**重复投递打印任务（CONTRACT §5）。

### 4. 验证

```bash
mysql -e "
SELECT order_no, pay_status, print_status FROM t_print_order WHERE order_no='P2026XXXXXXXXXX';
SELECT COUNT(*) AS tasks FROM t_print_task WHERE order_no='P2026XXXXXXXXXX';"
# 期望：pay_status=1；tasks=1（唯一，不重复）

# 支付成功率回升、兜底命中数
bash /opt/print/deploy/scripts/metrics_collect.sh | grep -E 'db_pay_success_rate_1h|db_orders_paid_1h'
```

---

## Runbook-3：打印助手掉线

**现象**：管理端终端显示「离线」；`last_heartbeat` 超 90s；`db_terminals_online < db_terminals_total`。

### 1. 确认

```bash
mysql -e "
SELECT code, name, online_status, last_heartbeat,
       TIMESTAMPDIFF(SECOND, last_heartbeat, NOW()) AS silent_seconds
FROM t_terminal WHERE deleted=0;"
# silent_seconds > 90 且 online_status=0 → 离线

bash /opt/print/deploy/scripts/metrics_collect.sh | grep -E 'db_terminals_(online|total)'
```

### 2. 定位

```bash
# 助手进程/服务状态
systemctl status print-agent --no-pager

# 助手日志（重连/401/网络错误）
journalctl -u print-agent -n 100 --no-pager
journalctl -u print-agent --since "30 min ago" --no-pager | grep -iE '401|unauthor|重连|retry|timeout|refused'

# 终端机到后端的连通性（在终端机上执行）
curl -sS -o /dev/null -w '%{http_code}\n' https://print.example.com/actuator/health

# 后端是否正常（从服务端看）
systemctl status print-backend --no-pager
```

**常见根因**：
- 助手进程停止/崩溃 → `systemctl status` 显示 inactive/failed；
- 终端机断网/关机 → 连通性命令超时；
- `401` → `PRINT_AGENT_TOKEN` 失效/被换（4xx 助手不重试，需人工换 token）；
- 后端不可用 → 助手进入指数退避重连（1→2→4→…→60s）。

### 3. 处置

```bash
# 3.1 进程停止 → 启动
sudo systemctl start print-agent && systemctl status print-agent --no-pager

# 3.2 网络不通 → 检查终端机网络、DNS、到后端 443 的连通性；恢复后助手自动重连

# 3.3 401 → 更新 token 并重启（token 与后端 t_terminal.agent_token 一致）
sudo vi /etc/print/agent.env            # 修改 PRINT_AGENT_TOKEN
sudo systemctl restart print-agent

# 3.4 助手假死（进程在但无心跳）→ 重启
sudo systemctl restart print-agent

# 3.5 期间新任务堆积在「待领取」，恢复后自动消费，无需人工干预；
#     若 spool 有积压，助手恢复后按完成时间升序补报（幂等）
ls -l /var/lib/print-agent/spool/
```

**Windows 终端**：

```bat
sc query PrintAgent
sc start PrintAgent
:: 恢复策略：服务属性 → 恢复 → 第一次/第二次/后续失败 = 重新启动服务
```

### 4. 验证

```bash
# 终端恢复在线、心跳刷新
mysql -e "
SELECT code, online_status, last_heartbeat,
       TIMESTAMPDIFF(SECOND, last_heartbeat, NOW()) AS silent_seconds
FROM t_terminal WHERE deleted=0;"
# 期望 online_status=1 且 silent_seconds < 90

# 队列积压回落
bash /opt/print/deploy/scripts/metrics_collect.sh | grep -E 'db_pending_tasks|db_terminals_online'
```

---

## Runbook-4：MySQL 不可用

**现象**：无法下单/支付/查询；后端 health 的 `db` 组件 DOWN（或 readiness 失败）；`backend_health=DOWN`。

### 1. 确认

```bash
# 服务状态与错误日志
systemctl status mysql --no-pager
journalctl -u mysql --since "30 min ago" --no-pager | tail -50

# 连通性
mysqladmin ping -h127.0.0.1 -P3306 --silent && echo "MySQL 存活" || echo "MySQL 不可达"

# 后端 health（readiness 查依赖）
curl -s http://127.0.0.1:8080/actuator/health; echo
```

### 2. 定位

```bash
# 磁盘满（最常见）
df -h / /data
du -sh /var/lib/mysql /data/backup /data/uploads /var/log 2>/dev/null | sort -h

# 连接数打满（对比 max_connections）
mysql -e "SHOW STATUS LIKE 'Threads_connected'; SHOW VARIABLES LIKE 'max_connections';"

# 慢查询 / 锁等待
mysql -e "SHOW FULL PROCESSLIST;" | head -30
journalctl -u mysql --since "30 min ago" --no-pager | grep -iE 'out of memory|corrupt|InnoDB|error'

# 进程崩溃？
systemctl is-active mysql; dmesg | tail -20
```

**根因分类**：
- **磁盘满** → 日志/binlog/备份占满；
- **连接打满** → 连接泄漏或慢 SQL；
- **进程崩溃/数据损坏** → 需恢复（见 §3）。

### 3. 处置

```bash
# 3.1 磁盘满：清理 binlog / 过期备份 / 日志，必要时扩容
mysql -e "PURGE BINARY LOGS BEFORE DATE_SUB(NOW(), INTERVAL 3 DAY);"
find /data/backup -name 'print_db_*.sql.gz' -mtime +7 -delete
journalctl --vacuum-time=3d
df -h / /data

# 3.2 连接打满：重启后端释放连接池，再排查慢 SQL
sudo systemctl restart print-backend
mysql -e "SHOW FULL PROCESSLIST;"   # 找出长事务/慢查询

# 3.3 数据损坏：用最近全量备份 + binlog 做 PITR（详见设计文档 §5.1 与 deploy/scripts/backup_mysql.sh）
#     ① 停后端，避免写入：sudo systemctl stop print-backend
#     ② 恢复全量：gunzip -c /data/backup/mysql/print_db_YYYYMMDD_HHMMSS.sql.gz | mysql -u print -p print_db
#     ③ 重放 binlog 到故障点：mysqlbinlog ... | mysql -u print -p
#     ④ 起后端：sudo systemctl start print-backend

# 3.4 恢复期间后端返回维护提示；恢复后补偿任务重放未完成的状态流转（幂等，可安全重放）
```

### 4. 验证

```bash
# readiness 通过 / health UP
curl -s http://127.0.0.1:8080/actuator/health; echo

# 订单/支付状态无丢失（关键计数核对）
mysql -e "
SELECT
  (SELECT COUNT(*) FROM t_print_order WHERE deleted=0)                       AS orders,
  (SELECT COUNT(*) FROM t_print_order WHERE deleted=0 AND pay_status=1)      AS paid,
  (SELECT COUNT(*) FROM t_print_task  WHERE deleted=0)                       AS tasks;"

# 对账无新增差异（T+1 对账报告）
ls -t docs/reconcile-report/ 2>/dev/null | head -3

# 指标恢复
bash /opt/print/deploy/scripts/metrics_collect.sh | grep -E 'backend_health|db_orders_created_1h'
```

---

## 附：故障复盘模板（无责复盘）

```markdown
## 事件：<一句话>
- 时间线：发现 → 定位 → 处置 → 恢复（各时间点）
- 影响面：影响订单数 / 时长 / 是否资损
- 根因（5 Why）：
- 临时处置：
- 根治措施与责任人/期限：
- 监控/告警改进（是否需要新增指标或 Runbook 步骤）：
```
