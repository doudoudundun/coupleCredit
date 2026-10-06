/**
 * 分类集合 + 预设导入（物资/账单）集成测试 —— spec: docs/CATEGORY_PRESETS_SPEC_20261002.md 第 11 节。
 * 覆盖服务端可验用例：
 *   CP-02 只导入分类不产生业务数据；CP-03 重复导入/同 mutation 重试幂等、改名后再导入不重复；
 *   CP-05 预设改名后再导入按来源识别；CP-06 删除后不复活、显式恢复原 ID；
 *   CP-10 并发排序/新增版本冲突；CP-15 收支独立集合、跨方向引用拒绝；
 *   CP-16 个人/共享同名不串、失权集合 404；CP-17 非法排序数组整体回滚；
 *   CP-18 超时核对端点。另含归档迁移、偏好、模板白名单校验。
 *
 * 需要服务运行在 AUTH_API_BASE_URL（默认 8080）：
 *   AUTH_API_BASE_URL=http://127.0.0.1:18099 node --env-file=.env --test test/categoryCollections.flows.test.js
 * 测试账号全部新建（hwcp 前缀），不碰既有用户数据。
 */
const assert = require("assert");
const { test } = require("node:test");

const baseUrl = process.env.AUTH_API_BASE_URL || "http://127.0.0.1:8080";
const inviteCode = process.env.AUTH_API_INVITE_CODE || "COUPLE-PRIVATE-2026";

async function readJsonOrText(response) {
  const text = await response.text();
  try { return JSON.parse(text); } catch (_e) { return text; }
}

async function registerUser(tag) {
  // base36 时间戳字母串可能拼出 "vx"+5 位字母数字，命中本地导流正则导致注册被拒；
  // 把 v 换成 w 生成，并用服务端同一组正则复核（同 test/housework.ac.test.js）。
  const { TEXT_PROMO_PATTERNS } = require("../src/services/contentSecurity");
  let username = "";
  for (let attempt = 0; attempt < 10 && !username; attempt += 1) {
    const stem = `${Date.now().toString(36).replace(/v/g, "w")}${tag}${Math.floor(Math.random() * 46656).toString(36).replace(/v/g, "w")}`;
    const candidate = `hwcp${stem}`;
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

async function setupCouple() {
  const userA = await registerUser("a");
  const userB = await registerUser("b");
  const invite = await api(userA, "POST", "/api/couple/generate-invite", {});
  assert.equal(invite.status, 200, JSON.stringify(invite.body));
  const bind = await api(userB, "POST", "/api/couple/bind", { inviteCode: invite.body.data.inviteCode });
  assert.equal(bind.status, 200, JSON.stringify(bind.body));
  return { userA, userB };
}

/** 取当前用户的账单支出集合（个人 + 共享）。 */
async function getCollections(user, domain) {
  const res = await api(user, "GET", `/api/category-collections?domain=${domain}`);
  assert.equal(res.status, 200, JSON.stringify(res.body));
  return res.body.data.collections;
}

async function listCategories(user, collectionId, status = "active") {
  // 基础包播撒后启用分类可达 20+，显式拉满单页上限避免分页截断
  const res = await api(user, "GET", `/api/category-collections/${collectionId}/categories?status=${status}&limit=50`);
  assert.equal(res.status, 200, JSON.stringify(res.body));
  return res.body.data;
}

/* ============ CP-02：只导入分类，不产生业务数据 ============ */
test("CP-02 只导入分类：分类出现，模板/账单/库存均不新增", async () => {
  const userA = await registerUser("a");
  const collections = await getCollections(userA, "inventory");
  const personal = collections.find((c) => c.scope === "personal");
  assert.ok(personal, "个人物资集合惰性创建");
  assert.equal(personal.direction, null);
  assert.equal(personal.canWrite, true);

  // 扩展包（基础居家包已在集合创建时自动播撒，spec 5.0；这里用宠物包验证显式导入）
  const categoryKeys = ["inventory.pets.food", "inventory.pets.supplies"];
  const preview = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/preset-preview`, {
    selectedPresetKeys: categoryKeys
  });
  assert.equal(preview.status, 200, JSON.stringify(preview.body));
  assert.ok(preview.body.data.items.every((item) => item.status === "will_create"));

  const apply = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/presets/apply`, {
    clientMutationId: uuid(),
    expectedCollectionVersion: personal.version,
    selectedPresetKeys: categoryKeys
  });
  assert.equal(apply.status, 200, JSON.stringify(apply.body));
  assert.equal(apply.body.data.imported.length, 2);
  assert.ok(apply.body.data.imported.every((item) => item.entityType === "category"));
  assert.equal(apply.body.data.collectionVersion, personal.version + 1);

  const templates = await api(userA, "GET", `/api/category-collections/${personal.collectionId}/templates`);
  assert.equal(templates.status, 200);
  assert.equal(templates.body.data.items.length, 0, "只导入分类不产生模板");

  // 业务表无新增：该用户的 inventory 列表仍为空
  const inventory = await api(userA, "GET", "/api/inventory");
  assert.equal(inventory.status, 200, JSON.stringify(inventory.body));
  const items = inventory.body.data && (inventory.body.data.items || inventory.body.data);
  assert.ok(Array.isArray(items) ? items.length === 0 : true, "导入分类不新增库存");
});

/* ============ CP-03/CP-05：重复导入、同 mutation 重试、改名后再导入 ============ */
test("CP-03/CP-05 重复导入跳过、同 mutation 重试幂等、改名后按来源识别不重复", async () => {
  const userA = await registerUser("a");
  const personal = (await getCollections(userA, "bills")).find((c) => c.scope === "personal" && c.direction === "expense");
  // 简洁包中与已播撒完整包不同名的两项（完整包已播撒 购物/医疗，故选 餐饮/健康）
  const keys = ["bills.expense.basic.dining", "bills.expense.basic.health"];

  const mutationId = uuid();
  const payload = {
    clientMutationId: mutationId,
    expectedCollectionVersion: personal.version,
    selectedPresetKeys: keys
  };
  const first = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/presets/apply`, payload);
  assert.equal(first.status, 200, JSON.stringify(first.body));
  assert.equal(first.body.data.imported.length, 2);
  assert.equal(first.body.data.replayed, false);

  // 同 mutation 重试：返回原结果，版本不再前进
  const retry = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/presets/apply`, payload);
  assert.equal(retry.status, 200, JSON.stringify(retry.body));
  assert.equal(retry.body.replayed ?? retry.body.data.replayed, true);
  assert.deepEqual(retry.body.data.imported, first.body.data.imported);

  // 同 mutationId 不同内容 → 409 IDEMPOTENCY_CONFLICT
  const conflict = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/presets/apply`, {
    ...payload,
    selectedPresetKeys: ["bills.expense.basic.dining"]
  });
  assert.equal(conflict.status, 409);
  assert.equal(conflict.body.error.code, "IDEMPOTENCY_CONFLICT");

  // 用户改名「餐饮」→「干饭」
  const categories = await listCategories(userA, personal.collectionId);
  const dining = categories.items.find((c) => c.name === "餐饮");
  assert.ok(dining, "餐饮已导入");
  const renamed = await api(userA, "PATCH", `/api/category-collections/${personal.collectionId}/categories/${dining.categoryId}`, {
    clientMutationId: uuid(),
    expectedVersion: dining.version,
    name: "干饭"
  });
  assert.equal(renamed.status, 200, JSON.stringify(renamed.body));

  // 改名后再次导入同包：按 preset_bindings 识别，跳过，不重复创建
  const currentVersion = (await listCategories(userA, personal.collectionId)).collectionVersion;
  const second = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/presets/apply`, {
    clientMutationId: uuid(),
    expectedCollectionVersion: currentVersion,
    selectedPresetKeys: keys
  });
  assert.equal(second.status, 200, JSON.stringify(second.body));
  assert.equal(second.body.data.imported.length, 0);
  assert.equal(second.body.data.skipped.length, 2);
  assert.ok(second.body.data.skipped.every((item) => item.reason === "already_imported"));

  const after = await listCategories(userA, personal.collectionId);
  const basicImported = after.items.filter((c) => c.source && c.source.packKey === "bills.expense.basic");
  assert.equal(basicImported.length, 2, "改名后再导入不产生第二个分类");
  assert.equal(basicImported.find((c) => c.categoryId === dining.categoryId).name, "干饭", "用户编辑不被覆盖");
});

