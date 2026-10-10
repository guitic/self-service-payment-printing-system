# staging 部署清单（docs/staging-deployment.md）

> 目标：把系统从 **demo 档（H2 内存、零依赖）** 切到 **staging 档（MySQL 8 + Redis 7 + Nginx + systemd）**，
> 并跑通端到端冒烟。staging = **prod 档部署形态 + 测试开关**（Mock 支付 + 短信 debug），
> 用于 E2E / 性能 / UAT；真实支付与真实短信仅在 prod 打开。
>
> 依据：`README.md`、`deploy/README.md`、设计文档第 2 章。
> 全程命令可复制执行（在目标 Linux 主机上，`sudo` 权限）。

---

## 0. 档位差异对照（先明确目标）

| 项 | demo（默认） | **staging（本文）** | prod |
|----|--------------|---------------------|------|
| 数据库 | H2 内存 | **MySQL 8** | MySQL 8 |
| 队列/缓存 | 内存队列 | **Redis 7** | Redis 7 |
| 短信 | 控制台 + 返回 `debugCode` | **Mock + `debug`（返回 debugCode，供自动化）** | 真实通道，`debug=false` |
| 支付 | Mock 网关 | **Mock 网关（`mock/confirm` 可用）** | 真实渠道（微信/支付宝） |
| 运行方式 | `mvn spring-boot:run` | **systemd `print-backend`** | systemd |
| 入口 | 直连 `:8080` | **Nginx（TLS/反代）** | Nginx |
| 数据 | 进程退出即清空 | 持久化，可重置 | 持久化，受备份保护 |

> ⚠️ **staging 安全红线**：`/payments/mock/confirm` 与短信 `debug` 仅用于非生产环境。
> 上线 prod 前必须关闭（`PAYMENT_GATEWAY≠mock`、`PRINT_SMS_DEBUG=false`），并用测试断言该端点返回 404/403。

---

## 1. 前置准备

```bash
# 1.1 目录与用户
sudo mkdir -p /opt/print/backend /opt/print/agent /etc/print \
              /data/uploads /data/backup/mysql /var/log/print \
              /var/www/print/dist /var/lib/print-agent/spool /var/log/print-agent
sudo useradd -r -s /sbin/nologin print        # 后端运行用户
sudo useradd -r -s /sbin/nologin printagent   # 助手运行用户（若本机即终端机）

# 1.2 依赖检查
java -version        # 需 Java 17+
docker --version     # 或自建 MySQL/Redis
nginx -v
node --version       # 用于跑冒烟脚本（可另备一台跳板机）
```

---

## 2. 启动 MySQL 8 + Redis 7

```bash
cd /opt/print            # 或项目根目录（含 docker-compose.yml）
sudo docker compose up -d
sudo docker compose ps   # 期望 print-mysql / print-redis 均 healthy
```

> `docker-compose.yml` 已定义：MySQL 库 `print_db`、账号 `print/print123`、端口 `3306`；
> Redis 端口 `6379`、`appendonly yes`、`maxmemory-policy noeviction`。
> **生产请改默认口令**，且 MySQL/Redis 仅监听 `127.0.0.1`（云主机安全组不要放行 3306/6379）。

---

## 3. 初始化数据库表结构

```bash
# 3.1 建表（含种子：A4/A3 计价规则 + 终端 TERM-001）
mysql -h127.0.0.1 -uprint -pprint123 print_db \
  < backend/src/main/resources/db/mysql/schema.sql

# 3.2 校验
mysql -h127.0.0.1 -uprint -pprint123 print_db -e "
SELECT paper_size, color_price, mono_price, duplex_discount FROM t_price_rule;
SELECT code, agent_token, online_status FROM t_terminal;"
# 期望：A4/A3 两条规则；终端 TERM-001（token dev-agent-token，上线前务必更换）
```

> 生产档 `application-prod.yml` 中 `spring.sql.init.mode=never`，表结构由本步骤手工/流水线初始化。

---

## 4. 构建并安装后端

```bash
# 4.1 构建（跳过测试，测试在 CI 跑）
cd backend && mvn -DskipTests package
ls target/*.jar

# 4.2 安装 jar
sudo cp target/print-backend-1.0.0.jar /opt/print/backend/app.jar
sudo chown print:print /opt/print/backend/app.jar
```

---

## 5. 配置环境变量 `/etc/print/backend.env`

