/**
 * 家务（housework）关系生命周期接入。
 *
 * 由 couple.js 的 bind/unbind 与 housework 路由的 bootstrap 共用。
 * 所有函数都接收调用方事务里已有的 conn，绝不自行开事务、绝不使用带 TTL 的关系缓存：
 * 关系状态、家务周期与空间冻结必须在同一数据库事务内完成（spec 第 9 节）。
 *
 * 不变量：
 *   - 每次 dissolved → active（或首次 INSERT）都在绑定事务内生成新 cycle + 新 space + 两名成员；
 *   - 旧 cycle / 旧 space 永不重开；closed 空间保留原成员只读；
 *   - 每个 relationshipId 最多一个未结束 cycle（DB 唯一键 uk_hw_cycles_relationship_open 兜底）；
 *   - 新建空间在同一事务内播撒基础预设包并写 baseline_seeded_at（spec §5.0，见 services/houseworkSeed.js）；
 *     closed/存量空间不由本模块补播，存量回填走 scripts/dev/seed-baseline-housework.js。
 */
const crypto = require("crypto");

const { ApiError } = require("../errors");
const { seedBaselinePackForSpace } = require("../services/houseworkSeed");

const FALLBACK_CATEGORY_NAME = "未分类";
const FALLBACK_CATEGORY_ICON = { type: "iconKey", value: "folder" };
const FALLBACK_CATEGORY_COLOR = "#8A8F99";
const DEFAULT_SETTINGS_JSON = JSON.stringify({
  showDurationStatistics: false,
  showWorkloadStatistics: false
});

function uuid() {
  return crypto.randomUUID();
}

function normalizeName(value) {
  return String(value).normalize("NFC").trim().toLowerCase();
}

function sameMemberSet(a, b) {
  if (a.size !== b.size) return false;
  for (const id of a) if (!b.has(id)) return false;
  return true;
}

function pickDisplayName(userRow) {
  const raw = userRow.nickname || userRow.username || String(userRow.id);
  return String(raw).trim().slice(0, 80) || String(userRow.id);
}

async function loadDisplayNames(conn, userIds) {
  const unique = [...new Set(userIds.map(Number))];
  const placeholders = unique.map(() => "?").join(", ");
  const [rows] = await conn.execute(
    `SELECT id, username, nickname FROM users WHERE id IN (${placeholders})`,
    unique
  );
  const byId = new Map();
  for (const row of rows) byId.set(Number(row.id), pickDisplayName(row));
  return byId;
}

async function ensureFallbackCategory(conn, spaceId) {
  const [rows] = await conn.execute(
    "SELECT category_id FROM housework_categories WHERE space_id = ? AND is_fallback = 1 LIMIT 1",
    [spaceId]
  );
  if (rows.length > 0) return rows[0].category_id;
  const categoryId = uuid();
  await conn.execute(
    `INSERT INTO housework_categories
       (category_id, space_id, name, normalized_name, icon_json, color, sort_order, is_fallback)
     VALUES (?, ?, ?, ?, ?, ?, 0, 1)`,
    [
      categoryId,
      spaceId,
      FALLBACK_CATEGORY_NAME,
      normalizeName(FALLBACK_CATEGORY_NAME),
      JSON.stringify(FALLBACK_CATEGORY_ICON),
      FALLBACK_CATEGORY_COLOR
    ]
  );
  return categoryId;
}

async function createSpaceWithMembers(conn, { scope, ownerUserId, cycleId, members }) {
  const spaceId = uuid();
  // 创建即播撒（spec: CATEGORY_PRESETS_SPEC §5.0，三模块统一收口）：baseline_seeded_at 随
  // INSERT 写 NOW(3)，同事务播撒基础包；播撒失败整个事务回滚，空间不会以未播撒状态落库。
  // 只在新建时播撒这一次，读路径绝不「空就补」；closed 空间永不进入本函数
  // （旧空间解绑时只冻结不删，重绑走新建才有新空间）。
  await conn.execute(
    `INSERT INTO housework_spaces
       (space_id, scope, owner_user_id, cycle_id, status, timezone, revision, version, settings_json, baseline_seeded_at)
     VALUES (?, ?, ?, ?, 'active', 'Asia/Shanghai', 0, 1, ?, NOW(3))`,
    [spaceId, scope, ownerUserId, cycleId, DEFAULT_SETTINGS_JSON]
  );
  for (const member of members) {
    await conn.execute(
      "INSERT INTO housework_space_members (space_id, user_id, display_name_snapshot) VALUES (?, ?, ?)",
      [spaceId, member.userId, member.displayName]
    );
  }
  await ensureFallbackCategory(conn, spaceId);
  // 审计 actor：个人空间记 owner；共享空间 owner 为 NULL，记 cycle 首成员（与回填脚本口径一致）。
  await seedBaselinePackForSpace(
    conn,
    spaceId,
    ownerUserId === null || ownerUserId === undefined ? members[0].userId : ownerUserId
  );
  return spaceId;
}

