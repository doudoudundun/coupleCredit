/**
 * 活动中心（F4）+ 账单导出 CSV（F3）集成测试。
 *
 * 覆盖：写路径钩子（bill.created/bill.deleted/goal.entry/plan.entry/
 * housework.recorded/recurring.confirmed → 伴侣收件箱有事件、本人没有）、
 * 去重键重放（同资源重触发重置 unread 且不重复）、seen 部分/全部、
 * 游标分页不重不漏、解绑后事件不可见（DB 行 revoked）、
 * CSV 导出口径与列表一致、RFC4180 转义、空月只有表头、未登录 401。
 *
 * 需要服务运行在 AUTH_API_BASE_URL（默认 18099 dev），迁移 038-040 已执行：
 *   AUTH_API_BASE_URL=http://127.0.0.1:8082 node --env-file=.env --test test/activity.test.js
 */
const assert = require("assert");
const { test } = require("node:test");
const mysql = require("mysql2/promise");

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
    const candidate = `act${stem}`;
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

async function openDb() {
  return mysql.createConnection({
    host: process.env.DB_HOST || "127.0.0.1",
    port: Number(process.env.DB_PORT || 3306),
    user: process.env.DB_USER || "couple_app",
    password: process.env.DB_PASSWORD || "ChangeThisPrivateDbPassword_2026!",
    database: process.env.DB_NAME || "couple_credit_private"
  });
}

function todayParts() {
  const now = new Date();
  const pad = (n) => String(n).padStart(2, "0");
  const today = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
  return { today, year: now.getFullYear(), month: now.getMonth() + 1, day: now.getDate() };
}

function billPayload(extra = {}) {
  const { today } = todayParts();
  return {
    billOwner: "共同",
    title: "活动中心测试账单",
    type: "餐饮",
    amount: 10,
    date: today,
    time: "12:00:00",
    incomeType: 0,
    ...extra
  };
}

async function getActivity(user, query = "") {
  const res = await api(user, "GET", `/api/activity${query}`);
  assert.equal(res.status, 200, JSON.stringify(res.body));
  return res.body.data;
}

/** 极简 RFC4180 解析器（测试用）：还原转义后的字段。 */
function parseCsv(text) {
  if (text.charCodeAt(0) === 0xfeff) text = text.slice(1);
  const rows = [];
  let row = [];
  let field = "";
  let inQuotes = false;
  for (let i = 0; i < text.length; i += 1) {
    const ch = text[i];
    if (inQuotes) {
      if (ch === '"') {
        if (text[i + 1] === '"') { field += '"'; i += 1; } else { inQuotes = false; }
      } else {
        field += ch;
      }
    } else if (ch === '"') {
      inQuotes = true;
    } else if (ch === ",") {
      row.push(field);
      field = "";
    } else if (ch === "\r" || ch === "\n") {
      if (ch === "\r" && text[i + 1] === "\n") i += 1;
      row.push(field);
      rows.push(row);
      row = [];
      field = "";
    } else {
      field += ch;
    }
  }
  if (field !== "" || row.length > 0) {
    row.push(field);
    rows.push(row);
  }
  return rows;
}

