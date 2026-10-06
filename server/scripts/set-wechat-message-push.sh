#!/usr/bin/env bash
#
# 写入微信「消息推送」配置（图片内容安全的异步回调必需），写入前做本地自检。
#
# 为什么需要它：
#   media_check_async（头像审核）的结果只能通过消息推送回来。Token 抄错一位、
#   EncodingAESKey 少一位、后台没开「安全模式」——这三种错都不会立刻报错，
#   而是表现为「用户换了头像，头像永远显示不出来」。等到那时候再排查成本很高。
#   本脚本在写盘前用与微信相同的算法造一段加密报文，走一遍真实的
#   验签 → 解密 → 解析流程，并在最后向回调路由发一个模拟的 URL 校验请求。
#
# 它能查出：格式错、路由没挂载、服务没重启、配置没被读到、验签/解密实现有问题。
# 它查不出：Token / EncodingAESKey 是否与微信后台填的完全一致（自加密自解密
# 用的是同一个值，抄错一位仍会自洽通过）。后者只能靠一次真实回调验证。
#
# 用法：
#   bash scripts/set-wechat-message-push.sh                 # 交互式输入
#   WECHAT_MSG_TOKEN=xxx WECHAT_ENCODING_AES_KEY=yyy bash scripts/set-wechat-message-push.sh
#
# 到哪里跑：**决定头像审核能否跑通的是收到回调的那台机器**。公众平台后台只能填
# 一个回调 URL（本项目是 https://api.couplecredit.top），微信的回调永远打到生产，
# 所以生产必须配。本地跑一次只是彩排（验证写入、重启、路由、解密链路），可选。
# 在本地演练且不想动真实 .env 时：
#   ENV_FILE=/tmp/env.copy CALLBACK_URL=http://127.0.0.1:9999/x bash $0
#
# 两个值都来自 公众平台 → 开发管理 → 开发设置 → 消息推送：
#   Token             你自己填的字符串（3~32 位**字母或数字**）
#   EncodingAESKey    43 位，字符范围 **只有 A-Z a-z 0-9**（不含 + /）
#                     最省事的做法：直接在后台点「随机生成」再复制过来，
#                     这样不可能踩到字符集问题（自己生成极易带上 + 或 /）。
#
# 快速生成一个合规的 Token（在后台填同一个值）：
#   openssl rand -hex 16
# 快速生成一个合规的 EncodingAESKey（43 位纯字母数字）：
#   LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 43; echo

set -euo pipefail

SERVER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# ENV_FILE 可覆盖，用于拿副本做演练（不碰真实 .env）
ENV_FILE="${ENV_FILE:-${SERVER_DIR}/.env}"
LAUNCHD_LABEL="${LAUNCHD_LABEL:-com.couplecredit.api}"   # 本地 macOS
PM2_NAME="${PM2_NAME:-couplecredit-api}"                 # 生产 Linux
NODE_BIN="${NODE_BIN:-node}"

red() { printf '\033[31m%s\033[0m\n' "$*"; }
grn() { printf '\033[32m%s\033[0m\n' "$*"; }
ylw() { printf '\033[33m%s\033[0m\n' "$*"; }

[ -f "$ENV_FILE" ] || { red "找不到 ${ENV_FILE}"; exit 1; }

# 端口从 .env 的 PORT 推导，本地与生产才能用同一条命令。
# 本地 .env 写 8082、生产写 8080；显式传 PORT 可覆盖。
PORT="${PORT:-$(grep -E '^PORT=' "$ENV_FILE" | head -1 | cut -d= -f2- || true)}"
PORT="${PORT:-8082}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:${PORT}/api/auth/healthz}"
CALLBACK_URL="${CALLBACK_URL:-http://127.0.0.1:${PORT}/api/wechat/callback}"

# ── 1. 取两个配置值 ────────────────────────────────────────────
TOKEN="${WECHAT_MSG_TOKEN:-}"
AES_KEY="${WECHAT_ENCODING_AES_KEY:-}"

if [ -z "$TOKEN" ] || [ -z "$AES_KEY" ]; then
  # 以 stdin 是否为终端来判断能否交互。不要用 /dev/tty 是否可读来判断：
  # 在 CI / 沙箱 / 定时任务里 /dev/tty 往往「可读但永远没有输入」，
  # 那会让脚本静默挂住等待输入，而不是给出提示退出。
  if [ ! -t 0 ]; then
    red "非交互环境（stdin 不是终端），请显式传入两个值："
    red "  WECHAT_MSG_TOKEN=xxx WECHAT_ENCODING_AES_KEY=yyy bash $0"
    exit 1
  fi
  if [ -z "$TOKEN" ]; then
    printf '请粘贴消息推送 Token（3~32 位字母或数字）：'
    read -r TOKEN
  fi
  if [ -z "$AES_KEY" ]; then
    printf '请粘贴 EncodingAESKey（43 位，不回显）：'
    read -rs AES_KEY
    printf '\n'
  fi
