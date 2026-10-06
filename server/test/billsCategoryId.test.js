/**
 * 账单 categoryId 集成测试（spec: docs/CATEGORY_PRESETS_SPEC_20261002.md 第 9 节，阶段二 B2）。
 *
 * 覆盖：创建携带 categoryId、非法 categoryId 拒绝（格式/不存在/方向不符/跨范围/已归档）、
 * legacy 字符串兼容、同时提交时 categoryId 优先、读路径快照、归档后历史行仍可读、回填幂等。
 *
 * 需要服务运行在 AUTH_API_BASE_URL（默认 18099 dev）。
 * 运行：node --env-file=.env --test test/billsCategoryId.test.js
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
  // 与 test/housework.ac.test.js 相同的用户名生成约定：v→w，避开 TEXT_PROMO_PATTERNS
  const { TEXT_PROMO_PATTERNS } = require("../src/services/contentSecurity");
  let username = "";
  for (let attempt = 0; attempt < 10 && !username; attempt += 1) {
    const stem = `${Date.now().toString(36).replace(/v/g, "w")}${tag}${Math.floor(Math.random() * 46656).toString(36).replace(/v/g, "w")}`;
    const candidate = `bcc${stem}`;
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

function uuid() { return globalThis.crypto.randomUUID(); }

const BILL_DATE = { date: "2026-10-01", time: "12:00:00", year: 2026, month: 10 };

async function getCollections(user, domain) {
  const res = await api(user, "GET", `/api/category-collections?domain=${domain}`);
  assert.equal(res.status, 200, JSON.stringify(res.body));
  return res.body.data.collections;
}

function pickCollection(collections, { domain, direction, scope }) {
  const found = collections.find((c) => c.domain === domain && c.direction === direction && c.scope === scope);
  assert.ok(found, `collection ${domain}/${direction}/${scope} 存在`);
  return found;
}

async function createCategory(user, collectionId, name, extra = {}) {
  const res = await api(user, "POST", `/api/category-collections/${collectionId}/categories`, {
    clientMutationId: uuid(),
    name,
    ...extra
  });
  assert.equal(res.status, 200, JSON.stringify(res.body));
  return res.body.data.entity;
}

async function listCategories(user, collectionId) {
  const res = await api(user, "GET", `/api/category-collections/${collectionId}/categories?status=all&limit=50`);
  assert.equal(res.status, 200, JSON.stringify(res.body));
  return res.body.data;
}

async function archiveCategory(user, collectionId, categoryId) {
  const listing = await listCategories(user, collectionId);
  const category = listing.items.find((c) => c.categoryId === categoryId);
  assert.ok(category, "待归档分类存在");
  const res = await api(user, "POST", `/api/category-collections/${collectionId}/categories/${categoryId}/archive`, {
    clientMutationId: uuid(),
    expectedVersion: category.version,
    expectedCollectionVersion: listing.collectionVersion
  });
  assert.equal(res.status, 200, JSON.stringify(res.body));
  return res.body.data.entity;
}

async function listBills(user) {
  const res = await api(user, "GET", `/api/bills?year=${BILL_DATE.year}&month=${BILL_DATE.month}`);
  assert.equal(res.status, 200, JSON.stringify(res.body));
  return res.body.data.bills;
}

function billPayload(extra) {
  return {
    billOwner: "自己",
    title: "categoryId 测试账单",
    amount: 12.5,
    date: BILL_DATE.date,
    time: BILL_DATE.time,
    incomeType: 0,
    ...extra
  };
}

test("创建账单携带 categoryId：解析显示名称、写快照、读路径返回分类信息", async () => {
  const user = await registerUser("c1");
  const collections = await getCollections(user, "bills");
  const expense = pickCollection(collections, { domain: "bills", direction: "expense", scope: "personal" });
  const category = await createCategory(user, expense.collectionId, "测试餐饮", {
    icon: { type: "emoji", value: "🍜" },
    color: "#FF6B81"
  });

  const created = await api(user, "POST", "/api/bills", billPayload({ categoryId: category.categoryId }));
  assert.equal(created.status, 201, JSON.stringify(created.body));
  assert.equal(created.body.data.categoryId, category.categoryId);
  assert.equal(created.body.data.type, "测试餐饮");
  assert.equal(created.body.data.categoryNameSnapshot, "测试餐饮");
  assert.equal(created.body.data.categoryIconSnapshot, "🍜");

  const bills = await listBills(user);
  const bill = bills.find((b) => b.billId === created.body.data.billId);
  assert.ok(bill, "列表返回新账单");
  assert.equal(bill.categoryId, category.categoryId);
  assert.equal(bill.categoryNameSnapshot, "测试餐饮");
  assert.equal(bill.categoryIconSnapshot, "🍜");
  assert.deepEqual(bill.category, {
    categoryId: category.categoryId,
    name: "测试餐饮",
    icon: { type: "emoji", value: "🍜" },
    color: "#FF6B81",
    status: "active"
  });
});

test("非法 categoryId：非 UUID 400、未知 UUID 400、方向不符 400", async () => {
  const user = await registerUser("c2");
  const collections = await getCollections(user, "bills");
  const expense = pickCollection(collections, { domain: "bills", direction: "expense", scope: "personal" });
  const income = pickCollection(collections, { domain: "bills", direction: "income", scope: "personal" });
  const incomeCategory = await createCategory(user, income.collectionId, "测试工资");

  const notUuid = await api(user, "POST", "/api/bills", billPayload({ type: "餐饮", categoryId: "not-a-uuid" }));
  assert.equal(notUuid.status, 400, JSON.stringify(notUuid.body));
  assert.equal(notUuid.body.error.code, "INVALID_REQUEST");

  const unknown = await api(user, "POST", "/api/bills", billPayload({ type: "餐饮", categoryId: uuid() }));
  assert.equal(unknown.status, 400, JSON.stringify(unknown.body));
  assert.equal(unknown.body.error.code, "INVALID_CATEGORY");

  // 收入分类用于支出账单（CP 15：跨方向引用被拒绝）
  const wrongDirection = await api(user, "POST", "/api/bills", billPayload({ categoryId: incomeCategory.categoryId }));
  assert.equal(wrongDirection.status, 400, JSON.stringify(wrongDirection.body));
  assert.equal(wrongDirection.body.error.code, "INVALID_CATEGORY");

  // 物资域分类不能用于账单
  const invCollections = await getCollections(user, "inventory");
  const invCollection = pickCollection(invCollections, { domain: "inventory", direction: null, scope: "personal" });
  const invCategory = await createCategory(user, invCollection.collectionId, "测试物资类");
  const wrongDomain = await api(user, "POST", "/api/bills", billPayload({ categoryId: invCategory.categoryId }));
  assert.equal(wrongDomain.status, 400, JSON.stringify(wrongDomain.body));
  assert.equal(wrongDomain.body.error.code, "INVALID_CATEGORY");
});

test("legacy 字符串提交行为不变：不写 categoryId，读路径兼容 NULL 行", async () => {
  const user = await registerUser("c3");
  const created = await api(user, "POST", "/api/bills", billPayload({ type: "餐饮" }));
  assert.equal(created.status, 201, JSON.stringify(created.body));
  assert.equal(created.body.data.type, "餐饮");
  assert.equal(created.body.data.categoryId, null);

  const bills = await listBills(user);
  const bill = bills.find((b) => b.billId === created.body.data.billId);
  assert.equal(bill.type, "餐饮");
  assert.equal(bill.categoryId, null);
  assert.equal(bill.category, null);
  assert.equal(bill.categoryNameSnapshot, null);
});

test("同时提交 categoryId 与 type 时以 categoryId 为准（spec 9 优先级）", async () => {
  const user = await registerUser("c4");
  const collections = await getCollections(user, "bills");
  const expense = pickCollection(collections, { domain: "bills", direction: "expense", scope: "personal" });
  const category = await createCategory(user, expense.collectionId, "权威名称");

  const created = await api(user, "POST", "/api/bills", billPayload({ type: "旧客户端乱写的", categoryId: category.categoryId }));
  assert.equal(created.status, 201, JSON.stringify(created.body));
  assert.equal(created.body.data.type, "权威名称");
  assert.equal(created.body.data.categoryId, category.categoryId);
});

test("更新账单 categoryId：切换、清除、非法值拒绝", async () => {
  const user = await registerUser("c5");
  const collections = await getCollections(user, "bills");
  const expense = pickCollection(collections, { domain: "bills", direction: "expense", scope: "personal" });
  const catA = await createCategory(user, expense.collectionId, "分类甲");
  const catB = await createCategory(user, expense.collectionId, "分类乙");

  const created = await api(user, "POST", "/api/bills", billPayload({ type: "餐饮" }));
  assert.equal(created.status, 201, JSON.stringify(created.body));
  const billId = created.body.data.billId;

  const updated = await api(user, "PUT", `/api/bills/${billId}`, { date: BILL_DATE.date, time: BILL_DATE.time, categoryId: catA.categoryId });
  assert.equal(updated.status, 200, JSON.stringify(updated.body));
  let bills = await listBills(user);
  let bill = bills.find((b) => b.billId === billId);
  assert.equal(bill.categoryId, catA.categoryId);
  assert.equal(bill.type, "分类甲");
  assert.equal(bill.categoryNameSnapshot, "分类甲");

  // 再次切换 + legacy type 同传时 categoryId 优先
  const switched = await api(user, "PUT", `/api/bills/${billId}`, { date: BILL_DATE.date, time: BILL_DATE.time, type: "娱乐", categoryId: catB.categoryId });
  assert.equal(switched.status, 200, JSON.stringify(switched.body));
  bills = await listBills(user);
  bill = bills.find((b) => b.billId === billId);
  assert.equal(bill.categoryId, catB.categoryId);
  assert.equal(bill.type, "分类乙");

  // 显式清除
  const cleared = await api(user, "PUT", `/api/bills/${billId}`, { date: BILL_DATE.date, time: BILL_DATE.time, categoryId: null });
  assert.equal(cleared.status, 200, JSON.stringify(cleared.body));
  bills = await listBills(user);
  bill = bills.find((b) => b.billId === billId);
  assert.equal(bill.categoryId, null);
  assert.equal(bill.category, null);

  // 非法值
  const bad = await api(user, "PUT", `/api/bills/${billId}`, { date: BILL_DATE.date, time: BILL_DATE.time, categoryId: uuid() });
  assert.equal(bad.status, 400, JSON.stringify(bad.body));
  assert.equal(bad.body.error.code, "INVALID_CATEGORY");
});

test("归档分类后历史账单仍可读（category_id 与快照保留），新引用被拒", async () => {
  const user = await registerUser("c6");
  const collections = await getCollections(user, "bills");
  const expense = pickCollection(collections, { domain: "bills", direction: "expense", scope: "personal" });
  const category = await createCategory(user, expense.collectionId, "将归档", { icon: { type: "emoji", value: "📦" } });

  const created = await api(user, "POST", "/api/bills", billPayload({ categoryId: category.categoryId }));
  assert.equal(created.status, 201, JSON.stringify(created.body));

  await archiveCategory(user, expense.collectionId, category.categoryId);

  // 归档不迁移账单历史：category_id 保留、快照保留、category 标记 archived（CP 13）
  const bills = await listBills(user);
  const bill = bills.find((b) => b.billId === created.body.data.billId);
  assert.equal(bill.categoryId, category.categoryId);
  assert.equal(bill.categoryNameSnapshot, "将归档");
  assert.equal(bill.categoryIconSnapshot, "📦");
  assert.equal(bill.category.status, "archived");
  assert.equal(bill.category.name, "将归档");

  // 归档分类不能用于新账单（spec 6.2：切换分类时只能选启用项）
  const reused = await api(user, "POST", "/api/bills", billPayload({ categoryId: category.categoryId, title: "归档后新账单" }));
  assert.equal(reused.status, 409, JSON.stringify(reused.body));
  assert.equal(reused.body.error.code, "CATEGORY_ARCHIVED");
});

test("共享范围：个人分类不能用于关系账单，共享分类双方可用", async () => {
  const userA = await registerUser("c7a");
  const userB = await registerUser("c7b");
  const invite = await api(userA, "POST", "/api/couple/generate-invite", {});
  assert.equal(invite.status, 200, JSON.stringify(invite.body));
  const bind = await api(userB, "POST", "/api/couple/bind", { inviteCode: invite.body.data.inviteCode });
  assert.equal(bind.status, 200, JSON.stringify(bind.body));

  const collections = await getCollections(userA, "bills");
  const personalExpense = pickCollection(collections, { domain: "bills", direction: "expense", scope: "personal" });
  const coupleExpense = pickCollection(collections, { domain: "bills", direction: "expense", scope: "couple" });
  const personalCategory = await createCategory(userA, personalExpense.collectionId, "个人专属");
  // 双方均可维护共享集合（spec 3）
  const coupleCategory = await createCategory(userB, coupleExpense.collectionId, "共享餐饮");

  // 个人分类不能关联到关系账单（spec 9：不能随意关联另一个范围的同名分类）
  const crossScope = await api(userA, "POST", "/api/bills", billPayload({ billOwner: "共同", categoryId: personalCategory.categoryId }));
  assert.equal(crossScope.status, 400, JSON.stringify(crossScope.body));
  assert.equal(crossScope.body.error.code, "INVALID_CATEGORY");

  // 对方创建的共享分类，本人可用于共同账单
  const ok = await api(userA, "POST", "/api/bills", billPayload({ billOwner: "共同", categoryId: coupleCategory.categoryId }));
  assert.equal(ok.status, 201, JSON.stringify(ok.body));
  assert.equal(ok.body.data.categoryId, coupleCategory.categoryId);

  // 双方读路径都能看到分类信息
  const billsA = await listBills(userA);
  const billA = billsA.find((b) => b.billId === ok.body.data.billId);
  assert.equal(billA.category.name, "共享餐饮");
  const billsB = await listBills(userB);
  const billB = billsB.find((b) => b.billId === ok.body.data.billId);
  assert.equal(billB.categoryId, coupleCategory.categoryId);
});

test("历史数据回填：legacy 字符串行映射到同范围分类，重复执行幂等", async () => {
  const { createPool } = require("../src/db");
  const { readConfig } = require("../src/config");
  const { runBackfill } = require("../scripts/dev/backfill-category-ids");
  const pool = createPool(readConfig());
  try {
    const user = await registerUser("c8");
    const legacy = await api(user, "POST", "/api/bills", billPayload({ type: "回填测试类" }));
    assert.equal(legacy.status, 201, JSON.stringify(legacy.body));
    const billId = legacy.body.data.billId;

    // dry-run：只统计不写库
    const dry = await runBackfill({ pool, apply: false, log: () => {} });
    assert.ok(dry.bills.mappedRows >= 1);
    const [beforeRows] = await pool.execute("SELECT category_id FROM bills WHERE bill_id = ?", [billId]);
    assert.equal(beforeRows[0].category_id, null);

    const first = await runBackfill({ pool, apply: true, log: () => {} });
    assert.ok(first.bills.categoriesCreated >= 1);
    const [afterRows] = await pool.execute(
      "SELECT category_id, category_name_snapshot FROM bills WHERE bill_id = ?",
      [billId]
    );
    assert.ok(afterRows[0].category_id, "回填写入 category_id");
    assert.equal(afterRows[0].category_name_snapshot, "回填测试类");

    // 映射到本人个人 expense 集合
    const [catRows] = await pool.execute(
      `SELECT ic.name, cc.scope, cc.direction, cc.owner_user_id
       FROM item_categories ic JOIN category_collections cc ON cc.id = ic.collection_id
       WHERE ic.id = ?`,
      [afterRows[0].category_id]
    );
    assert.equal(catRows[0].name, "回填测试类");
    assert.equal(catRows[0].scope, "personal");
    assert.equal(catRows[0].direction, "expense");
    assert.equal(Number(catRows[0].owner_user_id), Number(user.userId));

    // 幂等：再跑一次不新增分类、不改写已回填行
    const [countRows] = await pool.execute("SELECT COUNT(*) c FROM item_categories");
    const second = await runBackfill({ pool, apply: true, log: () => {} });
    assert.equal(second.bills.categoriesCreated, 0);
    const [countRows2] = await pool.execute("SELECT COUNT(*) c FROM item_categories");
    assert.equal(Number(countRows2[0].c), Number(countRows[0].c));
    const [againRows] = await pool.execute("SELECT category_id FROM bills WHERE bill_id = ?", [billId]);
    assert.equal(againRows[0].category_id, afterRows[0].category_id);
  } finally {
    await pool.end();
  }
});
