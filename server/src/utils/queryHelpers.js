/**
 * 服务器端共享验证和查询工具
 */
const { ApiError } = require("../errors");

async function loadActiveRelationship(pool, userId) {
  const [rows] = await pool.execute(
    "SELECT relationship_id, user_id_1, user_id_2 FROM couple_relationships WHERE status = 'active' AND (user_id_1 = ? OR user_id_2 = ?) ORDER BY relationship_id DESC LIMIT 1",
    [userId, userId]
  );
  return rows.length > 0 ? rows[0] : null;
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

module.exports = {
  loadActiveRelationship,
  trimValue,
  parseOptionalInteger,
  parseRequiredInteger,
  parseRequiredFloat,
  parseRequiredAmount
};