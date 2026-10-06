/**
 * 家务基础预设免导入（创建即播撒 + 一次性回填）集成测试 —— spec: docs/CATEGORY_PRESETS_SPEC_20261002.md §5.0。
 *
 * 覆盖：
 *   a) 新用户首次 bootstrap → 个人空间已有 6 基础分类 + 6 基础模板（presetCategoryKey/presetKey 来源标记）；
 *   b) 绑定后共享空间建空间即播撒，双方看到同一套内容；解绑→重绑的新空间同样播撒；
 *   c) 归档（删除）播撒项后反复 bootstrap 不复活；同 key 显式导入返回 archivedConflict；
 *      按既有规则显式恢复（PATCH status=active）后原 templateId 回归；
 *   d) 播撒后 presets/apply 基线 key 全部 skipped，扩展 key 正常导入；
 *   e) 回填脚本：dry-run 不写库；存量无标志 active 空间合并播撒（用户同名分类保留、
 *      归档墓碑不复活、revision 只递增一次）；二次执行 no-op；closed 空间只写标志不播撒。
 *
 * 需要服务运行在 AUTH_API_BASE_URL（默认 18099 dev，迁移 033 已执行）：
 *   AUTH_API_BASE_URL=http://127.0.0.1:18099 node --env-file=.env --test test/housework.baseline.test.js
 * 测试账号全部新建（whb 前缀），不碰既有用户数据。
 */
const assert = require("assert");
const { test } = require("node:test");

const { HOUSEWORK_BASELINE, PRESET_PACKS } = require("../src/services/houseworkPresets");

const baseUrl = process.env.AUTH_API_BASE_URL || "http://127.0.0.1:18099";
const inviteCode = process.env.AUTH_API_INVITE_CODE || "COUPLE-PRIVATE-2026";

const BASELINE_CATEGORY_KEYS = HOUSEWORK_BASELINE.presetCategoryKeys;
const BASELINE_TEMPLATE_KEYS = HOUSEWORK_BASELINE.templateKeys;
const EXTENDED_TEMPLATE_KEYS = PRESET_PACKS
  .flatMap((pack) => pack.templates.map((t) => t.presetKey))
  .filter((key) => !BASELINE_TEMPLATE_KEYS.includes(key));

async function readJsonOrText(response) {
  const text = await response.text();
  try { return JSON.parse(text); } catch (_e) { return text; }
}