/* ============ CP-06：删除后不复活，显式恢复原 ID ============ */
test("CP-06 归档预设分类记墓碑不复活；显式恢复原 entity_id；名称被占 422 可改名恢复", async () => {
  const userA = await registerUser("a");
  const personal = (await getCollections(userA, "bills")).find((c) => c.scope === "personal" && c.direction === "income");
  const keys = ["bills.income.basic.salary", "bills.income.basic.parttime"];

  // 收入基础包已随集合创建播撒（spec 5.0）：直接取播撒产物，与手动导入同构
  const seeded = await listCategories(userA, personal.collectionId);
  const salaryRow = seeded.items.find((c) => c.source && c.source.presetKey === "bills.income.basic.salary");
  assert.ok(salaryRow, "工资已随集合创建播撒");
  const salary = { entityId: salaryRow.categoryId };

  // 归档工资分类
  let list = await listCategories(userA, personal.collectionId);
  const archive = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/categories/${salary.entityId}/archive`, {
    clientMutationId: uuid(),
    expectedVersion: salaryRow.version,
    expectedCollectionVersion: list.collectionVersion
  });
  assert.equal(archive.status, 200, JSON.stringify(archive.body));

  // 预览显示 deleted_tombstone，默认不选
  const preview = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/preset-preview`, {
    selectedPresetKeys: keys
  });
  assert.equal(preview.status, 200);
  const salaryPreview = preview.body.data.items.find((item) => item.presetKey === "bills.income.basic.salary");
  assert.equal(salaryPreview.status, "deleted_tombstone");
  assert.equal(salaryPreview.entityId, salary.entityId);

  // 不带 restoreKeys 导入：墓碑项跳过，不复活
  list = await listCategories(userA, personal.collectionId);
  const noRestore = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/presets/apply`, {
    clientMutationId: uuid(),
    expectedCollectionVersion: list.collectionVersion,
    selectedPresetKeys: keys
  });
  assert.equal(noRestore.status, 200, JSON.stringify(noRestore.body));
  assert.equal(noRestore.body.data.imported.length, 0);
  const tombstoneSkipped = noRestore.body.data.skipped.find((item) => item.presetKey === "bills.income.basic.salary");
  assert.equal(tombstoneSkipped.reason, "deleted_tombstone");
  let archivedList = await listCategories(userA, personal.collectionId, "archived");
  assert.equal(archivedList.items.length, 1, "仍归档，不复活");

  // 占用「工资」名称后显式恢复：不带改名 → 422
  const createDupe = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/categories`, {
    clientMutationId: uuid(),
    name: "工资"
  });
  assert.equal(createDupe.status, 200, JSON.stringify(createDupe.body));
  list = await listCategories(userA, personal.collectionId);
  const conflictRestore = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/presets/apply`, {
    clientMutationId: uuid(),
    expectedCollectionVersion: list.collectionVersion,
    selectedPresetKeys: ["bills.income.basic.salary"],
    restoreKeys: ["bills.income.basic.salary"]
  });
  assert.equal(conflictRestore.status, 422, JSON.stringify(conflictRestore.body));
  assert.equal(conflictRestore.body.error.code, "VALIDATION_ERROR");
  assert.ok(conflictRestore.body.error.fieldErrors.some((f) => f.code === "NAME_CONFLICT"));

  // 带改名恢复：原 entity_id 复活
  const restored = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/presets/apply`, {
    clientMutationId: uuid(),
    expectedCollectionVersion: list.collectionVersion,
    selectedPresetKeys: ["bills.income.basic.salary"],
    restoreKeys: ["bills.income.basic.salary"],
    restoreRenames: { "bills.income.basic.salary": "工资（恢复）" }
  });
  assert.equal(restored.status, 200, JSON.stringify(restored.body));
  assert.equal(restored.body.data.restored.length, 1);
  assert.equal(restored.body.data.restored[0].entityId, salary.entityId, "显式恢复保留原 ID");
  list = await listCategories(userA, personal.collectionId);
  const restoredRow = list.items.find((c) => c.categoryId === salary.entityId);
  assert.equal(restoredRow.name, "工资（恢复）");
});

