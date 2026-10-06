/**
 * 基础预设一次性回填（spec: docs/CATEGORY_PRESETS_SPEC_20261002.md 第 5.0/9 节）：
 * 对迁移 032 之前已存在、从未播撒过的存量集合补播基础预设包。
 *
 * 用法：
 *   node scripts/dev/seed-baseline-categories.js            # dry-run：只输出统计清单，不写库
 *   node scripts/dev/seed-baseline-categories.js --apply    # 实际回填
 *
 * 规则：
 *   - 只处理 baseline_seeded_at IS NULL 的集合（新集合创建时已播撒并写标志，不参与）；
 *   - 集合当前没有任何分类（含归档行）→ 播撒对应基础包，写标志，递增集合版本；
 *   - 集合已有分类 → 只写标志不播撒：它已被初始化过（回填/手动导入/自建），
 *     用户日后删光分类也不能被本脚本复活；
 *   - 播撒与 presets/apply 同构（source_* + preset_bindings + 审计，见 services/categorySeed.js）。
 *
 * 幂等：以 baseline_seeded_at 为唯一闸门，重复执行不再播撒、不重复写分类。
 */
const path = require("path");

const { baselinePackFor, seedBaselinePack } = require("../../src/services/categorySeed");

async function runBaselineSeed({ pool, apply = false, log = console.log } = {}) {
  const stats = {
    apply,
    total: 0,
    seeded: 0,
    markedOnly: 0,
    categoriesCreated: 0,
    details: []
  };

  const [collections] = await pool.execute(
    "SELECT * FROM category_collections WHERE baseline_seeded_at IS NULL ORDER BY created_at ASC, id ASC"
  );
  stats.total = collections.length;

  for (const collection of collections) {
    const pack = baselinePackFor(collection.domain, collection.direction || null);
    const scopeDesc = collection.scope === "personal"
      ? `个人 user=${collection.owner_user_id}`
      : `共享 rel=${collection.relationship_id}`;
    const label = `[${collection.domain}${collection.direction ? `/${collection.direction}` : ""}] ${scopeDesc} ${collection.id}`;

    const [countRows] = await pool.execute(
      "SELECT COUNT(*) AS total FROM item_categories WHERE collection_id = ?",
      [collection.id]
    );
    const categoryCount = Number(countRows[0].total);

    if (categoryCount > 0) {
      stats.markedOnly += 1;
      stats.details.push({ collectionId: collection.id, action: "mark_only", categoryCount });
      log(`  跳过播撒（已有 ${categoryCount} 个分类，仅写标志）: ${label}`);
      if (apply) {
        await pool.execute(
          "UPDATE category_collections SET baseline_seeded_at = NOW(3) WHERE id = ? AND baseline_seeded_at IS NULL",
          [collection.id]
        );
      }
      continue;
    }

    stats.seeded += 1;
    stats.details.push({ collectionId: collection.id, action: "seed", packKey: pack.packKey, categories: pack.categories.length });
    log(`  播撒 ${pack.packKey}（${pack.categories.length} 类）: ${label}`);
    if (apply) {
      // 播撒 + 标志 + 版本递增同一事务；actor 记集合属主（共享集合记关系 user_id_1，缺省 0 仅审计展示）
      const conn = await pool.getConnection();
      try {
        await conn.beginTransaction();
        let actorUserId = collection.owner_user_id;
        if (actorUserId === null || actorUserId === undefined) {
          const [relRows] = await conn.execute(
            "SELECT user_id_1 FROM couple_relationships WHERE relationship_id = ?",
            [collection.relationship_id]
          );
          actorUserId = relRows[0] ? relRows[0].user_id_1 : 0;
        }
        const result = await seedBaselinePack(conn, collection, actorUserId);
        stats.categoriesCreated += result.imported.length;
        // 与 API 路径一致：新增分类递增集合版本，使排序冲突可检测（spec 7）
        await conn.execute("UPDATE category_collections SET version = version + 1 WHERE id = ?", [collection.id]);
        await conn.execute(
          "UPDATE category_collections SET baseline_seeded_at = NOW(3) WHERE id = ? AND baseline_seeded_at IS NULL",
          [collection.id]
        );
        await conn.commit();
      } catch (error) {
        await conn.rollback();
        throw error;
      } finally {
        conn.release();
      }
    }
  }

  log(`\n== 基础预设回填统计 ==`);
  log(`  待处理集合（baseline_seeded_at IS NULL）: ${stats.total}`);
  log(`  播撒: ${stats.seeded}（新建分类 ${stats.categoriesCreated}），仅写标志: ${stats.markedOnly}`);
  if (!apply) {
    log("\n(dry-run，未写库；加 --apply 执行回填)");
  }
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
  runBaselineSeed({ pool, apply: process.argv.includes("--apply") })
    .then(() => pool.end())
    .then(() => process.exit(0))
    .catch((error) => {
      console.error(error);
      process.exit(1);
    });
}

module.exports = { runBaselineSeed };
