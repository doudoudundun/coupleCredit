const jwt = require("jsonwebtoken");
const { readConfig } = require("../config");

// SECRET 由 config.js 强制校验（必填 + 长度 ≥ 32），不再有硬编码兜底。
// readConfig 是幂等的读取，不会重复抛错（启动时 index.js 已调用过一次）。
const config = readConfig();
const SECRET = config.jwtSecret;
const ACCESS_EXPIRES = process.env.JWT_ACCESS_EXPIRES || "15m";
const REFRESH_EXPIRES = process.env.JWT_REFRESH_EXPIRES || "7d";
const ALGORITHM = "HS256";
const ACCESS_TOKEN_TYPE = "access";
const REFRESH_TOKEN_TYPE = "refresh";

function normalizeClaims(payload, tokenType) {
  const source = payload && typeof payload === "object" ? payload : {};
  const userId = Number(source.userId);
  const sessionVersion = source.sessionVersion === undefined ? 0 : Number(source.sessionVersion);
  if (!Number.isSafeInteger(userId) || userId <= 0) {
    throw new TypeError("JWT userId must be a positive integer");
  }
  if (!Number.isSafeInteger(sessionVersion) || sessionVersion < 0) {
    throw new TypeError("JWT sessionVersion must be a non-negative integer");
  }
  return { userId, sessionVersion, tokenType };
}

function signToken(payload, expiresIn = ACCESS_EXPIRES) {
  return jwt.sign(normalizeClaims(payload, ACCESS_TOKEN_TYPE), SECRET, {
    expiresIn,
    algorithm: ALGORITHM,
  });
}

function verifyToken(token, expectedType = ACCESS_TOKEN_TYPE) {
  const decoded = jwt.verify(token, SECRET, { algorithms: [ALGORITHM] });
  if (!decoded || decoded.tokenType !== expectedType) {
    throw new jwt.JsonWebTokenError("invalid token type");
  }
  if (typeof decoded.userId !== "number" || !Number.isSafeInteger(decoded.userId) || decoded.userId <= 0) {
    throw new jwt.JsonWebTokenError("invalid token subject");
  }
  if (typeof decoded.sessionVersion !== "number" || !Number.isSafeInteger(decoded.sessionVersion) || decoded.sessionVersion < 0) {
    throw new jwt.JsonWebTokenError("invalid token session version");
  }
  return {
    ...decoded,
    userId: decoded.userId,
    sessionVersion: decoded.sessionVersion,
  };
}

function signRefreshToken(payload) {
  return jwt.sign(normalizeClaims(payload, REFRESH_TOKEN_TYPE), SECRET, {
    expiresIn: REFRESH_EXPIRES,
    algorithm: ALGORITHM,
  });
}

function verifyAccessToken(token) {
  return verifyToken(token, ACCESS_TOKEN_TYPE);
}

function verifyRefreshToken(token) {
  return verifyToken(token, REFRESH_TOKEN_TYPE);
}

module.exports = {
  ACCESS_TOKEN_TYPE,
  REFRESH_TOKEN_TYPE,
  signToken,
  verifyToken,
  verifyAccessToken,
  signRefreshToken,
  verifyRefreshToken,
};
