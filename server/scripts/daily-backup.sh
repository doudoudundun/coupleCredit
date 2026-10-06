#!/bin/bash
# 每日备份：打包 MySQL 库 + uploads 到 $BACKUP_DIR，保留最近 $KEEP_DAYS 天
# 由 cron 在每天凌晨 3:00 触发：
#   0 3 * * * /opt/couplecredit/scripts/daily-backup.sh
#
# ── 凭据（重要）────────────────────────────────────────────────────────
# 本脚本不内置任何数据库账号口令。
# 历史版本曾把 root/root 明文写在第 11-12 行并且已随 git 提交入库，
# 那个口令必须视为**已泄露**，需要轮换（见文件末尾的轮换清单）。
#
# 凭据来源，优先级：显式环境变量 > 凭据文件
#   凭据文件默认 /etc/couplecredit/backup.env，权限必须 600：
#     DB_USER=backup
#     DB_PASSWORD=<强口令>
#   用 BACKUP_ENV_FILE 可指定其他路径。示例见 scripts/backup.env.example。
#
# 可覆盖的变量（不设则用下面的默认值），用于本地验证：
#   BACKUP_DIR  KEEP_DAYS  DB_NAME  UPLOADS_DIR  LOG_FILE  BACKUP_ENV_FILE
# ──────────────────────────────────────────────────────────────────────

set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/backups/daily}"
KEEP_DAYS="${KEEP_DAYS:-14}"
DB_NAME="${DB_NAME:-couple_credit_private}"
UPLOADS_DIR="${UPLOADS_DIR:-/opt/couplecredit/uploads}"
LOG_FILE="${LOG_FILE:-/var/log/couplecredit-backup.log}"
BACKUP_ENV_FILE="${BACKUP_ENV_FILE:-/etc/couplecredit/backup.env}"
TS=$(date +%Y%m%d-%H%M%S)

mkdir -p "$(dirname "$LOG_FILE")" 2>/dev/null || true
log() { echo "[$(date '+%F %T')] $*" >>"$LOG_FILE"; }

# 凭据文件存在就加载；显式传入的环境变量优先，不会被文件覆盖。
if [ -z "${DB_PASSWORD:-}" ] && [ -r "$BACKUP_ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$BACKUP_ENV_FILE"
  set +a
fi

if [ -z "${DB_USER:-}" ] || [ -z "${DB_PASSWORD:-}" ]; then
  # 变量一律用 ${} 包裹：紧跟全角字符时，$VAR 形式会被 bash 把多字节字符的字节
  # 当成变量名的一部分，报出莫名的 unbound variable。
  msg="备份中止：缺少 DB_USER / DB_PASSWORD。请设置同名环境变量，或创建权限 600 的凭据文件 ${BACKUP_ENV_FILE}（示例见 scripts/backup.env.example）"
  echo "$msg" >&2
  log "$msg"
  exit 1
fi

# 口令不能走命令行参数：mysqldump -p"$DB_PASSWORD" 会让口令出现在 ps 的完整 argv 里，
# 同机任何用户都能看到。改用 MYSQL_PWD 环境变量传递。
export MYSQL_PWD="$DB_PASSWORD"

mkdir -p "$BACKUP_DIR"
log "开始备份 (db=$DB_NAME user=$DB_USER)"

# 1. 数据库
DB_FILE="$BACKUP_DIR/db-${TS}.sql.gz"
if mysqldump -u"$DB_USER" --single-transaction --routines --triggers "$DB_NAME" 2>>"$LOG_FILE" | gzip >"$DB_FILE"; then
  log "DB 备份完成: $(du -h "$DB_FILE" | awk '{print $1}')"
else
  log "DB 备份失败!"
  rm -f "$DB_FILE"
  exit 1
fi

# 2. uploads
UPLOADS_FILE="$BACKUP_DIR/uploads-${TS}.tar.gz"
if [ ! -d "$UPLOADS_DIR" ]; then
  log "uploads 目录不存在，跳过: $UPLOADS_DIR"
elif tar czf "$UPLOADS_FILE" -C "$(dirname "$UPLOADS_DIR")" "$(basename "$UPLOADS_DIR")" 2>>"$LOG_FILE"; then
  log "uploads 备份完成: $(du -h "$UPLOADS_FILE" | awk '{print $1}')"
else
  log "uploads 备份失败!"
  rm -f "$UPLOADS_FILE"
  exit 1
fi

# 3. 清理过期备份
find "$BACKUP_DIR" -name "db-*.sql.gz" -mtime +"$KEEP_DAYS" -delete 2>>"$LOG_FILE" || true
find "$BACKUP_DIR" -name "uploads-*.tar.gz" -mtime +"$KEEP_DAYS" -delete 2>>"$LOG_FILE" || true

log "备份全部完成，当前备份目录:"
ls -lh "$BACKUP_DIR" | tail -10 >>"$LOG_FILE"

# ── 口令轮换清单（生产执行，一次性）────────────────────────────────────
# 1) 建一个只有备份所需权限的账号，别再让备份用 root：
#      CREATE USER 'backup'@'localhost' IDENTIFIED BY '<强口令>';
#      GRANT SELECT, LOCK TABLES, SHOW VIEW, EVENT, TRIGGER, PROCESS,
#            RELOAD, REPLICATION CLIENT ON *.* TO 'backup'@'localhost';
#      FLUSH PRIVILEGES;
#    （--routines 需要 SHOW_ROUTINE / SELECT 存储过程相关权限，报错再按提示补。）
# 2) 把账号口令写入凭据文件并收紧权限（绝不能入库）：
#      install -m 600 /dev/null /etc/couplecredit/backup.env
#      printf 'DB_USER=backup\nDB_PASSWORD=%s\n' '<强口令>' > /etc/couplecredit/backup.env
# 3) 跑一次本脚本确认备份成功，再确认 cron 生效。
# 4) 轮换 root 口令（旧口令已随 git 历史泄露）：
#      ALTER USER 'root'@'localhost' IDENTIFIED BY '<新口令>';
#    注意：改完要同步所有仍用 root 的工具/脚本，否则会静默失败。
# 5) 视情况处理历史残留——删文件不会删历史，可评估：
#      git log --oneline -- server/scripts/daily-backup.sh   # 定位涉及提交
#    若仓库曾外发/公开，按「已泄露」处理即可（口令已轮换即无实际风险）；
#    确实要清历史需 git filter-repo 重写 + 强推，会打乱所有协作者，谨慎。
# ──────────────────────────────────────────────────────────────────────
