#!/usr/bin/env bash
# ============================================================================
#  自助付费打印系统 —— MySQL 每日备份脚本
#  用法：bash backup_mysql.sh
#  建议：crontab -e 添加（每日 03:00）
#        0 3 * * * /opt/print/deploy/scripts/backup_mysql.sh >> /var/log/print/backup.log 2>&1
#  说明：从 /etc/print/backend.env 读取数据库连接信息，mysqldump 全库备份 + gzip 压缩，
#        按保留天数清理旧备份，并做完整性校验（gzip -t）。
# ============================================================================
set -uo pipefail

# —— 可覆盖配置（环境变量优先）——
ENV_FILE="${ENV_FILE:-/etc/print/backend.env}"
BACKUP_DIR="${BACKUP_DIR:-/data/backup/mysql}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_DB="${MYSQL_DB:-print_db}"
MYSQL_USER="${MYSQL_USER:-print}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"

log()  { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }
fail() { log "ERROR: $*"; exit 1; }

# —— 载入密钥文件（key=value，跳过注释行）——
if [[ -f "$ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  set -a; source <(grep -E '^[A-Za-z_][A-Za-z0-9_]*=' "$ENV_FILE"); set +a
  log "已载入环境文件：$ENV_FILE"
else
  log "警告：环境文件不存在（$ENV_FILE），改用进程环境变量"
fi

# —— 依赖检查 ——
command -v mysqldump >/dev/null 2>&1 || fail "未找到 mysqldump，请安装 mysql-client"
[[ -n "$MYSQL_PASSWORD" ]] || fail "MYSQL_PASSWORD 为空，请检查 $ENV_FILE"

# —— 准备目录 ——
mkdir -p "$BACKUP_DIR" || fail "无法创建备份目录 $BACKUP_DIR"

STAMP="$(date '+%Y%m%d_%H%M%S')"
OUT_FILE="${BACKUP_DIR}/${MYSQL_DB}_${STAMP}.sql.gz"
TMP_FILE="${OUT_FILE}.tmp"

log "开始备份 库=${MYSQL_DB} 主机=${MYSQL_HOST}:${MYSQL_PORT} 用户=${MYSQL_USER}"

# —— 执行备份（--single-transaction 保证 InnoDB 一致性快照，不锁表）——
if mysqldump \
      --host="$MYSQL_HOST" --port="$MYSQL_PORT" \
      --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" \
      --single-transaction --routines --triggers --events \
      --default-character-set=utf8mb4 \
      --databases "$MYSQL_DB" 2>/dev/null | gzip -9 > "$TMP_FILE"; then
  # 原子 rename，避免半成品文件被误用
  mv "$TMP_FILE" "$OUT_FILE" || fail "重命名备份文件失败"
else
  rm -f "$TMP_FILE"
  fail "mysqldump 执行失败"
fi

# —— 完整性校验 ——
gzip -t "$OUT_FILE" 2>/dev/null || fail "备份文件校验失败（gzip -t）"
SIZE="$(du -h "$OUT_FILE" | cut -f1)"
log "备份完成：$OUT_FILE（$SIZE）"

# —— 清理过期备份 ——
DELETED="$(find "$BACKUP_DIR" -name "${MYSQL_DB}_*.sql.gz" -type f -mtime "+${RETENTION_DAYS}" -print -delete | wc -l)"
log "已清理 ${DELETED} 个超过 ${RETENTION_DAYS} 天的旧备份"

log "备份任务结束（OK）"
exit 0
