const assert = require("node:assert");
const test = require("node:test");
const jwt = require("jsonwebtoken");
const {
  signToken,
  signRefreshToken,
  verifyToken,
  verifyAccessToken,
  verifyRefreshToken,
} = require("../src/utils/jwt");

test("signToken returns a string with 3 dot-separated parts", () => {
  const token = signToken({ userId: 42 });
  assert.strictEqual(typeof token, "string");
  assert.strictEqual(token.split(".").length, 3);
});

test("verifyToken returns decoded payload for valid token", () => {
  const token = signToken({ userId: 42 });
  const decoded = verifyToken(token);
  assert.strictEqual(decoded.userId, 42);
});

test("verifyToken throws for invalid token", () => {
  assert.throws(() => verifyToken("bad-token"), /jwt/i);
});

test("verifyToken throws for expired token", () => {
  const token = signToken({ userId: 42 }, "0s");
  assert.throws(() => verifyToken(token), /expired/i);
});

test("access and refresh tokens carry distinct types and cannot be swapped", () => {
  const access = signToken({ userId: 42, sessionVersion: 3 });
  const refresh = signRefreshToken({ userId: 42, sessionVersion: 3 });
  assert.equal(verifyAccessToken(access).tokenType, "access");
  assert.equal(verifyRefreshToken(refresh).tokenType, "refresh");
  assert.throws(() => verifyRefreshToken(access), /token type/i);
  assert.throws(() => verifyAccessToken(refresh), /token type/i);
});

test("unclassified legacy JWTs are rejected by the strict verifier", () => {
  const legacy = jwt.sign({ userId: 42 }, process.env.JWT_SECRET, { expiresIn: "1h" });
  assert.throws(() => verifyToken(legacy), /token type|session version/i);
});