test("bill 钩子：创建/删除给伴侣发事件、本人没有；seen 支持部分与全部", async () => {
  const userA = await registerUser("a1a");
  const userB = await registerUser("a1b");
  await bindCouple(userA, userB);

  const created = await api(userA, "POST", "/api/bills", billPayload({ title: "共同晚餐", amount: 88.5 }));
  assert.equal(created.status, 201, JSON.stringify(created.body));
  const billId = created.body.data.billId;

  let inboxB = await getActivity(userB);
  assert.equal(inboxB.items.length, 1, "伴侣收件箱有 1 条事件");
  const event = inboxB.items[0];
  assert.equal(event.eventType, "bill.created");
  assert.equal(event.actorId, userA.userId);
  assert.equal(event.resourceId, String(billId));
  assert.equal(event.amountCents, 8850);
  assert.equal(event.titleSnapshot, "共同晚餐");
  assert.equal(event.state, "unread");
  assert.equal(inboxB.unreadCount, 1);
  assert.equal(inboxB.hasMore, false);
  assert.equal(inboxB.nextCursor, "");

  const inboxA = await getActivity(userA);
  assert.equal(inboxA.items.length, 0, "本人的动作不进本人收件箱");

  const deleted = await api(userA, "DELETE", `/api/bills/${billId}`);
  assert.equal(deleted.status, 200, JSON.stringify(deleted.body));
  inboxB = await getActivity(userB);
  assert.equal(inboxB.items.length, 2);
  // created_at 秒级精度：同秒内两条事件按 event_id 决胜，顺序不保证，按类型定位
  const deletedEvent = inboxB.items.find((item) => item.eventType === "bill.deleted");
  assert.ok(deletedEvent, "bill.deleted 已投递");
  assert.equal(deletedEvent.amountCents, 8850);
  assert.equal(inboxB.unreadCount, 2);

  // 部分 seen：只标记 bill.deleted
  const partial = await api(userB, "PATCH", "/api/activity/seen", { eventIds: [deletedEvent.eventId] });
  assert.equal(partial.status, 200, JSON.stringify(partial.body));
  assert.equal(partial.body.data.updated, 1);
  inboxB = await getActivity(userB);
  for (const item of inboxB.items) {
    assert.equal(item.state, item.eventType === "bill.deleted" ? "seen" : "unread");
  }
  assert.equal(inboxB.unreadCount, 1);

  // 全部 seen：不带 eventIds
  const all = await api(userB, "PATCH", "/api/activity/seen", {});
  assert.equal(all.body.data.updated, 1);
  inboxB = await getActivity(userB);
  assert.equal(inboxB.unreadCount, 0);

  // 非法 eventIds 拒绝
  const bad = await api(userB, "PATCH", "/api/activity/seen", { eventIds: ["not-a-uuid"] });
  assert.equal(bad.status, 400);
});

test("plan.entry 去重键重放：同计划再次收支不新增事件，重置为 unread", async () => {
  const userC = await registerUser("a2a");
  const userD = await registerUser("a2b");
  await bindCouple(userC, userD);

  const plan = await api(userC, "POST", "/api/shared-plans", { name: "旅行基金", initialAmount: 100, visibility: "both" });
  assert.equal(plan.status, 201, JSON.stringify(plan.body));
  const planId = plan.body.data.planId;

  const first = await api(userC, "POST", `/api/shared-plans/${planId}/adjust`, { amount: 10, direction: "in" });
  assert.equal(first.status, 200, JSON.stringify(first.body));

  let inboxD = await getActivity(userD);
  assert.equal(inboxD.items.length, 1);
  assert.equal(inboxD.items[0].eventType, "plan.entry");
  assert.equal(inboxD.items[0].amountCents, 1000);
  assert.equal(inboxD.items[0].titleSnapshot, "旅行基金");
  assert.equal(inboxD.items[0].state, "unread");

  await api(userD, "PATCH", "/api/activity/seen", {});
  inboxD = await getActivity(userD);
  assert.equal(inboxD.unreadCount, 0);

  // 再次收支同一计划：UNIQUE(recipient,event_type,resource) 去重，事件重置 unread
  const second = await api(userC, "POST", `/api/shared-plans/${planId}/adjust`, { amount: 5, direction: "out" });
  assert.equal(second.status, 200, JSON.stringify(second.body));
  inboxD = await getActivity(userD);
  assert.equal(inboxD.items.length, 1, "同一计划只保留一条 plan.entry 事件");
  assert.equal(inboxD.items[0].state, "unread", "重放把已 seen 的事件重新置 unread");
});

