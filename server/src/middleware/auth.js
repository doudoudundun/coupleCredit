const { verifyAccessToken } = require("../utils/jwt");
const { ApiError } = require("../errors");

function extractBearerToken(req) {
  const header = req.headers.authorization || "";
  return header.startsWith("Bearer ") ? header.slice(7) : null;
}

function decodeAccessToken(req) {
  const token = extractBearerToken(req);
  if (!token) {
    throw new ApiError(401, "UNAUTHORIZED", "缺少认证令牌");
  }
  try {
    return verifyAccessToken(token);
  } catch (_error) {
    // Keep token parsing failures indistinguishable to callers.  In
    // particular, an old untyped token and a refresh token cannot enter a
    // business route as an access token.
    throw new ApiError(401, "UNAUTHORIZED", "认证令牌无效或已过期");
  }
}

function isAuthSchemaError(error) {
  return error
    && (error.code === "ER_BAD_FIELD_ERROR" || error.code === "ER_NO_SUCH_TABLE")
    && /users|auth_token_version/i.test(String(error.sqlMessage || error.message || ""));
}

function authSchemaError() {
  return new ApiError(503, "AUTH_SCHEMA_MIGRATION_REQUIRED", "认证服务尚未完成会话版本迁移");
}

async function loadActiveUserSession(pool, decoded) {
  let rows;
  try {
    [rows] = await pool.execute(
      "SELECT id, status, auth_token_version FROM users WHERE id = ? LIMIT 1",
      [decoded.userId]
    );
  } catch (error) {
    if (isAuthSchemaError(error)) throw authSchemaError();
    throw error;
  }

  const user = rows[0];
  if (!user || user.status !== "active") return null;

  const currentVersion = Number(user.auth_token_version);
  if (!Number.isSafeInteger(currentVersion) || currentVersion < 0) return null;
  if (currentVersion !== decoded.sessionVersion) return null;
  return {
    id: Number(user.id),
    status: user.status,
    sessionVersion: currentVersion,
  };
}

function authenticate(req, next, pool) {
  let decoded;
  try {
    decoded = decodeAccessToken(req);
  } catch (error) {
    return next(error);
  }

  if (!pool) {
    return next(new ApiError(503, "AUTH_MISCONFIGURED", "认证中间件未绑定数据库"));
  }

  return loadActiveUserSession(pool, decoded)
    .then((session) => {
      if (!session) {
        throw new ApiError(401, "UNAUTHORIZED", "用户不存在、已停用或会话已撤销");
      }
      req.userId = session.id;
      req.auth = { ...decoded, sessionVersion: session.sessionVersion };
      next();
    })
    .catch((error) => next(error));
}

function createRequireAuth(pool) {
  if (!pool || typeof pool.execute !== "function") {
    throw new TypeError("createRequireAuth requires a database pool");
  }
  return (req, _res, next) => authenticate(req, next, pool);
}

function requireAuth(req, _res, next) {
  // app.locals.authPool is set before routes are mounted in index.js.  A
  // middleware call without that binding fails closed instead of trusting the
  // token without checking the current user/session row.
  const pool = req.app && req.app.locals ? req.app.locals.authPool : null;
  return authenticate(req, next, pool);
}

function optionalAuth(req, _res, next) {
  const token = extractBearerToken(req);
  if (!token) return next();

  const pool = req.app && req.app.locals ? req.app.locals.authPool : null;
  if (!pool) return next();

  let decoded;
  try {
    decoded = verifyAccessToken(token);
  } catch (_error) {
    // Invalid, expired, refresh, or legacy untyped tokens are ignored.
    return next();
  }

  return loadActiveUserSession(pool, decoded)
    .then((session) => {
      if (session) {
        req.userId = session.id;
        req.auth = { ...decoded, sessionVersion: session.sessionVersion };
      }
      next();
    })
    .catch((error) => next(error));
}

// Transitional export kept for callers outside this repository.  It fails
// closed when no app pool is configured; real traffic is mounted with
// createRequireAuth(pool), so every request is checked against the current
// user row and auth_token_version.
const requireAuthForBusiness = requireAuth;

module.exports = {
  requireAuth,
  optionalAuth,
  requireAuthForBusiness,
  createRequireAuth,
  loadActiveUserSession,
  isAuthSchemaError,
  authSchemaError,
};
