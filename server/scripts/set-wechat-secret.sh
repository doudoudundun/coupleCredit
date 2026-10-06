#!/usr/bin/env bash
#
# 安全填入微信小程序 AppSecret，并在写入前先向微信校验。
#
# 为什么需要它：
#   /api/auth/wechat-login 唯一缺的就是 WECHAT_SECRET 这一个字符串，
#   它只能由管理员从 微信公众平台 → 开发管理 → 开发设置 → 小程序密钥 生成/重置。
#   手填很容易出现「填错一位 → 上线后所有微信登录 503」且要重启才发现。
#   本脚本先拿 appid+secret 去问微信，确认有效才写进 .env，避免把错值落盘。
#
# 用法：
#   bash scripts/set-wechat-secret.sh              # 交互式输入（不回显）
#   WECHAT_SECRET=xxxx bash scripts/set-wechat-secret.sh   # 非交互（CI/远程）
#
# 注意：AppSecret 一旦生成只有一次可见机会，请从公众平台复制后立即填入。

set -euo pipefail

SERVER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${SERVER_DIR}/.env"
LAUNCHD_LABEL="${LAUNCHD_LABEL:-com.couplecredit.api}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8082/api/auth/healthz}"

red()  { printf '\033[31m%s\033[0m\n' "$*"; }
grn()  { printf '\033[32m%s\033[0m\n' "$*"; }
ylw()  { printf '\033[33m%s\033[0m\n' "$*"; }

[ -f "$ENV_FILE" ] || { red "找不到 $ENV_FILE"; exit 1; }

# ── 1. 取 appid ────────────────────────────────────────────────
APPID="$(grep -E '^WECHAT_APPID=' "$ENV_FILE" | head -1 | cut -d= -f2- || true)"
if [ -z "$APPID" ]; then
  red "server/.env 里没有 WECHAT_APPID，请先补上（应为 wx0d48cf80f76174c4）"
  exit 1
fi
echo "AppID：$APPID"

# ── 2. 取 secret（优先环境变量，否则交互输入且不回显）─────────
SECRET="${WECHAT_SECRET:-}"
if [ -z "$SECRET" ]; then
  # 以 stdin 是否为终端来判断能否交互，不要用 /dev/tty 是否可读：
  # 在 CI / 沙箱环境里 /dev/tty 常常「可读但永远没有输入」，
  # 那会让脚本静默挂住等待输入，而不是给出提示退出。
  if [ ! -t 0 ]; then
    red "非交互环境（stdin 不是终端），请用：WECHAT_SECRET=xxx bash $0"
    exit 1
  fi
  printf '请粘贴 AppSecret（输入不回显）：'
  read -rs SECRET
  printf '\n'
fi

# ── 3. 格式校验（微信 AppSecret 为 32 位十六进制）─────────────
if ! printf '%s' "$SECRET" | grep -qE '^[0-9a-fA-F]{32}$'; then
  red "格式不对：AppSecret 应为 32 位十六进制字符，实际长度 ${#SECRET}"
  exit 1
fi
echo "格式校验通过（32 位十六进制）"

# ── 4. 写入前先问微信：配置是否有效 ───────────────────────────
echo "正在向微信校验 appid + appsecret ..."
PROBE="$(curl -s --noproxy '*' -m 10 \
  "https://api.weixin.qq.com/sns/jscode2session?appid=${APPID}&secret=${SECRET}&js_code=config-check&grant_type=authorization_code" \
  || true)"

case "$PROBE" in
  *'"errcode":40029'*|*'"errcode":40163'*)
    # code 无效 —— 说明 appid+secret 已被微信接受，正是我们要的结果
    grn "✅ 微信确认配置有效（仅探测用的假 code 被拒，属预期）"
    ;;
  *'"errcode":40125'*)
    red "❌ 微信返回 40125 invalid appsecret：这个 AppSecret 与 AppID 不匹配。未写入。"
    exit 1
    ;;
  *'"errcode":40013'*)
    red "❌ 微信返回 40013 invalid appid：AppID 有误。未写入。"
    exit 1
    ;;
  *openid*)
    ylw "⚠️  微信直接返回了 openid（不应发生），仍继续写入。"
    ;;
  *)
    red "❌ 无法确认：微信返回 ${PROBE:-（空，可能是网络不通）}。为安全起见未写入。"
    exit 1
    ;;
esac

# ── 5. 原子写入 .env（只改 WECHAT_SECRET 这一行）──────────────
TMP="$(mktemp "${ENV_FILE}.XXXXXX")"
if grep -qE '^WECHAT_SECRET=' "$ENV_FILE"; then
  awk -v s="$SECRET" '/^WECHAT_SECRET=/{print "WECHAT_SECRET=" s; next} {print}' "$ENV_FILE" > "$TMP"
else
  cp "$ENV_FILE" "$TMP"
  printf '\nWECHAT_SECRET=%s\n' "$SECRET" >> "$TMP"
fi
chmod 600 "$TMP"
# 备份原文件，便于回滚。备份内容与 .env 等密（含 JWT_SECRET / DB 口令），
# 因此必须显式 chmod 600 —— cp -p 会保留原文的 644，不能省这一步。
BACKUP_FILE="${ENV_FILE}.bak.$(date +%Y%m%d%H%M%S)"
cp -p "$ENV_FILE" "$BACKUP_FILE"
chmod 600 "$BACKUP_FILE"
mv "$TMP" "$ENV_FILE"
chmod 600 "$ENV_FILE"
grn "✅ 已写入 ${ENV_FILE}（权限 600，原文件已备份至 $(basename "$BACKUP_FILE")）"

# ── 6. 重启服务 ───────────────────────────────────────────────
if launchctl print "gui/$(id -u)/${LAUNCHD_LABEL}" >/dev/null 2>&1; then
  launchctl kickstart -k "gui/$(id -u)/${LAUNCHD_LABEL}" >/dev/null 2>&1 || true
  for _ in $(seq 1 40); do
    curl -s --noproxy '*' -m 1 "$HEALTH_URL" 2>/dev/null | grep -q 'service alive' && break
    sleep 0.5
  done
  grn "✅ 已重启 ${LAUNCHD_LABEL}"
else
  ylw "⚠️  未找到 launchd 服务 ${LAUNCHD_LABEL}，请手动重启后端让新配置生效。"
fi

# ── 7. 端到端复核 ─────────────────────────────────────────────
echo "正在复核 http://127.0.0.1:8082/api/auth/wechat-login ..."
RESP="$(curl -s --noproxy '*' -m 10 -X POST -H 'Content-Type: application/json' \
  -d '{"code":"config-check"}' http://127.0.0.1:8082/api/auth/wechat-login || true)"

case "$RESP" in
  *WECHAT_CODE_INVALID*)
    grn "✅ 微信一键登录已就绪（假 code 被正确地判为 WECHAT_CODE_INVALID，说明配置生效）"
    ;;
  *WECHAT_NOT_CONFIGURED*|*WECHAT_CONFIG_INVALID*)
    red "❌ 配置未生效：$RESP"
    exit 1
    ;;
  *)
    ylw "⚠️  响应：$RESP"
    ylw "    若为 503/WECHAT_* 之外的内容，请检查服务是否已重启。"
    ;;
esac

echo ""
grn "下一步：在生产服务器 118.195.143.184 上对 /opt/couplecredit/server/.env 执行同样操作。"