test("goal.entry / recurring.confirmed / housework.recorded 钩子写入", async () => {
  const userE = await registerUser("a3a");
  const userF = await registerUser("a3b");
  await bindCouple(userE, userF);

  // goal.entry：共同目标记账（bind 时已建共享空间）
  const goal = await api(userE, "POST", "/api/goals", {
    title: "活动中心目标",
    targetAmountFen: 100000,
    idempotencyKey: `goal${Date.now()}act1`
  });
  assert.equal(goal.status, 200, JSON.stringify(goal.body));
  const goalId = goal.body.data.goalId;
  const entry = await api(userE, "POST", `/api/goals/${goalId}/entries`, {
    type: "increase",
    amountFen: 5000,
    idempotencyKey: `entry${Date.now()}act1`
  });
  assert.equal(entry.status, 200, JSON.stringify(entry.body));

  // recurring.confirmed：周期模板确认成账单
  const { day } = todayParts();
  const template = await api(userE, "POST", "/api/recurring-bills", {
    billOwner: "共同", title: "周期宽带费", amount: 99.9,
    frequency: "monthly", dayOfMonth: day
  });
  assert.equal(template.status, 201, JSON.stringify(template.body));
  await api(userE, "POST", "/api/recurring-bills/materialize", {});
  const pending = await api(userE, "GET", "/api/recurring-bills/pending");
  assert.equal(pending.body.data.instances.length, 1);
  const confirmed = await api(userF, "POST", `/api/recurring-bills/instances/${pending.body.data.instances[0].instanceId}/confirm`, {});
  assert.equal(confirmed.status, 200, JSON.stringify(confirmed.body));

  // housework.recorded：共享空间临时记录
  const boot = await api(userE, "POST", "/api/housework/bootstrap", {});
  assert.equal(boot.status, 200, JSON.stringify(boot.body));
  const shared = boot.body.data.spaces.find((s) => s.scope === "couple" && s.status === "active");
  assert.ok(shared, "共享空间存在");
  const config = await api(userE, "GET", `/api/housework/spaces/${shared.spaceId}/config`);
  const fallbackCategory = config.body.data.categories.find((c) => c.isFallback);
  assert.ok(fallbackCategory, "兜底分类存在");
  const record = await api(userE, "POST", `/api/housework/spaces/${shared.spaceId}/records`, {
    clientMutationId: globalThis.crypto.randomUUID(),
    adHoc: { name: "拖地", categoryId: fallbackCategory.categoryId, measureMode: "event" },
    completedDate: boot.body.data.today,
    participants: [{ userId: userE.userId }]
  });
  assert.equal(record.status, 200, JSON.stringify(record.body));

  const inboxF = await getActivity(userF);
  const types = inboxF.items.map((item) => item.eventType);
  assert.ok(types.includes("goal.entry"), "goal.entry 已投递");
  // recurring 由 F 确认（actor=F），事件投递给伴侣 E 的收件箱
  const inboxE = await getActivity(userE);
  assert.ok(inboxE.items.map((item) => item.eventType).includes("recurring.confirmed"), "recurring.confirmed 已投递（确认人的伴侣收件）");
  assert.ok(types.includes("housework.recorded"), "housework.recorded 已投递");

  const goalEvent = inboxF.items.find((item) => item.eventType === "goal.entry");
  assert.equal(goalEvent.amountCents, 5000, "目标流水金额本身就是分");
  assert.equal(goalEvent.titleSnapshot, "活动中心目标");
  const houseworkEvent = inboxF.items.find((item) => item.eventType === "housework.recorded");
  assert.equal(houseworkEvent.amountCents, null);
  assert.equal(houseworkEvent.titleSnapshot, "拖地");
  const recurringEvent = inboxE.items.find((item) => item.eventType === "recurring.confirmed");
  assert.equal(recurringEvent.amountCents, 9990);
});

