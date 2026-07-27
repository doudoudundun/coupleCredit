const { verifyToken } = require("../utils/jwt");
const { ApiError } = require("../errors");

function extractBearerToken(req) {
  const header = req.headers.authorization || "";
  return header.startsWith("Bearer ") ? header.slice(7) : null;
}

function requireAuth(req, _res, next) {
  const token = extractBearerToken(req);
  if (!token) {
    return next(new ApiError(401, "UNAUTHORIZED", "缺少认证令牌"));
  }
  try {
    const decoded = verifyToken(token);
    req.userId = decoded.userId;
    next();
  } catch (_e) {
    next(new ApiError(401, "UNAUTHORIZED", "认证令牌无效或已过期"));
  }
}

function optionalAuth(req, _res, next) {
  const token = extractBearerToken(req);
  if (token) {
    try {
      const decoded = verifyToken(token);
      req.userId = decoded.userId;
    } catch (_e) {
      // ignore invalid token, fall through
    }
  }
  if (req.userId === undefined) {
    const raw = req.query?.userId ?? req.body?.userId;
    if (raw !== undefined) {
      req.userId = Number(raw);
    }
  }
  next();
}

/**
 * 鉴权切换的过渡中间件（当前全局挂载）。
 *
 * 行为：
 *  - 有效 token：从 JWT 解出 userId，正常放行。
 *  - 无 token / token 无效：降级读 query/body.userId（兼容旧 App），但打 warn 日志。
 *
 * 兼容期结束后（App 全部带 token），本函数会简化为纯 requireAuth，
 * 删除 query/body fallback。
 */
function requireAuthForBusiness(req, _res, next) {
  const token = extractBearerToken(req);
  if (token) {
    try {
      const decoded = verifyToken(token);
      req.userId = decoded.userId;
      return next();
    } catch (_e) {
      // token 存在但无效，不降级，直接拒（防止用废 token 探测）
      return next(new ApiError(401, "UNAUTHORIZED", "认证令牌无效或已过期"));
    }
  }
  // 兼容期：无 token 时降级读 query/body.userId，但记 warn
  const raw = req.query?.userId ?? req.body?.userId;
  if (raw !== undefined) {
    req.userId = Number(raw);
    console.warn(`[AUTH_FALLBACK] ${req.method} ${req.originalUrl} 无 token，降级使用 userId=${req.userId}`);
    return next();
  }
  return next(new ApiError(401, "UNAUTHORIZED", "缺少认证令牌"));
}

module.exports = { requireAuth, optionalAuth, requireAuthForBusiness };
