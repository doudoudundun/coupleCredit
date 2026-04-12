const assert = require("assert");
const { test } = require("node:test");

const baseUrl = process.env.AUTH_API_BASE_URL || "http://127.0.0.1:8080";
const inviteCode = process.env.AUTH_API_INVITE_CODE || "COUPLE-PRIVATE-2026";
const uniqueSuffix = Date.now();

test("register accepts configured invite code", async () => {
  const response = await fetch(`${baseUrl}/api/auth/register`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      username: `api_reg_${uniqueSuffix}`,
      email: `api_reg_${uniqueSuffix}@example.com`,
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
