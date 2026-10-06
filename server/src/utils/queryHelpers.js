/**
 * Server-side shared validation and query utilities
 */
const { ApiError } = require("../errors");
const { cache, Keys, TTL } = require("../cache");

async function loadActiveRelationship(pool, userId) {
  const cacheKey = Keys.relationship(userId);
  const cached = cache.get(cacheKey);
  if (cached) return cached;

  const [rows] = await pool.execute(
    "SELECT relationship_id, user_id_1, user_id_2 FROM couple_relationships WHERE status = 'active' AND (user_id_1 = ? OR user_id_2 = ?) ORDER BY relationship_id DESC LIMIT 1",
    [userId, userId]
  );
  const result = rows.length > 0 ? rows[0] : null;
  cache.set(cacheKey, result, TTL.REL);
  return result;
}

function trimValue(value) {
  return typeof value === "string" ? value.trim() : "";
}

function parseOptionalInteger(value) {
  if (value === null || value === undefined || value === "") {
    return null;
  }
  if (!Number.isInteger(value) || value < 0) {
    throw new ApiError(400, "INVALID_REQUEST", "请求参数不完整或格式不正确");
  }
  return value;
}

function parseRequiredInteger(value) {
  if (!Number.isInteger(value) || value < 0) {
    throw new ApiError(400, "INVALID_REQUEST", "请求参数不完整或格式不正确");
  }
  return value;
}

function parseRequiredFloat(value) {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) {
    throw new ApiError(400, "INVALID_REQUEST", "数量参数格式不正确");
  }
  return value;
}

function parseRequiredAmount(value) {
  if (typeof value !== "number" || !Number.isFinite(value) || value <= 0) {
    throw new ApiError(400, "INVALID_REQUEST", "请求参数不完整或格式不正确");
  }
  return value;
}

function normalizeNullableText(value) {
  if (value === undefined || value === null) return null;
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed === "" ? null : trimmed;
}

function buildCoupleOrPrivateScope(userId, relationship, tableAlias = "") {
  const prefix = tableAlias ? `${tableAlias}.` : "";
  const userColumn = `${prefix}user_id`;
  const relationshipColumn = `${prefix}relationship_id`;
  if (relationship) {
    return {
      clause: `(${relationshipColumn} = ? OR (${userColumn} = ? AND ${relationshipColumn} IS NULL))`,
      params: [relationship.relationship_id, userId]
    };
  }
  return {
    clause: `${userColumn} = ? AND ${relationshipColumn} IS NULL`,
    params: [userId]
  };
}

function buildSharedPlanScope(userId, relationship, tableAlias = "") {
  const prefix = tableAlias ? `${tableAlias}.` : "";
  const relationshipColumn = `${prefix}relationship_id`;
  const ownerColumn = `${prefix}created_by`;
  const visibilityColumn = `${prefix}visibility`;
  const privateClause = `${ownerColumn} = ? AND (${visibilityColumn} = 'self' OR ${relationshipColumn} IS NULL)`;
  if (relationship) {
    return {
      clause: `((${relationshipColumn} = ? AND ${visibilityColumn} = 'both') OR (${privateClause}))`,
      params: [relationship.relationship_id, userId]
    };
  }
  return {
    clause: `(${privateClause})`,
    params: [userId]
  };
}

function buildSubjectOrCurrentRelationshipScope(userId, relationship, tableAlias = "") {
  const prefix = tableAlias ? `${tableAlias}.` : "";
  const userColumn = `${prefix}user_id`;
  const relationshipColumn = `${prefix}relationship_id`;
  if (relationship) {
    return {
      clause: `(${userColumn} = ? OR (${relationshipColumn} = ? AND ${userColumn} IN (?, ?)))`,
      params: [
        userId,
        relationship.relationship_id,
        relationship.user_id_1,
        relationship.user_id_2
      ]
    };
  }
  return {
    clause: `${userColumn} = ?`,
    params: [userId]
  };
}

function relationshipUserIds(userId, relationship) {
  const ids = new Set([userId]);
  if (relationship) {
    ids.add(relationship.user_id_1);
    ids.add(relationship.user_id_2);
  }
  return ids;
}

