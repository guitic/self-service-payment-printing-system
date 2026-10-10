#!/usr/bin/env bash
# ============================================================================
#  自助付费打印系统 —— 监控指标采集脚本
#  依据：设计文档第 2 章 §4.1 核心指标 / §4.3 健康检查
#
#  用法：
#    bash metrics_collect.sh                 # 采集并打印 metric=value 行
#    bash metrics_collect.sh > /tmp/metrics.txt   # 供巡检脚本消费
#
#  输出：每行 `metric=value`（value 为数值或 UP/DOWN/NA），便于被
#        health_check.sh / 告警脚本 / Telegraf(textfile) / 自研巡检消费。
#
#  数据来源：
#    1) /actuator/health        —— 整体状态无需鉴权；components 明细需 JWT
#    2) /actuator/metrics/*     —— 需 JWT（SecurityConfig 中 anyRequest().authenticated()）
#    3) MySQL 业务聚合查询       —— 打印/支付/队列/终端
#    4) df 磁盘水位
#
#  鉴权说明（重要）：/actuator/metrics 需要用户 JWT。脚本按以下顺序获取：
#    a. 环境变量 METRICS_JWT（推荐，生产用固定巡检账号 token）
#    b. demo/staging 打开 sms.debug 时，自动走 /auth/sms/send + /auth/login
#    c. 均不可用时，仅采集 health 与 DB 指标，metrics 段输出 NA
#
#  退出码：始终 0（采集失败以 NA 呈现，不阻断巡检链）。
# ============================================================================
set -uo pipefail

# —— 可覆盖配置（环境变量优先）——
ENV_FILE="${ENV_FILE:-/etc/print/backend.env}"
BACKEND_BASE="${BACKEND_BASE:-http://127.0.0.1:8080}"
HTTP_TIMEOUT="${HTTP_TIMEOUT:-5}"
METRICS_JWT="${METRICS_JWT:-}"

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_DB="${MYSQL_DB:-print_db}"
MYSQL_USER="${MYSQL_USER:-print}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"

BASE="${BACKEND_BASE%/}"
ACTUATOR="${BASE}/actuator"
API="${BASE}/api/v1"

