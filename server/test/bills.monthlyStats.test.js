/**
 * 月报聚合接口集成测试（spec: coupleCredit-mp docs/RETENTION_MONTHLY_SPEC_20261003.md §6.2）。
 *
 * 覆盖：未登录 401、返回结构字段齐备且金额为整数分、多月聚合数值正确
 * （含 byCategory 求和 = 总支出、byOwner 登录者视角、sameDayDays、weekendRatio、topBill）、
 * 无关用户之间数据不可见、months 参数钳制、账单写路径令 monthly 缓存失效。
 *
 * 需要服务运行在 AUTH_API_BASE_URL（默认 18099 dev）。
 * 运行：AUTH_API_BASE_URL=http://127.0.0.1:18099 node --env-file=.env --test test/bills.monthlyStats.test.js
 */
const assert = require("assert");
const { test } = require("node:test");

const baseUrl = process.env.AUTH_API_BASE_URL || "http://127.0.0.1:18099";
const inviteCode = process.env.AUTH_API_INVITE_CODE || "COUPLE-PRIVATE-2026";

async function readJsonOrText(response) {
  const text = await response.text();
  try { return JSON.parse(text); } catch (_e) { return text; }
}

async function registerUser(tag) {
  // 与 test/billsCategoryId.test.js 相同的用户名生成约定：v→w，避开 TEXT_PROMO_PATTERNS
  const { TEXT_PROMO_PATTERNS } = require("../src/services/contentSecurity");
  let username = "";
  for (let attempt = 0; attempt < 10 && !username; attempt += 1) {
    const stem = `${Date.now().toString(36).replace(/v/g, "w")}${tag}${Math.floor(Math.random() * 46656).toString(36).replace(/v/g, "w")}`;
    const candidate = `bms${stem}`;
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

async function createBill(user, extra) {
  const res = await api(user, "POST", "/api/bills", {
    billOwner: "自己",
    title: "monthly-stats 测试账单",
    amount: 10,
    date: dateInMonth(0, 2),
    time: "12:00:00",
    incomeType: 0,
    ...extra
  });
  assert.equal(res.status, 201, JSON.stringify(res.body));
  return res.body.data;
}

async function monthlyStats(user, months, endYm) {
  const query = new URLSearchParams();
  if (months !== undefined) query.set("months", months);
  if (endYm !== undefined) query.set("endYm", endYm);
  const res = await api(user, "GET", `/api/bills/monthly-stats${query.size ? `?${query}` : ""}`);
  assert.equal(res.status, 200, JSON.stringify(res.body));
  return res.body.data;
}

// n 个月前（n=0 为当前月）的 ym 字符串与该月某天的日期串
function ymOffset(n) {
  const now = new Date();
  const d = new Date(now.getFullYear(), now.getMonth() - n, 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
}

function dateInMonth(n, day) {
  return `${ymOffset(n)}-${String(day).padStart(2, "0")}`;
}

// 纯日历星期（0=周日 … 6=周六），与 MySQL WEEKDAY >= 5 的周末判定对齐
function isWeekend(dateStr) {
  const [y, m, d] = dateStr.split("-").map(Number);
  const weekday = new Date(Date.UTC(y, m - 1, d)).getUTCDay();
  return weekday === 0 || weekday === 6;
}

function assertEntryShape(entry) {
  assert.equal(typeof entry.ym, "string");
  assert.match(entry.ym, /^\d{4}-\d{2}$/);
  for (const field of ["expense", "income", "count", "recordDays", "sameDayDays"]) {
    assert.ok(Number.isInteger(entry[field]), `${field} 应为整数：${entry[field]}`);
  }
  assert.ok(typeof entry.weekendRatio === "number" && Number.isFinite(entry.weekendRatio));
  assert.ok(entry.weekendRatio >= 0 && entry.weekendRatio <= 1, "weekendRatio 应在 [0,1]");
  for (const [bucket, value] of Object.entries(entry.byOwner)) {
    assert.ok(["self", "partner", "common"].includes(bucket));
    assert.ok(Number.isInteger(value.count), `byOwner.${bucket}.count 应为整数`);
    assert.ok(Number.isInteger(value.amount), `byOwner.${bucket}.amount 应为整数分`);
  }
  for (const amount of Object.values(entry.byCategory)) {
    assert.ok(Number.isInteger(amount), "byCategory 金额应为整数分");
  }
  if (entry.topBill !== null) {
    assert.ok(Number.isInteger(entry.topBill.billId));
    assert.match(entry.topBill.date, /^\d{4}-\d{2}-\d{2}$/);
    assert.ok(Number.isInteger(entry.topBill.amount), "topBill.amount 应为整数分");
    assert.equal(typeof entry.topBill.title, "string");
  }
}

test("未登录访问 monthly-stats 返回 401", async () => {
  const response = await fetch(`${baseUrl}/api/bills/monthly-stats?months=6`);
  const body = await readJsonOrText(response);
  assert.equal(response.status, 401, JSON.stringify(body));
});

test("返回结构字段齐备且金额为整数分", async () => {
  const user = await registerUser("shape");
  const created = await createBill(user, { type: "餐饮", amount: 12.34 });

  const data = await monthlyStats(user, 2);
  assert.ok(Array.isArray(data.months));
  assert.equal(data.months.length, 2);
  const current = data.months[data.months.length - 1];
  assert.equal(current.ym, ymOffset(0));
  assert.equal(current.expense, 1234);
  assert.equal(current.topBill.billId, created.billId);
  assert.equal(current.topBill.amount, 1234);
  for (const entry of data.months) assertEntryShape(entry);
});

test("多月聚合数值正确：总额/笔数/recordDays/byCategory/byOwner/sameDayDays/weekendRatio/topBill", async () => {
  const userA = await registerUser("agg_a");
  const userB = await registerUser("agg_b");
  const invite = await api(userA, "POST", "/api/couple/generate-invite", {});
  assert.equal(invite.status, 200, JSON.stringify(invite.body));
  const bind = await api(userB, "POST", "/api/couple/bind", { inviteCode: invite.body.data.inviteCode });
  assert.equal(bind.status, 200, JSON.stringify(bind.body));

  const day2 = dateInMonth(0, 2);
  const day15 = dateInMonth(0, 15);
  // 当前月：A 两笔支出（12.34 + 100）+ B 一笔支出（56.78，同日不同人）+ 共同 88.80 + A 一笔收入 5000
  await createBill(userA, { type: "餐饮", amount: 12.34, date: day2, time: "12:00:00" });
  const top = await createBill(userA, { type: "购物", amount: 100, date: day2, time: "13:00:00", title: "大件购物" });
  await createBill(userB, { type: "餐饮", amount: 56.78, date: day2, time: "14:00:00" });
  await createBill(userA, { billOwner: "共同", type: "日用", amount: 88.8, date: day15, time: "10:00:00" });
  await createBill(userA, { type: "工资", amount: 5000, date: day15, time: "09:00:00", incomeType: 1 });
  // 上月：A 30.10 + B 20.20，同一天
  const day10 = dateInMonth(1, 10);
  await createBill(userA, { type: "交通", amount: 30.1, date: day10, time: "08:00:00" });
  await createBill(userB, { type: "交通", amount: 20.2, date: day10, time: "18:00:00" });

  const dataA = await monthlyStats(userA, 3);
  assert.deepEqual(dataA.months.map((m) => m.ym), [ymOffset(2), ymOffset(1), ymOffset(0)]);

  // 前前月无账单：零值占位
  const emptyMonth = dataA.months[0];
  assertEntryShape(emptyMonth);
  assert.equal(emptyMonth.expense, 0);
  assert.equal(emptyMonth.income, 0);
  assert.equal(emptyMonth.count, 0);
  assert.equal(emptyMonth.recordDays, 0);
  assert.equal(emptyMonth.sameDayDays, 0);
  assert.equal(emptyMonth.weekendRatio, 0);
  assert.deepEqual(emptyMonth.byCategory, {});
  assert.equal(emptyMonth.topBill, null);
  assert.deepEqual(emptyMonth.byOwner, {
    self: { count: 0, amount: 0 },
    partner: { count: 0, amount: 0 },
    common: { count: 0, amount: 0 }
  });

  const current = dataA.months[2];
  assert.equal(current.expense, 1234 + 10000 + 5678 + 8880);
  assert.equal(current.income, 500000);
  assert.equal(current.count, 5);
  assert.equal(current.recordDays, 2);
  assert.equal(current.sameDayDays, 1); // day2 两人都记账
  assert.deepEqual(current.byCategory, { 餐饮: 6912, 购物: 10000, 日用: 8880 });
  const byCategorySum = Object.values(current.byCategory).reduce((a, b) => a + b, 0);
  assert.equal(byCategorySum, current.expense);
  // A 是邀请方（user_id_1），自己记的账 owner=1 → self
  assert.deepEqual(current.byOwner, {
    self: { count: 3, amount: 1234 + 10000 },
    partner: { count: 1, amount: 5678 },
    common: { count: 1, amount: 8880 }
  });
  assert.deepEqual(current.topBill, {
    billId: top.billId,
    date: day2,
    amount: 10000,
    title: "大件购物"
  });
  const weekendCents = (isWeekend(day2) ? 1234 + 10000 + 5678 : 0) + (isWeekend(day15) ? 8880 : 0);
  const expectedRatio = Math.floor((weekendCents / current.expense) * 10000) / 10000;
  assert.equal(current.weekendRatio, expectedRatio);

  const prev = dataA.months[1];
  assert.equal(prev.expense, 3010 + 2020);
  assert.equal(prev.count, 2);
  assert.equal(prev.recordDays, 1);
  assert.equal(prev.sameDayDays, 1);
  assert.deepEqual(prev.byCategory, { 交通: 5030 });
  assert.equal(prev.topBill.amount, 3010);
  assert.deepEqual(prev.byOwner, {
    self: { count: 1, amount: 3010 },
    partner: { count: 1, amount: 2020 },
    common: { count: 0, amount: 0 }
  });

  // B 是被邀请方（user_id_2），自己记的账 owner=2 → 对 A 是 partner、对 B 是 self（视角换算）
  const dataB = await monthlyStats(userB, 3);
  const currentB = dataB.months[2];
  assert.deepEqual(currentB.byOwner, {
    self: { count: 1, amount: 5678 },
    partner: { count: 3, amount: 1234 + 10000 },
    common: { count: 1, amount: 8880 }
  });
  assert.equal(currentB.expense, current.expense);
});

test("无关用户之间数据不可见（越权拿不到对方数据）", async () => {
  const userE = await registerUser("isolated_e");
  const userF = await registerUser("isolated_f");
  await createBill(userE, { type: "餐饮", amount: 66.6, date: dateInMonth(0, 5) });

  const statsF = await monthlyStats(userF, 1);
  assert.equal(statsF.months.length, 1);
  assert.equal(statsF.months[0].ym, ymOffset(0));
  assert.equal(statsF.months[0].count, 0);
  assert.equal(statsF.months[0].expense, 0);
  assert.equal(statsF.months[0].topBill, null);

  const statsE = await monthlyStats(userE, 1);
  assert.equal(statsE.months[0].count, 1);
  assert.equal(statsE.months[0].expense, 6660);
});

test("months 参数钳制：0→1、超大→24、非法→默认6", async () => {
  const user = await registerUser("clamp");

  const clampedLow = await monthlyStats(user, 0);
  assert.equal(clampedLow.months.length, 1);
  assert.equal(clampedLow.months[0].ym, ymOffset(0));

  const clampedHigh = await monthlyStats(user, 999);
  assert.equal(clampedHigh.months.length, 24);
  assert.equal(clampedHigh.months[23].ym, ymOffset(0));

  const fallback = await monthlyStats(user, "abc");
  assert.equal(fallback.months.length, 6);
  assert.equal(fallback.months[5].ym, ymOffset(0));

  const none = await monthlyStats(user, undefined);
  assert.equal(none.months.length, 6);
});

test("账单写路径令 monthly-stats 缓存失效", async () => {
  const user = await registerUser("cacheinv");
  await createBill(user, { type: "餐饮", amount: 10, date: dateInMonth(0, 3) });

  const before = await monthlyStats(user, 3);
  const currentBefore = before.months[2];
  assert.equal(currentBefore.count, 1);
  assert.equal(currentBefore.expense, 1000);

  // 若写路径未清 `bills:{userId}:` 前缀缓存，第二次查询会命中 TTL 内的旧聚合
  await createBill(user, { type: "餐饮", amount: 20, date: dateInMonth(0, 4) });

  const after = await monthlyStats(user, 3);
  const currentAfter = after.months[2];
  assert.equal(currentAfter.count, 2);
  assert.equal(currentAfter.expense, 3000);
});

test("endYm 锚点支持旧年月/跨年/24月上限，并拒绝非法与未来月份", async () => {
  const user = await registerUser("anchor");
  const old = await monthlyStats(user, 6, "2020-01");
  assert.equal(old.months[0].ym, "2019-08");
  assert.equal(old.months[5].ym, "2020-01");
  assert.equal(old.months.every((m) => m.count === 0), true);
  const longer = await monthlyStats(user, 60, "2020-01");
  assert.equal(longer.months.length, 24);
  assert.equal(longer.months[23].ym, "2020-01");
  for (const endYm of ["2026-13", "2026-9", "bad", ymOffset(-1)]) {
    const res = await api(user, "GET", `/api/bills/monthly-stats?months=6&endYm=${encodeURIComponent(endYm)}`);
    assert.equal(res.status, 400, JSON.stringify(res.body));
  }
});