/** 幂等保证本人个人空间存在（含兜底分类）。返回 spaceId。 */
async function ensurePersonalSpace(conn, userId, displayName) {
  const [rows] = await conn.execute(
    "SELECT space_id FROM housework_spaces WHERE scope = 'personal' AND owner_user_id = ? LIMIT 1",
    [userId]
  );
  if (rows.length > 0) {
    await ensureFallbackCategory(conn, rows[0].space_id);
    return rows[0].space_id;
  }
  return createSpaceWithMembers(conn, {
    scope: "personal",
    ownerUserId: userId,
    cycleId: null,
    members: [{ userId, displayName }]
  });
}

async function findOpenCycles(conn, relationshipId) {
  const [rows] = await conn.execute(
    "SELECT * FROM housework_relationship_cycles WHERE relationship_id = ? AND ended_at IS NULL FOR UPDATE",
    [relationshipId]
  );
  return rows;
}

/** 校验 open cycle 对应的 active 空间及成员与关系行一致；不一致报 SPACE_STATE_INVALID。 */
async function validateOpenCycleSpace(conn, cycle, relationshipRow) {
  const [spaces] = await conn.execute(
    "SELECT space_id, status FROM housework_spaces WHERE cycle_id = ? FOR UPDATE",
    [cycle.cycle_id]
  );
  if (spaces.length !== 1 || spaces[0].status !== "active") {
    throw new ApiError(409, "SPACE_STATE_INVALID", "家务周期对应的空间缺失或已关闭");
  }
  const spaceId = spaces[0].space_id;
  const [members] = await conn.execute(
    "SELECT user_id FROM housework_space_members WHERE space_id = ?",
    [spaceId]
  );
  const expected = new Set([Number(relationshipRow.user_id_1), Number(relationshipRow.user_id_2)]);
  const cycleMembers = new Set([Number(cycle.member_user_id_1), Number(cycle.member_user_id_2)]);
  const actual = new Set(members.map((m) => Number(m.user_id)));
  if (!sameMemberSet(expected, actual) || !sameMemberSet(expected, cycleMembers)) {
    throw new ApiError(409, "SPACE_STATE_INVALID", "家务空间成员与关系成员不一致");
  }
  return spaceId;
}

/**
 * 绑定事务内调用：确保 active 关系有当前 cycle + active 共享空间。
 *  - 已有唯一 open cycle → 只复用（重复确认 / 并发幂等），不新建；
 *  - 无 open cycle → 新建 cycle + space + 成员 + 兜底分类；
 *  - 多个 open cycle → SPACE_STATE_INVALID（不猜哪个该复活，整体回滚绑定）。
 */
async function ensureCycleForBind(conn, relationshipRow, namesById) {
  const relationshipId = Number(relationshipRow.relationship_id);
  const open = await findOpenCycles(conn, relationshipId);
  if (open.length > 1) {
    throw new ApiError(409, "SPACE_STATE_INVALID", "存在多个未结束的家务周期");
  }
  const u1 = Number(relationshipRow.user_id_1);
  const u2 = Number(relationshipRow.user_id_2);
  if (open.length === 1) {
    const spaceId = await validateOpenCycleSpace(conn, open[0], relationshipRow);
    return { cycleId: open[0].cycle_id, spaceId, created: false };
  }
  const cycleId = uuid();
  await conn.execute(
    `INSERT INTO housework_relationship_cycles (cycle_id, relationship_id, member_user_id_1, member_user_id_2)
     VALUES (?, ?, ?, ?)`,
    [cycleId, relationshipId, u1, u2]
  );
  const members = [
    { userId: u1, displayName: namesById.get(u1) || String(u1) },
    { userId: u2, displayName: namesById.get(u2) || String(u2) }
  ];
  const spaceId = await createSpaceWithMembers(conn, {
    scope: "couple",
    ownerUserId: null,
    cycleId,
    members
  });
  return { cycleId, spaceId, created: true };
}

/**
 * bootstrap 事务内调用：对「已 active」的关系确保当前共享空间存在。
 * 与 ensureCycleForBind 的差别：完全没有 cycle 历史时才补建；有历史但没有 open cycle
 * 属于状态异常（不能猜测哪个旧 cycle 该复活），报 SPACE_STATE_INVALID。
 */
