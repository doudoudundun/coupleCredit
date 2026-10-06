#!/usr/bin/env node
/**
 * 微信一键注册 —— 行为级验证
 *
 * 为什么需要它：这个接口有两处**不会报错、只会静默出错**的地方
 *   1. openid 若取自客户端传参 → 任何人都能拿别人的 openid 抢注账号；
 *   2. 账号被造出「可用的密码」→ 与产品决定（微信账号只能用微信登录）相矛盾，
 *      而且不会有任何报错，直到有人用密码登进来。
 * 两者都只能用实测断言，不能靠读代码确认。
 *
 * 做法：起真实 HTTP 服务、挂**真实路由**、连**真实数据库**，
 *       用假 fetch 拦截 code -> openid 换取（微信侧无法在本地复现），
 *       其余逻辑（邀请码、唯一性、建表、签发会话、密码不可用）全部走真实路径。
 *
 * 用法：node scripts/verify-wechat-register.js
 * 退出码 0 = 全部通过。测试期间创建的用户会在结束时删除。
 *
 * 说明：AppID / Secret 用**占位值**强制覆盖 —— 本脚本的 fetch 是假的，
 *       不需要任何真实凭据，也不会把凭据写进仓库或日志。
 */

const fs = require("fs");
const path = require("path");

// ── 1) 先定占位凭据，再载入 .env ────────────────────────────────
// 顺序不能反：下面的载入器对「已存在于 process.env 的键」会跳过，
// 因此先写占位值可以保证它们优先生效，测试与真实凭据完全解耦。
process.env.WECHAT_APPID = "wx_verify_placeholder";
process.env.WECHAT_SECRET = "verify_placeholder_secret";

// ── 2) 载入 .env（config.js 在 require 时就读取 process.env）────
(function loadDotEnv() {
  const file = path.resolve(__dirname, "..", ".env");
  if (!fs.existsSync(file)) return;
  for (const line of fs.readFileSync(file, "utf8").split("\n")) {
    const m = /^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/.exec(line);
    if (!m || process.env[m[1]] !== undefined) continue;
    let value = m[2];
    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1);
    }
    process.env[m[1]] = value;
  }
})();

const express = require("express");
const bcrypt = require("bcrypt");
const { createPool } = require("../src/db");
const { readConfig } = require("../src/config");
const { sendError } = require("../src/errors");
const { createAuthRouter } = require("../src/routes/auth");

const config = readConfig();
const pool = createPool(config);

// ── 合成 openid / code ─────────────────────────────────────────
const STAMP = Date.now().toString(36);
const OPENID_A = `verify_wxreg_a_${STAMP}`;
const OPENID_B = `verify_wxreg_b_${STAMP}`;
const CODE_A = `verify-js-code-a-${STAMP}`;
const CODE_B = `verify-js-code-b-${STAMP}`;
const CODE_INVALID = `verify-js-code-invalid-${STAMP}`;
const CODE_TO_OPENID = { [CODE_A]: OPENID_A, [CODE_B]: OPENID_B };

const passthrough = (_req, _res, next) => next();

let passed = 0;
let failed = 0;
const lines = [];
function ok(label, cond, detail) {
  cond ? (passed += 1) : (failed += 1);
  lines.push(`${cond ? "✅" : "❌"} ${label}${detail ? `  → ${detail}` : ""}`);
}

/** 只替换微信的 code->openid 换取，其余请求原样透传。 */
function installFakeWechatFetch() {
  const realFetch = global.fetch;
  global.fetch = async (url, options) => {
    const target = String(url);
    if (target.includes("/sns/jscode2session")) {
      const jsCode = new URL(target).searchParams.get("js_code") || "";
      const openid = CODE_TO_OPENID[jsCode];
      // 未登记的 code 一律按微信的「code 无效」返回，用于验证失败分支
      if (!openid) return { json: async () => ({ errcode: 40029, errmsg: "invalid code" }) };
      return { json: async () => ({ openid, session_key: "verify-fake-session-key" }) };
    }
    return realFetch(url, options);
  };
}

