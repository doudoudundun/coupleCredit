const assert = require("assert");
const { test } = require("node:test");

const baseUrl = process.env.AUTH_API_BASE_URL || "http://127.0.0.1:8080";
const inviteCode = process.env.AUTH_API_INVITE_CODE || "COUPLE-PRIVATE-2026";

function uniqueName() {
  // 毫秒时间戳纯数字会命中内容安全手机号正则（CONTACT_NOT_ALLOWED），
  // base36 化并混排字母；v→w 规避导流正则，命中则换时间戳重来
  // （与 test/housework.flows.test.js 同一约定）。
  const { TEXT_PROMO_PATTERNS } = require("../src/services/contentSecurity");
  for (let attempt = 0; attempt < 10; attempt += 1) {
    const stem = `${Date.now().toString(36).replace(/v/g, "w")}${Math.floor(Math.random() * 46656).toString(36).replace(/v/g, "w")}`;
    const candidate = `api_reg_${stem}`;
    if (!TEXT_PROMO_PATTERNS.some((p) => p.re.test(candidate))) return candidate;
  }
  throw new Error("无法生成合法测试用户名");
}

test("register accepts configured invite code", async () => {
  const username = uniqueName();
  const response = await fetch(`${baseUrl}/api/auth/register`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      username,
      email: `${username}@example.com`,
      password: "secret123",
      inviteCode
    })
  });

  const body = await response.json();

  assert.equal(response.status, 201, JSON.stringify(body));
  assert.equal(body.ok, true);
  assert.equal(body.message, "注册成功");
  assert.equal(typeof body.data.userId, "number");
});
