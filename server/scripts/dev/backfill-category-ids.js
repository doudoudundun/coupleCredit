/**
 * 迁移 032 等效脚本：bills.type / inventory.category 历史字符串分类 → category_id 回填
 * （spec: docs/CATEGORY_PRESETS_SPEC_20261002.md 第 9 节）。
 *
 * 用法：
 *   node scripts/dev/backfill-category-ids.js            # dry-run：只输出统计清单，不写库
 *   node scripts/dev/backfill-category-ids.js --apply    # 实际回填
 *
 * 规则（spec 9）：
 *   - 按真实数据所属范围分别建分类：relationship_id 非空 → 该关系共享集合，否则 → 本人个人集合；
 *   - 账单按 income_type 分方向（0=expense / 1=income）；物资 direction 为 NULL；
 *   - 同集合内相同规范化名称（NFC + 小写 + 空白折叠）只合并为一个分类；
 *   - 映射不到的行（空白名、名称超过 20 字符、非法收支值、孤立关系）保持 category_id 为 NULL，
 *     由前端「未分类」兜底显示，不强行塞进兜底分类；
 *   - 排序首次迁移采用确定顺序：账单已知分类按预设包数组顺序，其余按规范化名称；
 *     物资按首次出现时间（MIN(created_at)），其次最小 inventory_id；
 *   - 名称命中预设注册表（同 domain+direction）时写入 source_* 并建立 preset_binding，
 *     使后续预设导入识别为 already_imported 而不是 name_conflict（spec 5.2 / 9）。
 *
 * 幂等：只处理 category_id IS NULL 的行；分类按 (collection_id, name_key) 复用，
 * 重复执行不新增分类、不重复改写已回填行。
 */
const path = require("path");
const crypto = require("crypto");

const { PACKS, normalizeNameKey } = require("../../src/services/categoryPresets");

const MAX_NAME_CODEPOINTS = 20;

function codePointLength(value) {
  return [...String(value)].length;
}

function uuid() {
  return crypto.randomUUID();
}

/** domain+direction+nameKey → 预设分类（账单支出优先 full 完整包，其余按注册表顺序先到先得）。 */
function buildPresetLookup() {
  const lookup = new Map();
  const ordered = [...PACKS].sort((a, b) => {
    if (a.packKey === "bills.expense.full") return -1;
    if (b.packKey === "bills.expense.full") return 1;
    return 0;
  });
  for (const pack of ordered) {
    for (const category of pack.categories) {
      const key = `${pack.domain}|${pack.direction || ""}|${normalizeNameKey(category.name)}`;
      if (!lookup.has(key)) {
        lookup.set(key, { pack, category });
      }
    }
  }
  return lookup;
}

/** 与 src/routes/categoryCollections.js 的惰性创建逻辑一致：先查后建，撞唯一键重读。 */
async function ensureCollection(conn, { domain, direction, scope, ownerUserId, relationshipId }) {
  const directionClause = direction === null ? "direction IS NULL" : "direction = ?";
  const selectSql = scope === "personal"
    ? `SELECT * FROM category_collections WHERE domain = ? AND scope = 'personal' AND owner_user_id = ? AND ${directionClause} LIMIT 1`
    : `SELECT * FROM category_collections WHERE domain = ? AND scope = 'couple' AND relationship_id = ? AND ${directionClause} LIMIT 1`;
  const baseParams = scope === "personal" ? [domain, ownerUserId] : [domain, relationshipId];
  const selectParams = direction === null ? baseParams : [...baseParams, direction];

  let [rows] = await conn.execute(selectSql, selectParams);
  if (rows.length === 0) {
    try {
      await conn.execute(
        `INSERT INTO category_collections (id, domain, direction, scope, owner_user_id, relationship_id, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, NOW(3), NOW(3))`,
        [uuid(), domain, direction, scope, scope === "personal" ? ownerUserId : null, scope === "couple" ? relationshipId : null]
      );
    } catch (error) {
      if (!error || error.code !== "ER_DUP_ENTRY") throw error;
    }
    [rows] = await conn.execute(selectSql, selectParams);
  }
  return rows[0];
}