test("游标分页：25 条事件按 limit=10 翻三页，不重不漏", async () => {
  const userG = await registerUser("a4a");
  const userH = await registerUser("a4b");
  await bindCouple(userG, userH);

  for (let i = 0; i < 25; i += 1) {
    const created = await api(userG, "POST", "/api/bills", billPayload({ title: `翻页账单${i}`, amount: 1 + i }));
    assert.equal(created.status, 201, JSON.stringify(created.body));
  }

  const seen = new Set();
  let cursor = "";
  let pages = 0;
  let firstPage = null;
  for (;;) {
    const query = cursor ? `?limit=10&cursor=${encodeURIComponent(cursor)}` : "?limit=10";
    const data = await getActivity(userH, query);
    if (!firstPage) firstPage = data;
    assert.ok(data.items.length > 0 && data.items.length <= 10);
    for (const item of data.items) {
      assert.equal(seen.has(item.eventId), false, "分页不得重复");
      seen.add(item.eventId);
    }
    pages += 1;
    if (!data.hasMore) {
      assert.equal(data.nextCursor, "");
      break;
    }
    cursor = data.nextCursor;
    assert.ok(pages < 10, "防止死循环");
  }
  assert.equal(seen.size, 25, "三页合计 25 条不重不漏");
  assert.equal(firstPage.unreadCount, 25, "未读角标数不受分页影响");
  assert.ok(pages >= 3, "至少分了三页");

  const badCursor = await api(userH, "GET", "/api/activity?cursor=not-a-cursor");
  assert.equal(badCursor.status, 400);
  const badLimit = await api(userH, "GET", "/api/activity?limit=51");
  assert.equal(badLimit.status, 400);
});

test("解绑后事件不可见：读路径重校验 + 整体 revoke", async () => {
  const userI = await registerUser("a5a");
  const userJ = await registerUser("a5b");
  await bindCouple(userI, userJ);

  const created = await api(userI, "POST", "/api/bills", billPayload({ title: "解绑前的账单" }));
  assert.equal(created.status, 201, JSON.stringify(created.body));
  let inboxJ = await getActivity(userJ);
  assert.equal(inboxJ.items.length, 1);
  const eventId = inboxJ.items[0].eventId;

  await unbind(userI);

  inboxJ = await getActivity(userJ);
  assert.equal(inboxJ.items.length, 0, "解绑后不可见");
  assert.equal(inboxJ.unreadCount, 0);

  const db = await openDb();
  try {
    const [rows] = await db.execute(
      "SELECT state FROM activity_events WHERE event_id = ?",
      [eventId]
    );
    assert.equal(rows.length, 1, "事件行保留");
    assert.equal(rows[0].state, "revoked", "unbind 事务内整体 revoke");
  } finally {
    await db.end();
  }
});

async function fetchExport(user, year, month) {
  const response = await fetch(`${baseUrl}/api/bills/export?year=${year}&month=${month}`, {
    headers: { Authorization: `Bearer ${user.accessToken}` }
  });
  const buffer = await response.arrayBuffer();
  const bytes = new Uint8Array(buffer);
  // fetch 的 text() 会吞掉前导 BOM，这里看原始字节
  const hasBom = bytes.length >= 3 && bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf;
  const text = Buffer.from(bytes).toString("utf8");
  return { response, hasBom, text };
}