```bash
sudo cp deploy/env/backend.env.example /etc/print/backend.env
sudo chmod 600 /etc/print/backend.env
sudo chown print:print /etc/print/backend.env
sudo vi /etc/print/backend.env
```

**staging 必须确认/修改的项**：

```ini
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8080

# MySQL / Redis（与 §2 对齐；生产换强口令）
MYSQL_HOST=127.0.0.1
MYSQL_PORT=3306
MYSQL_DB=print_db
MYSQL_USER=print
MYSQL_PASSWORD=print123
MYSQL_POOL_MAX=20
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_PASSWORD=                 # docker 默认无密码；生产必设
JWT_SECRET=<32 字节以上随机串>   # openssl rand -base64 48

# —— staging 测试开关（上线 prod 必须改回）——
SMS_PROVIDER=mock
PRINT_SMS_DEBUG=true            # 关键：让 /auth/sms/send 返回 debugCode，冒烟/压测才能自动登录
PAYMENT_GATEWAY=mock            # 关键：注册 /payments/mock/confirm，供自动化触发支付成功

FILE_ROOT=/data/uploads
FILE_MAX_SIZE_MB=50
```

> 说明：`application-prod.yml` 中 `print.sms.debug=false` 为硬编码默认，
> 环境变量 `PRINT_SMS_DEBUG=true` 会**覆盖**它（Spring Boot 外部化配置优先级）。prod 部署时删除该行。

**权限与安全**：`/etc/print/backend.env` 权限 `600`、属主 `print`，**不入 Git**。

---

## 6. systemd 启动后端

```bash
sudo cp deploy/systemd/print-backend.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now print-backend
sleep 5
systemctl status print-backend --no-pager
journalctl -u print-backend -f      # 观察 "Started PrintApplication"

# 本地健康检查（后端仅监听 127.0.0.1）
curl -s http://127.0.0.1:8080/actuator/health; echo    # 期望 {"status":"UP"}
```

---

## 7. 构建前端并部署静态资源

```bash
cd frontend
npm install --registry=https://registry.npmmirror.com
npm run build                      # Vite 产物在 frontend/dist
sudo cp -r dist/* /var/www/print/dist/
sudo chown -R nginx:nginx /var/www/print/dist 2>/dev/null || sudo chown -R www-data:www-data /var/www/print/dist
```

---

## 8. 配置 Nginx

```bash
sudo cp deploy/nginx/print.conf        /etc/nginx/conf.d/print.conf
sudo cp deploy/nginx/proxy_common.conf /etc/nginx/conf.d/proxy_common.conf
sudo vi /etc/nginx/conf.d/print.conf   # 改 server_name 与证书路径
```

- **有域名 + 证书**：将 `server_name` 改为 staging 域名，证书指向 Let's Encrypt 路径。
- **无域名（内网 IP 验证）**：可临时用自签证书，或改为 HTTP-only 反代（仅 staging，勿上 prod）：

```nginx
# 仅 staging 简化示例：HTTP 反代（生产必须 TLS）
server {
    listen 80;
    server_name staging.local;
    root /var/www/print/dist;
    location / { try_files $uri $uri/ /index.html; }
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_read_timeout 65s;      # 必须 > 助手长轮询 25s
        include /etc/nginx/conf.d/proxy_common.conf;
    }
}
```

```bash
sudo nginx -t && sudo systemctl reload nginx
```

> 要点：`client_max_body_size 50m`；验证码限流 `1r/m`、通用 API `20r/s`；
> `/api` 反代 `proxy_read_timeout 65s`；注入 `X-Trace-Id` 透传后端。

---

## 9. 启动打印助手

**方式 A：Node 仿真器（staging 无 .NET SDK 时，行为等价）**

```bash
node tools/agent-sim/agent-sim.mjs \
  --backend http://127.0.0.1:8080 \
  --terminal TERM-001 \
  --token dev-agent-token
```

**方式 B：.NET 8 助手（Linux 终端，systemd）**

```bash
sudo cp deploy/systemd/print-agent.service /etc/systemd/system/
sudo cp deploy/env/agent.env.example       /etc/print/agent.env
sudo chmod 600 /etc/print/agent.env && sudo chown printagent:printagent /etc/print/agent.env
sudo vi /etc/print/agent.env    # PRINT_BACKEND_URL / PRINT_AGENT_TOKEN
dotnet publish print-agent -c Release -o /opt/print/agent
sudo systemctl daemon-reload && sudo systemctl enable --now print-agent
journalctl -u print-agent -f
```

