# 部署配置（deploy/）

自助付费打印系统生产部署所需的 Nginx / systemd / 环境变量 / 运维脚本。
依据设计文档《系统设计》第 2 章（可运维性与可靠性）。

```
deploy/
├── nginx/
│   ├── print.conf            # Nginx 站点配置（TLS/反代/限流/WS）
│   └── proxy_common.conf     # 反代公共片段（被 print.conf include）
├── systemd/
│   ├── print-backend.service # 后端 unit（Spring Boot jar）
│   └── print-agent.service   # 打印助手 unit（.NET Worker）
├── env/
│   ├── backend.env.example   # 后端环境变量模板（不含密钥）
│   └── agent.env.example     # 助手环境变量模板
└── scripts/
    ├── backup_mysql.sh       # MySQL 每日备份
    └── health_check.sh       # 巡检 + 告警
```

## 一、前置准备

```bash
# 目录
sudo mkdir -p /opt/print/backend /opt/print/agent /etc/print /data/uploads /data/backup/mysql /var/log/print
sudo useradd -r -s /sbin/nologin print        # 后端运行用户
sudo useradd -r -s /sbin/nologin printagent   # 助手运行用户
```

## 二、Nginx

```bash
sudo cp deploy/nginx/print.conf        /etc/nginx/conf.d/print.conf
sudo cp deploy/nginx/proxy_common.conf /etc/nginx/conf.d/proxy_common.conf
# 修改 server_name 与证书路径（默认 print.example.com / letsencrypt）
sudo nginx -t && sudo systemctl reload nginx
```

要点：TLS 仅 1.2/1.3 + HSTS；静态 SPA `try_files … /index.html`；
`/api` 反代 `proxy_read_timeout 65s`（须 > 助手长轮询 25s）；`/ws` 升级；
`client_max_body_size 50m`；限流 验证码 `1r/m`、通用 API `20r/s`、上传 `30r/m`；
注入 `X-Trace-Id: $request_id` 透传后端。

## 三、后端（Spring Boot）

```bash
sudo cp deploy/systemd/print-backend.service /etc/systemd/system/
sudo cp deploy/env/backend.env.example       /etc/print/backend.env
sudo chmod 600 /etc/print/backend.env && sudo chown print:print /etc/print/backend.env
sudo vi /etc/print/backend.env               # 填写 MYSQL_PASSWORD / REDIS_PASSWORD / JWT_SECRET
sudo cp backend/target/*.jar /opt/print/backend/app.jar
sudo systemctl daemon-reload
sudo systemctl enable --now print-backend
journalctl -u print-backend -f
```

> MySQL/Redis/Backend 只监听 `127.0.0.1`，仅 Nginx 暴露 443/80。

## 四、打印助手（.NET 8 Worker）

**Linux 终端：**

```bash
sudo cp deploy/systemd/print-agent.service /etc/systemd/system/
sudo cp deploy/env/agent.env.example       /etc/print/agent.env
sudo chmod 600 /etc/print/agent.env && sudo chown printagent:printagent /etc/print/agent.env
sudo vi /etc/print/agent.env               # 填写 PRINT_AGENT_TOKEN
sudo mkdir -p /var/lib/print-agent/spool /var/log/print-agent
sudo chown -R printagent:printagent /var/lib/print-agent /var/log/print-agent
# 发布 .NET 产物
dotnet publish print-agent -c Release -o /opt/print/agent
sudo systemctl daemon-reload
sudo systemctl enable --now print-agent
journalctl -u print-agent -f
```

`print-agent.service` 使用 `Type=notify`，助手内置 `SystemdNotifier` 直接向 `$NOTIFY_SOCKET`
发送 `READY=1`，并按 30s 周期发送 `WATCHDOG=1`（`WatchdogSec=90`）。

**Windows 终端：** 不使用 systemd，改为注册 Windows 服务：

```bat
sc create PrintAgent binPath= "C:\print-agent\PrintAgent.exe" start= auto
sc start PrintAgent
```
并在「服务属性 → 恢复」中把「第一次/第二次/后续失败」都设为「重新启动服务」，重置计数 1 天。

## 五、备份与巡检（cron）

```bash
sudo chmod +x deploy/scripts/*.sh
sudo crontab -e
```
```cron
# MySQL 每日 03:00 备份
0 3 * * * /opt/print/deploy/scripts/backup_mysql.sh >> /var/log/print/backup.log 2>&1
# 每 5 分钟巡检
*/5 * * * * /opt/print/deploy/scripts/health_check.sh
```

- `backup_mysql.sh`：`mysqldump --single-transaction` 全库备份 + gzip + 完整性校验 + 按保留天数清理。
- `health_check.sh`：检查后端进程/健康端点、MySQL、Redis、Nginx、磁盘水位、助手 spool 积压；
  退出码 `0=正常 / 1=告警 / 2=严重`，可在脚本内 `alert()` 接入企业微信/钉钉/短信。

## 六、常用运维命令

```bash
systemctl daemon-reload
systemctl restart print-backend && journalctl -u print-backend -f
systemctl status print-agent
journalctl -u print-backend --since "30 min ago" -p err
ls /var/lib/print-agent/spool   # 查看助手离线补报积压
```

## 本地联调（无 systemd）

本地开发用根目录 `docker-compose.yml` 起 MySQL/Redis，后端用 `demo` 档（H2 内存，零依赖），
打印助手用 Node 仿真器替代：

```bash
docker compose up -d
node tools/agent-sim/agent-sim.mjs --backend http://127.0.0.1:8080 --terminal TERM-001 --token dev-agent-token
```