async function registerUser(tag) {
  // 与 test/categoryCollections.baseline.test.js 相同约定：v→w，避开 TEXT_PROMO_PATTERNS
  const { TEXT_PROMO_PATTERNS } = require("../src/services/contentSecurity");
  let username = "";
  for (let attempt = 0; attempt < 10 && !username; attempt += 1) {
    const stem = `${Date.now().toString(36).replace(/v/g, "w")}${tag}${Math.floor(Math.random() * 46656).toString(36).replace(/v/g, "w")}`;
    const candidate = `whb${stem}`;
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

async function bootstrap(user) {
  const res = await api(user, "POST", "/api/housework/bootstrap", {});
  assert.equal(res.status, 200, JSON.stringify(res.body));
  return res.body.data;
}

async function getOwnActiveSpace(user, data) {
  const space = (data || await bootstrap(user)).spaces
    .find((s) => s.scope === "personal" && s.status === "active" && s.ownerUserId === String(user.userId));
  assert.ok(space, "本人 active 个人空间存在");
  return space;
}

async function getSharedSpace(user, data) {
  const space = (data || await bootstrap(user)).spaces.find((s) => s.scope === "couple" && s.status === "active");
  assert.ok(space, "active 共享空间存在");
  return space;
}

async function getConfig(user, spaceId) {
  const res = await api(user, "GET", `/api/housework/spaces/${spaceId}/config`);
  assert.equal(res.status, 200, JSON.stringify(res.body));
  return res.body.data;
}

function assertSeededConfig(config) {
  const baselineCategories = config.categories.filter((c) => c.presetCategoryKey !== null);
  assert.deepEqual(baselineCategories.map((c) => c.presetCategoryKey).sort(), [...BASELINE_CATEGORY_KEYS].sort(),
    "6 个基础分类全部带 presetCategoryKey 标记");
  assert.ok(baselineCategories.every((c) => c.status === "active"), "基础分类均启用");
  const fallback = config.categories.find((c) => c.isFallback);
  assert.ok(fallback, "兜底分类仍在");
  assert.equal(fallback.presetCategoryKey, null, "兜底分类无预设标记");

  const templateKeys = config.templates.filter((t) => t.presetKey !== null).map((t) => t.presetKey);
  assert.deepEqual(templateKeys.sort(), [...BASELINE_TEMPLATE_KEYS].sort(), "6 个基础模板全部带 presetKey 标记");

  // 每个基础模板挂在正确的基础分类下（按注册表归属校验）
  const categoryByKey = new Map(baselineCategories.map((c) => [c.presetCategoryKey, c]));
  const packByTemplateKey = new Map(PRESET_PACKS.flatMap((pack) => pack.templates.map((t) => [t.presetKey, pack])));
  for (const template of config.templates) {
    const pack = packByTemplateKey.get(template.presetKey);
    assert.ok(pack, `模板 ${template.presetKey} 属于注册表`);
    assert.equal(template.categoryId, categoryByKey.get(pack.presetCategoryKey).categoryId,
      `${template.presetKey} 挂在 ${pack.presetCategoryKey} 下`);
  }
  // 基线洗衣保留注册表定义：quantity 模式 + 件
  const laundry = config.templates.find((t) => t.presetKey === "laundry.wash");
  assert.equal(laundry.measureMode, "quantity");
  assert.equal(laundry.unit, "件");
  return { baselineCategories, templates: config.templates };
}

/* ============ a：新用户首次 bootstrap 即播撒（个人空间） ============ */
test("a 新用户首次 bootstrap：个人空间已有 6 基础分类 + 6 基础模板，重复 bootstrap 不重复", async () => {
  const user = await registerUser("a");

  const first = await bootstrap(user);
  const space = await getOwnActiveSpace(user, first);
  const config = await getConfig(user, space.spaceId);
  assertSeededConfig(config);
  assert.equal(config.categories.length, 7, "6 基础 + 1 兜底");
  assert.equal(config.templates.length, 6, "只播撒 6 个基线模板，不灌全量");

  // 播撒只在创建时发生一次：再次 bootstrap（读路径）不重复、不复活
  await bootstrap(user);
  await bootstrap(user);
  const again = await getConfig(user, space.spaceId);
  assert.equal(again.categories.length, 7);
  assert.equal(again.templates.length, 6);
  assert.deepEqual(again.templates.map((t) => t.templateId).sort(), config.templates.map((t) => t.templateId).sort());
});

/* ============ b：绑定后共享空间双方看到同一套播撒；重绑新空间同样播撒 ============ */
test("b 绑定建空间即播撒：双方内容一致；解绑重绑的新周期空间同样播撒", async () => {
  const userA = await registerUser("b1");
  const userB = await registerUser("b2");
  const invite = await api(userA, "POST", "/api/couple/generate-invite", {});
  assert.equal(invite.status, 200, JSON.stringify(invite.body));
  const bind = await api(userB, "POST", "/api/couple/bind", { inviteCode: invite.body.data.inviteCode });
  assert.equal(bind.status, 200, JSON.stringify(bind.body));

  const sharedA = await getSharedSpace(userA);
  const sharedB = await getSharedSpace(userB);
  assert.equal(sharedA.spaceId, sharedB.spaceId, "同一共享空间");
  const configA = await getConfig(userA, sharedA.spaceId);
  const configB = await getConfig(userB, sharedB.spaceId);
  assertSeededConfig(configA);
  assert.deepEqual(configB.templates.map((t) => t.templateId).sort(), configA.templates.map((t) => t.templateId).sort(),
    "双方看到同一批模板");
  assert.deepEqual(configB.categories.map((c) => c.categoryId).sort(), configA.categories.map((c) => c.categoryId).sort(),
    "双方看到同一批分类");

  // 解绑 → 旧空间冻结；重绑 → 新 cycle/新空间，创建时同样播撒
  const unbind = await api(userA, "DELETE", "/api/couple/unbind");
  assert.equal(unbind.status, 200, JSON.stringify(unbind.body));
  const invite2 = await api(userA, "POST", "/api/couple/generate-invite", {});
  const rebind = await api(userB, "POST", "/api/couple/bind", { inviteCode: invite2.body.data.inviteCode });
  assert.equal(rebind.status, 200, JSON.stringify(rebind.body));
  const newShared = await getSharedSpace(userA);
  assert.notEqual(newShared.spaceId, sharedA.spaceId, "重绑生成新空间");
  assertSeededConfig(await getConfig(userB, newShared.spaceId));

  // 旧空间已 closed：只读，不参与任何播撒
  const closed = (await bootstrap(userA)).spaces.find((s) => s.spaceId === sharedA.spaceId);
  assert.equal(closed.status, "closed");
});

/* ============ c：删除（归档）播撒项不复活；显式恢复按既有规则 ============ */
test("c 归档播撒模板/分类后 bootstrap 不复活；同 key 导入 archivedConflict；显式恢复原 ID 回归", async () => {
  const user = await registerUser("c");
  const space = await getOwnActiveSpace(user);
  let config = await getConfig(user, space.spaceId);
  const laundry = config.templates.find((t) => t.presetKey === "laundry.wash");
  const pets = config.categories.find((c) => c.presetCategoryKey === "pets");

  // 归档基线模板「洗衣」
  const archive = await api(user, "PATCH", `/api/housework/spaces/${space.spaceId}/templates/${laundry.templateId}`, {
    clientMutationId: uuid(),
    expectedVersion: laundry.version,
    status: "archived"
  });
  assert.equal(archive.status, 200, JSON.stringify(archive.body));
  // 归档基线分类「宠物」（无启用模板，可直接归档）
  const archivePets = await api(user, "PATCH", `/api/housework/spaces/${space.spaceId}/categories/${pets.categoryId}`, {
    clientMutationId: uuid(),
    expectedVersion: pets.version,
    status: "archived"
  });
  assert.equal(archivePets.status, 200, JSON.stringify(archivePets.body));

  // 反复 bootstrap + config（读路径）：不复活
  for (let i = 0; i < 2; i += 1) {
    await bootstrap(user);
    config = await getConfig(user, space.spaceId);
    assert.ok(!config.templates.some((t) => t.presetKey === "laundry.wash"), `第 ${i + 1} 次读取：洗衣不复活`);
    assert.ok(!config.categories.some((c) => c.presetCategoryKey === "pets"), `第 ${i + 1} 次读取：宠物分类不复活`);
  }

  // 同 key 显式导入：按墓碑返回 archivedConflict，不新建副本
  const apply = await api(user, "POST", `/api/housework/spaces/${space.spaceId}/presets/apply`, {
    clientMutationId: uuid(),
    selectedPresetKeys: ["laundry.wash"]
  });
  assert.equal(apply.status, 200, JSON.stringify(apply.body));
  assert.deepEqual(apply.body.data.entity.archivedConflict, ["laundry.wash"]);
  assert.equal(apply.body.data.entity.imported.length, 0);
  config = await getConfig(user, space.spaceId);
  assert.ok(!config.templates.some((t) => t.presetKey === "laundry.wash"));

  // 显式恢复（既有规则：PATCH status=active）→ 原 templateId 回归，内容为用户自己的配置
  const archivedList = await api(user, "GET", `/api/housework/spaces/${space.spaceId}/templates?status=archived`);
  assert.equal(archivedList.status, 200, JSON.stringify(archivedList.body));
  const archivedLaundry = archivedList.body.data.items.find((t) => t.presetKey === "laundry.wash");
  assert.ok(archivedLaundry, "归档列表可见墓碑行");
  const restore = await api(user, "PATCH", `/api/housework/spaces/${space.spaceId}/templates/${laundry.templateId}`, {
    clientMutationId: uuid(),
    expectedVersion: archivedLaundry.version,
    status: "active"
  });
  assert.equal(restore.status, 200, JSON.stringify(restore.body));
  config = await getConfig(user, space.spaceId);
  const restored = config.templates.find((t) => t.presetKey === "laundry.wash");
  assert.equal(restored.templateId, laundry.templateId, "恢复保留原 templateId");

  // 恢复后的再次导入按已存在跳过
  const applyAgain = await api(user, "POST", `/api/housework/spaces/${space.spaceId}/presets/apply`, {
    clientMutationId: uuid(),
    selectedPresetKeys: ["laundry.wash"]
  });
  assert.deepEqual(applyAgain.body.data.entity.skipped, ["laundry.wash"]);
});

/* ============ d：播撒后再导入——基线 skipped，扩展 imported ============ */
test("d 播撒后 presets/apply：基线 key 全部 skipped，扩展 key 正常导入", async () => {
  const user = await registerUser("d");
  const space = await getOwnActiveSpace(user);

  const applyBaseline = await api(user, "POST", `/api/housework/spaces/${space.spaceId}/presets/apply`, {
    clientMutationId: uuid(),
    selectedPresetKeys: [...BASELINE_TEMPLATE_KEYS]
  });
  assert.equal(applyBaseline.status, 200, JSON.stringify(applyBaseline.body));
  assert.equal(applyBaseline.body.data.entity.imported.length, 0, "基线已播撒，无新建");
  assert.deepEqual(applyBaseline.body.data.entity.skipped.sort(), [...BASELINE_TEMPLATE_KEYS].sort());

  const config = await getConfig(user, space.spaceId);
  assert.equal(config.templates.length, 6, "不产生重复模板");

  const applyExtended = await api(user, "POST", `/api/housework/spaces/${space.spaceId}/presets/apply`, {
    clientMutationId: uuid(),
    selectedPresetKeys: [...EXTENDED_TEMPLATE_KEYS]
  });
  assert.equal(applyExtended.status, 200, JSON.stringify(applyExtended.body));
  assert.deepEqual(applyExtended.body.data.entity.imported.map((item) => item.presetKey).sort(),
    [...EXTENDED_TEMPLATE_KEYS].sort(), "扩展包仍走显式导入");
  const after = await getConfig(user, space.spaceId);
  assert.equal(after.templates.length, 6 + EXTENDED_TEMPLATE_KEYS.length);
  // 宠物喂食复用已播撒的「宠物」分类，不另建第二个宠物分类
  const petsCategories = after.categories.filter((c) => c.name === "宠物");
  assert.equal(petsCategories.length, 1);
});

/* ============ e：回填脚本 ============ */
test("e 回填脚本：dry-run 不写库；存量空间合并播撒（同名保留/墓碑不复活）；幂等；closed 只写标志", async () => {
  const { readConfig } = require("../src/config");
  const { createPool } = require("../src/db");
  const { runBaselineSeed } = require("../scripts/dev/seed-baseline-housework");
  const pool = createPool(readConfig());
  try {
    // e-1：用 SQL 构造「迁移 033 之前」的存量 active 个人空间（该用户从不 bootstrap，
    // 不经新代码建空间）：baseline_seeded_at 为 NULL，含兜底分类 + 用户自建「清洁」+
    // 一个已归档的预设模板墓碑（洗碗，模拟旧导入后删除）。
    const user = await registerUser("e");
    const legacySpaceId = uuid();
    const fallbackCategoryId = uuid();
    const userCategoryId = uuid();
    const archivedTemplateId = uuid();
    await pool.execute(
      `INSERT INTO housework_spaces
         (space_id, scope, owner_user_id, cycle_id, status, timezone, revision, version, settings_json, baseline_seeded_at)
       VALUES (?, 'personal', ?, NULL, 'active', 'Asia/Shanghai', 0, 1, '{}', NULL)`,
      [legacySpaceId, user.userId]
    );
    await pool.execute(
      "INSERT INTO housework_space_members (space_id, user_id, display_name_snapshot) VALUES (?, ?, 'whb-legacy')",
      [legacySpaceId, user.userId]
    );
    await pool.execute(
      `INSERT INTO housework_categories
         (category_id, space_id, name, normalized_name, icon_json, color, sort_order, is_fallback, status)
       VALUES (?, ?, '未分类', '未分类', NULL, '#8A8F99', 0, 1, 'active')`,
      [fallbackCategoryId, legacySpaceId]
    );
    await pool.execute(
      `INSERT INTO housework_categories
         (category_id, space_id, name, normalized_name, icon_json, color, sort_order, is_fallback, status, preset_category_key)
       VALUES (?, ?, '清洁', '清洁', NULL, '#4A90D9', 1, 0, 'active', NULL)`,
      [userCategoryId, legacySpaceId]
    );
    await pool.execute(
      `INSERT INTO housework_templates
         (template_id, space_id, category_id, name, normalized_name, description, icon_json, color,
          sort_order, measure_mode, unit, default_quantity, duration_enabled, default_duration_minutes,
          weight, fields_json, status, preset_key, version)
       VALUES (?, ?, ?, '洗碗', '洗碗', NULL, NULL, NULL, 0, 'event', '次', 1.00, 0, NULL, 1.00, '[]', 'archived', 'kitchen.dishes', 2)`,
      [archivedTemplateId, legacySpaceId, userCategoryId]
    );

    const space = { spaceId: legacySpaceId };
    const before = await getConfig(user, legacySpaceId);
    assert.equal(before.categories.length, 2, "存量空间只有兜底 + 用户自建分类");
    assert.equal(before.templates.length, 0, "归档模板不进启用列表");
    const revisionBefore = before.revision;

    // e-2：dry-run 不写库
    const dry = await runBaselineSeed({ pool, apply: false, log: () => {} });
    assert.ok(dry.total >= 1, "dry-run 看到待处理空间");
    const afterDry = await getConfig(user, legacySpaceId);
    assert.equal(afterDry.revision, revisionBefore, "dry-run 不递增 revision");
    assert.deepEqual(afterDry.categories.map((c) => c.categoryId).sort(), before.categories.map((c) => c.categoryId).sort(),
      "dry-run 不新增分类");
    const [flagAfterDry] = await pool.execute("SELECT baseline_seeded_at FROM housework_spaces WHERE space_id = ?", [legacySpaceId]);
    assert.equal(flagAfterDry[0].baseline_seeded_at, null, "dry-run 不写标志");

    // e-3：--apply 合并播撒
    const first = await runBaselineSeed({ pool, apply: true, log: () => {} });
    assert.ok(first.seeded >= 1, "至少播撒了手工构造的存量空间");
    assert.ok(first.details.some((d) => d.spaceId === legacySpaceId && d.action === "seed"));

    const [flag] = await pool.execute("SELECT baseline_seeded_at, revision FROM housework_spaces WHERE space_id = ?", [legacySpaceId]);
    assert.ok(flag[0].baseline_seeded_at !== null, "标志已写");
    assert.equal(Number(flag[0].revision), revisionBefore + 1, "revision 恰好递增一次");

    const config = await getConfig(user, legacySpaceId);
    // 用户自建「清洁」保留原 ID，播撒不按名称认领、不产生第二个清洁分类
    const cleaningCategories = config.categories.filter((c) => c.name === "清洁");
    assert.equal(cleaningCategories.length, 1, "同名分类不重复");
    assert.equal(cleaningCategories[0].categoryId, userCategoryId, "保留用户自建版本");
    assert.equal(cleaningCategories[0].presetCategoryKey, null, "用户分类不带预设标记");
    // 清洁组被跳过：扫地/拖地未播撒（分类没有着落）
    assert.ok(!config.templates.some((t) => t.presetKey === "cleaning.sweep"), "清洁组被跳过，扫地未播撒");
    assert.ok(!config.templates.some((t) => t.presetKey === "cleaning.mop"), "清洁组被跳过，拖地未播撒");
    // 其余基线组正常播撒
    const seededKeys = config.templates.map((t) => t.presetKey).filter(Boolean);
    assert.ok(seededKeys.includes("kitchen.cook"), "做饭已补播");
    assert.ok(seededKeys.includes("laundry.wash"), "洗衣已补播");
    // 归档墓碑不复活：洗碗保持归档且无副本
    assert.ok(!seededKeys.includes("kitchen.dishes"), "洗碗不被回填复活");
    const archivedAfter = await api(user, "GET", `/api/housework/spaces/${legacySpaceId}/templates?status=archived`);
    const archivedDishes = archivedAfter.body.data.items.filter((t) => t.presetKey === "kitchen.dishes");
    assert.equal(archivedDishes.length, 1, "洗碗墓碑仅一行，无副本");

    // e-4：构造 closed 空间（解绑）并抹标志：回填只写标志、绝不播撒
    const userC1 = await registerUser("e3");
    const userC2 = await registerUser("e4");
    const invite = await api(userC1, "POST", "/api/couple/generate-invite", {});
    await api(userC2, "POST", "/api/couple/bind", { inviteCode: invite.body.data.inviteCode });
    const shared = await getSharedSpace(userC1);
    const unbind = await api(userC1, "DELETE", "/api/couple/unbind");
    assert.equal(unbind.status, 200, JSON.stringify(unbind.body));
    await pool.execute("UPDATE housework_spaces SET baseline_seeded_at = NULL WHERE space_id = ?", [shared.spaceId]);
    const closedConfigBefore = await getConfig(userC1, shared.spaceId);

    const second = await runBaselineSeed({ pool, apply: true, log: () => {} });
    assert.ok(second.details.some((d) => d.spaceId === shared.spaceId && d.action === "mark_only"),
      "closed 空间只写标志");
    const closedConfigAfter = await getConfig(userC1, shared.spaceId);
    assert.deepEqual(closedConfigAfter.templates.map((t) => t.templateId).sort(),
      closedConfigBefore.templates.map((t) => t.templateId).sort(), "closed 空间模板未被改动");
    assert.equal(closedConfigAfter.revision, closedConfigBefore.revision, "closed 空间 revision 不变");
    const [closedFlag] = await pool.execute("SELECT baseline_seeded_at FROM housework_spaces WHERE space_id = ?", [shared.spaceId]);
    assert.ok(closedFlag[0].baseline_seeded_at !== null, "closed 空间标志已写（闸门关闭）");

    // e-5：二次执行 no-op
    const third = await runBaselineSeed({ pool, apply: true, log: () => {} });
    assert.equal(third.total, 0, "无待处理空间");
    assert.equal(third.seeded, 0);
    const finalConfig = await getConfig(user, space.spaceId);
    assert.equal(finalConfig.templates.filter((t) => t.presetKey === "laundry.wash").length, 1, "不产生重复");
  } finally {
    await pool.end();
  }
});
