const assert = require("node:assert");
const test = require("node:test");
const { standardLimiter, authLimiter, strictLimiter } = require("../src/middleware/rateLimit");

test("standardLimiter is a function (Express middleware)", () => {
  assert.strictEqual(typeof standardLimiter, "function");
});

test("authLimiter is a function (Express middleware)", () => {
  assert.strictEqual(typeof authLimiter, "function");
});

test("strictLimiter is a function (Express middleware)", () => {
  assert.strictEqual(typeof strictLimiter, "function");
});
