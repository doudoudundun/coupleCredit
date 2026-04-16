# Local API setup

## 1. Confirm local server configuration values

The launchd service uses the same values as `/Users/chengzi/Code/GitHub/coupleCredit/server/.env.example`, with the local listener kept on `127.0.0.1:8082` for the main workspace.

```properties
HOST=127.0.0.1
PORT=8082
DB_HOST=127.0.0.1
DB_PORT=3306
DB_NAME=couple_credit_private
DB_USER=couple_app
DB_PASSWORD=ChangeThisPrivateDbPassword_2026!
INVITE_CODE=COUPLE-PRIVATE-2026
BCRYPT_ROUNDS=10
```

## 2. Verify the Node runtime launchd will use

The plist currently starts Node through `nvm`'s `default` alias because this Mac does not have a stable non-`nvm` Node path such as `/opt/homebrew/bin/node` or `/usr/local/bin/node`.

Preflight check:

```bash
test -s "$HOME/.nvm/nvm.sh"
. "$HOME/.nvm/nvm.sh"
nvm which default
```

Expected: the first command exits successfully, sourcing `"$HOME/.nvm/nvm.sh"` succeeds without error, and `nvm which default` prints a real Node binary path.

If `nvm which default` fails, install a Node version, set the default alias, and re-run the preflight check before installing the agent:

```bash
. "$HOME/.nvm/nvm.sh"
nvm install 24
nvm alias default 24
nvm which default
```

## 3. Install server dependencies

```bash
npm install --prefix /Users/chengzi/Code/GitHub/coupleCredit/server
```

Expected: install completes without errors and `/Users/chengzi/Code/GitHub/coupleCredit/server/node_modules` is present.

## 3. Install or update the launchd agent

Copy the plist into `~/Library/LaunchAgents`:

```bash
mkdir -p ~/Library/LaunchAgents
cp /Users/chengzi/Code/GitHub/coupleCredit/server/launchd/com.couplecredit.api.plist ~/Library/LaunchAgents/com.couplecredit.api.plist
```

If the agent is not loaded yet:

```bash
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.couplecredit.api.plist
launchctl enable gui/$(id -u)/com.couplecredit.api
launchctl kickstart -k gui/$(id -u)/com.couplecredit.api
```

If the agent is already installed and you are updating it:

```bash
launchctl bootout gui/$(id -u) ~/Library/LaunchAgents/com.couplecredit.api.plist || true
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.couplecredit.api.plist
launchctl enable gui/$(id -u)/com.couplecredit.api
launchctl kickstart -k gui/$(id -u)/com.couplecredit.api
```

Inspect the loaded job:

```bash
launchctl print gui/$(id -u)/com.couplecredit.api
```

Expected: the output shows label `com.couplecredit.api` and no immediate crash loop.

## 4. Check current job state first, logs second

Use `launchctl print` and live HTTP responses as the primary source of truth. Stdout/stderr logs are useful for extra context, but they can contain stale lines from an earlier run.

Primary check:

```bash
launchctl print gui/$(id -u)/com.couplecredit.api
```

Expected: the job is present, has a current `pid` after `kickstart`, and does not show a repeating non-zero `last exit code` crash loop.

Secondary log checks:

launchd writes logs here:

- stdout: `/tmp/com.couplecredit.api.stdout.log`
- stderr: `/tmp/com.couplecredit.api.stderr.log`

Useful commands:

```bash
ls -l /tmp/com.couplecredit.api.stdout.log /tmp/com.couplecredit.api.stderr.log
/usr/bin/tail -n 20 /tmp/com.couplecredit.api.stdout.log
/usr/bin/tail -n 20 /tmp/com.couplecredit.api.stderr.log
```

Expected: stdout may include a recent line like `Local auth API listening on http://127.0.0.1:8082`, but treat that as supporting evidence only after `launchctl print` and live endpoint checks succeed.

## 5. Verify health and auth endpoints

Create rerunnable verification values once and reuse them for registration, login, and bill creation:

```bash
VERIFY_SUFFIX="$(date +%s)"
VERIFY_USER="api_accept_user_${VERIFY_SUFFIX}"
VERIFY_EMAIL="${VERIFY_USER}@example.com"
VERIFY_PASSWORD="secret123"
```

