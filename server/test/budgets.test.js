/**
 * 预算（F2）集成测试。
 *
 * 覆盖：PUT upsert / null-0 删除、GET 已用口径与 monthlyStats 一致（整数分、仅支出、
 * 共同+私人 scope、分类按 category_id 匹配）、私人/共同隔离（他人不可见、解绑后不可见、
 * 伴侣共享关系预算）、未登录 401、跨范围分类拒绝。
 *
 * 需要服务运行在 AUTH_API_BASE_URL（默认 18099 dev），迁移 039 已执行：
 *   AUTH_API_BASE_URL=http://127.0.0.1:8082 node --env-file=.env --test test/budgets.test.js
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
    const candidate = `bg${stem}`;
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
  const now = new Date();
  const pad = (n) => String(n).padStart(2, "0");
  return {
    today: `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`,
    year: now.getFullYear(),
    month: now.getMonth() + 1
  };
}

async function createBill(user, extra) {
  const parts = todayParts();
  const res = await api(user, "POST", "/api/bills", {
    billOwner: "自己",
    title: extra.title || "预算测试账单",
    type: "餐饮",
    amount: 10,
    date: parts.today,
    time: "12:00:00",
    incomeType: 0,
    ...extra
  });
  assert.equal(res.status, 201, JSON.stringify(res.body));
  return res.body.data;
}

async function getBudgets(user, { year, month }) {
  const res = await api(user, "GET", `/api/budgets?year=${year}&month=${month}`);
  assert.equal(res.status, 200, JSON.stringify(res.body));
  return res.body.data;
}

test("solo 用户：PUT upsert 总预算与分类预算，改金额覆盖，null/0 删除", async () => {
  const user = await registerUser("b1");
  const parts = todayParts();

  const collections = await getCollections(user, "bills");
  const personalExpense = collections.find((c) => c.domain === "bills" && c.direction === "expense" && c.scope === "personal");
  assert.ok(personalExpense, "personal expense 集合存在");
  const catA = await createCategory(user, personalExpense.collectionId, "预算餐饮", { icon: { type: "emoji", value: "🍜" } });
  const catB = await createCategory(user, personalExpense.collectionId, "预算交通");

  const saved = await api(user, "PUT", "/api/budgets", {
    total: 3000,
    categories: [
      { categoryId: catA.categoryId, amount: 800 },
      { categoryId: catB.categoryId, amount: 200 }
    ]
  });
  assert.equal(saved.status, 200, JSON.stringify(saved.body));

  let data = await getBudgets(user, parts);
  assert.equal(data.total.amount, 3000);
  assert.equal(data.total.used, 0, "新用户无账单，已用为 0");
  assert.equal(data.categories.length, 2);
  const aRow = data.categories.find((c) => c.categoryId === catA.categoryId);
  assert.equal(aRow.name, "预算餐饮");
  assert.deepEqual(aRow.icon, { type: "emoji", value: "🍜" });
  assert.equal(aRow.amount, 800);
  assert.equal(aRow.used, 0);

  // upsert：改总额与单分类金额
  await api(user, "PUT", "/api/budgets", {
    total: 4000,
    categories: [{ categoryId: catA.categoryId, amount: 900 }]
  });
  data = await getBudgets(user, parts);
  assert.equal(data.total.amount, 4000);
  const updated = data.categories.find((c) => c.categoryId === catA.categoryId);
  assert.equal(updated.amount, 900);
  assert.equal(data.categories.find((c) => c.categoryId === catB.categoryId).amount, 200, "未提交的分类不受影响");

  // 删除：null 删总额，0 删分类
  await api(user, "PUT", "/api/budgets", {
    total: null,
    categories: [{ categoryId: catB.categoryId, amount: 0 }]
  });
  data = await getBudgets(user, parts);
  assert.equal(data.total, null);
  assert.equal(data.categories.length, 1);
  assert.equal(data.categories[0].categoryId, catA.categoryId);
});

test("已用口径与 monthlyStats 一致：整数分、仅支出、共同+私人 scope、分类按 category_id", async () => {
  const userA = await registerUser("b2a");
  const userB = await registerUser("b2b");
  await bindCouple(userA, userB);
  const parts = todayParts();

  const collections = await getCollections(userA, "bills");
  const coupleExpense = collections.find((c) => c.domain === "bills" && c.direction === "expense" && c.scope === "couple");
  assert.ok(coupleExpense, "couple expense 集合存在");
  const coupleCat = await createCategory(userA, coupleExpense.collectionId, "预算共同餐饮");

  // 共同+私人 scope 下的支出：私人 10 + 共同（带分类）40.5 + 伴侣本人 20 + legacy 无分类 5 = 75.5 元
  await createBill(userA, { title: "私人咖啡", amount: 10 });
  await createBill(userA, { title: "共同晚餐", amount: 40.5, billOwner: "共同", categoryId: coupleCat.categoryId });
  await createBill(userB, { title: "伴侣午饭", amount: 20 });
  await createBill(userA, { title: "街头小吃", amount: 5, type: "小吃" });
  // 收入不计数
  await createBill(userA, { title: "工资", amount: 100, type: "工资", incomeType: 1 });

  await api(userA, "PUT", "/api/budgets", {
    total: 3000,
    categories: [{ categoryId: coupleCat.categoryId, amount: 1000 }]
  });

  const data = await getBudgets(userA, parts);
  assert.equal(data.total.amount, 3000);
  assert.equal(data.total.used, 7550, "总预算已用 = 当月全部支出（整数分）");
  const catRow = data.categories.find((c) => c.categoryId === coupleCat.categoryId);
  assert.equal(catRow.used, 4050, "分类预算已用仅按 category_id 匹配的支出");

  const stats = await api(userA, "GET", `/api/bills/monthly-stats?months=1`);
  assert.equal(stats.status, 200, JSON.stringify(stats.body));
  const monthStats = stats.body.data.months[stats.body.data.months.length - 1];
  assert.equal(monthStats.expense, 7550);
  assert.equal(data.total.used, monthStats.expense, "总预算已用与 monthlyStats expense 完全一致");
  assert.equal(monthStats.byCategory["预算共同餐饮"], 4050);
  assert.equal(catRow.used, monthStats.byCategory["预算共同餐饮"], "分类已用与 monthlyStats byCategory 一致");
});

test("共同预算双方可见（伴侣行合并）；个人分类不能用于关系范围", async () => {
  const userA = await registerUser("b3a");
  const userB = await registerUser("b3b");
  await bindCouple(userA, userB);
  const parts = todayParts();

  const collectionsA = await getCollections(userA, "bills");
  const coupleExpense = collectionsA.find((c) => c.domain === "bills" && c.direction === "expense" && c.scope === "couple");
  const personalExpense = collectionsA.find((c) => c.domain === "bills" && c.direction === "expense" && c.scope === "personal");
  const coupleCat = await createCategory(userA, coupleExpense.collectionId, "共同预算分类");
  const personalCat = await createCategory(userA, personalExpense.collectionId, "个人预算分类");

  // 个人分类挂在关系范围 → 拒绝（与账单写入同一校验）
  const crossScope = await api(userA, "PUT", "/api/budgets", {
    categories: [{ categoryId: personalCat.categoryId, amount: 100 }]
  });
  assert.equal(crossScope.status, 400, JSON.stringify(crossScope.body));
  assert.equal(crossScope.body.error.code, "INVALID_CATEGORY");

  // A 设总额，B 设分类 → 双方 GET 都能看到合并后的关系预算
  await api(userA, "PUT", "/api/budgets", { total: 5000 });
  await api(userB, "PUT", "/api/budgets", { categories: [{ categoryId: coupleCat.categoryId, amount: 1200 }] });

  for (const viewer of [userA, userB]) {
    const data = await getBudgets(viewer, parts);
    assert.equal(data.total.amount, 5000, "关系预算总额双方可见");
    const catRow = data.categories.find((c) => c.categoryId === coupleCat.categoryId);
    assert.ok(catRow, "伴侣设置的关系分类预算可见");
    assert.equal(catRow.amount, 1200);
  }
});

test("私人/共同隔离：他人看不到；解绑后关系预算不可见、私人预算保留", async () => {
  const userA = await registerUser("b4a");
  const userB = await registerUser("b4b");
  const userC = await registerUser("b4c");
  const parts = todayParts();

  // 独立第三人只看得到自己的预算
  const collectionsC = await getCollections(userC, "bills");
  const personalC = collectionsC.find((c) => c.domain === "bills" && c.direction === "expense" && c.scope === "personal");
  const catC = await createCategory(userC, personalC.collectionId, "第三人的分类");
  await api(userC, "PUT", "/api/budgets", { total: 111, categories: [{ categoryId: catC.categoryId, amount: 11 }] });

  await bindCouple(userA, userB);
  const collectionsA = await getCollections(userA, "bills");
  const coupleExpense = collectionsA.find((c) => c.domain === "bills" && c.direction === "expense" && c.scope === "couple");
  const coupleCat = await createCategory(userA, coupleExpense.collectionId, "隔离共同分类");
  await api(userA, "PUT", "/api/budgets", { total: 5000, categories: [{ categoryId: coupleCat.categoryId, amount: 500 }] });

  const dataA = await getBudgets(userA, parts);
  assert.equal(dataA.total.amount, 5000);
  assert.equal(dataA.categories.length, 1);

  const dataC = await getBudgets(userC, parts);
  assert.equal(dataC.total.amount, 111, "C 只见自己的私人预算");
  assert.equal(dataC.categories.length, 1);
  assert.equal(dataC.categories[0].categoryId, catC.categoryId);

  await unbind(userA);

  const afterA = await getBudgets(userA, parts);
  assert.equal(afterA.total, null, "解绑后关系预算不可见");
  assert.equal(afterA.categories.length, 0);
  const dataC2 = await getBudgets(userC, parts);
  assert.equal(dataC2.total.amount, 111, "解绑不影响无关用户");
});

test("未登录访问预算接口返回 401", async () => {
  const parts = todayParts();
  const noAuthGet = await fetch(`${baseUrl}/api/budgets?year=${parts.year}&month=${parts.month}`);
  assert.equal(noAuthGet.status, 401);
  const noAuthPut = await fetch(`${baseUrl}/api/budgets`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ total: 100 })
  });
  assert.equal(noAuthPut.status, 401);
});