/* ============ CP-15：收支独立集合、跨方向引用拒绝 ============ */
test("CP-15 收支独立集合：同名「其他」ID 不同；跨方向 presetKey 拒绝", async () => {
  const userA = await registerUser("a");
  const collections = await getCollections(userA, "bills");
  const expense = collections.find((c) => c.scope === "personal" && c.direction === "expense");
  const income = collections.find((c) => c.scope === "personal" && c.direction === "income");
  assert.ok(expense && income, "收支两个集合独立");
  assert.notEqual(expense.collectionId, income.collectionId);

  for (const collection of [expense, income]) {
    const create = await api(userA, "POST", `/api/category-collections/${collection.collectionId}/categories`, {
      clientMutationId: uuid(),
      name: "重名测试"
    });
    assert.equal(create.status, 200, JSON.stringify(create.body));
  }
  const expenseCategories = await listCategories(userA, expense.collectionId);
  const incomeCategories = await listCategories(userA, income.collectionId);
  const expenseDupe = expenseCategories.items.find((c) => c.name === "重名测试");
  const incomeDupe = incomeCategories.items.find((c) => c.name === "重名测试");
  assert.ok(expenseDupe && incomeDupe, "收支各自创建同名分类");
  assert.notEqual(expenseDupe.categoryId, incomeDupe.categoryId, "同名分类 ID 独立");

  // 跨方向引用拒绝：收入 presetKey 提交到支出集合（422 参数校验优先于版本冲突）
  const crossApply = await api(userA, "POST", `/api/category-collections/${expense.collectionId}/presets/apply`, {
    clientMutationId: uuid(),
    expectedCollectionVersion: expense.version,
    selectedPresetKeys: ["bills.income.basic.salary"]
  });
  assert.equal(crossApply.status, 422, JSON.stringify(crossApply.body));
  assert.ok(crossApply.body.error.fieldErrors.some((f) => f.code === "WRONG_COLLECTION"));

  // 跨方向预览同样拒绝
  const crossPreview = await api(userA, "POST", `/api/category-collections/${expense.collectionId}/preset-preview`, {
    selectedPresetKeys: ["bills.income.basic.salary"]
  });
  assert.equal(crossPreview.status, 422);

  // 收支分别排序互不影响（排序数组须含同集合全部启用分类，含已播撒的基础包）
  const order = await api(userA, "PUT", `/api/category-collections/${expense.collectionId}/order`, {
    clientMutationId: uuid(),
    expectedCollectionVersion: (await listCategories(userA, expense.collectionId)).collectionVersion,
    categoryIds: expenseCategories.items.map((c) => c.categoryId).reverse()
  });
  assert.equal(order.status, 200, JSON.stringify(order.body));
});

/* ============ CP-16：个人/共享同名不串、失权集合不可见 ============ */
test("CP-16 个人与共享同名分类不串范围；解绑后共享集合只读，第三人不可见", async () => {
  const { userA, userB } = await setupCouple();
  const userC = await registerUser("c");

  const collectionsA = await getCollections(userA, "inventory");
  const personalA = collectionsA.find((c) => c.scope === "personal");
  const shared = collectionsA.find((c) => c.scope === "couple");
  assert.ok(shared, "绑定后出现共享集合");
  assert.equal(shared.canWrite, true);

  const createdIds = [];
  for (const collection of [personalA, shared]) {
    const create = await api(userA, "POST", `/api/category-collections/${collection.collectionId}/categories`, {
      clientMutationId: uuid(),
      name: "零食"
    });
    assert.equal(create.status, 200, JSON.stringify(create.body));
    createdIds.push(create.body.data.entity.categoryId);
  }
  const personalCats = await listCategories(userA, personalA.collectionId);
  const sharedCats = await listCategories(userB, shared.collectionId);
  assert.notEqual(createdIds[0], createdIds[1], "个人/共享同名分类 ID 独立");
  assert.ok(personalCats.items.some((c) => c.categoryId === createdIds[0]));
  assert.ok(sharedCats.items.some((c) => c.categoryId === createdIds[1]));

  // B 可写共享集合，只读不到 A 的个人集合（404 不泄露存在性）
  const bSeesPersonal = await api(userB, "GET", `/api/category-collections/${personalA.collectionId}/categories`);
  assert.equal(bSeesPersonal.status, 404);

  // 第三人 C 对共享集合 404
  const cSeesShared = await api(userC, "GET", `/api/category-collections/${shared.collectionId}/categories`);
  assert.equal(cSeesShared.status, 404);
  const cWritesShared = await api(userC, "POST", `/api/category-collections/${shared.collectionId}/categories`, {
    clientMutationId: uuid(),
    name: "入侵"
  });
  assert.equal(cWritesShared.status, 404);

  // 解绑后共享集合禁止编辑（409 RELATIONSHIP_CHANGED），但仍可读历史配置
  const unbind = await api(userA, "DELETE", "/api/couple/unbind");
  assert.equal(unbind.status, 200, JSON.stringify(unbind.body));
  const writeAfter = await api(userA, "POST", `/api/category-collections/${shared.collectionId}/categories`, {
    clientMutationId: uuid(),
    name: "失权写入"
  });
  assert.equal(writeAfter.status, 409, JSON.stringify(writeAfter.body));
  assert.equal(writeAfter.body.error.code, "RELATIONSHIP_CHANGED");
  const readAfter = await api(userB, "GET", `/api/category-collections/${shared.collectionId}/categories`);
  assert.equal(readAfter.status, 200);
  assert.equal(readAfter.body.data.canWrite, false);
});

