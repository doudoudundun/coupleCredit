const assert = require("assert");
const { test } = require("node:test");
const mysql = require("mysql2/promise");

const baseUrl = process.env.AUTH_API_BASE_URL || "http://127.0.0.1:8080";
const inviteCode = process.env.AUTH_API_INVITE_CODE || "COUPLE-PRIVATE-2026";
const uniqueSuffix = Date.now();

async function readJsonOrText(response) {
  const text = await response.text();
  try {
    return JSON.parse(text);
  } catch (_error) {
    return text;
  }
}

async function registerUser(suffix) {
  const registerResponse = await fetch(`${baseUrl}/api/auth/register`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      username: `api_bill_${suffix}`,
      email: `api_bill_${suffix}@example.com`,
      password: "secret123",
      inviteCode
    })
  });

  const registerBody = await readJsonOrText(registerResponse);
  assert.equal(registerResponse.status, 201, JSON.stringify(registerBody));
  return registerBody.data;
}

async function createRelationship(userId1, userId2) {
  const connection = await mysql.createConnection({
    host: process.env.DB_HOST || "127.0.0.1",
    port: Number(process.env.DB_PORT || 3306),
    user: process.env.DB_USER || "couple_app",
    password: process.env.DB_PASSWORD || "ChangeThisPrivateDbPassword_2026!",
    database: process.env.DB_NAME || "couple_credit_private"
  });

  try {
    const [result] = await connection.execute(
      "INSERT INTO couple_relationships (user_id_1, user_id_2, status) VALUES (?, ?, 'active')",
      [userId1, userId2]
    );
    return result.insertId;
  } finally {
    await connection.end();
  }
}

test("create bill accepts minimal bill payload", async () => {
  const user = await registerUser(`${uniqueSuffix}_numeric`);

  const createResponse = await fetch(`${baseUrl}/api/bills`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      userId: user.userId,
      relationshipId: null,
      owner: 1,
      title: "午餐",
      type: "餐饮",
      amount: 25.5,
      date: "2026-04-12",
      time: "12:30:00",
      incomeType: 0,
      isHelp: 0
    })
  });

  const createBody = await readJsonOrText(createResponse);

  assert.equal(createResponse.status, 201, JSON.stringify(createBody));
  assert.equal(createBody.ok, true);
  assert.equal(typeof createBody.data.billId, "number");
  assert.equal(createBody.data.userId, user.userId);
  assert.equal(createBody.data.title, "午餐");
});

test("create bill resolves self owner without relationship lookup in client", async () => {
  const user = await registerUser(`${uniqueSuffix}_self`);

  const createResponse = await fetch(`${baseUrl}/api/bills`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      userId: user.userId,
      billOwner: "自己",
      title: "咖啡",
      type: "餐饮",
      amount: 18,
      date: "2026-04-12",
      time: "09:20:00",
      incomeType: 0
    })
  });

  const createBody = await readJsonOrText(createResponse);

  assert.equal(createResponse.status, 201, JSON.stringify(createBody));
  assert.equal(createBody.ok, true);
  assert.equal(createBody.data.relationshipId, null);
  assert.equal(createBody.data.owner, 1);
  assert.equal(createBody.data.isHelp, 0);
  assert.equal(createBody.data.userId, user.userId);
});

test("create bill rejects partner owner when no relationship exists", async () => {
  const user = await registerUser(`${uniqueSuffix}_partner`);

  const createResponse = await fetch(`${baseUrl}/api/bills`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      userId: user.userId,
      billOwner: "对方",
      title: "晚饭",
      type: "餐饮",
      amount: 52,
      date: "2026-04-12",
      time: "19:10:00",
      incomeType: 0
    })
  });

  const createBody = await readJsonOrText(createResponse);

  assert.equal(createResponse.status, 400, JSON.stringify(createBody));
  assert.equal(createBody.ok, false);
  assert.equal(createBody.error.message, "未找到情侣关系，无法为对方记账");
});

test("create bill resolves owner and relationship for coupled users", async () => {
  const inviter = await registerUser(`${uniqueSuffix}_coupled_a`);
  const invitee = await registerUser(`${uniqueSuffix}_coupled_b`);
  const relationshipId = await createRelationship(inviter.userId, invitee.userId);

  const selfResponse = await fetch(`${baseUrl}/api/bills`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      userId: inviter.userId,
      billOwner: "自己",
      title: "早餐",
      type: "餐饮",
      amount: 16,
      date: "2026-04-12",
      time: "08:00:00",
      incomeType: 0
    })
  });
  const selfBody = await readJsonOrText(selfResponse);

  assert.equal(selfResponse.status, 201, JSON.stringify(selfBody));
  assert.equal(selfBody.data.relationshipId, relationshipId);
  assert.equal(selfBody.data.owner, 1);
  assert.equal(selfBody.data.isHelp, 0);

  const partnerResponse = await fetch(`${baseUrl}/api/bills`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      userId: inviter.userId,
      billOwner: "对方",
      title: "午饭",
      type: "餐饮",
      amount: 35,
      date: "2026-04-12",
      time: "12:10:00",
      incomeType: 0
    })
  });
  const partnerBody = await readJsonOrText(partnerResponse);

  assert.equal(partnerResponse.status, 201, JSON.stringify(partnerBody));
  assert.equal(partnerBody.data.relationshipId, relationshipId);
  assert.equal(partnerBody.data.owner, 2);
  assert.equal(partnerBody.data.isHelp, 1);

  const inviteeSelfResponse = await fetch(`${baseUrl}/api/bills`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      userId: invitee.userId,
      billOwner: "自己",
      title: "晚餐",
      type: "餐饮",
      amount: 40,
      date: "2026-04-12",
      time: "19:20:00",
      incomeType: 0
    })
  });
  const inviteeSelfBody = await readJsonOrText(inviteeSelfResponse);

  assert.equal(inviteeSelfResponse.status, 201, JSON.stringify(inviteeSelfBody));
  assert.equal(inviteeSelfBody.data.relationshipId, relationshipId);
  assert.equal(inviteeSelfBody.data.owner, 2);
  assert.equal(inviteeSelfBody.data.isHelp, 0);
});

test("create bill resolves shared owner for coupled users", async () => {
  const inviter = await registerUser(`${uniqueSuffix}_shared_a`);
  const invitee = await registerUser(`${uniqueSuffix}_shared_b`);
  const relationshipId = await createRelationship(inviter.userId, invitee.userId);

  const createResponse = await fetch(`${baseUrl}/api/bills`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      userId: inviter.userId,
      billOwner: "共同",
      title: "超市",
      type: "购物",
      amount: 88,
      date: "2026-04-12",
      time: "20:10:00",
      incomeType: 0
    })
  });

  const createBody = await readJsonOrText(createResponse);

  assert.equal(createResponse.status, 201, JSON.stringify(createBody));
  assert.equal(createBody.data.relationshipId, relationshipId);
  assert.equal(createBody.data.owner, 3);
  assert.equal(createBody.data.isHelp, 0);
});
