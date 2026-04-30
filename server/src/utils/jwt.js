const jwt = require("jsonwebtoken");

const SECRET = process.env.JWT_SECRET || "change-me-in-production";
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