/* ============ CP-17：非法排序数组整体回滚 ============ */
test("CP-17 外集合 ID / 重复 / 缺项排序数组拒绝且不改变集合", async () => {
  const { userA } = await setupCouple();
  const collections = await getCollections(userA, "bills");
  const expense = collections.find((c) => c.scope === "personal" && c.direction === "expense");
  const income = collections.find((c) => c.scope === "personal" && c.direction === "income");

  const created = [];
  for (const name of ["甲", "乙", "丙"]) {
    const res = await api(userA, "POST", `/api/category-collections/${expense.collectionId}/categories`, {
      clientMutationId: uuid(), name
    });
    assert.equal(res.status, 200, JSON.stringify(res.body));
    created.push(res.body.data.entity.categoryId);
  }
  const incomeCreate = await api(userA, "POST", `/api/category-collections/${income.collectionId}/categories`, {
    clientMutationId: uuid(), name: "外集合"
  });
  assert.equal(incomeCreate.status, 200);
  const incomeCategoryId = incomeCreate.body.data.entity.categoryId;

  const before = await listCategories(userA, expense.collectionId);
  const versionBefore = before.collectionVersion;
  // 排序数组必须包含同集合全部启用分类（含已播撒的 20 类基础包）
  const fullOrder = before.items.map((c) => c.categoryId);

  const invalidPayloads = [
    { label: "夹带外集合 ID", categoryIds: [...fullOrder.slice(0, -1), incomeCategoryId] },
    { label: "重复 ID", categoryIds: [created[0], created[0], created[2]] },
    { label: "缺项", categoryIds: [created[0], created[1]] }
  ];
  for (const { label, categoryIds } of invalidPayloads) {
    const res = await api(userA, "PUT", `/api/category-collections/${expense.collectionId}/order`, {
      clientMutationId: uuid(),
      expectedCollectionVersion: versionBefore,
      categoryIds
    });
    assert.equal(res.status, 422, `${label}: ${JSON.stringify(res.body)}`);
    assert.equal(res.body.error.code, "VALIDATION_ERROR");
  }
  const after = await listCategories(userA, expense.collectionId);
  assert.equal(after.collectionVersion, versionBefore, "非法排序不改变集合版本");
  assert.deepEqual(after.items.map((c) => c.categoryId), fullOrder, "顺序未变");

  // 相同顺序重复提交：版本不前进（unchanged）
  const same = await api(userA, "PUT", `/api/category-collections/${expense.collectionId}/order`, {
    clientMutationId: uuid(),
    expectedCollectionVersion: versionBefore,
    categoryIds: fullOrder
  });
  assert.equal(same.status, 200, JSON.stringify(same.body));
  assert.equal(same.body.data.unchanged, true);
  assert.equal(same.body.data.collectionVersion, versionBefore);
});

/* ============ CP-10：排序并发冲突 / 排序后新增使旧版本失效 ============ */
test("CP-10 双方同时排序旧版本冲突；一方排序后另一方新增，旧排序版本失效", async () => {
  const { userA, userB } = await setupCouple();
  const shared = (await getCollections(userA, "bills")).find((c) => c.scope === "couple" && c.direction === "expense");

  for (const name of ["一", "二", "三"]) {
    const res = await api(userA, "POST", `/api/category-collections/${shared.collectionId}/categories`, {
      clientMutationId: uuid(), name
    });
    assert.equal(res.status, 200, JSON.stringify(res.body));
  }
  const listA = await listCategories(userA, shared.collectionId);
  const listB = await listCategories(userB, shared.collectionId);
  assert.equal(listA.collectionVersion, listB.collectionVersion);
  const ids = listA.items.map((c) => c.categoryId);

  // A 先排序成功
  const orderA = await api(userA, "PUT", `/api/category-collections/${shared.collectionId}/order`, {
    clientMutationId: uuid(),
    expectedCollectionVersion: listA.collectionVersion,
    categoryIds: [...ids].reverse()
  });
  assert.equal(orderA.status, 200, JSON.stringify(orderA.body));
  assert.equal(orderA.body.data.collectionVersion, listA.collectionVersion + 1);

  // B 用旧版本排序 → 409 VERSION_CONFLICT
  const orderB = await api(userB, "PUT", `/api/category-collections/${shared.collectionId}/order`, {
    clientMutationId: uuid(),
    expectedCollectionVersion: listB.collectionVersion,
    categoryIds: ids
  });
  assert.equal(orderB.status, 409, JSON.stringify(orderB.body));
  assert.equal(orderB.body.error.code, "VERSION_CONFLICT");
  assert.equal(orderB.body.error.currentVersion, listA.collectionVersion + 1);

  // B 新增分类使版本再次前进；A 用排序后的版本归档 → 冲突
  const addB = await api(userB, "POST", `/api/category-collections/${shared.collectionId}/categories`, {
    clientMutationId: uuid(), name: "四"
  });
  assert.equal(addB.status, 200);
  const archiveStale = await api(userA, "POST", `/api/category-collections/${shared.collectionId}/categories/${ids[0]}/archive`, {
    clientMutationId: uuid(),
    expectedVersion: 1,
    expectedCollectionVersion: orderA.body.data.collectionVersion
  });
  assert.equal(archiveStale.status, 409, JSON.stringify(archiveStale.body));
  assert.equal(archiveStale.body.error.code, "VERSION_CONFLICT");
});