function invalidateOverviewForUser(cacheInstance, userId, relationship) {
  for (const id of relationshipUserIds(userId, relationship)) {
    cacheInstance.delPrefix(`overview:${id}:`);
  }
}

function invalidateRelationshipScopedCaches(cacheInstance, userIds) {
  for (const id of new Set(userIds)) {
    cacheInstance.del(Keys.relationship(id));
    cacheInstance.del(Keys.coupleRole(id));
    cacheInstance.del(Keys.profile(id));
    cacheInstance.delPrefix(`bills:${id}:`);
    cacheInstance.del(Keys.inventory(id));
    cacheInstance.del(Keys.beads(id));
    cacheInstance.del(Keys.beadBlueprints(id));
    cacheInstance.del(Keys.recipes(id));
    cacheInstance.delPrefix(`${Keys.recipeRecommend(id)}:`);
    cacheInstance.del(Keys.recipeCategories(id));
    cacheInstance.del(Keys.sharedPlans(id));
    cacheInstance.del(Keys.todos(id));
    cacheInstance.del(Keys.restaurants(id));
    cacheInstance.del(Keys.calorieToday(id));
    cacheInstance.delPrefix(Keys.calorieHistoryByUser(id));
    cacheInstance.del(Keys.assets(id));
    cacheInstance.del(Keys.assetStats(id));
    cacheInstance.del(Keys.assetCategories(id));
    cacheInstance.delPrefix(`overview:${id}:`);
  }
}

function invalidateForUser(cache, keyFn, userId, relationship) {
  for (const id of relationshipUserIds(userId, relationship)) {
    cache.del(keyFn(id));
    cache.delPrefix(`overview:${id}:`);
  }
}

/**
 * 读取「对方」的资料（含头像审核状态）。
 *
 * avatar_status 是迁移 028 新增的列。若该迁移尚未执行，这里自动退回不带该列的查询，
 * 并把头像视为已通过 —— 内容安全是新增能力，不该成为「查看对方资料」这个既有基础
 * 能力的单点故障（否则迁移漏跑会让 App 首页直接 500）。
 *
 * @returns {Promise<null | {id:number, username:string, nickname:string|null,
 *                            avatarUrl:string|null, avatarPending:boolean}>}
 *   avatarUrl 仅在审核通过时返回值；审核中/被驳回返回 null。
 *   avatarPending 用来区分「对方没设头像」和「对方头像在审核中」。
 */
async function loadPartnerProfile(pool, partnerId) {
  let rows;
  try {
    [rows] = await pool.execute(
      "SELECT id, username, nickname, avatar, avatar_status FROM users WHERE id = ? LIMIT 1",
      [partnerId]
    );
  } catch (error) {
    if (!/avatar_status/i.test(error.message || "")) throw error;
    console.warn(
      "[queryHelpers] users.avatar_status 不存在，请执行迁移 028_add_content_safety.sql；本次回退为不审核"
    );
    [rows] = await pool.execute(
      "SELECT id, username, nickname, avatar FROM users WHERE id = ? LIMIT 1",
      [partnerId]
    );
    rows = rows.map((row) => Object.assign({}, row, { avatar_status: "approved" }));
  }

  if (rows.length === 0) return null;
  const row = rows[0];
  const hasAvatar = Boolean(row.avatar);
  // 没有头像时状态无意义，统一按已通过返回，免得客户端显示「审核中」却无图可审
  const status = hasAvatar ? row.avatar_status || "approved" : "approved";

  return {
    id: row.id,
    username: row.username,
    nickname: row.nickname || null,
    avatarUrl: status === "approved" ? row.avatar || null : null,
    avatarPending: hasAvatar && status === "pending"
  };
}

module.exports = {
  loadActiveRelationship,
  loadPartnerProfile,
  trimValue,
  parseOptionalInteger,
  parseRequiredInteger,
  parseRequiredFloat,
  parseRequiredAmount,
  normalizeNullableText,
  buildCoupleOrPrivateScope,
  buildSharedPlanScope,
  buildSubjectOrCurrentRelationshipScope,
  invalidateOverviewForUser,
  invalidateRelationshipScopedCaches,
  invalidateForUser
};
