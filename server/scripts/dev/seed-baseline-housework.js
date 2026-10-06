/**
 * 家务基础预设一次性回填（spec: docs/CATEGORY_PRESETS_SPEC_20261002.md 第 5.0/9 节）：
 * 对迁移 033 之前已存在、从未播撒过的存量家务空间补播基础包（services/houseworkSeed.js）。
 *
 * 用法：
 *   node scripts/dev/seed-baseline-housework.js            # dry-run：只输出统计清单，不写库
 *   node scripts/dev/seed-baseline-housework.js --apply    # 实际回填
 *
 * 规则：
 *   - 只处理 baseline_seeded_at IS NULL 的空间（新空间创建时已播撒并写标志，不参与）；
 *   - closed 空间（解绑归档周期，只读）只写标志，绝不播撒；
 *   - active 空间合并播撒：与 presets/apply 同构（preset_*_key + 配置审计），
 *     跳过已存在项（含归档墓碑，绝不复活用户删过的内容）与同名冲突项
 *     （用户自建同名分类/模板优先，不覆盖用户配置），详见 services/houseworkSeed.js；
 *   - 播撒有实际新增时 space.revision + 1（客户端据此刷新配置），无新增只写标志。
 *
 * 幂等：以 baseline_seeded_at 为唯一闸门，重复执行不再播撒、不重复写分类/模板。
 * 与 scripts/dev/seed-baseline-categories.js 的差异：物资/账单存量集合「有分类即只写标志」，
 * 家务存量空间则合并播撒缺失的基础项 —— 家务空间普遍只有兜底分类 + 少量自建项，
 * 不合并会让存量用户永远拿不到基础包（家务没有「空集合即未初始化」的可靠信号）。
 */
const path = require("path");

const { seedBaselinePackForSpace } = require("../../src/services/houseworkSeed");

async function runBaselineSeed({ pool, apply = false, log = console.log } = {}) {
  const stats = {
    apply,
    total: 0,
    seeded: 0,
    markedOnly: 0,
    categoriesCreated: 0,
    templatesCreated: 0,
    details: []
  };

  const [spaces] = await pool.execute(
    `SELECT s.*, c.member_user_id_1 AS cycle_member_1
     FROM housework_spaces s
     LEFT JOIN housework_relationship_cycles c ON c.cycle_id = s.cycle_id
     WHERE s.baseline_seeded_at IS NULL
     ORDER BY s.created_at ASC, s.space_id ASC`
  );
  stats.total = spaces.length;

  for (const space of spaces) {
    const scopeDesc = space.scope === "personal"
      ? `个人 user=${space.owner_user_id}`
      : `共享 cycle=${space.cycle_id}`;
    const label = `[${space.scope}/${space.status}] ${scopeDesc} ${space.space_id}`;

    // closed 空间只读：只写标志关闭闸门，绝不播撒（否则回填会改动归档周期数据）
    if (space.status !== "active") {
      stats.markedOnly += 1;
      stats.details.push({ spaceId: space.space_id, action: "mark_only", status: space.status });
      log(`  跳过播撒（${space.status} 空间只读，仅写标志）: ${label}`);
      if (apply) {
        await pool.execute(
          "UPDATE housework_spaces SET baseline_seeded_at = NOW(3) WHERE space_id = ? AND baseline_seeded_at IS NULL",
          [space.space_id]
        );
      }
      continue;
    }

    // 审计 actor：个人空间记 owner；共享空间记 cycle 首成员；历史行缺失时退 0（仅审计展示）
    const actorUserId = space.scope === "personal"
      ? space.owner_user_id
      : (space.cycle_member_1 === null || space.cycle_member_1 === undefined ? 0 : space.cycle_member_1);

    if (apply) {
      // 播撒 + 标志 + revision 递增同一事务；失败整体回滚，不留半播撒状态
      const conn = await pool.getConnection();
      try {
        await conn.beginTransaction();
        const result = await seedBaselinePackForSpace(conn, space.space_id, actorUserId);
        const changed = result.imported.categories.length + result.imported.templates.length;
        if (changed > 0) {
          // 与 API 写路径一致：配置变更递增 revision，让客户端刷新配置缓存
          await conn.execute(
            "UPDATE housework_spaces SET revision = revision + 1 WHERE space_id = ?",
            [space.space_id]
          );
        }
        await conn.execute(
          "UPDATE housework_spaces SET baseline_seeded_at = NOW(3) WHERE space_id = ? AND baseline_seeded_at IS NULL",
          [space.space_id]
        );
        await conn.commit();
        stats.seeded += 1;
        stats.categoriesCreated += result.imported.categories.length;
        stats.templatesCreated += result.imported.templates.length;
        stats.details.push({
          spaceId: space.space_id,
          action: "seed",
          categories: result.imported.categories.length,
          templates: result.imported.templates.length,
          skipped: result.skipped.length
        });
        log(`  播撒（分类 ${result.imported.categories.length} + 模板 ${result.imported.templates.length}，跳过 ${result.skipped.length}）: ${label}`);
      } catch (error) {
        await conn.rollback();
        throw error;
      } finally {
        conn.release();
      }
    } else {
      stats.seeded += 1;
      stats.details.push({ spaceId: space.space_id, action: "seed_dry_run" });
      log(`  将播撒基础包（dry-run）: ${label}`);
    }
  }

  log(`\n== 家务基础预设回填统计 ==`);
  log(`  待处理空间（baseline_seeded_at IS NULL）: ${stats.total}`);
  log(`  播撒: ${stats.seeded}（新建分类 ${stats.categoriesCreated}、模板 ${stats.templatesCreated}），仅写标志: ${stats.markedOnly}`);
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
