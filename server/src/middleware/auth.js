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

module.exports = { requireAuth, optionalAuth };
