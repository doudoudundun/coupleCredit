// 空间上下文解析（共同目标 & 共同小记 v0.2）
//
// 「当前空间」复用 housework 生命周期：
//   - 有 active 关系 → 当前 open cycle 的 active 共享空间（couple）；
//   - 无关系 → 本人的个人空间（personal，必要时按需创建，仅目标可用）；
// 空间被冻结（解绑）后不再作为「当前空间」返回，历史数据按归档口径读取。
const { ApiError } = require("../errors");
const {
  loadDisplayNames,
  ensurePersonalSpace
} = require("./houseworkLifecycle");

/** active 关系（不用缓存版：写路径必须事务内复核）。executor 为 pool 或 conn。 */
async function loadActiveRelationshipFresh(executor, userId, forUpdate = false) {
  const [rows] = await executor.execute(
    "SELECT relationship_id, user_id_1, user_id_2 FROM couple_relationships WHERE status = 'active' AND (user_id_1 = ? OR user_id_2 = ?) ORDER BY relationship_id DESC LIMIT 1" + (forUpdate ? " FOR UPDATE" : ""),
    [userId, userId]
  );
  return rows.length > 0 ? rows[0] : null;
}

/**
 * 共享空间上下文。返回 null 表示当前无 active 关系/共享空间。
 * forUpdate 时锁定关系行与空间行（写事务内使用；调用方需先按锁顺序锁 users 行）。
 */
async function loadCoupleSpaceContext(executor, userId, { forUpdate = false } = {}) {
  const rel = await loadActiveRelationshipFresh(executor, userId, forUpdate);
  if (!rel) return null;
  const lock = forUpdate ? " FOR UPDATE" : "";
  const [rows] = await executor.execute(
    `SELECT s.space_id, s.status, s.timezone, c.cycle_id
       FROM housework_relationship_cycles c
       JOIN housework_spaces s ON s.cycle_id = c.cycle_id
      WHERE c.relationship_id = ? AND c.ended_at IS NULL AND s.status = 'active'
      LIMIT 1${lock}`,
    [rel.relationship_id]
  );
  if (rows.length === 0) return null;
  const u1 = Number(rel.user_id_1);
  const u2 = Number(rel.user_id_2);
  return {
    relationshipId: Number(rel.relationship_id),
    // 每次重绑都有新 cycle_id；关系行 ID 可复用，不能作为发布 epoch。
    relationship: { id: Number(rel.relationship_id), version: rows[0].cycle_id },
    memberIds: [u1, u2],
    partnerId: u1 === Number(userId) ? u2 : u1,
    spaceId: rows[0].space_id,
    cycleId: rows[0].cycle_id,
    timezone: rows[0].timezone || "Asia/Shanghai"
  };
}

/**
 * 目标可用的「当前空间」：有共享空间用共享空间，否则回落本人个人空间
 * （个人空间在事务内按需创建；executor 必须是事务连接）。
 */
async function resolveGoalSpace(conn, userId) {
  const couple = await loadCoupleSpaceContext(conn, userId, { forUpdate: true });
  if (couple) return { ...couple, scope: "couple" };
  const names = await loadDisplayNames(conn, [userId]);
  const spaceId = await ensurePersonalSpace(conn, userId, names.get(Number(userId)) || String(userId));
  const [rows] = await conn.execute(
    "SELECT timezone FROM housework_spaces WHERE space_id = ? LIMIT 1",
    [spaceId]
  );
  return {
    relationshipId: null,
    relationship: { id: null, version: 0 },
    memberIds: [Number(userId)],
    partnerId: null,
    spaceId,
    cycleId: null,
    timezone: (rows[0] && rows[0].timezone) || "Asia/Shanghai",
    scope: "personal"
  };
}

/** 空间时区的「今天」（YYYY-MM-DD）。 */
function todayInTimezone(timezone) {
  try {
    return new Intl.DateTimeFormat("en-CA", {
      timeZone: timezone || "Asia/Shanghai",
      year: "numeric", month: "2-digit", day: "2-digit"
    }).format(new Date());
  } catch (_error) {
    return new Intl.DateTimeFormat("en-CA").format(new Date());
  }
}

/** 校验 YYYY-MM-DD 日期串；不合法抛 422。 */
function parseDateOnly(value, field) {
  const text = typeof value === "string" ? value.trim() : "";
  if (!/^\d{4}-\d{2}-\d{2}$/.test(text)) {
    throw new ApiError(422, "VALIDATION_FAILED", "字段校验失败", {
      fieldErrors: { [field]: "日期格式应为 YYYY-MM-DD" }
    });
  }
  const date = new Date(`${text}T00:00:00Z`);
  if (Number.isNaN(date.getTime()) || date.toISOString().slice(0, 10) !== text) {
    throw new ApiError(422, "VALIDATION_FAILED", "字段校验失败", {
      fieldErrors: { [field]: "日期不存在" }
    });
  }
  return text;
}

module.exports = {
  loadActiveRelationshipFresh,
  loadCoupleSpaceContext,
  resolveGoalSpace,
  todayInTimezone,
  parseDateOnly
};