/* ============ CP-18：超时核对端点 ============ */
test("CP-18 核对端点：本人可查 applied 结果与 not_found；对方不可见本人 receipt", async () => {
  const { userA, userB } = await setupCouple();
  const shared = (await getCollections(userA, "inventory")).find((c) => c.scope === "couple");

  const missing = await api(userA, "GET", `/api/category-collections/${shared.collectionId}/mutations/${uuid()}`);
  assert.equal(missing.status, 200);
  assert.equal(missing.body.data.status, "not_found");

  const mutationId = uuid();
  const create = await api(userA, "POST", `/api/category-collections/${shared.collectionId}/categories`, {
    clientMutationId: mutationId,
    name: "核对分类"
  });
  assert.equal(create.status, 200, JSON.stringify(create.body));

  const check = await api(userA, "GET", `/api/category-collections/${shared.collectionId}/mutations/${mutationId}`);
  assert.equal(check.status, 200, JSON.stringify(check.body));
  assert.equal(check.body.data.status, "applied");
  assert.equal(check.body.data.result.entity.categoryId, create.body.data.entity.categoryId, "复用原 ID");

  // 同一集合的对方查不到 A 的 receipt（仅本人可读，不暴露未确认草稿）
  const checkByB = await api(userB, "GET", `/api/category-collections/${shared.collectionId}/mutations/${mutationId}`);
  assert.equal(checkByB.status, 200);
  assert.equal(checkByB.body.data.status, "not_found");
});

