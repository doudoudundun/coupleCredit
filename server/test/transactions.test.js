const assert = require("node:assert");
const test = require("node:test");
const { withTransaction } = require("../src/utils/transactions");

test("withTransaction commits when fn succeeds", async () => {
  const conn = {
    beginTransaction: () => Promise.resolve(),
    commit: () => Promise.resolve(),
    rollback: () => Promise.resolve(),
    release: () => {},
  };
  const pool = { getConnection: () => Promise.resolve(conn) };
  let ran = false;
  await withTransaction(pool, async (c) => {
    assert.strictEqual(c, conn);
    ran = true;
  });
  assert.strictEqual(ran, true);
});

test("withTransaction rolls back and re-throws when fn fails", async () => {
  let rollbackCalled = false;
  const conn = {
    beginTransaction: () => Promise.resolve(),
    commit: () => Promise.resolve(),
    rollback: () => { rollbackCalled = true; return Promise.resolve(); },
    release: () => {},
  };
  const pool = { getConnection: () => Promise.resolve(conn) };
  await assert.rejects(
    async () => {
      await withTransaction(pool, async () => {
        throw new Error("boom");
      });
    },
    /boom/
  );
  assert.strictEqual(rollbackCalled, true);
});
