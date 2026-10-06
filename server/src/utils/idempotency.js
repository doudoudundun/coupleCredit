// 幂等键支持（v0.2 写操作通用）
//
// 语义（契约 §0）：按 (操作人, 作用域, 业务键) 持久去重；
//   - 同键同载荷 → 返回首次存储的响应（replayed）；
//   - 同键不同载荷 → 409 IDEMPOTENCY_CONFLICT；
//   - 首次执行失败 → 占位随事务回滚消失，客户端可用同键安全重试。
//
// 原子性：占位插入、业务执行、回执落库在同一个事务里提交。
// 绝不出现「业务已提交、回执没存上」的半截状态——那曾导致同键重试再次执行业务，
// 产生重复记录。executor(conn) 必须使用传入的连接，且不得自行提交/回滚。
const crypto = require("crypto");
const { ApiError } = require("../errors");

const KEY_PATTERN = /^[A-Za-z0-9_-]{8,80}$/;

function requireIdempotencyKey(value, field = "idempotencyKey") {
  const key = typeof value === "string" ? value.trim() : "";
  if (!KEY_PATTERN.test(key)) {
    throw new ApiError(422, "VALIDATION_FAILED", "字段校验失败", {
      fieldErrors: { [field]: "幂等键缺失或格式不正确（8-80 位字母数字-_）" }
    });
  }
  return key;
}

function canonicalize(value) {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (value && typeof value === "object") {
    const out = {};
    for (const key of Object.keys(value).sort()) {
      out[key] = canonicalize(value[key]);
    }
    return out;
  }
  return value;
}

function hashRequest(payload) {
  const canonical = JSON.stringify(canonicalize(payload || {}));
  return crypto.createHash("sha256").update(canonical).digest("hex");
}

/**
 * 以幂等键包裹一次写操作。
 * executor(conn) 在首次执行时调用（事务内），其返回值（可 JSON 序列化）与业务
 * 同事务持久化，重放时原样返回。返回 { replayed, result }。
 */
async function withIdempotency(pool, { userId, scope, key, payload }, executor) {
  const requestHash = hashRequest(payload || {});
  const conn = await pool.getConnection();
  let duplicate = false;
  try {
    await conn.beginTransaction();
    try {
      // 并发下同键第二个请求在此阻塞至首个事务提交/回滚：
      // 前者提交 → 本请求拿到 ER_DUP_ENTRY 走重放；前者回滚 → 本请求插入成功接管执行。
      await conn.execute(
        "INSERT INTO idempotency_records (user_id, scope, idem_key, request_hash, status) VALUES (?, ?, ?, ?, 'processing')",
        [userId, scope, key, requestHash]
      );
    } catch (error) {
      if (error.code !== "ER_DUP_ENTRY") throw error;
      duplicate = true;
    }

    if (!duplicate) {
      const result = await executor(conn);
      await conn.execute(
        "UPDATE idempotency_records SET status = 'completed', response_json = ? WHERE user_id = ? AND scope = ? AND idem_key = ?",
        [JSON.stringify(result === undefined ? null : result), userId, scope, key]
      );
      await conn.commit();
      return { replayed: false, result };
    }
    await conn.rollback();
  } catch (error) {
    try { await conn.rollback(); } catch (_rollbackError) { /* 连接释放时同样回滚 */ }
    throw error;
  } finally {
    conn.release();
  }

  return resolveDuplicate(pool, { userId, scope, key, payload, requestHash }, executor);
}

/** 撞键后的裁决（事务外读已提交数据）：重放 / 载荷冲突 / 回收遗留死占位后接管。 */
async function resolveDuplicate(pool, { userId, scope, key, payload, requestHash }, executor) {
  const [rows] = await pool.execute(
    "SELECT request_hash, response_json, status, created_at FROM idempotency_records WHERE user_id = ? AND scope = ? AND idem_key = ? LIMIT 1",
    [userId, scope, key]
  );
  const existing = rows[0];
  if (existing && existing.request_hash === requestHash) {
    if (existing.status === "completed" && existing.response_json) {
      const response = typeof existing.response_json === "string"
        ? JSON.parse(existing.response_json)
        : existing.response_json;
      return { replayed: true, result: response };
    }
    // 遗留 processing 占位（仅可能来自旧版非原子实现或异常残留）：超过 2 分钟回收后重试
    if (Date.now() - new Date(existing.created_at).getTime() > 2 * 60 * 1000) {
      await pool.execute(
        "DELETE FROM idempotency_records WHERE user_id = ? AND scope = ? AND idem_key = ? AND status = 'processing'",
        [userId, scope, key]
      );
      return withIdempotency(pool, { userId, scope, key, payload }, executor);
    }
    throw new ApiError(409, "IDEMPOTENCY_IN_PROGRESS", "相同请求正在处理中，请稍后重试");
  }
  throw new ApiError(409, "IDEMPOTENCY_CONFLICT", "同一幂等键提交了不同的请求内容");
}

module.exports = { requireIdempotencyKey, withIdempotency };
