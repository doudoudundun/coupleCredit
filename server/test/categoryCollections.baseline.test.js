/**
 * 基础预设免导入（创建即播撒 + 一次性回填）集成测试 —— spec §5.0/§9。
 *
 * 覆盖：
 *   a) 新用户首次 GET collections → bills 支出/收入个人集合立即可见 full/basic 包分类；
 *   b) inventory 个人集合立即可见基础居家包分类；
 *   c) 绑定关系的双方看到同一个 couple 集合且均已播撒（双方一致）；
 *   d) 删除播撒分类后重新 GET → 不复活（读路径不补）；
 *   e) 回填脚本：已播撒（标志已写）但删光的集合不复活；存量无标志空集合播撒成功且幂等；
 *   f) 对已播撒集合 presets/apply 同一 pack → 全部 skipped，不产生重复。
 *
 * 需要服务运行在 AUTH_API_BASE_URL（默认 18099 dev，迁移 032 已执行）：
 *   AUTH_API_BASE_URL=http://127.0.0.1:18099 node --env-file=.env --test test/categoryCollections.baseline.test.js
 * 测试账号全部新建（wbs 前缀），不碰既有用户数据。
 */
const assert = require("assert");
const { test } = require("node:test");

const { PACKS } = require("../src/services/categoryPresets");
const { baselinePackFor } = require("../src/services/categorySeed");

const baseUrl = process.env.AUTH_API_BASE_URL || "http://127.0.0.1:18099";
const inviteCode = process.env.AUTH_API_INVITE_CODE || "COUPLE-PRIVATE-2026";

async function readJsonOrText(response) {
  const text = await response.text();
  try { return JSON.parse(text); } catch (_e) { return text; }
}