async function getOrCreateCategory(conn, { collection, nameKey, displayName, sortOrder, createdBy, presetMatch, log }) {
  const [existing] = await conn.execute(
    "SELECT * FROM item_categories WHERE collection_id = ? AND name_key = ? AND status = 'active' LIMIT 1",
    [collection.id, nameKey]
  );
  if (existing.length > 0) {
    return { category: existing[0], created: false };
  }
  const categoryId = uuid();
  const icon = presetMatch ? presetMatch.category.icon : null;
  const color = presetMatch ? presetMatch.category.color : null;
  await conn.execute(
    `INSERT INTO item_categories
       (id, collection_id, name, name_key, icon_type, icon_value, color, sort_order, status,
        source_pack_key, source_preset_key, source_pack_version, created_by)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'active', ?, ?, ?, ?)`,
    [
      categoryId, collection.id, displayName, nameKey,
      icon ? icon.type : null, icon ? icon.value : null, color, sortOrder,
      presetMatch ? presetMatch.pack.packKey : null,
      presetMatch ? presetMatch.category.presetKey : null,
      presetMatch ? presetMatch.pack.packVersion : null,
      createdBy
    ]
  );
  if (presetMatch) {
    // 绑定是预设身份唯一权威；已有绑定（被显式映射到别的实体）时不抢绑
    const [bindings] = await conn.execute(
      "SELECT id FROM preset_bindings WHERE collection_id = ? AND entity_type = 'category' AND preset_key = ?",
      [collection.id, presetMatch.category.presetKey]
    );
    if (bindings.length === 0) {
      await conn.execute(
        `INSERT INTO preset_bindings (collection_id, entity_type, preset_key, entity_id, pack_key, pack_version, status)
         VALUES (?, 'category', ?, ?, ?, ?, 'active')`,
        [collection.id, presetMatch.category.presetKey, categoryId, presetMatch.pack.packKey, presetMatch.pack.packVersion]
      );
    }
  }
  const [created] = await conn.execute(
    "SELECT * FROM item_categories WHERE id = ?",
    [categoryId]
  );
  if (log) log(`    + 新建分类「${displayName}」(${categoryId})${presetMatch ? ` ← 预设 ${presetMatch.category.presetKey}` : ""}`);
  return { category: created[0], created: true };
}

/**
 * 收集可映射分组并按范围聚合到集合。
 * 返回 { collections: Map<scopeKey, bucket>, unmappable: [...] }。
 */
async function planGroups(conn, { domain, groups, presetLookup, directionOf, orderNames, log }) {
  const buckets = new Map();
  const unmappable = [];
  const relationshipCache = new Map();

  for (const group of groups) {
    const displayName = group.legacy_name;
    const fail = (reason) => unmappable.push({ ...group, reason });
    if (!displayName) {
      fail("blank_name");
      continue;
    }
    if (codePointLength(displayName) > MAX_NAME_CODEPOINTS) {
      fail("name_too_long");
      continue;
    }
    const direction = directionOf(group);
    if (direction === undefined) {
      fail("invalid_income_type");
      continue;
    }
    const scope = group.relationship_id === null ? "personal" : "couple";
    let createdBy = group.user_id;
    if (scope === "couple") {
      if (!relationshipCache.has(group.relationship_id)) {
        const [relRows] = await conn.execute(
          "SELECT relationship_id, user_id_1 FROM couple_relationships WHERE relationship_id = ?",
          [group.relationship_id]
        );
        relationshipCache.set(group.relationship_id, relRows[0] || null);
      }
      const relationship = relationshipCache.get(group.relationship_id);
      if (!relationship) {
        fail("orphan_relationship");
        continue;
      }
      createdBy = relationship.user_id_1;
    }
    const scopeKey = `${domain}|${direction || ""}|${scope}|${scope === "personal" ? group.user_id : group.relationship_id}`;
    if (!buckets.has(scopeKey)) {
      buckets.set(scopeKey, {
        domain,
        direction,
        scope,
        ownerUserId: scope === "personal" ? group.user_id : null,
        relationshipId: scope === "couple" ? group.relationship_id : null,
        createdBy,
        names: new Map()
      });
    }
    const bucket = buckets.get(scopeKey);
    const nameKey = normalizeNameKey(displayName);
    // 相同规范化名称只在同集合内合并（spec 9）
    if (!bucket.names.has(nameKey)) {
      bucket.names.set(nameKey, {
        nameKey,
        displayName,
        firstSeen: group.first_seen || null,
        firstId: group.first_id || null,
        count: 0,
        presetMatch: presetLookup.get(`${domain}|${direction || ""}|${nameKey}`) || null,
        groups: []
      });
    }
    const entry = bucket.names.get(nameKey);
    entry.count += Number(group.cnt);
    entry.groups.push(group);
    if (group.first_seen && (!entry.firstSeen || group.first_seen < entry.first_seen)) entry.firstSeen = group.first_seen;
    if (group.first_id && (!entry.firstId || group.first_id < entry.first_id)) entry.firstId = group.first_id;
  }

  for (const bucket of buckets.values()) {
    bucket.orderedNames = orderNames([...bucket.names.values()], bucket);
  }
  return { buckets, unmappable };
}