# —— 载入 /etc/print/backend.env（若存在）——
if [[ -f "$ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  set -a; source <(grep -E '^[A-Za-z_][A-Za-z0-9_]*=' "$ENV_FILE" 2>/dev/null); set +a
  MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
  MYSQL_PORT="${MYSQL_PORT:-3306}"
  MYSQL_DB="${MYSQL_DB:-print_db}"
  MYSQL_USER="${MYSQL_USER:-print}"
  MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"
fi

# ——————————————————— 输出与工具函数 ———————————————————

# 打印一行 metric=value（去除空白/引号，空值→NA）
emit() {
  local name="$1" value="${2:-}"
  value="$(printf '%s' "$value" | tr -d '[:space:]"')"
  # 去掉数值尾部的 .0（actuator COUNT 常返回 1234.0）
  [[ "$value" =~ ^([0-9]+)\.0$ ]] && value="${BASH_REMATCH[1]}"
  [[ -z "$value" ]] && value="NA"
  echo "${name}=${value}"
}

http_get()      { curl -fsS --max-time "$HTTP_TIMEOUT" "$1" 2>/dev/null; }
http_get_auth() { curl -fsS --max-time "$HTTP_TIMEOUT" -H "Authorization: Bearer ${METRICS_JWT}" "$1" 2>/dev/null; }
http_post_json(){ curl -fsS --max-time "$HTTP_TIMEOUT" -H 'Content-Type: application/json' -d "$2" "$1" 2>/dev/null; }

# 从 actuator metrics JSON 中按 statistic 取 value（VALUE / COUNT / MAX / TOTAL_TIME）
metric_value() {
  local json="$1" stat="$2"
  printf '%s' "$json" \
    | grep -o "\"statistic\":\"${stat}\",\"value\":[^}]*" \
    | head -1 \
    | sed 's/.*"value"://'
}

# 从 health JSON 取整体 status
health_status() {
  printf '%s' "$1" | grep -o '"status":"[A-Z]*"' | head -1 | sed 's/.*"status":"//; s/"//'
}
# 从 health JSON 取指定 component 的 status
health_component_status() {
  printf '%s' "$1" \
    | grep -o "\"${2}\":{[^}]*\"status\":\"[A-Z]*\"" \
    | grep -o '"status":"[A-Z]*"' | head -1 | sed 's/.*"status":"//; s/"//'
}

# ——————————————————— 0. 获取 metrics 鉴权 JWT ———————————
# a) 环境变量已提供；b) demo/staging（sms.debug=true）自动登录取 token
if [[ -z "$METRICS_JWT" ]]; then
  METRICS_PHONE="139$(date +%H%M%S)00"   # 每次不同，规避 60s 冷却
  SMS_JSON="$(http_post_json "${API}/auth/sms/send" "{\"phone\":\"${METRICS_PHONE}\"}")"
  DEBUG_CODE="$(printf '%s' "$SMS_JSON" | grep -o '"debugCode":"[^"]*"' | head -1 | sed 's/.*"debugCode":"//; s/"//')"
  if [[ -n "$DEBUG_CODE" ]]; then
    LOGIN_JSON="$(http_post_json "${API}/auth/login" "{\"phone\":\"${METRICS_PHONE}\",\"code\":\"${DEBUG_CODE}\"}")"
    METRICS_JWT="$(printf '%s' "$LOGIN_JSON" | grep -o '"token":"[^"]*"' | head -1 | sed 's/.*"token":"//; s/"//')"
  fi
fi
AUTH_OK=0
[[ -n "$METRICS_JWT" ]] && AUTH_OK=1

# ——————————————————— 1. 健康检查 ———————————————————

# 整体状态（无需鉴权）
HEALTH_JSON="$(http_get "${ACTUATOR}/health")"
emit "backend_health" "$(health_status "$HEALTH_JSON")"

# 组件明细：show-details=when-authorized，带 JWT 才返回 components
HEALTH_DETAIL=""
if [[ "$AUTH_OK" -eq 1 ]]; then
  HEALTH_DETAIL="$(http_get_auth "${ACTUATOR}/health")"
fi
if printf '%s' "$HEALTH_DETAIL" | grep -q '"components"'; then
  emit "backend_health_db"    "$(health_component_status "$HEALTH_DETAIL" "db")"
  emit "backend_health_redis" "$(health_component_status "$HEALTH_DETAIL" "redis")"
  emit "backend_health_disk"  "$(health_component_status "$HEALTH_DETAIL" "diskSpace")"
  emit "backend_health_ping"  "$(health_component_status "$HEALTH_DETAIL" "ping")"
else
  # 未授权或未开启 show-details：明细不可得，输出 NA（整体状态以上面 backend_health 为准）
  emit "backend_health_db"    "NA"
  emit "backend_health_redis" "NA"
  emit "backend_health_disk"  "NA"
  emit "backend_health_ping"  "NA"
fi

# ——————————————————— 2. Actuator metrics（需 JWT）———————————

if [[ "$AUTH_OK" -eq 1 ]]; then
  M="$(http_get_auth "${ACTUATOR}/metrics/jvm.memory.used")"
  emit "jvm_memory_used_bytes" "$(metric_value "$M" "VALUE")"

  M="$(http_get_auth "${ACTUATOR}/metrics/process.cpu.usage")"
  CPU="$(metric_value "$M" "VALUE")"
  emit "process_cpu_usage_pct" "$(awk -v v="$CPU" 'BEGIN{if(v=="")print ""; else printf "%.2f", v*100}')"
  M="$(http_get_auth "${ACTUATOR}/metrics/system.cpu.usage")"
  CPU="$(metric_value "$M" "VALUE")"
  emit "system_cpu_usage_pct" "$(awk -v v="$CPU" 'BEGIN{if(v=="")print ""; else printf "%.2f", v*100}')"

  # HikariCP 连接池（Micrometer 指标名为 hikaricp.connections.*）
  M="$(http_get_auth "${ACTUATOR}/metrics/hikaricp.connections.active")"
  emit "hikaricp_active_connections" "$(metric_value "$M" "VALUE")"
  M="$(http_get_auth "${ACTUATOR}/metrics/hikaricp.connections.pending")"
  emit "hikaricp_pending_connections" "$(metric_value "$M" "VALUE")"
  M="$(http_get_auth "${ACTUATOR}/metrics/hikaricp.connections.max")"
  emit "hikaricp_max_connections" "$(metric_value "$M" "VALUE")"

  # HTTP 请求统计（累计计数 + 平均/最大耗时）
  M="$(http_get_auth "${ACTUATOR}/metrics/http.server.requests")"
  HCOUNT="$(metric_value "$M" "COUNT")"
  HTOTAL="$(metric_value "$M" "TOTAL_TIME")"
  HMAX="$(metric_value "$M" "MAX")"
  emit "http_server_requests_count" "$HCOUNT"
  emit "http_server_requests_avg_ms" "$(awk -v t="$HTOTAL" -v c="$HCOUNT" 'BEGIN{if(t==""||c==""||c==0)print ""; else printf "%.2f", t/c*1000}')"
  emit "http_server_requests_max_ms" "$(awk -v m="$HMAX" 'BEGIN{if(m=="")print ""; else printf "%.2f", m*1000}')"
else
  for k in jvm_memory_used_bytes process_cpu_usage_pct system_cpu_usage_pct \
           hikaricp_active_connections hikaricp_pending_connections hikaricp_max_connections \
           http_server_requests_count http_server_requests_avg_ms http_server_requests_max_ms; do
    emit "$k" "NA"
  done
fi

# ——————————————————— 3. MySQL 业务指标 ———————————————————

MYSQL_OK=0
if command -v mysql >/dev/null 2>&1 && [[ -n "$MYSQL_PASSWORD" ]]; then
  if mysql --connect-timeout=3 -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" \
        -N -B "$MYSQL_DB" -e "SELECT 1" >/dev/null 2>&1; then
    MYSQL_OK=1
  fi
fi

# 单值查询助手（失败返回空）
sql1() {
  [[ "$MYSQL_OK" -eq 1 ]] || { echo ""; return; }
  mysql --connect-timeout=3 -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" \
    -N -B "$MYSQL_DB" -e "$1" 2>/dev/null | head -1
}

if [[ "$MYSQL_OK" -eq 1 ]]; then
  emit "db_pending_tasks"     "$(sql1 "SELECT COUNT(*) FROM t_print_task WHERE status=0 AND deleted=0")"
  emit "db_printing_tasks"    "$(sql1 "SELECT COUNT(*) FROM t_print_task WHERE status IN (1,2) AND deleted=0")"
  emit "db_retry_tasks"       "$(sql1 "SELECT COUNT(*) FROM t_print_task WHERE status=5 AND deleted=0")"
  emit "db_failed_tasks"      "$(sql1 "SELECT COUNT(*) FROM t_print_task WHERE status=4 AND deleted=0")"
  emit "db_terminals_total"   "$(sql1 "SELECT COUNT(*) FROM t_terminal WHERE deleted=0")"
  emit "db_terminals_online"  "$(sql1 "SELECT COUNT(*) FROM t_terminal WHERE deleted=0 AND online_status=1")"
  emit "db_orders_created_1h" "$(sql1 "SELECT COUNT(*) FROM t_print_order WHERE deleted=0 AND created_at >= NOW() - INTERVAL 1 HOUR")"
  emit "db_orders_paid_1h"    "$(sql1 "SELECT COUNT(*) FROM t_print_order WHERE deleted=0 AND pay_status=1 AND updated_at >= NOW() - INTERVAL 1 HOUR")"

  # 打印成功率（滚动 1h）= 成功 / (成功 + 失败)
  POK="$(sql1 "SELECT COUNT(*) FROM t_print_task WHERE deleted=0 AND status=3 AND updated_at >= NOW() - INTERVAL 1 HOUR")"
  PTERM="$(sql1 "SELECT COUNT(*) FROM t_print_task WHERE deleted=0 AND status IN (3,4) AND updated_at >= NOW() - INTERVAL 1 HOUR")"
  emit "db_print_success_rate_1h" "$(awk -v s="$POK" -v t="$PTERM" 'BEGIN{if(t==""||t==0)print ""; else printf "%.2f", s/t*100}')"

  # 支付成功率（滚动 1h）= 已支付 / 近 1h 创建订单
  ORD="$(sql1 "SELECT COUNT(*) FROM t_print_order WHERE deleted=0 AND created_at >= NOW() - INTERVAL 1 HOUR")"
  PAID="$(sql1 "SELECT COUNT(*) FROM t_print_order WHERE deleted=0 AND pay_status=1 AND created_at >= NOW() - INTERVAL 1 HOUR")"
  emit "db_pay_success_rate_1h" "$(awk -v p="$PAID" -v o="$ORD" 'BEGIN{if(o==""||o==0)print ""; else printf "%.2f", p/o*100}')"

  # 已支付但 5min 未完成打印（超时未终态，应恒为 0）
  emit "db_stuck_paid_orders" "$(sql1 "SELECT COUNT(*) FROM t_print_order WHERE deleted=0 AND pay_status=1 AND print_status<>2 AND updated_at < NOW() - INTERVAL 5 MINUTE")"
else
  for k in db_pending_tasks db_printing_tasks db_retry_tasks db_failed_tasks \
           db_terminals_total db_terminals_online db_orders_created_1h db_orders_paid_1h \
           db_print_success_rate_1h db_pay_success_rate_1h db_stuck_paid_orders; do
    emit "$k" "NA"
  done
fi

# ——————————————————— 4. 磁盘水位 ———————————————————

emit "disk_used_pct_root" "$(df -P / 2>/dev/null | awk 'NR==2 {gsub("%","",$5); print $5}')"
if [[ -d /data ]]; then
  emit "disk_used_pct_data" "$(df -P /data 2>/dev/null | awk 'NR==2 {gsub("%","",$5); print $5}')"
fi

exit 0