Health:

```bash
health_body="$(mktemp)"
health_status="$(curl -sS -o "$health_body" -w '%{http_code}' http://127.0.0.1:8082/api/auth/healthz)"
printf 'HTTP %s\n' "$health_status"
cat "$health_body"
```

Expected: `HTTP 200` and JSON `{"ok":true,"message":"service alive"}`.

Register a verification user:

```bash
register_body="$(mktemp)"
register_status="$(curl -sS -o "$register_body" -w '%{http_code}' -X POST http://127.0.0.1:8082/api/auth/register \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"${VERIFY_USER}\",\"email\":\"${VERIFY_EMAIL}\",\"password\":\"${VERIFY_PASSWORD}\",\"inviteCode\":\"COUPLE-PRIVATE-2026\"}")"
printf 'HTTP %s\n' "$register_status"
cat "$register_body"
```

Expected: `HTTP 201` and JSON containing `"ok":true`, `"message":"注册成功"`, and a numeric `userId`.

Login with the same user:

```bash
login_body="$(mktemp)"
login_status="$(curl -sS -o "$login_body" -w '%{http_code}' -X POST http://127.0.0.1:8082/api/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"${VERIFY_USER}\",\"password\":\"${VERIFY_PASSWORD}\"}")"
printf 'HTTP %s\n' "$login_status"
cat "$login_body"
```

Expected: `HTTP 200` and JSON containing `"ok":true` and `"message":"登录成功"`.

## 6. Verify bill creation

Create a bill for the same user after registration succeeds. Replace `USER_ID` with the `userId` returned by the register command.

```bash
bill_body="$(mktemp)"
bill_status="$(curl -sS -o "$bill_body" -w '%{http_code}' -X POST http://127.0.0.1:8082/api/bills \
  -H 'Content-Type: application/json' \
  -d '{"userId":USER_ID,"billOwner":"自己","title":"午餐","type":"餐饮","amount":25.5,"date":"2026-04-12","time":"12:30:00","incomeType":0}')"
printf 'HTTP %s\n' "$bill_status"
cat "$bill_body"
```

Expected: `HTTP 201` and JSON containing `"ok":true`, `"message":"账单创建成功"`, and a numeric `billId`.

## 8. Install or update the public tunnel agent

The external-access path keeps MySQL private and exposes only the local HTTP API through `localhost.run`.

Copy the tunnel plist into `~/Library/LaunchAgents`:

```bash
mkdir -p ~/Library/LaunchAgents
cp /Users/chengzi/Code/GitHub/coupleCredit/server/launchd/com.couplecredit.api-tunnel.plist ~/Library/LaunchAgents/com.couplecredit.api-tunnel.plist
```

If the tunnel agent is not loaded yet:

```bash
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.couplecredit.api-tunnel.plist
launchctl enable gui/$(id -u)/com.couplecredit.api-tunnel
launchctl kickstart -k gui/$(id -u)/com.couplecredit.api-tunnel
```

If the tunnel agent is already installed and you are updating it:

```bash
launchctl bootout gui/$(id -u) ~/Library/LaunchAgents/com.couplecredit.api-tunnel.plist || true
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.couplecredit.api-tunnel.plist
launchctl enable gui/$(id -u)/com.couplecredit.api-tunnel
launchctl kickstart -k gui/$(id -u)/com.couplecredit.api-tunnel
```

Inspect the loaded job:

```bash
launchctl print gui/$(id -u)/com.couplecredit.api-tunnel
```

Expected: the output shows label `com.couplecredit.api-tunnel`, a current `pid`, and no immediate crash loop.

## 9. Read the current public URL

The tunnel URL can change after restart. Treat `/tmp/couplecredit-public-tunnel/public-url.txt` as the source of truth for the current public endpoint.

```bash
cat /tmp/couplecredit-public-tunnel/public-url.txt
```

Expected: a single HTTPS URL such as `https://example-subdomain.lhr.life`.

Supporting files:

- current URL: `/tmp/couplecredit-public-tunnel/public-url.txt`
- tunnel log: `/tmp/couplecredit-public-tunnel/localhost-run.log`
- tunnel stdout: `/tmp/com.couplecredit.api-tunnel.stdout.log`
- tunnel stderr: `/tmp/com.couplecredit.api-tunnel.stderr.log`

