/**
 * 家务基础预设免导入：空间创建/一次性回填时的基础包播撒 —— spec: docs/CATEGORY_PRESETS_SPEC_20261002.md §5.0。
 *
 * 三模块统一收口（物资/账单见 services/categorySeed.js）：播撒与 presets/apply 显式导入
 * 走同一写入形状（housework_categories.preset_category_key / housework_templates.preset_key
 * + housework_config_revisions），产物完全同构：可改名/换图标/排序/删除，
 * 归档（删除）后墓碑不复活，再次导入同一 key 按空间内唯一约束识别跳过（archivedConflict）。
 *
 * 基础包内容见 services/houseworkPresets.js 的 HOUSEWORK_BASELINE（唯一权威）。
 *
 * 播撒只发生在两个时机，读路径绝不「空就补」：
 *   1. houseworkLifecycle.createSpaceWithMembers 新建空间的同一事务内
 *      （创建即播撒，baseline_seeded_at 随 INSERT 写入，失败整体回滚）；
 *   2. scripts/dev/seed-baseline-housework.js 对存量无标志空间的一次性回填。
 *
 * closed 空间（解绑归档周期）永不播撒：本函数只被「新建空间」与「回填 active 空间」调用，
 * 回填脚本对 closed 空间只写标志。
 */
const crypto = require("crypto");

const { HOUSEWORK_BASELINE, PRESET_CATEGORY_BY_KEY, presetPackByTemplateKey } = require("./houseworkPresets");

function uuid() {
  return crypto.randomUUID();
}

/** 与 utils/houseworkLifecycle.normalizeName 同一规则（NFC + trim + 小写）；本地复制避免 utils↔services 循环依赖。 */
function normalizeName(value) {
  return String(value).normalize("NFC").trim().toLowerCase();
}

/** 配置审计行，形状与 routes/housework.js writeConfigRevision 的 create 分支一致。 */
async function writeSeedConfigRevision(conn, { spaceId, entityType, entityId, actorUserId, afterJson }) {
  await conn.execute(
    `INSERT INTO housework_config_revisions
       (space_id, entity_type, entity_id, actor_user_id, action, before_version, after_version, before_json, after_json)
     VALUES (?, ?, ?, ?, 'create', NULL, 1, NULL, ?)`,
    [spaceId, entityType, entityId, actorUserId, JSON.stringify(afterJson)]
  );
}

/**
 * 在已有事务连接上对一个空间播撒基础包（幂等）。
 * 跳过规则与 presets/apply 对齐，并在存量空间上更保守（绝不覆盖用户配置，spec §5.2）：
 *   - 分类：preset_category_key 已存在（含归档墓碑）→ 跳过，不复活；
 *     与用户自建启用分类同名（normalized_name 相同、无预设 key）→ 跳过，不按名称悄悄认领；
 *   - 模板：preset_key 已存在（含归档墓碑）→ 跳过，不复活；
 *     所属预设分类缺失（被跳过）或已归档 → 跳过（模板不硬塞进不可用分类）；
 *     同分类下已有同名启用模板（用户自建）→ 跳过，不产生重复。
 * 并发撞 (space_id, preset_*) 唯一键按已存在处理，不使整批失败（与 presets/apply 一致）。
 *
 * 不递增 space.revision、不写 baseline_seeded_at：由调用方按场景决定
 * （新建空间标志随 INSERT 写入且 revision 保持 0；回填脚本播撒后自行递增并写标志）。
 *
 * @returns {{imported: {categories: Array, templates: Array}, skipped: Array}}
 */
