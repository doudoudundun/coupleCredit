/**
 * 家务首次上线前的关系初始化与发布门禁（不放宽运行时解绑 guard）。
 *
 * 默认只读检查：node --env-file=.env scripts/dev/initialize-housework-legacy.js
 * 指定检查：    上述命令 --relationship-id 123
 * 显式初始化：  上述命令 --apply --relationship-id 123 [--relationship-id 456]
 * --apply 必须指定关系 ID；禁止隐式全量回填。输出只含目标库和业务 ID，不含帐号资料/密钥。
 *
 * 门禁：默认检查 exit 0 才可启用新版 couple/housework 路由；needs_initialization 或 invalid 均 exit 1。
 * 在首次发布窗口人工确认无周期的关系属于旧版本后，才对 dry-run 列出的关系显式 --apply。
 * 有周期历史但无 open cycle 是损坏，永远不重开旧周期；事务整体回滚。
 * 缺失全部历史无法仅从现存行判断原因为 legacy 或损坏，所以本脚本不能作为线上自动修复任务。
 */
const mysql = require("mysql2/promise");
const { readConfig } = require("../../src/config");
const { ApiError } = require("../../src/errors");
const { withTransaction } = require("../../src/utils/transactions");
const { loadDisplayNames, ensureSharedSpaceForBootstrap } = require("../../src/utils/houseworkLifecycle");

function parseArguments(args) {
  let apply = false;
  const relationshipIds = [];
  for (let index = 0; index < args.length; index++) {
    if (args[index] === "--apply") apply = true;
    else if (args[index] === "--relationship-id") {
      const value = args[++index];
      if (!value || !/^[1-9]\d*$/.test(value) || !Number.isSafeInteger(Number(value))) {
        throw new Error("--relationship-id 必须是正整数");
      }
      relationshipIds.push(Number(value));
    } else throw new Error("未知参数: " + args[index]);
  }
  const ids = [...new Set(relationshipIds)].sort((a, b) => a - b);
  if (apply && ids.length === 0) throw new Error("--apply 必须显式指定 --relationship-id，先运行 dry-run");
  return { apply, relationshipIds: ids };
}

async function withLockedRelationship(pool, relationshipId, callback) {
  return withTransaction(pool, async (conn) => {
    const [found] = await conn.execute(
      "SELECT relationship_id, user_id_1, user_id_2, status FROM couple_relationships WHERE relationship_id = ?",
      [relationshipId]
    );
    if (!found.length) throw new ApiError(404, "RESOURCE_NOT_FOUND", "关系不存在");
    const ids = [Number(found[0].user_id_1), Number(found[0].user_id_2)].sort((a, b) => a - b);
    for (const userId of ids) {
      const [users] = await conn.execute("SELECT id FROM users WHERE id = ? FOR UPDATE", [userId]);
      if (!users.length) throw new ApiError(409, "SPACE_STATE_INVALID", "关系成员缺失");
    }
    const [locked] = await conn.execute(
      "SELECT relationship_id, user_id_1, user_id_2, status FROM couple_relationships WHERE relationship_id = ? FOR UPDATE",
      [relationshipId]
    );
    if (!locked.length || locked[0].status !== "active") {
      throw new ApiError(409, "RELATIONSHIP_CHANGED", "关系已变化，只初始化 active 关系");
    }
    if (!ids.includes(Number(locked[0].user_id_1)) || !ids.includes(Number(locked[0].user_id_2))) {
      throw new ApiError(409, "RELATIONSHIP_CHANGED", "关系成员已变化");
    }
    return callback(conn, locked[0]);
  });
}

async function inspectRelationship(pool, relationshipId) {
  return withLockedRelationship(pool, relationshipId, async (conn, relationship) => {
    const [cycles] = await conn.execute(
      "SELECT cycle_id FROM housework_relationship_cycles WHERE relationship_id = ? ORDER BY cycle_id FOR UPDATE",
      [relationshipId]
    );
    if (!cycles.length) return { relationshipId, state: "needs_initialization" };
    // 仅对有历史的行调用：此分支绝不会创建周期；缺 open/空间/成员直接拒绝。
    const existing = await ensureSharedSpaceForBootstrap(conn, relationship, new Map());
    return { relationshipId, state: "ready", cycleId: existing.cycleId, spaceId: existing.spaceId };
  });
}

async function initializeRelationship(pool, relationshipId) {
  return withLockedRelationship(pool, relationshipId, async (conn, relationship) => {
    const names = await loadDisplayNames(conn, [relationship.user_id_1, relationship.user_id_2]);
    const outcome = await ensureSharedSpaceForBootstrap(conn, relationship, names);
    return { relationshipId, state: "ready", ...outcome };
  });
}

async function run({ pool, relationshipIds, apply = false }) {
  let ids = relationshipIds;
  if (!ids.length) {
    const [rows] = await pool.execute(
      "SELECT relationship_id FROM couple_relationships WHERE status = 'active' ORDER BY relationship_id"
    );
    ids = rows.map((row) => Number(row.relationship_id));
  }
  const inspections = [];
  for (const relationshipId of ids) {
    try { inspections.push(await inspectRelationship(pool, relationshipId)); }
    catch (error) {
      inspections.push({ relationshipId, state: "invalid", code: error.code || "INITIALIZATION_FAILED", message: error.message });
    }
  }
  // 批次预检有损坏时，所有关系都不写，避免把部分成功误当发布成功。
  if (apply && !inspections.some((item) => item.state === "invalid")) {
    const results = [];
    for (const relationshipId of ids) {
      try { results.push(await initializeRelationship(pool, relationshipId)); }
      catch (error) {
        results.push({ relationshipId, state: "invalid", code: error.code || "INITIALIZATION_FAILED", message: error.message });
      }
    }
    return { mode: "apply", ready: results.every((item) => item.state === "ready"), relationships: results };
  }
  return { mode: apply ? "apply_blocked" : "dry_run", ready: inspections.every((item) => item.state === "ready"), relationships: inspections };
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  const config = readConfig();
  const pool = mysql.createPool({
    host: config.dbHost, port: config.dbPort, user: config.dbUser,
    password: config.dbPassword, database: config.dbName, connectionLimit: 2
  });
  try {
    const result = await run({ pool, ...options });
    console.log(JSON.stringify({ target: { host: config.dbHost, port: config.dbPort, database: config.dbName }, ...result }));
    process.exitCode = result.ready ? 0 : 1;
  } finally { await pool.end(); }
}
if (require.main === module) main().catch((error) => {
  console.error(JSON.stringify({ code: error.code || "INITIALIZATION_FAILED", message: error.message }));
  process.exitCode = 1;
});
module.exports = { parseArguments, inspectRelationship, initializeRelationship, run };