If the URL file is empty or missing, restart the tunnel agent and re-check `launchctl print` before trusting old log lines.

## 10. Point Android Studio builds at the public API

`app/build.gradle.kts` now reads the public API base URL in this order:

1. `PRIVATE_API_BASE_URL` from `/Users/chengzi/Code/GitHub/coupleCredit/local.properties`
2. `/tmp/couplecredit-public-tunnel/public-url.txt`
3. fallback `http://10.0.2.2:8080`

That means you usually do not need to edit `local.properties` after the tunnel restarts. Keep only this in `local.properties`:

```properties
sdk.dir=/Users/chengzi/Library/Android/sdk
PRIVATE_API_INVITE_CODE=COUPLE-PRIVATE-2026
```

Only set `PRIVATE_API_BASE_URL` manually if you intentionally want to override the tunnel URL.

## 11. Verify the public API path

Read the current URL once and reuse it for verification:

```bash
PUBLIC_API_BASE_URL="$(cat /tmp/couplecredit-public-tunnel/public-url.txt)"
VERIFY_SUFFIX="$(date +%s)"
VERIFY_USER="public_api_user_${VERIFY_SUFFIX}"
VERIFY_EMAIL="${VERIFY_USER}@example.com"
VERIFY_PASSWORD="secret123"
```

Health:

```bash
health_body="$(mktemp)"
health_status="$(curl -sS -o "$health_body" -w '%{http_code}' "$PUBLIC_API_BASE_URL/api/auth/healthz")"
printf 'HTTP %s\n' "$health_status"
cat "$health_body"
```

Expected: `HTTP 200` and JSON `{"ok":true,"message":"service alive"}`.

Register:

```bash
register_body="$(mktemp)"
register_status="$(curl -sS -o "$register_body" -w '%{http_code}' -X POST "$PUBLIC_API_BASE_URL/api/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"${VERIFY_USER}\",\"email\":\"${VERIFY_EMAIL}\",\"password\":\"${VERIFY_PASSWORD}\",\"inviteCode\":\"COUPLE-PRIVATE-2026\"}")"
printf 'HTTP %s\n' "$register_status"
cat "$register_body"
```

Expected: `HTTP 201` and JSON containing `"ok":true`, `"message":"注册成功"`, and a numeric `userId`.

Login:

```bash
login_body="$(mktemp)"
login_status="$(curl -sS -o "$login_body" -w '%{http_code}' -X POST "$PUBLIC_API_BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"${VERIFY_USER}\",\"password\":\"${VERIFY_PASSWORD}\"}")"
printf 'HTTP %s\n' "$login_status"
cat "$login_body"
```

Expected: `HTTP 200` and JSON containing `"ok":true` and `"message":"登录成功"`.

Bill creation:

```bash
bill_body="$(mktemp)"
bill_status="$(curl -sS -o "$bill_body" -w '%{http_code}' -X POST "$PUBLIC_API_BASE_URL/api/bills" \
  -H 'Content-Type: application/json' \
  -d '{"userId":USER_ID,"billOwner":"自己","title":"午餐","type":"餐饮","amount":25.5,"date":"2026-04-12","time":"12:30:00","incomeType":0}')"
printf 'HTTP %s\n' "$bill_status"
cat "$bill_body"
```

Replace `USER_ID` with the `userId` from the register response.

Expected: `HTTP 201` and JSON containing `"ok":true` and `"message":"账单创建成功"`.

## 12. External-access acceptance checklist

- `launchctl print gui/$(id -u)/com.couplecredit.api` shows the local API agent loaded and running.
- `launchctl print gui/$(id -u)/com.couplecredit.api-tunnel` shows the public tunnel agent loaded and running.
- `/tmp/couplecredit-public-tunnel/public-url.txt` contains the current public HTTPS URL.
- Android builds can read the current tunnel URL without manually editing `local.properties`.
- Public verification returns the expected statuses: health `200`, register `201`, login `200`, bill creation `201`.
- The public path exposes only the HTTP API; MySQL stays on the Mac.
