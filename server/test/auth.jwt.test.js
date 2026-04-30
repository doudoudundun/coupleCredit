const assert = require("node:assert");
const test = require("node:test");
const { signToken, verifyToken } = require("../src/utils/jwt");

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