/* ============ 归档迁移：有模板必须迁移，历史数据不动 ============ */
test("归档：有启用模板必须给迁移目标；模板迁移后原分类归档；无引用可直接归档", async () => {
  const userA = await registerUser("a");
  const personal = (await getCollections(userA, "inventory")).find((c) => c.scope === "personal");

  const apply = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/presets/apply`, {
    clientMutationId: uuid(),
    expectedCollectionVersion: personal.version,
    selectedPresetKeys: ["inventory.home.cleaning", "inventory.home.paper",
      "inventory.home.laundry_detergent", "inventory.home.garbage_bags", "inventory.home.tissues"]
  });
  assert.equal(apply.status, 200, JSON.stringify(apply.body));
  // 基础居家包分类已随集合创建播撒（spec 5.0）→ 分类 skipped，模板正常导入
  const cleaning = apply.body.data.skipped.find((item) => item.presetKey === "inventory.home.cleaning");
  const paper = apply.body.data.skipped.find((item) => item.presetKey === "inventory.home.paper");
  assert.ok(cleaning && paper, "播撒分类按 already_imported 跳过");
  assert.equal(apply.body.data.imported.filter((item) => item.entityType === "template").length, 3);

  // delete-preview：清洁用品下有 2 个启用模板（洗衣液、垃圾袋），0 条业务引用
  const preview = await api(userA, "GET", `/api/category-collections/${personal.collectionId}/categories/${cleaning.entityId}/delete-preview`);
  assert.equal(preview.status, 200, JSON.stringify(preview.body));
  assert.equal(preview.body.data.references.templates, 2);
  assert.equal(preview.body.data.references.records, 0);
  assert.ok(preview.body.data.moveTargets.some((c) => c.categoryId === paper.entityId));
  assert.ok(!preview.body.data.moveTargets.some((c) => c.categoryId === cleaning.entityId));

  // 不给 moveToCategoryId → 422
  let list = await listCategories(userA, personal.collectionId);
  const cleaningRow = list.items.find((c) => c.categoryId === cleaning.entityId);
  const noTarget = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/categories/${cleaning.entityId}/archive`, {
    clientMutationId: uuid(),
    expectedVersion: cleaningRow.version,
    expectedCollectionVersion: list.collectionVersion
  });
  assert.equal(noTarget.status, 422, JSON.stringify(noTarget.body));

  // 迁移到纸品并归档
  const archived = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/categories/${cleaning.entityId}/archive`, {
    clientMutationId: uuid(),
    expectedVersion: cleaningRow.version,
    expectedCollectionVersion: list.collectionVersion,
    moveToCategoryId: paper.entityId
  });
  assert.equal(archived.status, 200, JSON.stringify(archived.body));
  assert.equal(archived.body.data.entity.status, "archived");
  assert.equal(archived.body.data.movedTemplateIds.length, 2);
  assert.equal(archived.body.data.references.templates, 2);

  const templates = await api(userA, "GET", `/api/category-collections/${personal.collectionId}/templates`);
  const moved = templates.body.data.items.filter((t) => archived.body.data.movedTemplateIds.includes(t.templateId));
  assert.ok(moved.every((t) => t.categoryId === paper.entityId), "模板已迁移到目标分类");

  // 归档后该分类不再可选（启用列表无），归档列表可见
  list = await listCategories(userA, personal.collectionId);
  assert.ok(!list.items.some((c) => c.categoryId === cleaning.entityId));
  const archivedList = await listCategories(userA, personal.collectionId, "archived");
  assert.ok(archivedList.items.some((c) => c.categoryId === cleaning.entityId));
});

/* ============ 模板白名单与 suggested_* null 语义 ============ */
test("模板：字段白名单校验；suggested_* 为 null 不补 0；归档需 expectedVersion", async () => {
  const userA = await registerUser("a");
  const personal = (await getCollections(userA, "inventory")).find((c) => c.scope === "personal");
  const category = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/categories`, {
    clientMutationId: uuid(), name: "耗材"
  });
  assert.equal(category.status, 200);
  const categoryId = category.body.data.entity.categoryId;

  // 白名单：账单字段提交到物资模板 → 422
  const wrongField = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/templates`, {
    clientMutationId: uuid(), name: "洗衣液", categoryId, suggestedAmount: "9.90"
  });
  assert.equal(wrongField.status, 422, JSON.stringify(wrongField.body));

  // null 建议值：显式 null 与不传都保持 null，不补 0
  const created = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/templates`, {
    clientMutationId: uuid(), name: "洗衣液", categoryId,
    defaultUnit: "瓶", suggestedAlertLine: null, remark: null
  });
  assert.equal(created.status, 200, JSON.stringify(created.body));
  assert.equal(created.body.data.entity.suggestedAlertLine, null);
  assert.equal(created.body.data.entity.defaultUnit, "瓶");

  // 数值约束：非法格式 422
  const badAmount = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/templates`, {
    clientMutationId: uuid(), name: "垃圾袋", categoryId, suggestedAlertLine: "abc"
  });
  assert.equal(badAmount.status, 422);

  // 编辑 + 乐观锁
  const templateId = created.body.data.entity.templateId;
  const patched = await api(userA, "PATCH", `/api/category-collections/${personal.collectionId}/templates/${templateId}`, {
    clientMutationId: uuid(), expectedVersion: 1, suggestedAlertLine: "2.00"
  });
  assert.equal(patched.status, 200, JSON.stringify(patched.body));
  assert.equal(patched.body.data.entity.suggestedAlertLine, "2.00");
  const stale = await api(userA, "PATCH", `/api/category-collections/${personal.collectionId}/templates/${templateId}`, {
    clientMutationId: uuid(), expectedVersion: 1, remark: "过期编辑"
  });
  assert.equal(stale.status, 409);
  assert.equal(stale.body.error.code, "VERSION_CONFLICT");

  // 归档
  const archive = await api(userA, "PATCH", `/api/category-collections/${personal.collectionId}/templates/${templateId}`, {
    clientMutationId: uuid(), expectedVersion: 2, status: "archived"
  });
  assert.equal(archive.status, 200, JSON.stringify(archive.body));
  assert.equal(archive.body.data.entity.status, "archived");
});

/* ============ 个人偏好：校验本集合模板、乐观锁、不改共享顺序 ============ */
test("偏好：本人 ordered/pinned/hidden 持久保存，外集合模板 ID 拒绝", async () => {
  const { userA, userB } = await setupCouple();
  const shared = (await getCollections(userA, "inventory")).find((c) => c.scope === "couple");

  const apply = await api(userA, "POST", `/api/category-collections/${shared.collectionId}/presets/apply`, {
    clientMutationId: uuid(),
    expectedCollectionVersion: shared.version,
    selectedPresetKeys: ["inventory.home.cleaning", "inventory.home.laundry_detergent", "inventory.home.garbage_bags"]
  });
  assert.equal(apply.status, 200, JSON.stringify(apply.body));
  const templateIds = apply.body.data.imported.filter((item) => item.entityType === "template").map((item) => item.entityId);
  assert.equal(templateIds.length, 2);

  // 默认偏好
  const defaults = await api(userA, "GET", `/api/category-collections/${shared.collectionId}/preferences`);
  assert.equal(defaults.status, 200);
  assert.deepEqual(defaults.body.data.preferences.orderedTemplateIds, []);
  assert.equal(defaults.body.data.preferences.version, 1);

  // 外集合模板 ID 拒绝
  const foreignCollection = (await getCollections(userA, "inventory")).find((c) => c.scope === "personal");
  const foreignCategory = await api(userA, "POST", `/api/category-collections/${foreignCollection.collectionId}/categories`, {
    clientMutationId: uuid(), name: "外用分类"
  });
  const foreignTemplate = await api(userA, "POST", `/api/category-collections/${foreignCollection.collectionId}/templates`, {
    clientMutationId: uuid(), name: "外用模板", categoryId: foreignCategory.body.data.entity.categoryId
  });
  const badPref = await api(userA, "PATCH", `/api/category-collections/${shared.collectionId}/preferences`, {
    clientMutationId: uuid(), expectedVersion: 1,
    orderedTemplateIds: [foreignTemplate.body.data.entity.templateId]
  });
  assert.equal(badPref.status, 422, JSON.stringify(badPref.body));

  // A 设置偏好；B 的偏好不受影响
  const setA = await api(userA, "PATCH", `/api/category-collections/${shared.collectionId}/preferences`, {
    clientMutationId: uuid(), expectedVersion: 1,
    orderedTemplateIds: [...templateIds].reverse(), pinnedTemplateIds: [templateIds[0]]
  });
  assert.equal(setA.status, 200, JSON.stringify(setA.body));
  assert.equal(setA.body.data.entity.version, 2);
  const prefB = await api(userB, "GET", `/api/category-collections/${shared.collectionId}/preferences`);
  assert.deepEqual(prefB.body.data.preferences.orderedTemplateIds, [], "对方偏好独立");

  // 旧版本提交冲突
  const stale = await api(userA, "PATCH", `/api/category-collections/${shared.collectionId}/preferences`, {
    clientMutationId: uuid(), expectedVersion: 1, hiddenTemplateIds: [templateIds[1]]
  });
  assert.equal(stale.status, 409);
  assert.equal(stale.body.error.code, "VERSION_CONFLICT");

  // 共享分类顺序不受偏好影响
  const cats = await listCategories(userB, shared.collectionId);
  assert.equal(cats.collectionVersion, apply.body.data.collectionVersion);
});

/* ============ 归档迁移物资条目（spec 6.2「有物资条目」） ============ */
test("归档：物资条目必须迁移到启用分类；账单历史不迁移", async () => {
  const userA = await registerUser("a");
  const personal = (await getCollections(userA, "inventory")).find((c) => c.scope === "personal");

  const catA = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/categories`, {
    clientMutationId: uuid(), name: "迁移源分类"
  });
  assert.equal(catA.status, 200, JSON.stringify(catA.body));
  const catB = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/categories`, {
    clientMutationId: uuid(), name: "迁移目标分类"
  });
  assert.equal(catB.status, 200, JSON.stringify(catB.body));
  const sourceId = catA.body.data.entity.categoryId;
  const targetId = catB.body.data.entity.categoryId;

  for (const name of ["迁移物资甲", "迁移物资乙"]) {
    const created = await api(userA, "POST", "/api/inventory", {
      name, category: "杂项", quantity: 2, unit: "个", threshold: 1, categoryId: sourceId
    });
    assert.equal(created.status, 201, JSON.stringify(created.body));
  }

  // delete-preview：records=2 且物资域 recordsMoveRequired=true
  const preview = await api(userA, "GET", `/api/category-collections/${personal.collectionId}/categories/${sourceId}/delete-preview`);
  assert.equal(preview.status, 200, JSON.stringify(preview.body));
  assert.equal(preview.body.data.references.records, 2);
  assert.equal(preview.body.data.recordsMoveRequired, true);

  let list = await listCategories(userA, personal.collectionId);
  const sourceRow = list.items.find((c) => c.categoryId === sourceId);

  // 不给 moveToCategoryId → 422 REQUIRED
  const noTarget = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/categories/${sourceId}/archive`, {
    clientMutationId: uuid(),
    expectedVersion: sourceRow.version,
    expectedCollectionVersion: list.collectionVersion
  });
  assert.equal(noTarget.status, 422, JSON.stringify(noTarget.body));

  // 迁移并归档：物资条目整体搬到目标分类，其余字段不动
  const archived = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/categories/${sourceId}/archive`, {
    clientMutationId: uuid(),
    expectedVersion: sourceRow.version,
    expectedCollectionVersion: list.collectionVersion,
    moveToCategoryId: targetId
  });
  assert.equal(archived.status, 200, JSON.stringify(archived.body));
  assert.equal(archived.body.data.movedRecordCount, 2);
  const items = await api(userA, "GET", "/api/inventory");
  assert.equal(items.status, 200, JSON.stringify(items.body));
  const movedItems = (items.body.data.items || items.body.data).filter((it) => ["迁移物资甲", "迁移物资乙"].includes(it.name));
  assert.equal(movedItems.length, 2);
  assert.ok(movedItems.every((it) => it.categoryId === targetId), "物资条目已迁移到目标分类");
  assert.ok(movedItems.every((it) => Number(it.quantity) === 2 && it.unit === "个"), "数量/单位保留");

  // 账单域：有账单历史不给目标也可归档，历史 categoryId 不动
  const expense = (await getCollections(userA, "bills")).find((c) => c.scope === "personal" && c.direction === "expense");
  const billCat = await api(userA, "POST", `/api/category-collections/${expense.collectionId}/categories`, {
    clientMutationId: uuid(), name: "归档账单分类"
  });
  assert.equal(billCat.status, 200, JSON.stringify(billCat.body));
  const billCategoryId = billCat.body.data.entity.categoryId;
  const billCreated = await api(userA, "POST", "/api/bills", {
    billOwner: "自己", title: "归档迁移测试账单", amount: 12.5,
    date: "2026-10-01", time: "12:00", incomeType: 0, categoryId: billCategoryId
  });
  assert.equal(billCreated.status, 201, JSON.stringify(billCreated.body));

  const billPreview = await api(userA, "GET", `/api/category-collections/${expense.collectionId}/categories/${billCategoryId}/delete-preview`);
  assert.equal(billPreview.body.data.references.records, 1);
  assert.equal(billPreview.body.data.recordsMoveRequired, false, "账单历史不强制迁移");

  const billList = await listCategories(userA, expense.collectionId);
  const billRow = billList.items.find((c) => c.categoryId === billCategoryId);
  const billArchived = await api(userA, "POST", `/api/category-collections/${expense.collectionId}/categories/${billCategoryId}/archive`, {
    clientMutationId: uuid(),
    expectedVersion: billRow.version,
    expectedCollectionVersion: billList.collectionVersion
  });
  assert.equal(billArchived.status, 200, JSON.stringify(billArchived.body));
  assert.equal(billArchived.body.data.movedRecordCount, 0, "账单历史不迁移");

  const bills = await api(userA, "GET", "/api/bills?year=2026&month=10");
  assert.equal(bills.status, 200, JSON.stringify(bills.body));
  const bill = bills.body.data.bills.find((b) => b.billId === billCreated.body.data.billId);
  assert.equal(bill.categoryId, billCategoryId, "历史账单仍引用原分类（已删除分类标记兜底）");
});

/* ============ 恢复归档分类（spec 6.2：归档保留 ID，可显式恢复） ============ */
test("恢复归档：自建分类可恢复、改名解决冲突、预设绑定复活、幂等回放", async () => {
  const userA = await registerUser("a");
  const personal = (await getCollections(userA, "inventory")).find((c) => c.scope === "personal");

  async function createAndArchive(name) {
    const created = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/categories`, {
      clientMutationId: uuid(), name
    });
    assert.equal(created.status, 200, JSON.stringify(created.body));
    const categoryId = created.body.data.entity.categoryId;
    let list = await listCategories(userA, personal.collectionId);
    const row = list.items.find((c) => c.categoryId === categoryId);
    const archived = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/categories/${categoryId}/archive`, {
      clientMutationId: uuid(),
      expectedVersion: row.version,
      expectedCollectionVersion: list.collectionVersion
    });
    assert.equal(archived.status, 200, JSON.stringify(archived.body));
    return { categoryId, version: archived.body.data.entity.version, collectionVersion: archived.body.data.collectionVersion };
  }

  // 1) 自建分类恢复：ID 不变、回到启用列表末尾
  const self = await createAndArchive("自建恢复分类");
  const mutationId = uuid();
  const restored = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/categories/${self.categoryId}/unarchive`, {
    clientMutationId: mutationId,
    expectedVersion: self.version,
    expectedCollectionVersion: self.collectionVersion
  });
  assert.equal(restored.status, 200, JSON.stringify(restored.body));
  assert.equal(restored.body.data.entity.categoryId, self.categoryId, "恢复原 ID");
  assert.equal(restored.body.data.entity.status, "active");
  assert.equal(restored.body.data.entity.name, "自建恢复分类");
  let list = await listCategories(userA, personal.collectionId);
  assert.ok(list.items.some((c) => c.categoryId === self.categoryId), "恢复后回到启用列表");

  // 同 mutationId 重试 → 幂等回放，不产生第二份结果
  const replay = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/categories/${self.categoryId}/unarchive`, {
    clientMutationId: mutationId,
    expectedVersion: self.version,
    expectedCollectionVersion: self.collectionVersion
  });
  assert.equal(replay.status, 200, JSON.stringify(replay.body));
  assert.equal(replay.body.data.replayed, true);

  // 2) 启用中的分类不可恢复
  list = await listCategories(userA, personal.collectionId);
  const activeRow = list.items.find((c) => c.categoryId === self.categoryId);
  const activeRestore = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/categories/${self.categoryId}/unarchive`, {
    clientMutationId: uuid(),
    expectedVersion: activeRow.version,
    expectedCollectionVersion: list.collectionVersion
  });
  assert.equal(activeRestore.status, 409);
  assert.equal(activeRestore.body.error.code, "STATE_CONFLICT");

  // 3) 名称冲突：归档「冲突分类」后新建同名启用分类，恢复需 rename
  const conflict = await createAndArchive("冲突分类");
  const dupe = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/categories`, {
    clientMutationId: uuid(), name: "冲突分类"
  });
  assert.equal(dupe.status, 200, JSON.stringify(dupe.body));
  const noRename = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/categories/${conflict.categoryId}/unarchive`, {
    clientMutationId: uuid(),
    expectedVersion: conflict.version,
    expectedCollectionVersion: (await listCategories(userA, personal.collectionId)).collectionVersion
  });
  assert.equal(noRename.status, 422, JSON.stringify(noRename.body));
  assert.equal(noRename.body.error.fieldErrors[0].code, "NAME_CONFLICT");

  const renamed = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/categories/${conflict.categoryId}/unarchive`, {
    clientMutationId: uuid(),
    expectedVersion: conflict.version,
    expectedCollectionVersion: (await listCategories(userA, personal.collectionId)).collectionVersion,
    rename: "冲突分类（恢复）"
  });
  assert.equal(renamed.status, 200, JSON.stringify(renamed.body));
  assert.equal(renamed.body.data.entity.name, "冲突分类（恢复）");
  assert.equal(renamed.body.data.entity.categoryId, conflict.categoryId);

  // 4) 预设来源分类：恢复后绑定复活，再次导入按 already_imported 跳过
  list = await listCategories(userA, personal.collectionId);
  const seeded = list.items.find((c) => c.source && c.source.presetKey === "inventory.home.cleaning");
  assert.ok(seeded, "基础包播撒的清洁用品分类存在");
  const presetArchived = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/categories/${seeded.categoryId}/archive`, {
    clientMutationId: uuid(),
    expectedVersion: seeded.version,
    expectedCollectionVersion: list.collectionVersion
  });
  assert.equal(presetArchived.status, 200, JSON.stringify(presetArchived.body));
  const presetRestored = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/categories/${seeded.categoryId}/unarchive`, {
    clientMutationId: uuid(),
    expectedVersion: presetArchived.body.data.entity.version,
    expectedCollectionVersion: presetArchived.body.data.collectionVersion
  });
  assert.equal(presetRestored.status, 200, JSON.stringify(presetRestored.body));
  const reapply = await api(userA, "POST", `/api/category-collections/${personal.collectionId}/presets/apply`, {
    clientMutationId: uuid(),
    expectedCollectionVersion: presetRestored.body.data.collectionVersion,
    selectedPresetKeys: ["inventory.home.cleaning"]
  });
  assert.equal(reapply.status, 200, JSON.stringify(reapply.body));
  assert.ok(reapply.body.data.skipped.some((item) => item.presetKey === "inventory.home.cleaning"),
    "绑定复活后再次导入按已导入跳过，不重复建类");
  assert.equal(reapply.body.data.imported.length, 0);
});