test("CSV 导出：口径与列表一致、RFC4180 转义、BOM 与响应头正确", async () => {
  const userK = await registerUser("a6a");
  const userL = await registerUser("a6b");
  await bindCouple(userK, userL);
  const parts = todayParts();
  const monthFile = `${parts.year}-${String(parts.month).padStart(2, "0")}`;

  const collections = await api(userK, "GET", "/api/category-collections?domain=bills");
  const coupleExpense = collections.body.data.collections.find((c) => c.domain === "bills" && c.direction === "expense" && c.scope === "couple");
  const category = await api(userK, "POST", `/api/category-collections/${coupleExpense.collectionId}/categories`, {
    clientMutationId: globalThis.crypto.randomUUID(),
    name: "导出餐饮",
    icon: { type: "emoji", value: "🍜" }
  });
  assert.equal(category.status, 200, JSON.stringify(category.body));
  const categoryId = category.body.data.entity.categoryId;

  // 含逗号/引号/换行的标题 + 三种归属 + 收入 + 分类 + 小钱包备注
  const trickyTitle = '豆浆,油条"加冰"\n第二行';
  const self = await api(userK, "POST", "/api/bills", billPayload({ billOwner: "自己", title: "我的早餐", amount: 6, categoryId }));
  const partner = await api(userK, "POST", "/api/bills", billPayload({ billOwner: "对方", title: "对方午餐", amount: 20 }));
  const common = await api(userK, "POST", "/api/bills", billPayload({ billOwner: "共同", title: trickyTitle, amount: 12.5 }));
  const income = await api(userL, "POST", "/api/bills", billPayload({ billOwner: "自己", title: "兼职", type: "工资", amount: 300, incomeType: 1 }));
  const plan = await api(userK, "POST", "/api/shared-plans", { name: "导出小钱包", initialAmount: 50, visibility: "both" });
  const withPlan = await api(userK, "POST", "/api/bills", billPayload({ billOwner: "共同", title: "计划支出", amount: 15, sharedPlanId: plan.body.data.planId }));
  for (const res of [self, partner, common, income, withPlan]) {
    assert.equal(res.status, 201, JSON.stringify(res.body));
  }

  const listRes = await api(userK, "GET", `/api/bills?year=${parts.year}&month=${parts.month}`);
  assert.equal(listRes.status, 200, JSON.stringify(listRes.body));
  const listCount = listRes.body.data.bills.length;

  const exportResponse = await fetch(`${baseUrl}/api/bills/export?year=${parts.year}&month=${parts.month}`, {
    headers: { Authorization: `Bearer ${userK.accessToken}` }
  });
  assert.equal(exportResponse.status, 200);
  assert.equal(exportResponse.headers.get("Content-Type"), "text/csv; charset=utf-8");
  assert.equal(
    exportResponse.headers.get("Content-Disposition"),
    `attachment; filename="bills-${monthFile}.csv"; filename*=UTF-8''bills-${monthFile}.csv`
  );
  const { hasBom, text: raw } = await fetchExport(userK, parts.year, parts.month);
  assert.equal(hasBom, true, "UTF-8 BOM");

  const rows = parseCsv(raw);
  assert.equal(rows.length, listCount + 1, "数据行数与列表一致（含表头）");
  assert.deepEqual(rows[0], ["日期", "时间", "类型", "标题", "金额(元)", "归属", "分类", "备注"]);

  const byTitle = new Map(rows.slice(1).map((row) => [row[3], row]));
  assert.deepEqual(
    byTitle.get(trickyTitle),
    [parts.today, "12:00:00", "支出", trickyTitle, "12.50", "共同", "餐饮", ""],
    "含逗号/引号/换行的字段按 RFC4180 转义后完整还原"
  );
  assert.ok(raw.includes('"豆浆,油条""加冰""'), "原始文本包含转义后的引号");
  assert.deepEqual(byTitle.get("我的早餐")[5], "自己");
  assert.deepEqual(byTitle.get("对方午餐")[5], "对方");
  assert.deepEqual(byTitle.get("对方午餐")[7], "代记");
  assert.deepEqual(byTitle.get("兼职")[2], "收入");
  assert.deepEqual(byTitle.get("计划支出")[7], "小钱包·导出小钱包");
  assert.deepEqual(byTitle.get("我的早餐")[6], "导出餐饮", "分类列当前名 > 快照 > legacy type");
  for (const row of rows.slice(1)) {
    assert.match(row[4], /^\d+\.\d{2}$/, "金额两位小数字符串");
  }

  // 导出的是 K 视角：L 视角下归属互换（自己/对方）
  const exportL = await fetchExport(userL, parts.year, parts.month);
  const rowsL = parseCsv(exportL.text);
  const byTitleL = new Map(rowsL.slice(1).map((row) => [row[3], row]));
  assert.equal(byTitleL.get("我的早餐")[5], "对方", "L 视角下 K 的账单是对方");
  assert.equal(byTitleL.get("兼职")[5], "自己", "L 自己的账单是自己");
});

test("CSV 导出：空月只有表头；未登录 401", async () => {
  const userM = await registerUser("a7");
  const empty = await fetchExport(userM, 2035, 1);
  assert.equal(empty.response.status, 200);
  assert.equal(empty.hasBom, true, "空月也带 BOM");
  const rows = parseCsv(empty.text);
  assert.equal(rows.length, 1, "空月只有表头");
  assert.equal(rows[0][0], "日期");

  const noAuth = await fetch(`${baseUrl}/api/bills/export?year=2035&month=1`);
  assert.equal(noAuth.status, 401);
  const noAuthActivity = await fetch(`${baseUrl}/api/activity`);
  assert.equal(noAuthActivity.status, 401);
});
