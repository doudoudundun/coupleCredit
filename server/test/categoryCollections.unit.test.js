/**
 * 分类集合 + 预设导入 单元测试 —— 纯函数与注册表完整性，不依赖真实数据库。
 * 覆盖：nameKey 规范化（spec 6.1）、预设注册表结构（spec 5.1/5.2，含与小程序
 * utils/categoryIcon.js 的 20 支出分类名单交叉校验）、排序数组校验（spec 7）、
 * 迁移 031 静态断言（幂等要点）。
 */
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const {
  PACKS,
  normalizeNameKey,
  listPacks,
  getCategoryPreset,
  getTemplatePreset
} = require("../src/services/categoryPresets");
const { _internals } = require("../src/routes/categoryCollections");

/* ------------------------------------------------------------------ *
 * nameKey 规范化（spec 6.1：NFC + 英文小写 + 连续空白折叠为一个空格）
 * ------------------------------------------------------------------ */

test("nameKey: NFC 规范化 + 英文小写 + 空白折叠", () => {
  assert.equal(normalizeNameKey("  Clean   Supplies "), "clean supplies");
  assert.equal(normalizeNameKey("ABC"), "abc");
  assert.equal(normalizeNameKey("清洁用品"), "清洁用品");
  assert.equal(normalizeNameKey("a\t\n b"), "a b");
  // NFC：兼容字符（如全角Ａ\uFF21）不折叠成 a，但组合序列归一
  assert.equal(normalizeNameKey("é"), "é"); // e + ́ → é
  assert.equal(normalizeNameKey("Café"), "café");
  assert.notEqual(normalizeNameKey("ＡＢＣ"), "abc"); // 全角不折叠
});

test("nameKey: 路由内部导出与服务端注册表为同一实现", () => {
  assert.equal(_internals.normalizeNameKey, normalizeNameKey);
});

/* ------------------------------------------------------------------ *
 * 注册表结构完整性（spec 5.1/5.2）
 * ------------------------------------------------------------------ */