async function registerUser(tag) {
  // 与 test/categoryCollections.flows.test.js 相同约定：v→w，避开 TEXT_PROMO_PATTERNS
  const { TEXT_PROMO_PATTERNS } = require("../src/services/contentSecurity");
  let username = "";
  for (let attempt = 0; attempt < 10 && !username; attempt += 1) {
    const stem = `${Date.now().toString(36).replace(/v/g, "w")}${tag}${Math.floor(Math.random() * 46656).toString(36).replace(/v/g, "w")}`;
    const candidate = `wbs${stem}`;
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

async function getCollections(user, domain) {
  const res = await api(user, "GET", `/api/category-collections?domain=${domain}`);
  assert.equal(res.status, 200, JSON.stringify(res.body));
  return res.body.data.collections;
}

async function listCategories(user, collectionId, status = "active") {
  const res = await api(user, "GET", `/api/category-collections/${collectionId}/categories?status=${status}`);
  assert.equal(res.status, 200, JSON.stringify(res.body));
  return res.body.data;
}

function packByKey(packKey) {
  const pack = PACKS.find((p) => p.packKey === packKey);
  assert.ok(pack, `注册表存在 ${packKey}`);
  return pack;
}

/** 断言集合分类与基础包完全一致：数量、顺序、名称、source 标记。 */
function assertSeededCategories(items, pack) {
  assert.equal(items.length, pack.categories.length, `${pack.packKey} 分类数量`);
  items.forEach((item, index) => {
    const preset = pack.categories[index];
    assert.equal(item.name, preset.name, `第 ${index} 个分类名称`);
    assert.ok(item.source, `第 ${index} 个分类有 source 标记`);
    assert.equal(item.source.packKey, pack.packKey);
    assert.equal(item.source.presetKey, preset.presetKey);
    assert.equal(item.source.packVersion, pack.packVersion);
    assert.equal(item.status, "active");
    assert.equal(item.sortOrder, index, "按包内顺序连续排序");
  });
}

/* ============ a/b：新用户首次访问即播撒（bills 双方向 + inventory） ============ */
test("a/b 新用户 GET collections：bills 支出 20 类/收入 5 类、inventory 5 类，source 标记正确", async () => {
  const userA = await registerUser("a");

  const billsCollections = await getCollections(userA, "bills");
  const expense = billsCollections.find((c) => c.scope === "personal" && c.direction === "expense");
  const income = billsCollections.find((c) => c.scope === "personal" && c.direction === "income");
  assert.ok(expense && income, "收支个人集合各一");
  assert.equal(expense.version, 1, "集合版本从播撒后的状态起算");
  assert.equal(income.version, 1);

  const expenseCategories = await listCategories(userA, expense.collectionId);
  assertSeededCategories(expenseCategories.items, packByKey("bills.expense.full"));
  const incomeCategories = await listCategories(userA, income.collectionId);
  assertSeededCategories(incomeCategories.items, packByKey("bills.income.basic"));

  const inventoryCollections = await getCollections(userA, "inventory");
  const inventory = inventoryCollections.find((c) => c.scope === "personal");
  assert.ok(inventory, "物资个人集合");
  const inventoryCategories = await listCategories(userA, inventory.collectionId);
  assertSeededCategories(inventoryCategories.items, packByKey("inventory.home"));

  // 播撒不产生模板（与旧版硬编码分类所见一致；模板仍走显式导入）
  const templates = await api(userA, "GET", `/api/category-collections/${inventory.collectionId}/templates`);
  assert.equal(templates.status, 200);
  assert.equal(templates.body.data.items.length, 0, "播撒只含分类，不含模板");
});

/* ============ c：绑定关系双方看到同一个已播撒 couple 集合 ============ */
test("c 双方 GET：同一 couple 集合，双方分类一致且已播撒", async () => {
  const { userA, userB } = await setupCouple();

  for (const domain of ["bills", "inventory"]) {
    const collectionsA = await getCollections(userA, domain);
    const collectionsB = await getCollections(userB, domain);
    const sharedA = collectionsA.filter((c) => c.scope === "couple");
    const sharedB = collectionsB.filter((c) => c.scope === "couple");
    const expectedDirections = domain === "bills" ? ["expense", "income"] : [null];
    assert.equal(sharedA.length, expectedDirections.length, `${domain} 共享集合数量`);
    assert.deepEqual(sharedA.map((c) => c.collectionId).sort(), sharedB.map((c) => c.collectionId).sort(), "双方同一集合");

    for (const direction of expectedDirections) {
      const shared = sharedA.find((c) => (c.direction || null) === direction);
      const pack = baselinePackFor(domain, direction);
      const catsA = await listCategories(userA, shared.collectionId);
      const catsB = await listCategories(userB, shared.collectionId);
      assertSeededCategories(catsA.items, pack);
      assert.deepEqual(catsB.items.map((c) => c.categoryId), catsA.items.map((c) => c.categoryId), "双方分类 ID 一致");
    }
  }
});

/* ============ d：删除播撒分类后重新 GET 不复活 ============ */
test("d 归档播撒分类后反复 GET：不复活，读路径不补", async () => {
  const userA = await registerUser("a");
  const expense = (await getCollections(userA, "bills")).find((c) => c.scope === "personal" && c.direction === "expense");
  let list = await listCategories(userA, expense.collectionId);
  const target = list.items.find((c) => c.source && c.source.presetKey === "bills.expense.full.fruit");
  assert.ok(target, "播撒的「水果」存在");

  const archive = await api(userA, "POST", `/api/category-collections/${expense.collectionId}/categories/${target.categoryId}/archive`, {
    clientMutationId: uuid(),
    expectedVersion: target.version,
    expectedCollectionVersion: list.collectionVersion
  });
  assert.equal(archive.status, 200, JSON.stringify(archive.body));

  // 反复 GET（触发 ensureCollection 读路径）：不复活、不重播
  for (let i = 0; i < 3; i += 1) {
    await getCollections(userA, "bills");
    list = await listCategories(userA, expense.collectionId);
    assert.equal(list.items.length, 19, `第 ${i + 1} 次 GET：仍 19 个启用分类`);
    assert.ok(!list.items.some((c) => c.categoryId === target.categoryId), "已删分类不复活");
  }
  const archived = await listCategories(userA, expense.collectionId, "archived");
  assert.equal(archived.items.length, 1);
  assert.equal(archived.items[0].categoryId, target.categoryId);
});

/* ============ e：回填脚本——已播撒删光不复活；存量无标志空集合播撒且幂等 ============ */
test("e 回填脚本：标志已写的删光集合不复活；手工无标志空集合播撒成功且二次执行幂等", async () => {
  const { readConfig } = require("../src/config");
  const { createPool } = require("../src/db");
  const { runBaselineSeed } = require("../scripts/dev/seed-baseline-categories");
  const pool = createPool(readConfig());
  try {
    // e-1：已播撒（标志已写）的集合，用户删光全部 5 个分类
    const userA = await registerUser("a");
    const inventory = (await getCollections(userA, "inventory")).find((c) => c.scope === "personal");
    let list = await listCategories(userA, inventory.collectionId);
    assert.equal(list.items.length, 5);
    for (const category of list.items) {
      const current = await listCategories(userA, inventory.collectionId);
      const row = current.items.find((c) => c.categoryId === category.categoryId);
      const archive = await api(userA, "POST", `/api/category-collections/${inventory.collectionId}/categories/${category.categoryId}/archive`, {
        clientMutationId: uuid(),
        expectedVersion: row.version,
        expectedCollectionVersion: current.collectionVersion
      });
      assert.equal(archive.status, 200, JSON.stringify(archive.body));
    }
    list = await listCategories(userA, inventory.collectionId);
    assert.equal(list.items.length, 0, "已删光");

    // e-2：手工 INSERT 模拟迁移 032 之前的存量空集合（baseline_seeded_at 为 NULL）
    const userB = await registerUser("b");
    const legacyId = uuid();
    await pool.execute(
      `INSERT INTO category_collections (id, domain, direction, scope, owner_user_id, relationship_id, baseline_seeded_at, created_at, updated_at)
       VALUES (?, 'bills', 'expense', 'personal', ?, NULL, NULL, NOW(3), NOW(3))`,
      [legacyId, userB.userId]
    );

    // 第一次回填：无标志空集合播撒；标志已写的删光集合不动
    const first = await runBaselineSeed({ pool, apply: true, log: () => {} });
    assert.ok(first.seeded >= 1, "至少播撒了手工集合");

    const [legacyCategories] = await pool.execute(
      "SELECT * FROM item_categories WHERE collection_id = ? AND status = 'active' ORDER BY sort_order ASC",
      [legacyId]
    );
    assert.equal(legacyCategories.length, 20, "存量空集合播撒完整包 20 类");
    assert.ok(legacyCategories.every((c) => c.source_pack_key === "bills.expense.full"), "source 标记正确");
    const [legacyFlag] = await pool.execute(
      "SELECT baseline_seeded_at, version FROM category_collections WHERE id = ?",
      [legacyId]
    );
    assert.ok(legacyFlag[0].baseline_seeded_at !== null, "标志已写");
    assert.equal(Number(legacyFlag[0].version), 2, "存量集合播撒后版本递增一次");

    const emptied = await listCategories(userA, inventory.collectionId);
    assert.equal(emptied.items.length, 0, "标志已写的删光集合不被回填复活");
    const emptiedArchived = await listCategories(userA, inventory.collectionId, "archived");
    assert.equal(emptiedArchived.items.length, 5, "归档分类保持归档");

    // 第二次回填：幂等，不再播撒、分类数量不变
    const second = await runBaselineSeed({ pool, apply: true, log: () => {} });
    assert.equal(second.seeded, 0, "二次执行无播撒");
    assert.equal(second.total, 0, "无待处理集合");
    const [legacyCategoriesAfter] = await pool.execute(
      "SELECT COUNT(*) AS total FROM item_categories WHERE collection_id = ?",
      [legacyId]
    );
    assert.equal(Number(legacyCategoriesAfter[0].total), 20, "二次执行数量不变");
  } finally {
    await pool.end();
  }
});

/* ============ f：对已播撒集合 presets/apply 同一 pack → 全部 skipped ============ */
test("f 播撒后再导入同一 pack：全部 skipped already_imported，不产生重复", async () => {
  const userA = await registerUser("a");
  const expense = (await getCollections(userA, "bills")).find((c) => c.scope === "personal" && c.direction === "expense");
  const pack = packByKey("bills.expense.full");

  const apply = await api(userA, "POST", `/api/category-collections/${expense.collectionId}/presets/apply`, {
    clientMutationId: uuid(),
    expectedCollectionVersion: expense.version,
    selectedPresetKeys: pack.categories.map((c) => c.presetKey)
  });
  assert.equal(apply.status, 200, JSON.stringify(apply.body));
  assert.equal(apply.body.data.imported.length, 0, "无新建");
  assert.equal(apply.body.data.skipped.length, pack.categories.length, "全部跳过");
  assert.ok(apply.body.data.skipped.every((item) => item.reason === "already_imported"));

  const list = await listCategories(userA, expense.collectionId);
  assert.equal(list.items.length, pack.categories.length, "不产生重复分类");
});