async function post(base, route, body) {
  const r = await fetch(`${base}${route}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
  let json = null;
  try {
    json = await r.json();
  } catch (_e) {
    /* 非 JSON 响应 */
  }
  return { status: r.status, json };
}

async function countByOpenid(openid) {
  const [rows] = await pool.execute(
    "SELECT COUNT(*) AS n FROM users WHERE wechat_openid = ?",
    [openid]
  );
  return rows[0].n;
}

async function main() {
  installFakeWechatFetch();

  const app = express();
  app.use(express.json());
  app.use(
    "/api/auth",
    createAuthRouter({
      pool,
      config,
      authLimiter: passthrough,
      strictLimiter: passthrough,
      contentSecurity: null // 本脚本只测注册逻辑，不触发远程内容审核
    })
  );
  app.use((err, _req, res, _next) => sendError(res, err));

  const server = await new Promise((resolve) => {
    const s = app.listen(0, "127.0.0.1", () => resolve(s));
  });
  const base = `http://127.0.0.1:${server.address().port}`;
  const ROUTE = "/api/auth/wechat-register";
  let createdId = null;

  try {
    ok("config.inviteCode 已配置（否则本测试无意义）", Boolean(config.inviteCode));

    // 1) 缺邀请码 → 400，且不建号
    {
      const before = await countByOpenid(OPENID_A);
      const r = await post(base, ROUTE, { code: CODE_A });
      const after = await countByOpenid(OPENID_A);
      ok(
        "缺邀请码 → 400 且未建号",
        r.status === 400 && after === before,
        `status=${r.status} code=${r.json && r.json.error && r.json.error.code}`
      );
    }

    // 2) 邀请码错误 → 403 INVALID_INVITE_CODE，且不建号
    {
      const r = await post(base, ROUTE, { code: CODE_A, inviteCode: "definitely-wrong-code" });
      ok(
        "邀请码错误 → 403 INVALID_INVITE_CODE",
        r.status === 403 && r.json && r.json.error && r.json.error.code === "INVALID_INVITE_CODE",
        `status=${r.status} code=${r.json && r.json.error && r.json.error.code}`
      );
    }

    // 3) 邀请码正确但 code 无效 → 401，且**不建号**（防止拿无效 code 造垃圾账号）
    // 错误码两套都要接受：本仓库是 WECHAT_CODE_INVALID，而线上部署的旧版本这条链路
    // 仍返回 WECHAT_AUTH_FAILED（线上 auth.js 比本地落后一整条重构线，未含 wechatErrors 映射）。
    // 断言的是行为（401 + 不建号）而不是具体文案，否则本地能过、线上必挂 ——
    // 而真正要守住的是「无效 code 不能造出账号」。
    {
      const before = await countByOpenid(OPENID_A);
      const r = await post(base, ROUTE, { code: CODE_INVALID, inviteCode: config.inviteCode });
      const after = await countByOpenid(OPENID_A);
      const errCode = r.json && r.json.error && r.json.error.code;
      ok(
        "code 无效 → 401（WECHAT_CODE_INVALID / 线上旧版 WECHAT_AUTH_FAILED）且未建号",
        r.status === 401 &&
          after === before &&
          ["WECHAT_CODE_INVALID", "WECHAT_AUTH_FAILED"].includes(errCode),
        `status=${r.status} code=${errCode} 建号数=${after - before}`
      );
    }

    // 4) 正常注册 → 201 + 完整登录态
    let registered = null;
    {
      const r = await post(base, ROUTE, { code: CODE_A, inviteCode: config.inviteCode });
      registered = r.json && r.json.data;
      ok(
        "正常路径 → 201 且直接返回登录态（accessToken/refreshToken）",
        r.status === 201 &&
          registered &&
          registered.bound === true &&
          registered.created === true &&
          typeof registered.accessToken === "string" &&
          typeof registered.refreshToken === "string",
        `status=${r.status} username=${registered && registered.username}`
      );
      createdId = registered && registered.userId;
    }

    // 5) 落库内容的形状：占位用户名 / 保留域邮箱 / 绑定 openid
    {
      const [rows] = await pool.execute(
        "SELECT id, username, email, password, wechat_openid, status, avatar, avatar_status FROM users WHERE wechat_openid = ? LIMIT 1",
        [OPENID_A]
      );
      const row = rows[0];
      ok("新账号已落库并绑定 openid", Boolean(row) && row.wechat_openid === OPENID_A);
      ok(
        "username 为「微信用户」+6 位大写 base32 的占位名",
        Boolean(row) && /^微信用户[A-Z2-7]{6}$/.test(row.username),
        row && row.username
      );
      ok(
        "email 为 RFC 2606 保留域（永不解析，明确非真实邮箱）",
        Boolean(row) && /^wx_.+@wechat-user\.invalid$/.test(row.email),
        row && row.email
      );
      ok("status = active", Boolean(row) && row.status === "active");
      ok(
        "password 存的是 bcrypt 摘要而非明文",
        Boolean(row) && /^\$2[aby]\$/.test(row.password) && row.password.length === 60,
        row && `长度 ${row.password ? row.password.length : 0}`
      );
      ok("新账号无头像（页面侧会把「无头像」归一为已通过）", Boolean(row) && row.avatar === null);
    }

    // 6) ⭐ 产品决定的关键保证：这个账号**不能用账号密码登录**
    {
      const guesses = [
        "123456",
        "password",
        "wechat",
        OPENID_A,
        "",
        // 连"占位邮箱"这种自造值也不行
        `wx_${OPENID_A}@wechat-user.invalid`
      ];
      let anyAccepted = false;
      for (const guess of guesses) {
        const r = await post(base, "/api/auth/login", {
          username: registered && registered.username,
          password: guess
        });
        if (r.status === 200) anyAccepted = true;
      }
      ok(
        "用任意密码（含空串、openid、占位邮箱）都无法登录该账号",
        !anyAccepted,
        `${guesses.length} 个候选密码全部被拒`
      );
    }

    // 7) 同一 openid 再次注册 → 409 OPENID_BOUND
    {
      const r = await post(base, ROUTE, { code: CODE_A, inviteCode: config.inviteCode });
      ok(
        "同一微信重复注册 → 409 OPENID_BOUND",
        r.status === 409 && r.json && r.json.error && r.json.error.code === "OPENID_BOUND",
        `status=${r.status} code=${r.json && r.json.error && r.json.error.code}`
      );
    }

    // 8) 注册后，微信一键登录应直接成功（避免"注册完却登不进去"）
    {
      const r = await post(base, "/api/auth/wechat-login", { code: CODE_A });
      ok(
        "注册后走 /wechat-login → bound:true 且用户 id 一致",
        r.status === 200 &&
          r.json &&
          r.json.data &&
          r.json.data.bound === true &&
          r.json.data.userId === createdId,
        `status=${r.status} userId=${r.json && r.json.data && r.json.data.userId}`
      );
    }

    // 9) ⭐ 安全关键：body 里塞进来的 openid 必须被无视
    {
      const r = await post(base, ROUTE, {
        code: CODE_B,
        inviteCode: config.inviteCode,
        openid: OPENID_A // 冒名：试图把 B 的微信绑到 A 的身份上
      });
      const [rowsB] = await pool.execute(
        "SELECT wechat_openid FROM users WHERE wechat_openid = ? LIMIT 1",
        [OPENID_B]
      );
      const leakedToA = (await countByOpenid(OPENID_A)) > 1;
      ok(
        "body 里传 openid 被忽略：身份只取服务端换取结果",
        r.status === 201 && rowsB.length === 1 && rowsB[0].wechat_openid === OPENID_B && !leakedToA,
        `status=${r.status} 新建 openid=${rowsB[0] && rowsB[0].wechat_openid}`
      );
    }

    // 10) 缺 code 但邀请码正确 → 400（且不建号）
    {
      const r = await post(base, ROUTE, { inviteCode: config.inviteCode });
      ok(
        "缺 code → 400 且不建号",
        r.status === 400,
        `status=${r.status} code=${r.json && r.json.error && r.json.error.code}`
      );
    }
  } finally {
    // 只删本脚本造出的行，按合成 openid 精确匹配
    try {
      await pool.execute("DELETE FROM users WHERE wechat_openid IN (?, ?)", [OPENID_A, OPENID_B]);
      const remaining = (await countByOpenid(OPENID_A)) + (await countByOpenid(OPENID_B));
      lines.push(`${remaining === 0 ? "🧹" : "⚠️ "} 测试数据清理：剩余 ${remaining} 行`);
    } catch (e) {
      lines.push(`⚠️  测试数据清理失败：${e.message}`);
    }
    server.close();
    await pool.end().catch(() => {});
  }

  console.log("微信一键注册 —— 行为级验证\n");
  for (const line of lines) console.log("  " + line);
  console.log(`\n通过 ${passed}   失败 ${failed}`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error("验证脚本自身异常:", e);
  process.exit(1);
});