> `PRINT_AGENT_TOKEN` 必须与库中 `t_terminal.agent_token` 一致；staging 用 `dev-agent-token`，
> prod 必须更换为强随机值。

---

## 10. 冒烟验证（端到端）

使用仓库自带脚本 `tools/smoke-test.mjs`（覆盖：登录→上传→试算→下单→支付→助手领取回传→幂等）。

```bash
# 10.1 直连后端（staging 后端与本机同机时）
node tools/smoke-test.mjs http://127.0.0.1:8080

# 10.2 走 Nginx（staging 域名）
node tools/smoke-test.mjs https://staging.example.com
```

**期望输出**（全部 ✅，末尾 `结果：N 通过 / 0 失败`）：

```
✅ 健康检查返回 UP
✅ 发送验证码成功且返回 debugCode
✅ 验证码登录成功并签发 JWT
✅ 上传 PDF 并解析页数 = 3
✅ 试算金额 = 1.20（3页×2份×0.20）
✅ 双面折扣金额 = 1.62（3页×3份×0.20×0.9）
✅ 单页小额按实算 = 0.20
✅ 彩色单页 = 0.80（与黑白区分）
✅ 创建订单成功
✅ 发起支付返回二维码内容
✅ Mock 支付成功 → payStatus=1
✅ 重复确认后订单仍只有 1 笔（幂等）
✅ 打印任务最终状态 = 已完成
```

> 若最后一项超时，检查助手是否已启动（§9）且 `TERM-001` token 匹配。

**补充验证**：

```bash
# 监控指标可采集（staging 打开了 sms.debug，脚本能自动登录取 JWT）
bash deploy/scripts/metrics_collect.sh | grep -E 'backend_health|db_terminals|db_pending_tasks'

# 性能摸底（无 k6 时）
node tools/load-test/load-test.mjs --base http://127.0.0.1:8080 --vus 50 --duration 30 --mix quote,order
# 正式 200 并发验收（需 k6）
k6 run -e BASE=http://127.0.0.1:8080 -e VUS=200 -e DURATION=3m tools/load-test/k6-booking.js
```

---

## 11. 重置 staging 数据（每次 UAT / 压测后）

```bash
# 11.1 清空业务数据（保留表结构与计价规则/终端种子）
mysql -h127.0.0.1 -uprint -pprint123 print_db -e "
SET FOREIGN_KEY_CHECKS=0;
TRUNCATE t_print_task;
TRUNCATE t_print_order;
TRUNCATE t_payment_record;
TRUNCATE t_file;
TRUNCATE t_user;
SET FOREIGN_KEY_CHECKS=1;"

# 11.2 清理上传文件与助手 spool
sudo find /data/uploads -type f -delete
find /var/lib/print-agent/spool -type f -delete 2>/dev/null

# 11.3 （可选）Redis 会话/验证码清理
redis-cli -h 127.0.0.1 FLUSHDB
```

---

## 12. 上线 prod 前的差异检查清单

从 staging 切 prod 时，逐项确认：

- [ ] `PAYMENT_GATEWAY` 改为真实渠道（`wechat`/`alipay`）并配置商户密钥/证书（见 `application-prod.yml` 占位项）。
- [ ] `SMS_PROVIDER` 改为真实通道（`aliyun`/`tencent`），删除 `PRINT_SMS_DEBUG=true`（`debug=false`）。
- [ ] 断言 `/api/v1/payments/mock/confirm` 在 prod 返回 **404/403**。
- [ ] `JWT_SECRET` / `MYSQL_PASSWORD` / `REDIS_PASSWORD` / 渠道密钥全部换为强随机值，`/etc/print/backend.env` 权限 `600`。
- [ ] `TERM-001` 的 `agent_token` 更换为强随机值，并同步到 `/etc/print/agent.env`。
- [ ] Nginx 启用 TLS 1.2/1.3 + HSTS；`server_name` 与证书为生产域名。
- [ ] MySQL/Redis 仅监听 `127.0.0.1`；云安全组未放行 3306/6379/8080。
- [ ] 备份 cron 与巡检 cron 已配置（见 `deploy/README.md` §五）。
- [ ] 跑完 `tools/smoke-test.mjs` 与（真实渠道 Sandbox）支付回归清单（设计文档 §7.5.5）。
