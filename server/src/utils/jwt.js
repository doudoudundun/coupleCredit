const jwt = require("jsonwebtoken");
const { readConfig } = require("../config");

// SECRET 由 config.js 强制校验（必填 + 长度 ≥ 32），不再有硬编码兜底。
// readConfig 是幂等的读取，不会重复抛错（启动时 index.js 已调用过一次）。
const config = readConfig();
const SECRET = config.jwtSecret;
const ACCESS_EXPIRES = process.env.JWT_ACCESS_EXPIRES || "15m";
const REFRESH_EXPIRES = process.env.JWT_REFRESH_EXPIRES || "7d";

function signToken(payload, expiresIn = ACCESS_EXPIRES) {
  return jwt.sign(payload, SECRET, { expiresIn });
}

function verifyToken(token) {
  return jwt.verify(token, SECRET);
}

function signRefreshToken(payload) {
  return jwt.sign(payload, SECRET, { expiresIn: REFRESH_EXPIRES });
}

module.exports = { signToken, verifyToken, signRefreshToken };
