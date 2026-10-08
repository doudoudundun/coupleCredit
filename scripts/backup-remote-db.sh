#!/usr/bin/env bash
# Pull remote couple_credit_private DB + uploads to local for backup.
# Optionally mirror the dump back into the local MySQL so the local launchd
# instance reflects production data.
#
# Config via ~/.couplecredit-backup-env (chmod 600), example:
#   REMOTE_DB_USER=root
#   REMOTE_DB_PASSWORD=root
#   LOCAL_DB_ADMIN_USER=root
#   LOCAL_DB_ADMIN_PASSWORD=<your-local-mysql-root-pwd>
#   LOCAL_DB_NAME=couple_credit_private
#   MIRROR_LOCAL_DB=true
#
# Without ~/.couplecredit-backup-env the script falls back to sensible
# defaults but the mirror step is skipped.

set -euo pipefail

SSH_KEY="${SSH_KEY:-$HOME/Downloads/newexm.pem}"
REMOTE_HOST="${REMOTE_HOST:-root@118.195.143.184}"
REMOTE_DB_NAME="${REMOTE_DB_NAME:-couple_credit_private}"
REMOTE_UPLOADS_DIR="${REMOTE_UPLOADS_DIR:-/opt/couplecredit/uploads}"
LOCAL_UPLOADS_DIR="${LOCAL_UPLOADS_DIR:-/Users/chengzi/Code/GitHub/coupleCredit/server/uploads}"
BACKUP_DIR="${BACKUP_DIR:-$HOME/backups/couplecredit}"
KEEP_DUMPS="${KEEP_DUMPS:-14}"
LAUNCHD_LABEL="${LAUNCHD_LABEL:-com.couplecredit.api}"
PROJECT_ROOT="${PROJECT_ROOT:-/Users/chengzi/Code/GitHub/coupleCredit}"

ENV_FILE="${ENV_FILE:-$HOME/.couplecredit-backup-env}"
if [[ -f "$ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$ENV_FILE"
fi

mkdir -p "$BACKUP_DIR"

TS="$(date +%Y%m%d-%H%M%S)"
DUMP_FILE="$BACKUP_DIR/cc-$TS.sql.gz"
LOG_PREFIX="[cc-backup $TS]"

log() { echo "$LOG_PREFIX $*"; }

REMOTE_DB_USER_VAL="${REMOTE_DB_USER:-root}"
REMOTE_DB_PASSWORD_VAL="${REMOTE_DB_PASSWORD:-root}"

log "1/4 Pulling remote DB dump..."
ssh -i "$SSH_KEY" -o BatchMode=yes "$REMOTE_HOST" \
  "mysqldump --single-transaction --routines --triggers --events \
     -u'$REMOTE_DB_USER_VAL' -p'$REMOTE_DB_PASSWORD_VAL' '$REMOTE_DB_NAME' 2>/dev/null | gzip" \
  > "$DUMP_FILE"

DUMP_SIZE="$(du -h "$DUMP_FILE" | cut -f1)"
TABLE_COUNT="$(gunzip -c "$DUMP_FILE" | grep -c '^CREATE TABLE' || true)"
log "  saved $DUMP_FILE ($DUMP_SIZE, $TABLE_COUNT tables)"

log "2/4 Pruning old dumps (keep $KEEP_DUMPS)..."
ls -1t "$BACKUP_DIR"/cc-*.sql.gz 2>/dev/null | tail -n +"$((KEEP_DUMPS + 1))" | while read -r old; do
  rm -f "$old"
  log "  removed $(basename "$old")"
done

log "3/4 rsync uploads/..."
mkdir -p "$LOCAL_UPLOADS_DIR"
rsync -aqz --partial -e "ssh -i $SSH_KEY -o BatchMode=yes" \
  "$REMOTE_HOST:$REMOTE_UPLOADS_DIR/" "$LOCAL_UPLOADS_DIR/" \
  || log "  WARN: uploads rsync had non-zero exit"

LOCAL_DB_ADMIN_USER_VAL="${LOCAL_DB_ADMIN_USER:-}"
LOCAL_DB_ADMIN_PASSWORD_VAL="${LOCAL_DB_ADMIN_PASSWORD:-}"
LOCAL_DB_NAME_VAL="${LOCAL_DB_NAME:-couple_credit_private}"
MIRROR_LOCAL_DB_VAL="${MIRROR_LOCAL_DB:-false}"

log "4/4 Mirror refresh (MIRROR_LOCAL_DB=$MIRROR_LOCAL_DB_VAL)..."
if [[ "$MIRROR_LOCAL_DB_VAL" != "true" ]]; then
  log "  skipped (set MIRROR_LOCAL_DB=true to enable)"
elif [[ -z "$LOCAL_DB_ADMIN_USER_VAL" || -z "$LOCAL_DB_ADMIN_PASSWORD_VAL" ]]; then
  log "  skipped (LOCAL_DB_ADMIN_USER/PASSWORD not set in $ENV_FILE)"
else
  log "  stopping local launchd..."
  launchctl unload "$HOME/Library/LaunchAgents/$LAUNCHD_LABEL.plist" 2>/dev/null || true

  log "  recreating local DB..."
  mysql -u"$LOCAL_DB_ADMIN_USER_VAL" -p"$LOCAL_DB_ADMIN_PASSWORD_VAL" \
    -e "DROP DATABASE IF EXISTS \`$LOCAL_DB_NAME_VAL\`; \
        CREATE DATABASE \`$LOCAL_DB_NAME_VAL\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" \
    2>&1 | grep -v "Warning" || true

  log "  importing dump..."
  gunzip -c "$DUMP_FILE" | \
    mysql -u"$LOCAL_DB_ADMIN_USER_VAL" -p"$LOCAL_DB_ADMIN_PASSWORD_VAL" "$LOCAL_DB_NAME_VAL" \
    2>&1 | grep -v "Warning" || true

  log "  restarting local launchd..."
  launchctl load "$HOME/Library/LaunchAgents/$LAUNCHD_LABEL.plist" 2>/dev/null || true
  sleep 2
  if curl -sf --max-time 5 http://127.0.0.1:8082/api/health >/dev/null 2>&1; then
    log "  local API healthy again"
  else
    log "  WARN: local API health check failed"
  fi
fi

log "done."
