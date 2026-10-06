const assert = require("node:assert");
const test = require("node:test");
const { requireAuth, optionalAuth, createRequireAuth } = require("../src/middleware/auth");
const { signToken, signRefreshToken } = require("../src/utils/jwt");

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

test("requireAuth sets req.userId for valid token", async () => {
  const token = signToken({ userId: 99 });
  const req = {
    headers: { authorization: `Bearer ${token}` },
    app: { locals: { authPool: { execute: async () => [[{ id: 99, status: "active", auth_token_version: 0 }]] } } },
  };
  const res = mockRes();
  let nextCalled = false;
  await new Promise((resolve, reject) => {
    requireAuth(req, res, (error) => {
      if (error) return reject(error);
      nextCalled = true;
      resolve();
    });
  });
  assert.strictEqual(req.userId, 99);
  assert.strictEqual(nextCalled, true);
});

test("optionalAuth sets req.userId when token present", () => {
  const token = signToken({ userId: 77 });
  const req = {
    headers: { authorization: `Bearer ${token}` },
    app: { locals: { authPool: { execute: async () => [[{ id: 77, status: "active", auth_token_version: 0 }]] } } },
  };
  const res = mockRes();
  return new Promise((resolve, reject) => {
    optionalAuth(req, res, (error) => {
      try {
        assert.equal(error, undefined);
        assert.strictEqual(req.userId, 77);
        resolve();
      } catch (assertionError) {
        reject(assertionError);
      }
    });
  });
});

test("optionalAuth does not derive identity from query.userId", () => {
  const req = { headers: {}, query: { userId: "55" } };
  const res = mockRes();
  let nextCalled = false;
  optionalAuth(req, res, () => { nextCalled = true; });
  assert.strictEqual(req.userId, undefined);
  assert.strictEqual(nextCalled, true);
});

function invokeAsyncMiddleware(middleware, req) {
  return new Promise((resolve) => {
    middleware(req, {}, (error) => resolve(error));
  });
}

test("createRequireAuth checks the current user and session version", async () => {
  const pool = {
    async execute() {
      return [[{ id: 99, status: "active", auth_token_version: 4 }]];
    },
  };
  const middleware = createRequireAuth(pool);
  const req = { headers: { authorization: `Bearer ${signToken({ userId: 99, sessionVersion: 4 })}` } };
  const error = await invokeAsyncMiddleware(middleware, req);
  assert.equal(error, undefined);
  assert.equal(req.userId, 99);
  assert.equal(req.auth.sessionVersion, 4);
});

test("createRequireAuth rejects a revoked version, disabled user, and refresh token", async () => {
  const pool = {
    async execute(_sql, [userId]) {
      if (userId === 1) return [[{ id: 1, status: "active", auth_token_version: 2 }]];
      if (userId === 2) return [[{ id: 2, status: "inactive", auth_token_version: 0 }]];
      return [[]];
    },
  };
  const middleware = createRequireAuth(pool);
  const revoked = await invokeAsyncMiddleware(
    middleware,
    { headers: { authorization: `Bearer ${signToken({ userId: 1, sessionVersion: 1 })}` } }
  );
  const disabled = await invokeAsyncMiddleware(
    middleware,
    { headers: { authorization: `Bearer ${signToken({ userId: 2, sessionVersion: 0 })}` } }
  );
  const refresh = await invokeAsyncMiddleware(
    middleware,
    { headers: { authorization: `Bearer ${signRefreshToken({ userId: 1, sessionVersion: 2 })}` } }
  );
  assert.equal(revoked.status, 401);
  assert.equal(disabled.status, 401);
  assert.equal(refresh.status, 401);
});

test("createRequireAuth reports a missing session-version migration clearly", async () => {
  const pool = {
    async execute() {
      const error = new Error("Unknown column 'auth_token_version' in 'field list'");
      error.code = "ER_BAD_FIELD_ERROR";
      error.sqlMessage = error.message;
      throw error;
    },
  };
  const middleware = createRequireAuth(pool);
  const error = await invokeAsyncMiddleware(
    middleware,
    { headers: { authorization: `Bearer ${signToken({ userId: 3, sessionVersion: 0 })}` } }
  );
  assert.equal(error.status, 503);
  assert.equal(error.code, "AUTH_SCHEMA_MIGRATION_REQUIRED");
});
