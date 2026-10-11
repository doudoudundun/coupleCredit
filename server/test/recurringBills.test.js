/**
 * 周期自动记账（F1）集成测试。
 *
 * 覆盖：建模板（owner 口径/缺省 nextDueOn/分类快照）、物化幂等（连调/并发）、
 * 跨期补齐（GET /api/bills 懒触发）、confirm 生成账单（金额/owner/快照/date=due_on，
 * 列表可查）、重复 confirm 幂等、skip 后不再出现、暂停不物化、私人模板他人不可见、
 * 解绑后关系模板不可见、删除模板不动已生成期次。
 *
 * 需要服务运行在 AUTH_API_BASE_URL（默认 18099 dev），迁移 038 已执行：
 *   AUTH_API_BASE_URL=http://127.0.0.1:8082 node --env-file=.env --test test/recurringBills.test.js
 */
const assert = require("assert");
const { test } = require("node:test");
const mysql = require("mysql2/promise");
const {
  todayDateString,
  firstOccurrenceOnOrAfter
} = require("../src/utils/recurringBills");

const baseUrl = process.env.AUTH_API_BASE_URL || "http://127.0.0.1:18099";
const inviteCode = process.env.AUTH_API_INVITE_CODE || "COUPLE-PRIVATE-2026";

async function readJsonOrText(response) {
  const text = await response.text();
  try { return JSON.parse(text); } catch (_e) { return text; }
}