fi

# ── 2. 格式校验（本地自检也会再查一遍，这里先给出人能看懂的错误）──
if ! printf '%s' "$TOKEN" | grep -qE '^[A-Za-z0-9]{3,32}$'; then
  red "Token 格式不对：应为 3~32 位字母或数字，当前长度 ${#TOKEN}"
  exit 1
fi
if ! printf '%s' "$AES_KEY" | grep -qE '^[A-Za-z0-9]{43}$'; then
  # 微信后台对该字段的提示是「由43位字符组成，字符范围为 A-Z,a-z,0-9」——
  # 只有字母数字。虽然 AES 密钥本身是 base64（合法字符含 + 和 /），但微信的表单
  # **不接受** + 和 /：带这类字符时后台会静默拒绝提交，只回一句含糊的「系统繁忙」，
  # 长度仍是 43/43，从界面上完全看不出是哪一位的问题。必须在这里挡住。
  if printf '%s' "$AES_KEY" | grep -qE '^[A-Za-z0-9+/]{43}$'; then
    red "EncodingAESKey 含 + 或 /，微信后台不接受（虽然它是合法 base64）。"
    red "请到后台点「随机生成」，或改用 43 位纯字母数字，当前值含非法字符："
    printf '%s\n' "$AES_KEY" | tr -d 'A-Za-z0-9' | sed 's/^/  非法字符: /'
  else
    red "EncodingAESKey 格式不对：应为 43 位字母或数字，当前长度 ${#AES_KEY}"
  fi
  exit 1
fi
echo "格式校验通过（Token ${#TOKEN} 位，EncodingAESKey 43 位）"

# ── 3. 本地自检：验签 / 解密 / 解析全流程 ──────────────────────
APPID="$(grep -E '^WECHAT_APPID=' "$ENV_FILE" | head -1 | cut -d= -f2- || true)"
[ -n "$APPID" ] || APPID="wxself-test"

echo "正在做本地自检（不访问微信）..."
SELFTEST_OUT="$(
  cd "$SERVER_DIR" && \
  SELFTEST_TOKEN="$TOKEN" SELFTEST_KEY="$AES_KEY" SELFTEST_APPID="$APPID" "$NODE_BIN" -e '
    const { runPushSelfTest } = require("./scripts/lib/wechat-push-selftest.js");
    const r = runPushSelfTest({
      token: process.env.SELFTEST_TOKEN,
      encodingAESKey: process.env.SELFTEST_KEY,
      appid: process.env.SELFTEST_APPID,
    });
    for (const c of r.checks) {
      console.log(`  ${c.ok ? "OK" : "FAIL"} ${c.label}${c.detail && !c.ok ? " → " + c.detail : ""}`);
    }
    process.exit(r.ok ? 0 : 1);
  ' 2>&1
)" && SELFTEST_OK=1 || SELFTEST_OK=0

printf '%s\n' "$SELFTEST_OUT"

if [ "$SELFTEST_OK" != "1" ]; then
  red "❌ 本地自检未通过，未写入 ${ENV_FILE}。请核对后台的 Token 与 EncodingAESKey。"
  exit 1
fi
grn "✅ 本地自检通过（验签、解密、JSON/XML 两种格式、防伪造均正常）"

# ── 4. 原子写入 .env（只改这两个键）───────────────────────────
TMP="$(mktemp "${ENV_FILE}.XXXXXX")"

write_key() {
  local key="$1" value="$2" src="$3" dst="$4"
  if grep -qE "^${key}=" "$src"; then
    # 用 index()==1 判断行首而不是正则匹配：键名只含字母数字下划线，
    # 这个比较既精确又不会因为正则元字符出意外
    awk -v k="$key" -v v="$value" '
      index($0, k "=") == 1 { print k "=" v; next } { print }
    ' "$src" > "$dst"
  else
    cat "$src" > "$dst"
    printf '%s=%s\n' "$key" "$value" >> "$dst"
  fi
}

write_key "WECHAT_MSG_TOKEN" "$TOKEN" "$ENV_FILE" "$TMP"
STEP="$(mktemp "${ENV_FILE}.XXXXXX")"
write_key "WECHAT_ENCODING_AES_KEY" "$AES_KEY" "$TMP" "$STEP"
mv "$STEP" "$TMP"

chmod 600 "$TMP"
# 备份含 JWT_SECRET / DB 口令，必须显式 chmod 600：cp -p 会保留原文权限，不能省
BACKUP_FILE="${ENV_FILE}.bak.$(date +%Y%m%d%H%M%S)"
cp -p "$ENV_FILE" "$BACKUP_FILE"
chmod 600 "$BACKUP_FILE"
mv "$TMP" "$ENV_FILE"
chmod 600 "$ENV_FILE"
grn "✅ 已写入 ${ENV_FILE}（权限 600，原文件已备份至 $(basename "$BACKUP_FILE")）"