async function seedBaselinePackForSpace(conn, spaceId, actorUserId) {
  const [categoryRows] = await conn.execute(
    "SELECT category_id, preset_category_key, normalized_name, status, sort_order FROM housework_categories WHERE space_id = ?",
    [spaceId]
  );
  const categoryByKey = new Map();
  const activeCategoryNames = new Set();
  let maxCategoryOrder = -1;
  for (const row of categoryRows) {
    if (row.preset_category_key) categoryByKey.set(row.preset_category_key, row);
    if (row.status === "active") activeCategoryNames.add(row.normalized_name);
    if (Number(row.sort_order) > maxCategoryOrder) maxCategoryOrder = Number(row.sort_order);
  }

  const [templateRows] = await conn.execute(
    "SELECT preset_key, category_id, normalized_name, status, sort_order FROM housework_templates WHERE space_id = ?",
    [spaceId]
  );
  const templateKeys = new Set();
  const activeTemplateNamesByCategory = new Map();
  let maxTemplateOrder = -1;
  for (const row of templateRows) {
    if (row.preset_key) templateKeys.add(row.preset_key);
    if (row.status === "active") {
      const names = activeTemplateNamesByCategory.get(row.category_id) || new Set();
      names.add(row.normalized_name);
      activeTemplateNamesByCategory.set(row.category_id, names);
    }
    if (Number(row.sort_order) > maxTemplateOrder) maxTemplateOrder = Number(row.sort_order);
  }

  const imported = { categories: [], templates: [] };
  const skipped = [];
  // preset_category_key → 本次可挂载模板的 categoryId（已存在且启用，或本次新建）
  const mountableCategoryIds = new Map();

  for (const categoryKey of HOUSEWORK_BASELINE.presetCategoryKeys) {
    const pack = PRESET_CATEGORY_BY_KEY.get(categoryKey);
    if (!pack) throw new Error(`seedBaselinePackForSpace: 未知预设分类 ${categoryKey}`);
    const existing = categoryByKey.get(categoryKey);
    if (existing) {
      skipped.push({ presetCategoryKey: categoryKey, reason: "already_present" });
      if (existing.status === "active") mountableCategoryIds.set(categoryKey, existing.category_id);
      continue;
    }
    const nameKey = normalizeName(pack.name);
    if (activeCategoryNames.has(nameKey)) {
      skipped.push({ presetCategoryKey: categoryKey, reason: "name_conflict" });
      continue;
    }
    const categoryId = uuid();
    try {
      await conn.execute(
        `INSERT INTO housework_categories
           (category_id, space_id, name, normalized_name, icon_json, color, sort_order, is_fallback, status, preset_category_key)
         VALUES (?, ?, ?, ?, ?, ?, ?, 0, 'active', ?)`,
        [categoryId, spaceId, pack.name, nameKey, JSON.stringify(pack.icon), pack.color, maxCategoryOrder + 1, categoryKey]
      );
    } catch (error) {
      if (error && error.code === "ER_DUP_ENTRY") {
        // 并发播撒撞 (space_id, preset_category_key) 唯一键：按已存在处理
        skipped.push({ presetCategoryKey: categoryKey, reason: "already_present" });
        continue;
      }
      throw error;
    }
    maxCategoryOrder += 1;
    activeCategoryNames.add(nameKey);
    mountableCategoryIds.set(categoryKey, categoryId);
    await writeSeedConfigRevision(conn, {
      spaceId,
      entityType: "category",
      entityId: categoryId,
      actorUserId,
      afterJson: { presetCategoryKey: categoryKey, seed: "baseline" }
    });
    imported.categories.push({ presetCategoryKey: categoryKey, categoryId, name: pack.name });
  }

  for (const templateKey of HOUSEWORK_BASELINE.templateKeys) {
    const pack = presetPackByTemplateKey(templateKey);
    if (!pack) throw new Error(`seedBaselinePackForSpace: 未知推荐项 ${templateKey}`);
    const preset = pack.templates.find((t) => t.presetKey === templateKey);
    if (templateKeys.has(templateKey)) {
      skipped.push({ presetKey: templateKey, reason: "already_present" });
      continue;
    }
    const categoryId = mountableCategoryIds.get(pack.presetCategoryKey);
    if (!categoryId) {
      skipped.push({ presetKey: templateKey, reason: "category_unavailable" });
      continue;
    }
    const nameKey = normalizeName(preset.name);
    const takenNames = activeTemplateNamesByCategory.get(categoryId);
    if (takenNames && takenNames.has(nameKey)) {
      skipped.push({ presetKey: templateKey, reason: "name_conflict" });
      continue;
    }
    const templateId = uuid();
    try {
      await conn.execute(
        `INSERT INTO housework_templates
           (template_id, space_id, category_id, name, normalized_name, description, icon_json, color,
            sort_order, measure_mode, unit, default_quantity, duration_enabled, default_duration_minutes,
            weight, fields_json, status, preset_key)
         VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', ?)`,
        [
          templateId,
          spaceId,
          categoryId,
          preset.name,
          nameKey,
          preset.description || null,
          pack.color,
          maxTemplateOrder + 1,
          preset.measureMode,
          preset.measureMode === "event" ? "次" : preset.unit,
          preset.measureMode === "event" ? "1.00" : (preset.defaultQuantity || null),
          preset.durationEnabled ? 1 : 0,
          preset.defaultDurationMinutes || null,
          preset.weight,
          JSON.stringify(preset.fields || []),
          templateKey
        ]
      );
    } catch (error) {
      if (error && error.code === "ER_DUP_ENTRY") {
        // 并发播撒撞 (space_id, preset_key) 唯一键：按已存在处理
        skipped.push({ presetKey: templateKey, reason: "already_present" });
        continue;
      }
      throw error;
    }
    maxTemplateOrder += 1;
    templateKeys.add(templateKey);
    const names = activeTemplateNamesByCategory.get(categoryId) || new Set();
    names.add(nameKey);
    activeTemplateNamesByCategory.set(categoryId, names);
    await writeSeedConfigRevision(conn, {
      spaceId,
      entityType: "template",
      entityId: templateId,
      actorUserId,
      afterJson: { presetKey: templateKey, seed: "baseline" }
    });
    imported.templates.push({ presetKey: templateKey, templateId, name: preset.name });
  }

  return { imported, skipped };
}

module.exports = { seedBaselinePackForSpace };
