// 幂等键支持（v0.2 写操作通用）
//
// 语义（契约 §0）：按 (操作人, 作用域, 业务键) 持久去重；
//   - 同键同载荷 → 返回首次存储的响应（replayed）；
//   - 同键不同载荷 → 409 IDEMPOTENCY_CONFLICT；
//   - 首次执行失败 → 删除占位记录，客户端可用同键安全重试。
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
 * executor 在首次执行时调用，其返回值（可 JSON 序列化）被持久化并在重放时原样返回。
 * 返回 { replayed, result }。
 */
async function withIdempotency(pool, { userId, scope, key, payload }, executor) {
  const requestHash = hashRequest(payload || {});
  try {
    await pool.execute(
      "INSERT INTO idempotency_records (user_id, scope, idem_key, request_hash, status) VALUES (?, ?, ?, ?, 'processing')",
      [userId, scope, key, requestHash]
    );
  } catch (error) {
    if (error.code !== "ER_DUP_ENTRY") throw error;
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
      // 遗留 processing 占位（进程崩溃）：超过 2 分钟视为死记录，回收后重插一次
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

  let result;
  try {
    result = await executor();
  } catch (error) {
    await pool.execute(
      "DELETE FROM idempotency_records WHERE user_id = ? AND scope = ? AND idem_key = ? AND status = 'processing'",
      [userId, scope, key]
    ).catch(() => {});
    throw error;
  }

  await pool.execute(
    "UPDATE idempotency_records SET status = 'completed', response_json = ? WHERE user_id = ? AND scope = ? AND idem_key = ?",
    [JSON.stringify(result === undefined ? null : result), userId, scope, key]
  );
  return { replayed: false, result };
}

module.exports = { requireIdempotencyKey, withIdempotency };
