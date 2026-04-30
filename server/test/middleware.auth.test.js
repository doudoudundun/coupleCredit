const assert = require("node:assert");
const test = require("node:test");
const { requireAuth, optionalAuth } = require("../src/middleware/auth");
const { signToken } = require("../src/utils/jwt");

function mockRes() {
  let statusCode = 200;
  let jsonBody = null;
  return {
    status(code) { statusCode = code; return this; },
    json(body) { jsonBody = body; return this; },
    _statusCode: () => statusCode,
    _jsonBody: () => jsonBody,
  };
}

test("requireAuth returns 401 when no header", () => {
  const req = { headers: {} };
  const res = mockRes();
  let nextCalled = false;
  requireAuth(req, res, (err) => {
    if (err) {
      assert.strictEqual(err.status, 401);
    } else {
      nextCalled = true;
    }
  });
  assert.strictEqual(nextCalled, false);
});

test("requireAuth sets req.userId for valid token", () => {
  const token = signToken({ userId: 99 });
  const req = { headers: { authorization: `Bearer ${token}` } };
  const res = mockRes();
  let nextCalled = false;
  requireAuth(req, res, () => { nextCalled = true; });
  assert.strictEqual(req.userId, 99);
  assert.strictEqual(nextCalled, true);
});

test("optionalAuth sets req.userId when token present", () => {
  const token = signToken({ userId: 77 });
  const req = { headers: { authorization: `Bearer ${token}` } };
  const res = mockRes();
  let nextCalled = false;
  optionalAuth(req, res, () => { nextCalled = true; });
  assert.strictEqual(req.userId, 77);
  assert.strictEqual(nextCalled, true);
});

test("optionalAuth falls back to query.userId when no token", () => {
  const req = { headers: {}, query: { userId: "55" } };
  const res = mockRes();
  let nextCalled = false;
  optionalAuth(req, res, () => { nextCalled = true; });
  assert.strictEqual(req.userId, 55);
  assert.strictEqual(nextCalled, true);
});
