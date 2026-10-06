/**
 * 基础预设免导入：集合创建/一次性回填时的基础包播撒 —— spec §5.0。
 *
 * 播撒与 presets/apply 手动导入走同一套写入形状（item_categories.source_* +
 * preset_bindings + collection_audits），产物完全同构：可改名/删除/排序，
 * 删除后墓碑不复活，再导入同一 presetKey 按绑定识别跳过。
 *
 * 只播撒分类，不播撒模板：与旧版硬编码分类所见一致；模板仍由用户显式导入。
 *
 * 播撒只发生在两个时机，读路径绝不「空就补」：
 *   1. ensureCollection 新建集合的同一事务内（创建即播撒，标志随 INSERT 写入）；
 *   2. scripts/dev/seed-baseline-categories.js 对存量空集合的一次性回填。
 */
const crypto = require("crypto");

const { getPack, normalizeNameKey } = require("./categoryPresets");

/** domain/direction → 基础包（spec 5.0：物资基础居家；支出完整包；收入 5 类基础包）。 */
const BASELINE_PACK_KEY = Object.freeze({
  "inventory": "inventory.home",
  "bills:expense": "bills.expense.full",
  "bills:income": "bills.income.basic"
});

function baselinePackFor(domain, direction) {
  const key = domain === "bills" ? `bills:${direction}` : domain;
  const packKey = BASELINE_PACK_KEY[key];
  return packKey ? getPack(packKey) : null;
}

function uuid() {
  return crypto.randomUUID();
}

/**
 * 在已有事务连接上播撒基础包（幂等）。
 * 跳过规则与 presets/apply 对齐：
 *   - 已有 preset_bindings（active 或 tombstone）→ 跳过，不复活墓碑；
 *   - 同名启用分类但无绑定（用户自建）→ 跳过，不按名称悄悄认领（spec 5.2）。
 * 不递增集合版本、不写 baseline_seeded_at 标志：由调用方按场景决定
 * （新建集合版本即播撒后状态；回填脚本对存量集合播撒后自行递增版本并写标志）。
 *
 * @returns {{pack: object, imported: Array, skipped: Array}}
 */
async function seedBaselinePack(conn, collection, actorUserId) {
  const pack = baselinePackFor(collection.domain, collection.direction || null);
  if (!pack) {
    throw new Error(`seedBaselinePack: 无基础包 domain=${collection.domain} direction=${collection.direction}`);
  }

  const [bindingRows] = await conn.execute(
    "SELECT entity_type, preset_key, status FROM preset_bindings WHERE collection_id = ?",
    [collection.id]
  );
  const boundKeys = new Set(bindingRows.map((b) => `${b.entity_type}:${b.preset_key}`));
  const [activeRows] = await conn.execute(
    "SELECT name_key FROM item_categories WHERE collection_id = ? AND status = 'active'",
    [collection.id]
  );
  const activeNameKeys = new Set(activeRows.map((c) => c.name_key));
  const [maxRows] = await conn.execute(
    "SELECT COALESCE(MAX(sort_order), -1) AS max_order FROM item_categories WHERE collection_id = ?",
    [collection.id]
  );
  let nextOrder = Number(maxRows[0].max_order) + 1;

  const imported = [];
  const skipped = [];
  for (const category of pack.categories) {
    if (boundKeys.has(`category:${category.presetKey}`)) {
      skipped.push({ presetKey: category.presetKey, reason: "already_bound" });
      continue;
    }
    const nameKey = normalizeNameKey(category.name);
    if (activeNameKeys.has(nameKey)) {
      skipped.push({ presetKey: category.presetKey, reason: "name_conflict" });
      continue;
    }
    const categoryId = uuid();
    await conn.execute(
      `INSERT INTO item_categories
         (id, collection_id, name, name_key, icon_type, icon_value, color, sort_order, status,
          source_pack_key, source_preset_key, source_pack_version, created_by)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'active', ?, ?, ?, ?)`,
      [
        categoryId, collection.id, category.name, nameKey,
        category.icon ? category.icon.type : null,
        category.icon ? category.icon.value : null,
        category.color, nextOrder,
        pack.packKey, category.presetKey, pack.packVersion, actorUserId
      ]
    );
    nextOrder += 1;
    activeNameKeys.add(nameKey);
    await conn.execute(
      `INSERT INTO preset_bindings (collection_id, entity_type, preset_key, entity_id, pack_key, pack_version, status)
       VALUES (?, 'category', ?, ?, ?, ?, 'active')`,
      [collection.id, category.presetKey, categoryId, pack.packKey, pack.packVersion]
    );
    await conn.execute(
      `INSERT INTO collection_audits
         (collection_id, actor_user_id, action, entity_type, entity_id, before_json, after_json)
       VALUES (?, ?, 'create', 'category', ?, NULL, ?)`,
      [
        collection.id,
        actorUserId,
        categoryId,
        JSON.stringify({ category: { categoryId, name: category.name }, presetKey: category.presetKey, seed: pack.packKey })
      ]
    );
    imported.push({ presetKey: category.presetKey, entityId: categoryId, name: category.name });
  }
  return { pack, imported, skipped };
}

module.exports = { baselinePackFor, seedBaselinePack };