async function ensureSharedSpaceForBootstrap(conn, relationshipRow, namesById) {
  const relationshipId = Number(relationshipRow.relationship_id);
  const [all] = await conn.execute(
    `SELECT * FROM housework_relationship_cycles WHERE relationship_id = ?
     ORDER BY started_at DESC, cycle_id DESC`,
    [relationshipId]
  );
  const open = all.filter((cycle) => cycle.ended_at === null);
  if (open.length > 1) {
    throw new ApiError(409, "SPACE_STATE_INVALID", "存在多个未结束的家务周期");
  }
  if (open.length === 1) {
    const spaceId = await validateOpenCycleSpace(conn, open[0], relationshipRow);
    return { cycleId: open[0].cycle_id, spaceId, created: false };
  }
  if (all.length > 0) {
    throw new ApiError(409, "SPACE_STATE_INVALID", "关系已恢复但缺少当前家务周期");
  }
  const u1 = Number(relationshipRow.user_id_1);
  const u2 = Number(relationshipRow.user_id_2);
  const cycleId = uuid();
  await conn.execute(
    `INSERT INTO housework_relationship_cycles (cycle_id, relationship_id, member_user_id_1, member_user_id_2)
     VALUES (?, ?, ?, ?)`,
    [cycleId, relationshipId, u1, u2]
  );
  const members = [
    { userId: u1, displayName: namesById.get(u1) || String(u1) },
    { userId: u2, displayName: namesById.get(u2) || String(u2) }
  ];
  const spaceId = await createSpaceWithMembers(conn, {
    scope: "couple",
    ownerUserId: null,
    cycleId,
    members
  });
  return { cycleId, spaceId, created: true };
}

/**
 * 解绑事务内调用：关闭当前 open cycle 并冻结其共享空间（行保留、revision+1）。
 * 缺少 open cycle 或 active 空间属于状态异常，整体回滚解绑。
 */
async function closeCurrentCycleAndSpace(conn, relationshipId, relationshipRow) {
  const open = await findOpenCycles(conn, relationshipId);
  if (open.length === 0) {
    throw new ApiError(409, "SPACE_STATE_INVALID", "活跃关系缺少当前家务周期");
  }
  if (open.length > 1) {
    throw new ApiError(409, "SPACE_STATE_INVALID", "存在多个未结束的家务周期");
  }
  const cycle = open[0];
  await validateOpenCycleSpace(conn, cycle, relationshipRow || {
    user_id_1: cycle.member_user_id_1,
    user_id_2: cycle.member_user_id_2
  });
  await conn.execute(
    "UPDATE housework_relationship_cycles SET ended_at = NOW(3) WHERE cycle_id = ? AND ended_at IS NULL",
    [cycle.cycle_id]
  );
  await conn.execute(
    `UPDATE housework_spaces
     SET status = 'closed', closed_at = NOW(3), revision = revision + 1
     WHERE cycle_id = ? AND status = 'active'`,
    [cycle.cycle_id]
  );
  return cycle.cycle_id;
}

/** 帐号删除事务内调用：共享历史保留，个人业务彻底清理。调用方已锁定相关用户/关系。 */
async function cleanHouseworkForAccountDeletion(conn, userId, relationships) {
  for (const relationship of relationships) {
    const open = await findOpenCycles(conn, relationship.relationship_id);
    if (open.length > 0) {
      await closeCurrentCycleAndSpace(conn, relationship.relationship_id, relationship);
    }
  }
  // 即使遗留关系行已缺失，也不得留下仍可写的共享孤儿空间。
  await conn.execute(
    `UPDATE housework_relationship_cycles SET ended_at = COALESCE(ended_at, NOW(3))
     WHERE member_user_id_1 = ? OR member_user_id_2 = ?`,
    [userId, userId]
  );
  await conn.execute(
    `UPDATE housework_spaces s JOIN housework_space_members m ON m.space_id = s.space_id
     SET s.status = 'closed', s.closed_at = NOW(3), s.revision = s.revision + 1
     WHERE m.user_id = ? AND s.scope = 'couple' AND s.status = 'active'`,
    [userId]
  );
  await conn.execute("DELETE FROM housework_preferences WHERE user_id = ?", [userId]);
  const [personal] = await conn.execute(
    "SELECT space_id FROM housework_spaces WHERE scope = 'personal' AND owner_user_id = ? FOR UPDATE",
    [userId]
  );
  for (const { space_id: spaceId } of personal) {
    // 明确按内部 FK 的依赖逆序删除，不能依靠同一个 space 的多个 CASCADE 顺序。
    await conn.execute(
      `DELETE p FROM housework_record_participants p
       JOIN housework_records r ON r.record_id = p.record_id WHERE r.space_id = ?`, [spaceId]);
    for (const table of ["housework_record_revisions", "housework_mutations", "housework_config_revisions",
      "housework_records", "housework_preferences", "housework_templates", "housework_categories", "housework_space_members"]) {
      await conn.execute(`DELETE FROM ${table} WHERE space_id = ?`, [spaceId]);
    }
    await conn.execute("DELETE FROM housework_spaces WHERE space_id = ? AND owner_user_id = ?", [spaceId, userId]);
  }
}

module.exports = {
  uuid,
  normalizeName,
  pickDisplayName,
  loadDisplayNames,
  ensureFallbackCategory,
  ensurePersonalSpace,
  ensureCycleForBind,
  ensureSharedSpaceForBootstrap,
  closeCurrentCycleAndSpace,
  cleanHouseworkForAccountDeletion
};