test("注册表：五个包、domain/direction 合法、key 稳定格式", () => {
  const packKeys = PACKS.map((p) => p.packKey);
  assert.deepEqual(packKeys, [
    "inventory.home",
    "inventory.pets",
    "inventory.tools",
    "bills.expense.basic",
    "bills.expense.full",
    "bills.income.basic"
  ]);
  for (const pack of PACKS) {
    assert.ok(Number.isSafeInteger(pack.packVersion) && pack.packVersion >= 1, `${pack.packKey} packVersion`);
    if (pack.domain === "bills") {
      assert.ok(pack.direction === "expense" || pack.direction === "income");
    } else {
      assert.equal(pack.domain, "inventory");
      assert.equal(pack.direction, null);
    }
    for (const category of pack.categories) {
      // presetKey 以 packKey 为前缀，稳定可追溯
      assert.ok(category.presetKey.startsWith(`${pack.packKey}.`), `${category.presetKey} 前缀`);
      assert.ok(category.name.length >= 1 && category.name.length <= 20);
      assert.ok(category.icon && (category.icon.type === "iconKey" || category.icon.type === "emoji"));
      assert.match(category.color, /^#[0-9a-fA-F]{6}$/);
      for (const template of category.templates) {
        assert.ok(template.presetKey.startsWith(`${pack.packKey}.`), `${template.presetKey} 前缀`);
        assert.ok(template.name.length >= 1);
        if (pack.domain === "inventory") {
          // 物资模板：单位是建议值，数量不属于导入结果（注册表不得出现数量字段）
          assert.ok(!("quantity" in template), "模板不得包含实际库存数量");
          assert.ok(template.suggestedAlertLine === null || /^\d+\.\d{2}$/.test(template.suggestedAlertLine));
        } else {
          // 账单模板：建议金额可空，null 不补 0
          assert.ok(template.suggestedAmount === null || /^\d+\.\d{2}$/.test(template.suggestedAmount));
        }
      }
    }
  }
});

test("注册表：listPacks 按 domain/direction 过滤", () => {
  assert.equal(listPacks({ domain: "inventory" }).length, 3);
  assert.deepEqual(listPacks({ domain: "bills", direction: "expense" }).map((p) => p.packKey),
    ["bills.expense.basic", "bills.expense.full"]);
  assert.deepEqual(listPacks({ domain: "bills", direction: "income" }).map((p) => p.packKey),
    ["bills.income.basic"]);
  assert.ok(getCategoryPreset("inventory.home.cleaning"));
  assert.ok(getTemplatePreset("bills.expense.basic.rent"));
  assert.equal(getCategoryPreset("bills.expense.basic.rent"), null, "模板 key 不在分类索引");
});

test("注册表：bills.expense.full 与小程序 categoryIcon.js 的 20 支出分类同名同序", () => {
  // 交叉校验：直接读小程序仓库的硬编码名单（只读参考；server 与 mp 仓库同级位于 GitHub/ 下）
  const mpFile = path.resolve(__dirname, "../../../coupleCredit-mp/utils/categoryIcon.js");
  const source = fs.readFileSync(mpFile, "utf8");
  const match = source.match(/const EXPENSE_CATEGORIES = \[([\s\S]*?)\];/);
  assert.ok(match, "categoryIcon.js 中能找到 EXPENSE_CATEGORIES");
  const expected = [...match[1].matchAll(/'([^']+)'/g)].map((m) => m[1]);
  assert.equal(expected.length, 20, "旧客户端 20 个支出分类");
  const fullPack = PACKS.find((p) => p.packKey === "bills.expense.full");
  assert.deepEqual(fullPack.categories.map((c) => c.name), expected);
});

test("注册表：iconKey 均为前端 images/category/ 已有图标", () => {
  const iconDir = path.resolve(__dirname, "../../../coupleCredit-mp/images/category");
  const available = new Set(
    fs.readdirSync(iconDir).filter((name) => name.endsWith(".png")).map((name) => name.replace(/\.png$/, ""))
  );
  for (const pack of PACKS) {
    for (const category of pack.categories) {
      if (category.icon.type === "iconKey") {
        assert.ok(available.has(category.icon.value), `iconKey ${category.icon.value} 无对应前端图标`);
      }
      for (const template of category.templates) {
        if (template.icon && template.icon.type === "iconKey") {
          assert.ok(available.has(template.icon.value), `模板 iconKey ${template.icon.value} 无对应前端图标`);
        }
      }
    }
  }
});

/* ------------------------------------------------------------------ *
 * 排序数组校验（spec 7：无重复、恰为同集合全部启用分类、不夹带外集合 ID）
 * ------------------------------------------------------------------ */

const A = "11111111-1111-4111-8111-111111111111";
const B = "22222222-2222-4222-8222-222222222222";
const C = "33333333-3333-4333-8333-333333333333";
const FOREIGN = "99999999-9999-4999-8999-999999999999";

test("排序数组：合法完整数组通过（顺序任意）", () => {
  assert.equal(_internals.validateOrderIds([B, A, C], [A, B, C]), null);
  assert.equal(_internals.validateOrderIds([], []), null);
});

test("排序数组：重复 ID 拒绝", () => {
  const errors = _internals.validateOrderIds([A, B, A], [A, B]);
  assert.ok(errors && errors.some((e) => e.code === "DUPLICATE"));
});

test("排序数组：缺项拒绝", () => {
  const errors = _internals.validateOrderIds([A], [A, B]);
  assert.ok(errors && errors.some((e) => e.code === "INCOMPLETE"));
});

test("排序数组：夹带外集合/未知 ID 拒绝", () => {
  const errors = _internals.validateOrderIds([A, B, FOREIGN], [A, B]);
  assert.ok(errors && errors.some((e) => e.code === "FOREIGN_ID"));
});

test("排序数组：非数组 / 非 UUID 拒绝", () => {
  assert.ok(_internals.validateOrderIds("not-array", [A]));
  assert.ok(_internals.validateOrderIds(["abc"], ["abc"]));
});

/* ------------------------------------------------------------------ *
 * requestHash 规范化：剔除 clientMutationId、键序无关
 * ------------------------------------------------------------------ */

test("requestHash: clientMutationId 不参与哈希，键序无关", () => {
  const h1 = _internals.buildRequestHash({
    method: "POST", resourcePath: "/x", targetId: null,
    body: { clientMutationId: A, name: "清洁", color: "#FFFFFF" }
  });
  const h2 = _internals.buildRequestHash({
    method: "POST", resourcePath: "/x", targetId: null,
    body: { color: "#FFFFFF", name: "清洁", clientMutationId: B }
  });
  assert.equal(h1, h2);
  const h3 = _internals.buildRequestHash({
    method: "POST", resourcePath: "/x", targetId: null,
    body: { name: "清洁", color: "#000000" }
  });
  assert.notEqual(h1, h3);
});

/* ------------------------------------------------------------------ *
 * 迁移 031 静态断言：幂等要点与禁止项
 * ------------------------------------------------------------------ */

test("迁移 031：幂等要点齐备，不关闭 FOREIGN_KEY_CHECKS", () => {
  const sql = fs.readFileSync(
    path.resolve(__dirname, "../migrations/031_create_category_collections.sql"), "utf8"
  );
  assert.ok(!/FOREIGN_KEY_CHECKS\s*=\s*0/i.test(sql), "不得关闭 FOREIGN_KEY_CHECKS");
  for (const table of [
    "category_collections", "item_categories", "item_templates",
    "preset_bindings", "collection_audits", "collection_mutations", "item_template_preferences"
  ]) {
    assert.ok(sql.includes(`CREATE TABLE IF NOT EXISTS ${table}`), `${table} 必须 IF NOT EXISTS`);
  }
  // direction NULL 语义：用生成列折叠空值参与唯一键
  assert.ok(/direction_key\s+VARCHAR\(16\)\s+GENERATED ALWAYS AS \(COALESCE\(direction, ''\)\)/.test(sql));
  assert.ok(/uk_cc_personal \(domain, direction_key, scope, owner_user_id\)/.test(sql));
  assert.ok(/uk_cc_couple \(domain, direction_key, scope, relationship_id\)/.test(sql));
  // 启用分类 name_key 部分唯一（参考 030 生成列技巧）
  assert.ok(/active_name_key\s+VARCHAR\(20\)\s+GENERATED ALWAYS AS \(IF\(status = 'active', name_key, NULL\)\)/.test(sql));
  assert.ok(/UNIQUE KEY uk_ic_active_name \(collection_id, active_name_key\)/.test(sql));
  // bills / inventory 只加列不回填
  assert.ok(/ADD COLUMN `category_id`/.test(sql));
  assert.ok(/ADD COLUMN `category_name_snapshot`/.test(sql));
  assert.ok(!/UPDATE `?bills`?/i.test(sql) && !/UPDATE `?inventory`?/i.test(sql), "本迁移不回填数据");
});

test("迁移 032：baseline_seeded_at 加列幂等，不回填数据", () => {
  const sql = fs.readFileSync(
    path.resolve(__dirname, "../migrations/032_add_baseline_seeded_at.sql"), "utf8"
  );
  assert.ok(!/FOREIGN_KEY_CHECKS\s*=\s*0/i.test(sql), "不得关闭 FOREIGN_KEY_CHECKS");
  assert.ok(/ADD COLUMN `baseline_seeded_at` DATETIME\(3\) NULL DEFAULT NULL/.test(sql));
  assert.ok(/INFORMATION_SCHEMA\.COLUMNS/i.test(sql), "加列必须 INFORMATION_SCHEMA 检查保证幂等");
  assert.ok(!/UPDATE `?category_collections`?/i.test(sql), "本迁移不回填标志（回填在独立脚本）");
});
