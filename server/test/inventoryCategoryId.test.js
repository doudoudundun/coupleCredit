/**
 * 物资 categoryId 集成测试（spec: docs/CATEGORY_PRESETS_SPEC_20261002.md 第 9 节，阶段二 B2）。
 *
 * 覆盖：创建携带 categoryId、非法 categoryId 拒绝（格式/未知/跨域/跨范围）、legacy 字符串兼容、
 * 同时提交时 categoryId 优先、同名合并不改既有分类、读路径分类信息、归档时物资条目随迁目标分类、回填幂等。
 *
 * 需要服务运行在 AUTH_API_BASE_URL（默认 18099 dev）。
 * 运行：node --env-file=.env --test test/inventoryCategoryId.test.js
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
    const candidate = `icc${stem}`;
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

async function archiveCategory(user, collectionId, categoryId, { moveToCategoryId = null } = {}) {
  const listing = await listCategories(user, collectionId);
  const category = listing.items.find((c) => c.categoryId === categoryId);
  assert.ok(category, "待归档分类存在");
  const res = await api(user, "POST", `/api/category-collections/${collectionId}/categories/${categoryId}/archive`, {
    clientMutationId: uuid(),
    expectedVersion: category.version,
    expectedCollectionVersion: listing.collectionVersion,
    moveToCategoryId
  });
  assert.equal(res.status, 200, JSON.stringify(res.body));
  return res.body.data.entity;
}

async function listItems(user) {
  const res = await api(user, "GET", "/api/inventory");
  assert.equal(res.status, 200, JSON.stringify(res.body));
  return res.body.data.items;
}

function itemPayload(extra) {
  return { name: "categoryId 测试物资", category: "杂项", quantity: 2, unit: "个", threshold: 1, ...extra };
}

test("创建物资携带 categoryId：解析显示名称，读路径返回分类信息", async () => {
  const user = await registerUser("i1");
  const collections = await getCollections(user, "inventory");
  const collection = pickCollection(collections, { domain: "inventory", direction: null, scope: "personal" });
  const category = await createCategory(user, collection.collectionId, "测试纸品", {
    icon: { type: "emoji", value: "🧻" },
    color: "#8D6E63"
  });

  const created = await api(user, "POST", "/api/inventory", itemPayload({ categoryId: category.categoryId }));
  assert.equal(created.status, 201, JSON.stringify(created.body));
  assert.equal(created.body.data.categoryId, category.categoryId);
  // 服务端解析显示字符串（spec 9）：legacy category 列写入分类名
  assert.equal(created.body.data.category, "测试纸品");

  const items = await listItems(user);
  const item = items.find((i) => i.inventoryId === created.body.data.inventoryId);
  assert.ok(item, "列表返回新物资");
  assert.equal(item.categoryId, category.categoryId);
  assert.deepEqual(item.categoryInfo, {
    categoryId: category.categoryId,
    name: "测试纸品",
    icon: { type: "emoji", value: "🧻" },
    color: "#8D6E63",
    status: "active"
  });
});

test("非法 categoryId：非 UUID 400、未知 UUID 400、账单域分类 400、归档分类 409", async () => {
  const user = await registerUser("i2");
  const collections = await getCollections(user, "inventory");
  const collection = pickCollection(collections, { domain: "inventory", direction: null, scope: "personal" });

  const notUuid = await api(user, "POST", "/api/inventory", itemPayload({ categoryId: "abc" }));
  assert.equal(notUuid.status, 400, JSON.stringify(notUuid.body));
  assert.equal(notUuid.body.error.code, "INVALID_REQUEST");

  const unknown = await api(user, "POST", "/api/inventory", itemPayload({ categoryId: uuid() }));
  assert.equal(unknown.status, 400, JSON.stringify(unknown.body));
  assert.equal(unknown.body.error.code, "INVALID_CATEGORY");

  // 账单域分类不能用于物资
  const billCollections = await getCollections(user, "bills");
  const billCollection = pickCollection(billCollections, { domain: "bills", direction: "expense", scope: "personal" });
  const billCategory = await createCategory(user, billCollection.collectionId, "账单分类");
  const wrongDomain = await api(user, "POST", "/api/inventory", itemPayload({ categoryId: billCategory.categoryId }));
  assert.equal(wrongDomain.status, 400, JSON.stringify(wrongDomain.body));
  assert.equal(wrongDomain.body.error.code, "INVALID_CATEGORY");

  // 归档分类不能用于新物资
  const archived = await createCategory(user, collection.collectionId, "将归档物资类");
  await archiveCategory(user, collection.collectionId, archived.categoryId);
  const reused = await api(user, "POST", "/api/inventory", itemPayload({ name: "归档后新物资", categoryId: archived.categoryId }));
  assert.equal(reused.status, 409, JSON.stringify(reused.body));
  assert.equal(reused.body.error.code, "CATEGORY_ARCHIVED");
});

test("legacy 字符串提交行为不变：不写 categoryId，读路径兼容 NULL 行", async () => {
  const user = await registerUser("i3");
  const created = await api(user, "POST", "/api/inventory", itemPayload({ category: "日用品" }));
  assert.equal(created.status, 201, JSON.stringify(created.body));
  assert.equal(created.body.data.category, "日用品");
  assert.equal(created.body.data.categoryId, null);

  const items = await listItems(user);
  const item = items.find((i) => i.inventoryId === created.body.data.inventoryId);
  // legacy category 字符串列原样返回；categoryInfo 对 NULL 行为 null
  assert.equal(item.category, "日用品");
  assert.equal(item.categoryId, null);
  assert.equal(item.categoryInfo, null);
});

test("同时提交 categoryId 与 category 字符串时以 categoryId 为准", async () => {
  const user = await registerUser("i4");
  const collections = await getCollections(user, "inventory");
  const collection = pickCollection(collections, { domain: "inventory", direction: null, scope: "personal" });
  const category = await createCategory(user, collection.collectionId, "权威物资类");

  const created = await api(user, "POST", "/api/inventory", itemPayload({ category: "旧客户端乱写的", categoryId: category.categoryId }));
  assert.equal(created.status, 201, JSON.stringify(created.body));
  assert.equal(created.body.data.category, "权威物资类");
  assert.equal(created.body.data.categoryId, category.categoryId);
});

test("同名合并保持既有分类归属（spec 9：合并不改变既有条目分类）", async () => {
  const user = await registerUser("i5");
  const collections = await getCollections(user, "inventory");
  const collection = pickCollection(collections, { domain: "inventory", direction: null, scope: "personal" });
  const catA = await createCategory(user, collection.collectionId, "合并甲");
  const catB = await createCategory(user, collection.collectionId, "合并乙");

  const first = await api(user, "POST", "/api/inventory", itemPayload({ name: "合并目标", categoryId: catA.categoryId }));
  assert.equal(first.status, 201, JSON.stringify(first.body));

  const merged = await api(user, "POST", "/api/inventory", itemPayload({ name: "合并目标", quantity: 3, categoryId: catB.categoryId }));
  assert.equal(merged.status, 200, JSON.stringify(merged.body));
  assert.equal(merged.body.message, "已合并到同名物资");
  assert.equal(merged.body.data.inventoryId, first.body.data.inventoryId);
  assert.equal(Number(merged.body.data.quantity), 5);
  // 分类仍为首次创建的甲
  assert.equal(merged.body.data.categoryId, catA.categoryId);
  assert.equal(merged.body.data.category, "合并甲");
});

test("更新物资 categoryId：切换与显式清除", async () => {
  const user = await registerUser("i6");
  const collections = await getCollections(user, "inventory");
  const collection = pickCollection(collections, { domain: "inventory", direction: null, scope: "personal" });
  const catA = await createCategory(user, collection.collectionId, "更新甲");
  const catB = await createCategory(user, collection.collectionId, "更新乙");

  const created = await api(user, "POST", "/api/inventory", itemPayload({ name: "待更新物资" }));
  assert.equal(created.status, 201, JSON.stringify(created.body));
  const inventoryId = created.body.data.inventoryId;

  const updated = await api(user, "PUT", `/api/inventory/${inventoryId}`, { categoryId: catA.categoryId });
  assert.equal(updated.status, 200, JSON.stringify(updated.body));
  let items = await listItems(user);
  let item = items.find((i) => i.inventoryId === inventoryId);
  assert.equal(item.categoryId, catA.categoryId);
  assert.equal(item.category, "更新甲");

  // categoryId 与 category 同传时以 categoryId 为准
  const switched = await api(user, "PUT", `/api/inventory/${inventoryId}`, { category: "杂项", categoryId: catB.categoryId });
  assert.equal(switched.status, 200, JSON.stringify(switched.body));
  items = await listItems(user);
  item = items.find((i) => i.inventoryId === inventoryId);
  assert.equal(item.categoryId, catB.categoryId);
  assert.equal(item.category, "更新乙");

  const cleared = await api(user, "PUT", `/api/inventory/${inventoryId}`, { categoryId: null });
  assert.equal(cleared.status, 200, JSON.stringify(cleared.body));
  items = await listItems(user);
  item = items.find((i) => i.inventoryId === inventoryId);
  assert.equal(item.categoryId, null);
  assert.equal(item.categoryInfo, null);
  // 清除关联不改写 legacy 字符串列
  assert.equal(item.category, "更新乙");
});

// spec 6.2：物资不是历史快照——归档有物资条目的分类必须指定迁移目标，条目整体随迁
test("归档分类时物资条目迁移到目标分类（spec 6.2 有物资条目）", async () => {
  const user = await registerUser("i7");
  const collections = await getCollections(user, "inventory");
  const collection = pickCollection(collections, { domain: "inventory", direction: null, scope: "personal" });
  const category = await createCategory(user, collection.collectionId, "将归档物资", { icon: { type: "emoji", value: "🗃️" } });
  const target = await createCategory(user, collection.collectionId, "归档迁入处", { icon: { type: "emoji", value: "📦" } });

  const created = await api(user, "POST", "/api/inventory", itemPayload({ name: "归档历史物资", categoryId: category.categoryId }));
  assert.equal(created.status, 201, JSON.stringify(created.body));

  await archiveCategory(user, collection.collectionId, category.categoryId, { moveToCategoryId: target.categoryId });

  const items = await listItems(user);
  const item = items.find((i) => i.inventoryId === created.body.data.inventoryId);
  assert.equal(item.categoryId, target.categoryId, "物资条目随迁到目标分类");
  assert.equal(item.categoryInfo.status, "active");
  assert.equal(item.categoryInfo.name, "归档迁入处");
});

test("共享范围：个人分类不能用于关系物资，共享集合双方可写", async () => {
  const userA = await registerUser("i8a");
  const userB = await registerUser("i8b");
  const invite = await api(userA, "POST", "/api/couple/generate-invite", {});
  assert.equal(invite.status, 200, JSON.stringify(invite.body));
  const bind = await api(userB, "POST", "/api/couple/bind", { inviteCode: invite.body.data.inviteCode });
  assert.equal(bind.status, 200, JSON.stringify(bind.body));

  const collections = await getCollections(userA, "inventory");
  const personal = pickCollection(collections, { domain: "inventory", direction: null, scope: "personal" });
  const couple = pickCollection(collections, { domain: "inventory", direction: null, scope: "couple" });
  const personalCategory = await createCategory(userA, personal.collectionId, "个人物资类");
  const coupleCategory = await createCategory(userB, couple.collectionId, "共享物资类");

  // 绑定关系后新增物资进入共享集合，个人分类引用被拒（spec 3/9）
  const crossScope = await api(userA, "POST", "/api/inventory", itemPayload({ categoryId: personalCategory.categoryId }));
  assert.equal(crossScope.status, 400, JSON.stringify(crossScope.body));
  assert.equal(crossScope.body.error.code, "INVALID_CATEGORY");

  const ok = await api(userA, "POST", "/api/inventory", itemPayload({ categoryId: coupleCategory.categoryId }));
  assert.equal(ok.status, 201, JSON.stringify(ok.body));
  assert.equal(ok.body.data.categoryId, coupleCategory.categoryId);

  const itemsB = await listItems(userB);
  const itemB = itemsB.find((i) => i.inventoryId === ok.body.data.inventoryId);
  assert.equal(itemB.categoryInfo.name, "共享物资类");
});

test("历史数据回填：legacy 物资映射到同范围分类，重复执行幂等", async () => {
  const { createPool } = require("../src/db");
  const { readConfig } = require("../src/config");
  const { runBackfill } = require("../scripts/dev/backfill-category-ids");
  const pool = createPool(readConfig());
  try {
    const user = await registerUser("i9");
    const legacy = await api(user, "POST", "/api/inventory", itemPayload({ name: "回填测试物资", category: "回填物资类" }));
    assert.equal(legacy.status, 201, JSON.stringify(legacy.body));
    const inventoryId = legacy.body.data.inventoryId;

    const first = await runBackfill({ pool, apply: true, log: () => {} });
    assert.ok(first.inventory.categoriesCreated >= 1);
    const [afterRows] = await pool.execute("SELECT category_id FROM inventory WHERE inventory_id = ?", [inventoryId]);
    assert.ok(afterRows[0].category_id, "回填写入 category_id");

    const [catRows] = await pool.execute(
      `SELECT ic.name, cc.scope, cc.direction, cc.owner_user_id
       FROM item_categories ic JOIN category_collections cc ON cc.id = ic.collection_id
       WHERE ic.id = ?`,
      [afterRows[0].category_id]
    );
    assert.equal(catRows[0].name, "回填物资类");
    assert.equal(catRows[0].scope, "personal");
    assert.equal(catRows[0].direction, null);
    assert.equal(Number(catRows[0].owner_user_id), Number(user.userId));

    const [countRows] = await pool.execute("SELECT COUNT(*) c FROM item_categories");
    const second = await runBackfill({ pool, apply: true, log: () => {} });
    assert.equal(second.inventory.categoriesCreated, 0);
    const [countRows2] = await pool.execute("SELECT COUNT(*) c FROM item_categories");
    assert.equal(Number(countRows2[0].c), Number(countRows[0].c));
    const [againRows] = await pool.execute("SELECT category_id FROM inventory WHERE inventory_id = ?", [inventoryId]);
    assert.equal(againRows[0].category_id, afterRows[0].category_id);
  } finally {
    await pool.end();
  }
});
