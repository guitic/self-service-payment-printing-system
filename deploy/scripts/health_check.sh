#!/usr/bin/env bash
# ============================================================================
#  自助付费打印系统 —— 巡检 + 告警脚本
#  用法：bash health_check.sh
#  建议：crontab -e 添加（每 5 分钟）
#        */5 * * * * /opt/print/deploy/scripts/health_check.sh
#  退出码：0=全部正常  1=存在告警(WARN)  2=存在严重故障(CRIT)
#  说明：检查后端进程/健康端点、MySQL、Redis、Nginx、磁盘水位；
#        任一 CRIT 触发告警（此处以日志输出为例，接入企业微信/钉钉/短信请替换 alert()）。
# ============================================================================
set -uo pipefail

# —— 可覆盖配置 ——
BACKEND_HEALTH_URL="${BACKEND_HEALTH_URL:-http://127.0.0.1:8080/actuator/health}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-6379}"
DISK_WARN="${DISK_WARN:-80}"
DISK_CRIT="${DISK_CRIT:-90}"
HTTP_TIMEOUT="${HTTP_TIMEOUT:-5}"

WARN_COUNT=0
CRIT_COUNT=0

log()   { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }
warn()  { log "WARN  $*"; WARN_COUNT=$((WARN_COUNT + 1)); }
crit()  { log "CRIT  $*"; CRIT_COUNT=$((CRIT_COUNT + 1)); }
ok()    { log "OK    $*"; }

# 告警出口（按需替换为 webhook / 短信）
alert() { log "ALERT $*"; }

# —— 1. 后端进程 + 健康端点 ——
if systemctl is-active --quiet print-backend 2>/dev/null; then
  ok "print-backend.service 运行中"
else
  crit "print-backend.service 未运行"
  alert "后端服务异常"
fi

if command -v curl >/dev/null 2>&1; then
  HEALTH="$(curl -fsS --max-time "$HTTP_TIMEOUT" "$BACKEND_HEALTH_URL" 2>/dev/null || true)"
  if echo "$HEALTH" | grep -q '"status":"UP"'; then
    ok "后端健康端点 UP"
  else
    crit "后端健康端点异常：${HEALTH:-无响应}"
    alert "后端健康检查失败"
  fi
else
  warn "未安装 curl，跳过健康端点检查"
fi

# —— 2. MySQL ——
if command -v mysqladmin >/dev/null 2>&1; then
  if mysqladmin ping -h"$MYSQL_HOST" -P"$MYSQL_PORT" --silent >/dev/null 2>&1; then
    ok "MySQL 存活"
  else
    crit "MySQL 不可达（${MYSQL_HOST}:${MYSQL_PORT}）"
    alert "MySQL 故障"
  fi
else
  warn "未安装 mysql-client，跳过 MySQL 检查"
fi

# —— 3. Redis ——
if command -v redis-cli >/dev/null 2>&1; then
  if [[ "$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" ping 2>/dev/null)" == "PONG" ]]; then
    ok "Redis 存活"
  else
    crit "Redis 不可达（${REDIS_HOST}:${REDIS_PORT}）"
    alert "Redis 故障"
  fi
else
  warn "未安装 redis-cli，跳过 Redis 检查"
fi

# —— 4. Nginx ——
if systemctl is-active --quiet nginx 2>/dev/null; then
  ok "nginx.service 运行中"
else
  crit "nginx.service 未运行"
  alert "Nginx 故障"
fi

# —— 5. 磁盘水位（/ 与 /data）——
for MOUNT in / /data; do
  [[ -d "$MOUNT" ]] || continue
  USE="$(df -P "$MOUNT" | awk 'NR==2 {gsub("%","",$5); print $5}')"
  if [[ -n "$USE" ]]; then
    if (( USE >= DISK_CRIT )); then
      crit "磁盘 $MOUNT 使用率 ${USE}%（≥${DISK_CRIT}%）"
      alert "磁盘告急 $MOUNT"
    elif (( USE >= DISK_WARN )); then
      warn "磁盘 $MOUNT 使用率 ${USE}%（≥${DISK_WARN}%）"
    else
      ok "磁盘 $MOUNT 使用率 ${USE}%"
    fi
  fi
done

# —— 6. 助手 spool 积压（若本机即终端机）——
SPOOL_DIR="${PRINT_SPOOL_DIR:-/var/lib/print-agent/spool}"
if [[ -d "$SPOOL_DIR" ]]; then
  BACKLOG="$(find "$SPOOL_DIR" -maxdepth 1 -name '*.json' -type f 2>/dev/null | wc -l)"
  if (( BACKLOG > 0 )); then
    warn "助手 spool 存在 ${BACKLOG} 条待补报（可能断网/后端不可用）"
  else
    ok "助手 spool 无积压"
  fi
fi

# —— 汇总 ——
log "巡检完成：CRIT=${CRIT_COUNT} WARN=${WARN_COUNT}"
if (( CRIT_COUNT > 0 )); then exit 2; fi
if (( WARN_COUNT > 0 )); then exit 1; fi
exit 0
