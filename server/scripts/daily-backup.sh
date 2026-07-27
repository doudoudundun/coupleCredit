#!/bin/bash
# 每日备份：打包 couple_credit_private DB + uploads 到 /backups/daily/
# 保留最近 14 天，更早的自动删除
# 由 cron 在每天凌晨 3:00 触发：0 3 * * * /opt/couplecredit/scripts/daily-backup.sh

set -euo pipefail

BACKUP_DIR="/backups/daily"
KEEP_DAYS=14
DB_NAME="couple_credit_private"
DB_USER="root"
DB_PASSWORD="root"
UPLOADS_DIR="/opt/couplecredit/uploads"
TS=$(date +%Y%m%d-%H%M%S)
LOG_FILE="/var/log/couplecredit-backup.log"

mkdir -p "$BACKUP_DIR"

echo "[$(date '+%F %T')] 开始备份..." >> "$LOG_FILE"

# 1. 数据库
DB_FILE="$BACKUP_DIR/db-${TS}.sql.gz"
if mysqldump -u"$DB_USER" -p"$DB_PASSWORD" --single-transaction --routines --triggers "$DB_NAME" 2>>"$LOG_FILE" | gzip > "$DB_FILE"; then
  echo "[$(date '+%F %T')] DB 备份完成: $(du -h "$DB_FILE" | awk '{print $1}')" >> "$LOG_FILE"
else
  echo "[$(date '+%F %T')] DB 备份失败!" >> "$LOG_FILE"
  rm -f "$DB_FILE"
  exit 1
fi

# 2. uploads
UPLOADS_FILE="$BACKUP_DIR/uploads-${TS}.tar.gz"
if tar czf "$UPLOADS_FILE" -C "$(dirname "$UPLOADS_DIR")" "$(basename "$UPLOADS_DIR")" 2>>"$LOG_FILE"; then
  echo "[$(date '+%F %T')] uploads 备份完成: $(du -h "$UPLOADS_FILE" | awk '{print $1}')" >> "$LOG_FILE"
else
  echo "[$(date '+%F %T')] uploads 备份失败!" >> "$LOG_FILE"
  rm -f "$UPLOADS_FILE"
  exit 1
fi

# 3. 清理过期备份
find "$BACKUP_DIR" -name "db-*.sql.gz" -mtime +$KEEP_DAYS -delete 2>>"$LOG_FILE" || true
find "$BACKUP_DIR" -name "uploads-*.tar.gz" -mtime +$KEEP_DAYS -delete 2>>"$LOG_FILE" || true

echo "[$(date '+%F %T')] 备份全部完成，当前备份目录:" >> "$LOG_FILE"
ls -lh "$BACKUP_DIR" | tail -10 >> "$LOG_FILE"