async function runBackfill({ pool, apply = false, log = console.log } = {}) {
  const presetLookup = buildPresetLookup();
  const stats = {
    apply,
    bills: { totalNullRows: 0, mappedRows: 0, unmappableRows: 0, categoriesCreated: 0, collections: 0 },
    inventory: { totalNullRows: 0, mappedRows: 0, unmappableRows: 0, categoriesCreated: 0, collections: 0 },
    unmappable: []
  };

  const [billGroups] = await pool.execute(
    `SELECT relationship_id, user_id, income_type, TRIM(type) AS legacy_name, COUNT(*) AS cnt
     FROM bills WHERE category_id IS NULL
     GROUP BY relationship_id, user_id, income_type, legacy_name`
  );
  const [inventoryGroups] = await pool.execute(
    `SELECT relationship_id, user_id, TRIM(category) AS legacy_name, COUNT(*) AS cnt,
            MIN(created_at) AS first_seen, MIN(inventory_id) AS first_id
     FROM inventory WHERE category_id IS NULL
     GROUP BY relationship_id, user_id, legacy_name`
  );

  const billsPlan = await planGroups(pool, {
    domain: "bills",
    groups: billGroups,
    presetLookup,
    directionOf: (group) => (Number(group.income_type) === 1 ? "income" : Number(group.income_type) === 0 ? "expense" : undefined),
    // 账单：预设包数组顺序优先，其余按规范化名称（spec 9）
    orderNames: (names) => [...names].sort((a, b) => {
      const ai = a.presetMatch ? a.presetMatch.pack.categories.indexOf(a.presetMatch.category) : Number.MAX_SAFE_INTEGER;
      const bi = b.presetMatch ? b.presetMatch.pack.categories.indexOf(b.presetMatch.category) : Number.MAX_SAFE_INTEGER;
      if (ai !== bi) return ai - bi;
      return a.nameKey < b.nameKey ? -1 : a.nameKey > b.nameKey ? 1 : 0;
    }),
    log
  });
  const inventoryPlan = await planGroups(pool, {
    domain: "inventory",
    groups: inventoryGroups,
    presetLookup,
    directionOf: () => null,
    // 物资：按首次出现时间，其次最小 ID（spec 9）
    orderNames: (names) => [...names].sort((a, b) => {
      const at = a.firstSeen ? new Date(a.firstSeen).getTime() : 0;
      const bt = b.firstSeen ? new Date(b.firstSeen).getTime() : 0;
      if (at !== bt) return at - bt;
      if ((a.firstId || 0) !== (b.firstId || 0)) return (a.firstId || 0) - (b.firstId || 0);
      return a.nameKey < b.nameKey ? -1 : 1;
    }),
    log
  });

  const report = (label, plan, statBlock) => {
    const nullRows = plan === billsPlan
      ? billGroups.reduce((sum, g) => sum + Number(g.cnt), 0)
      : inventoryGroups.reduce((sum, g) => sum + Number(g.cnt), 0);
    statBlock.totalNullRows = nullRows;
    statBlock.unmappableRows = plan.unmappable.reduce((sum, g) => sum + Number(g.cnt), 0);
    statBlock.mappedRows = nullRows - statBlock.unmappableRows;
    statBlock.collections = plan.buckets.size;
    log(`\n== ${label} 统计 ==`);
    log(`  category_id 为 NULL 的行: ${nullRows}（可映射 ${statBlock.mappedRows}，不可映射 ${statBlock.unmappableRows}）`);
    log(`  涉及集合: ${plan.buckets.size}`);
    for (const bucket of plan.buckets.values()) {
      const scopeDesc = bucket.scope === "personal" ? `个人 user=${bucket.ownerUserId}` : `共享 rel=${bucket.relationshipId}`;
      log(`  [${bucket.domain}${bucket.direction ? `/${bucket.direction}` : ""}] ${scopeDesc}:`);
      for (const entry of bucket.orderedNames) {
        log(`    「${entry.displayName}」×${entry.count}${entry.presetMatch ? ` (预设 ${entry.presetMatch.category.presetKey})` : ""}`);
      }
    }
    for (const item of plan.unmappable) {
      log(`  [不可映射:${item.reason}] 「${item.legacy_name}」×${item.cnt} user=${item.user_id} rel=${item.relationship_id}${item.income_type !== undefined ? ` income_type=${item.income_type}` : ""}`);
      stats.unmappable.push({ domain: label, ...item });
    }
  };
  report("bills", billsPlan, stats.bills);
  report("inventory", inventoryPlan, stats.inventory);

  if (!apply) {
    log("\n(dry-run，未写库；加 --apply 执行回填)");
    return stats;
  }

  const applyPlan = async (plan, statBlock, updateRows) => {
    for (const bucket of plan.buckets.values()) {
      const conn = await pool.getConnection();
      try {
        await conn.beginTransaction();
        const collection = await ensureCollection(conn, bucket);
        const [maxRows] = await conn.execute(
          "SELECT COALESCE(MAX(sort_order), -1) AS max_order FROM item_categories WHERE collection_id = ?",
          [collection.id]
        );
        let nextOrder = Number(maxRows[0].max_order) + 1;
        let createdAny = false;
        for (const entry of bucket.orderedNames) {
          const { category, created } = await getOrCreateCategory(conn, {
            collection,
            nameKey: entry.nameKey,
            displayName: entry.displayName,
            sortOrder: nextOrder,
            createdBy: bucket.createdBy,
            presetMatch: entry.presetMatch,
            log
          });
          if (created) {
            createdAny = true;
            nextOrder += 1;
            statBlock.categoriesCreated += 1;
          }
          for (const group of entry.groups) {
            await updateRows(conn, category, group);
          }
        }
        if (createdAny) {
          // 与 API 路径一致：新增分类递增集合版本，使排序冲突可检测（spec 7）
          await conn.execute("UPDATE category_collections SET version = version + 1 WHERE id = ?", [collection.id]);
        }
        await conn.commit();
      } catch (error) {
        await conn.rollback();
        throw error;
      } finally {
        conn.release();
      }
    }
  };

  await applyPlan(billsPlan, stats.bills, (conn, category, group) => conn.execute(
    `UPDATE bills SET category_id = ?, category_name_snapshot = ?, category_icon_snapshot = ?
     WHERE category_id IS NULL AND relationship_id <=> ? AND user_id = ? AND income_type = ? AND TRIM(type) = ?`,
    [category.id, category.name, category.icon_value, group.relationship_id, group.user_id, group.income_type, group.legacy_name]
  ));
  await applyPlan(inventoryPlan, stats.inventory, (conn, category, group) => conn.execute(
    `UPDATE inventory SET category_id = ?
     WHERE category_id IS NULL AND relationship_id <=> ? AND user_id = ? AND TRIM(category) = ?`,
    [category.id, group.relationship_id, group.user_id, group.legacy_name]
  ));

  log(`\n== 回填完成：bills 新建分类 ${stats.bills.categoriesCreated}，inventory 新建分类 ${stats.inventory.categoriesCreated} ==`);
  return stats;
}

if (require.main === module) {
  // 与 scripts/dev/run-migration.js 相同的 .env 加载方式
  const fs = require("fs");
  const envPath = path.resolve(__dirname, "../../.env");
  if (fs.existsSync(envPath)) {
    for (const line of fs.readFileSync(envPath, "utf8").split(/\r?\n/)) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith("#")) continue;
      const index = trimmed.indexOf("=");
      if (index <= 0) continue;
      const key = trimmed.slice(0, index).trim();
      if (process.env[key] === undefined) process.env[key] = trimmed.slice(index + 1).trim();
    }
  }
  const { readConfig } = require("../../src/config");
  const { createPool } = require("../../src/db");
  const pool = createPool(readConfig());
  runBackfill({ pool, apply: process.argv.includes("--apply") })
    .then(() => pool.end())
    .then(() => process.exit(0))
    .catch((error) => {
      console.error(error);
      process.exit(1);
    });
}

module.exports = { runBackfill };