async function registerUser(tag) {
  // 与 test/housework.ac.test.js 相同的用户名生成约定：v→w，避开 TEXT_PROMO_PATTERNS
  const { TEXT_PROMO_PATTERNS } = require("../src/services/contentSecurity");
  let username = "";
  for (let attempt = 0; attempt < 10 && !username; attempt += 1) {
    const stem = `${Date.now().toString(36).replace(/v/g, "w")}${tag}${Math.floor(Math.random() * 46656).toString(36).replace(/v/g, "w")}`;
    const candidate = `rb${stem}`;
    if (!TEXT_PROMO_PATTERNS.some((p) => p.re.test(candidate))) username = candidate;
  }
  if (!username) throw new Error("无法生成合法测试用户名");
  const password = "secret123";
  const reg = await fetch(`${baseUrl}/api/auth/register`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, email: `${username}@example.com`, password, inviteCode })
  });
  const regBody = await readJsonOrText(reg);
  assert.equal(reg.status, 201, JSON.stringify(regBody));
  const login = await fetch(`${baseUrl}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password })
  });
  const loginBody = await readJsonOrText(login);
  assert.equal(login.status, 200, JSON.stringify(loginBody));
  return loginBody.data;
}

function authHeaders(user) {
  return { "Content-Type": "application/json", Authorization: `Bearer ${user.accessToken}` };
}

async function api(user, method, path, body) {
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: authHeaders(user),
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  return { status: response.status, body: await readJsonOrText(response) };
}

async function openDb() {
  return mysql.createConnection({
    host: process.env.DB_HOST || "127.0.0.1",
    port: Number(process.env.DB_PORT || 3306),
    user: process.env.DB_USER || "couple_app",
    password: process.env.DB_PASSWORD || "ChangeThisPrivateDbPassword_2026!",
    database: process.env.DB_NAME || "couple_credit_private"
  });
}

async function bindCouple(userA, userB) {
  const invite = await api(userA, "POST", "/api/couple/generate-invite", {});
  assert.equal(invite.status, 200, JSON.stringify(invite.body));
  const bind = await api(userB, "POST", "/api/couple/bind", { inviteCode: invite.body.data.inviteCode });
  assert.equal(bind.status, 200, JSON.stringify(bind.body));
  return bind.body.data.relationshipId;
}

async function unbind(user) {
  const res = await api(user, "DELETE", "/api/couple/unbind", {});
  assert.equal(res.status, 200, JSON.stringify(res.body));
}

async function getCollections(user, domain) {
  const res = await api(user, "GET", `/api/category-collections?domain=${domain}`);
  assert.equal(res.status, 200, JSON.stringify(res.body));
  return res.body.data.collections;
}

async function createCategory(user, collectionId, name, extra = {}) {
  const res = await api(user, "POST", `/api/category-collections/${collectionId}/categories`, {
    clientMutationId: globalThis.crypto.randomUUID(),
    name,
    ...extra
  });
  assert.equal(res.status, 200, JSON.stringify(res.body));
  return res.body.data.entity;
}

function todayParts() {
  const today = todayDateString();
  const [year, month, day] = today.split("-").map(Number);
  return { today, year, month, day };
}

/** 当前月份往前推 back 个月的第一天（UTC 纯日历运算）。 */
function firstOfMonthBack(back, { year, month }) {
  const date = new Date(Date.UTC(year, month - 1 - back, 1));
  const pad = (n) => String(n).padStart(2, "0");
  return `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-01`;
}

function monthShift({ year, month }, delta) {
  const date = new Date(Date.UTC(year, month - 1 + delta, 1));
  return { year: date.getUTCFullYear(), month: date.getUTCMonth() + 1 };
}

async function createTemplate(user, extra = {}) {
  const res = await api(user, "POST", "/api/recurring-bills", {
    billOwner: "自己",
    title: "周期测试模板",
    amount: 66.6,
    frequency: "monthly",
    ...extra
  });
  assert.equal(res.status, 201, JSON.stringify(res.body));
  return res.body.data;
}

async function listBills(user, { year, month }) {
  const res = await api(user, "GET", `/api/bills?year=${year}&month=${month}`);
  assert.equal(res.status, 200, JSON.stringify(res.body));
  return res.body.data.bills;
}

test("建模板：无关系时 owner 恒为 1（私人模板），weekly 缺省 nextDueOn 按今天推", async () => {
  const user = await registerUser("w1");
  const parts = todayParts();

  const created = await createTemplate(user, {
    billOwner: "共同",
    frequency: "weekly",
    dayOfWeek: (parts.day + 3) % 7
  });
  assert.equal(created.relationshipId, null);
  assert.equal(created.owner, 1);
  assert.equal(created.incomeType, 0);
  assert.equal(created.status, "active");
  assert.equal(
    created.nextDueOn,
    firstOccurrenceOnOrAfter("weekly", null, (parts.day + 3) % 7, parts.today)
  );

  const listed = await api(user, "GET", "/api/recurring-bills");
  assert.equal(listed.status, 200);
  assert.ok(listed.body.data.templates.some((t) => t.id === created.id));

  // 无关系时 monthly dayOfMonth 必填
  const bad = await api(user, "POST", "/api/recurring-bills", {
    billOwner: "自己", title: "x", amount: 1, frequency: "monthly"
  });
  assert.equal(bad.status, 400, JSON.stringify(bad.body));

  const cleanup = await api(user, "DELETE", `/api/recurring-bills/${created.id}`);
  assert.equal(cleanup.status, 200);
});

test("物化幂等：连调两次不重复，并发不重复；confirm 前一直 pending", async () => {
  const user = await registerUser("w2");
  const { day } = todayParts();
  const template = await createTemplate(user, { dayOfMonth: day });

  const first = await api(user, "POST", "/api/recurring-bills/materialize", {});
  assert.equal(first.status, 200, JSON.stringify(first.body));
  assert.equal(first.body.data.created, 1);

  const again = await api(user, "POST", "/api/recurring-bills/materialize", {});
  assert.equal(again.body.data.created, 0);

  // 并发：回拨 next_due_on 后两路同时物化，(template, today) 只允许一条实例
  const db = await openDb();
  try {
    await db.execute("UPDATE recurring_bill_templates SET next_due_on = ? WHERE id = ?", [todayParts().today, template.id]);
    const [left, right] = await Promise.all([
      api(user, "POST", "/api/recurring-bills/materialize", {}),
      api(user, "POST", "/api/recurring-bills/materialize", {})
    ]);
    assert.equal(left.status, 200, JSON.stringify(left.body));
    assert.equal(right.status, 200, JSON.stringify(right.body));
    assert.equal(left.body.data.created + right.body.data.created, 1);
    const [rows] = await db.execute(
      "SELECT COUNT(*) AS c FROM recurring_bill_instances WHERE template_id = ?",
      [template.id]
    );
    assert.equal(Number(rows[0].c), 1);
  } finally {
    await db.end();
  }

  const pending = await api(user, "GET", "/api/recurring-bills/pending");
  assert.equal(pending.body.data.instances.length, 1);
  assert.equal(pending.body.data.instances[0].dueOn, todayParts().today);
  assert.equal(pending.body.data.instances[0].amount, 66.6);
  assert.equal(pending.body.data.instances[0].owner, 1);
});

test("跨期补齐：next_due_on 回拨 3 个月，GET /api/bills 懒触发一次补齐 4 期", async () => {
  const user = await registerUser("w3");
  const parts = todayParts();
  const template = await createTemplate(user, { dayOfMonth: 1 });

  const db = await openDb();
  try {
    await db.execute(
      "UPDATE recurring_bill_templates SET next_due_on = ? WHERE id = ?",
      [firstOfMonthBack(3, parts), template.id]
    );
  } finally {
    await db.end();
  }

  // 列表读路径触发物化（不调 POST /materialize）
  const bills = await listBills(user, parts);
  assert.equal(Array.isArray(bills), true);

  const pending = await api(user, "GET", "/api/recurring-bills/pending");
  const instances = pending.body.data.instances;
  assert.equal(instances.length, 4);
  const expectedDues = [3, 2, 1, 0].map((back) => firstOfMonthBack(back, parts));
  assert.deepEqual(instances.map((i) => i.dueOn), expectedDues);

  // 模板 next_due_on 推进到下月 1 号
  const listed = await api(user, "GET", "/api/recurring-bills");
  const updated = listed.body.data.templates.find((t) => t.id === template.id);
  const next = monthShift(parts, 1);
  assert.equal(updated.nextDueOn, `${next.year}-${String(next.month).padStart(2, "0")}-01`);
});

test("confirm 生成账单：金额/owner/分类快照/date=due_on 正确，双方列表可查；重复 confirm 幂等", async () => {
  const userA = await registerUser("w4a");
  const userB = await registerUser("w4b");
  await bindCouple(userA, userB);
  const parts = todayParts();

  const collections = await getCollections(userA, "bills");
  const coupleExpense = collections.find((c) => c.domain === "bills" && c.direction === "expense" && c.scope === "couple");
  assert.ok(coupleExpense, "couple expense 集合存在");
  const category = await createCategory(userA, coupleExpense.collectionId, "周期餐饮", {
    icon: { type: "emoji", value: "🍜" }
  });

  // A 建「对方」模板 → owner 解析到 B 的关系位
  const template = await createTemplate(userA, {
    billOwner: "对方",
    title: "伴侣健身月卡",
    amount: 88.8,
    dayOfMonth: parts.day,
    categoryId: category.categoryId
  });
  assert.ok(template.relationshipId, "关系模板带 relationshipId");
  assert.equal(template.owner, 2, "A 是 user_id_1，对方 → owner=2");
  assert.equal(template.categoryId, category.categoryId);
  assert.equal(template.categoryNameSnapshot, "周期餐饮");
  assert.equal(template.categoryIconSnapshot, "🍜");

  const materialized = await api(userB, "POST", "/api/recurring-bills/materialize", {});
  assert.equal(materialized.body.data.created, 1, "伴侣也能物化关系模板");

  const pendingB = await api(userB, "GET", "/api/recurring-bills/pending");
  assert.equal(pendingB.body.data.instances.length, 1);
  const instance = pendingB.body.data.instances[0];

  // B 确认（owner 位是 B 自己 → isHelp=0），生成正式账单
  const confirmed = await api(userB, "POST", `/api/recurring-bills/instances/${instance.instanceId}/confirm`, {});
  assert.equal(confirmed.status, 200, JSON.stringify(confirmed.body));
  assert.equal(confirmed.body.data.status, "confirmed");
  const billId = confirmed.body.data.billId;
  assert.equal(typeof billId, "number");

  for (const viewer of [userA, userB]) {
    const bills = await listBills(viewer, parts);
    const bill = bills.find((b) => b.billId === billId);
    assert.ok(bill, `${viewer === userA ? "A" : "B"} 的列表可见该账单`);
    assert.equal(bill.title, "伴侣健身月卡");
    assert.equal(Number(bill.amount), 88.8);
    assert.equal(bill.owner, 2);
    assert.equal(bill.userId, userB.userId, "确认人记为创建者");
    assert.equal(bill.isHelp, 0, "owner 位是确认人自己，非代记");
    assert.equal(bill.date, instance.dueOn, "date=due_on");
    assert.equal(bill.categoryId, category.categoryId);
    assert.equal(bill.categoryNameSnapshot, "周期餐饮");
    assert.equal(bill.categoryIconSnapshot, "🍜");
  }

  // 重复 confirm 幂等：不新建账单，返回现状
  const reconfirsm = await api(userB, "POST", `/api/recurring-bills/instances/${instance.instanceId}/confirm`, {});
  assert.equal(reconfirsm.status, 200);
  assert.equal(reconfirsm.body.data.created, false);
  assert.equal(reconfirsm.body.data.billId, billId);
  const billsAfter = await listBills(userB, parts);
  assert.equal(billsAfter.filter((b) => b.title === "伴侣健身月卡").length, 1);

  const pendingAfter = await api(userB, "GET", "/api/recurring-bills/pending");
  assert.equal(pendingAfter.body.data.instances.length, 0, "确认后不再出现在待确认列表");
});

test("skip：置 skipped 后不再出现，confirm 仅返回现状不生成账单", async () => {
  const user = await registerUser("w5");
  const { day } = todayParts();
  const template = await createTemplate(user, { dayOfMonth: day, title: "跳过我" });

  await api(user, "POST", "/api/recurring-bills/materialize", {});
  let pending = await api(user, "GET", "/api/recurring-bills/pending");
  assert.equal(pending.body.data.instances.length, 1);
  const instanceId = pending.body.data.instances[0].instanceId;

  const skipped = await api(user, "POST", `/api/recurring-bills/instances/${instanceId}/skip`, {});
  assert.equal(skipped.status, 200, JSON.stringify(skipped.body));
  assert.equal(skipped.body.data.status, "skipped");

  pending = await api(user, "GET", "/api/recurring-bills/pending");
  assert.equal(pending.body.data.instances.length, 0);

  const confirmed = await api(user, "POST", `/api/recurring-bills/instances/${instanceId}/confirm`, {});
  assert.equal(confirmed.status, 200);
  assert.equal(confirmed.body.data.created, false);
  assert.equal(confirmed.body.data.status, "skipped");
  assert.equal(confirmed.body.data.billId, null);
});

test("暂停模板不物化；恢复后按补齐口径继续", async () => {
  const user = await registerUser("w6");
  const parts = todayParts();
  const template = await createTemplate(user, { dayOfMonth: 1, title: "暂停测试" });

  const paused = await api(user, "PATCH", `/api/recurring-bills/${template.id}`, { status: "pause" });
  assert.equal(paused.status, 200, JSON.stringify(paused.body));
  assert.equal(paused.body.data.status, "paused");

  const db = await openDb();
  try {
    await db.execute(
      "UPDATE recurring_bill_templates SET next_due_on = ? WHERE id = ?",
      [firstOfMonthBack(2, parts), template.id]
    );
  } finally {
    await db.end();
  }

  const materialized = await api(user, "POST", "/api/recurring-bills/materialize", {});
  assert.equal(materialized.body.data.created, 0);
  const pendingWhilePaused = await api(user, "GET", "/api/recurring-bills/pending");
  assert.equal(pendingWhilePaused.body.data.instances.length, 0);

  const resumed = await api(user, "PATCH", `/api/recurring-bills/${template.id}`, { status: "resume" });
  assert.equal(resumed.body.data.status, "active");
  const afterResume = await api(user, "POST", "/api/recurring-bills/materialize", {});
  assert.equal(afterResume.body.data.created, 3, "恢复后补齐 2 个月前至今共 3 期");
});

test("私人模板他人不可见且不可改", async () => {
  const userA = await registerUser("w7a");
  const userB = await registerUser("w7b");
  const { day } = todayParts();
  const template = await createTemplate(userA, { dayOfMonth: day, title: "私人的" });

  const listB = await api(userB, "GET", "/api/recurring-bills");
  assert.equal(listB.body.data.templates.filter((t) => t.id === template.id).length, 0);

  await api(userA, "POST", "/api/recurring-bills/materialize", {});
  const pendingB = await api(userB, "GET", "/api/recurring-bills/pending");
  assert.equal(pendingB.body.data.instances.length, 0);

  const patched = await api(userB, "PATCH", `/api/recurring-bills/${template.id}`, { amount: 1 });
  assert.equal(patched.status, 404);
  const deleted = await api(userB, "DELETE", `/api/recurring-bills/${template.id}`);
  assert.equal(deleted.status, 404);
});

test("解绑后关系模板双方均不可见（行保留）；改日期字段重推 nextDueOn", async () => {
  const userA = await registerUser("w8a");
  const userB = await registerUser("w8b");
  await bindCouple(userA, userB);

  const template = await createTemplate(userA, { billOwner: "共同", dayOfMonth: 1, title: "共同的" });
  assert.ok(template.relationshipId);
  assert.equal(template.owner, 3);

  // 伴侣（关系成员）可改
  const patched = await api(userB, "PATCH", `/api/recurring-bills/${template.id}`, {
    amount: 99.9,
    dayOfMonth: 28
  });
  assert.equal(patched.status, 200, JSON.stringify(patched.body));
  assert.equal(patched.body.data.amount, 99.9);
  // 改 day 字段后 nextDueOn 从今天重推
  const parts = todayParts();
  assert.equal(patched.body.data.nextDueOn, firstOccurrenceOnOrAfter("monthly", 28, null, parts.today));

  const listB = await api(userB, "GET", "/api/recurring-bills");
  assert.ok(listB.body.data.templates.some((t) => t.id === template.id), "解绑前伴侣可见");

  await unbind(userA);

  for (const viewer of [userA, userB]) {
    const listed = await api(viewer, "GET", "/api/recurring-bills");
    assert.equal(
      listed.body.data.templates.filter((t) => t.id === template.id).length,
      0,
      "解绑后不可见"
    );
    const pending = await api(viewer, "GET", "/api/recurring-bills/pending");
    assert.equal(pending.body.data.instances.length, 0);
  }

  const db = await openDb();
  try {
    const [rows] = await db.execute(
      "SELECT status FROM recurring_bill_templates WHERE id = ?",
      [template.id]
    );
    assert.equal(rows.length, 1, "解绑只影响可见性，模板行保留");
  } finally {
    await db.end();
  }
});

test("删除模板不动已生成期次（实例行保留，pending 列表自然隐藏）", async () => {
  const user = await registerUser("w9");
  const { day } = todayParts();
  const template = await createTemplate(user, { dayOfMonth: day, title: "将被删除" });

  await api(user, "POST", "/api/recurring-bills/materialize", {});
  const deleted = await api(user, "DELETE", `/api/recurring-bills/${template.id}`);
  assert.equal(deleted.status, 200);

  const pending = await api(user, "GET", "/api/recurring-bills/pending");
  assert.equal(pending.body.data.instances.length, 0);

  const db = await openDb();
  try {
    const [rows] = await db.execute(
      "SELECT COUNT(*) AS c FROM recurring_bill_instances WHERE template_id = ?",
      [template.id]
    );
    assert.ok(Number(rows[0].c) >= 1, "已生成期次不随模板删除");
  } finally {
    await db.end();
  }
});

test("未登录访问周期记账接口返回 401", async () => {
  const noAuth = await fetch(`${baseUrl}/api/recurring-bills`);
  assert.equal(noAuth.status, 401);
  const noAuthMaterialize = await fetch(`${baseUrl}/api/recurring-bills/materialize`, { method: "POST" });
  assert.equal(noAuthMaterialize.status, 401);
});