# ── 5. 重启服务 ────────────────────────────────────────────────
# 本地是 launchd、生产是 PM2，两条都试。都找不到时必须明确提示手动重启：
# 静默跳过会让配置不生效，而第 6 步会报出「未配置」，看起来像实现坏了而不是没重启。
RESTARTED=""
if command -v launchctl >/dev/null 2>&1 && launchctl print "gui/$(id -u)/${LAUNCHD_LABEL}" >/dev/null 2>&1; then
  launchctl kickstart -k "gui/$(id -u)/${LAUNCHD_LABEL}" >/dev/null 2>&1 || true
  RESTARTED="launchd:${LAUNCHD_LABEL}"
elif command -v pm2 >/dev/null 2>&1 && pm2 describe "$PM2_NAME" >/dev/null 2>&1; then
  # --update-env 不可省：否则 pm2 沿用启动时的环境，读不到新写入的 .env
  pm2 restart "$PM2_NAME" --update-env >/dev/null 2>&1 || true
  RESTARTED="pm2:${PM2_NAME}"
fi

if [ -n "$RESTARTED" ]; then
  for _ in $(seq 1 40); do
    curl -s --noproxy '*' -m 1 "$HEALTH_URL" 2>/dev/null | grep -q 'service alive' && break
    sleep 0.5
  done
  grn "✅ 已重启 ${RESTARTED}"
else
  ylw "⚠️  既没有 launchd 服务（${LAUNCHD_LABEL}）也没有 pm2 进程（${PM2_NAME}）。"
  ylw "    请手动重启后端，否则下面的复核会失败："
  ylw "      pm2 restart ${PM2_NAME} --update-env"
fi

# ── 6. 端到端复核：模拟微信的 URL 校验请求 ─────────────────────
# 微信在后台保存配置时会发一个带密文 echostr 的 GET；服务应验签、解密后
# 原样返回明文。这一步能真实证明回调路由通了（不只是配置写进去了）。
echo "正在复核回调路由（模拟微信的 URL 校验）..."
EXPECTED_ECHOSTR="callback-probe-$RANDOM"
PROBE_QUERY="$(
  cd "$SERVER_DIR" && \
  PROBE_TOKEN="$TOKEN" PROBE_KEY="$AES_KEY" PROBE_APPID="$APPID" PROBE_ECHOSTR="$EXPECTED_ECHOSTR" "$NODE_BIN" -e '
    const crypto = require("crypto");
    const { encryptLikeWechat } = require("./scripts/lib/wechat-push-selftest.js");
    const { computeMsgSignature } = require("./src/utils/wechatCrypto.js");
    const t = process.env.PROBE_TOKEN, k = process.env.PROBE_KEY;
    const ts = "1700000002", nonce = "probe-nonce";
    const enc = encryptLikeWechat(process.env.PROBE_ECHOSTR, process.env.PROBE_APPID, k);
    const sig = computeMsgSignature({ token: t, timestamp: ts, nonce, encrypt: enc });
    console.log(`msg_signature=${encodeURIComponent(sig)}&timestamp=${ts}&nonce=${nonce}&echostr=${encodeURIComponent(enc)}`);
  '
)"

RESP="$(curl -s --noproxy '*' -m 10 "${CALLBACK_URL}?${PROBE_QUERY}" || true)"

if [ "$RESP" = "$EXPECTED_ECHOSTR" ]; then
  grn "✅ 回调路由已就绪（密文 echostr 被正确验签并解密后原样返回）"
elif printf '%s' "$RESP" | grep -q '未配置'; then
  red "❌ 配置未生效：$RESP"
  exit 1
else
  ylw "⚠️  返回：${RESP:-（空）}"
  ylw "    若服务未重启或路由未挂载，会得到非预期内容，请检查。"
fi

# ── 7. 后台还需要做什么 ────────────────────────────────────────
echo ""
ylw "还需要在公众平台后台完成（这几步脚本无法代劳）："
echo "  1) 开发管理 → 开发设置 → 消息推送 → 启用"
echo "  2) URL 填：https://api.couplecredit.top/api/wechat/callback"
echo "  3) Token 填：${TOKEN}"
echo "  4) EncodingAESKey：与本次写入 .env 的值保持一致（后台生成的那一串）"
echo "  5) 消息加密方式选【安全模式】；数据格式选 JSON 或 XML 均可（本服务都支持）"
echo "     ↑ 必须选安全模式：本服务会拒绝未加密的回调报文，以防伪造「审核通过」"
echo ""
grn "提示：微信的回调只会打到公众平台后台填的那个 URL（本项目是 https://api.couplecredit.top）。"
grn "      所以「哪台机器收回调，哪台就必须配」—— 生产是必须的，本地跑只是彩排。"
grn "      后台点「保存」时微信会发一次真实的加密校验请求：保存成功 = 这里的值与后台一致。"
