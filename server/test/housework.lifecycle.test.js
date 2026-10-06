const assert = require("node:assert/strict");
const test = require("node:test");
const bcrypt = require("bcrypt");
const { createCoupleRouter } = require("../src/routes/couple");
const { createAuthRouter } = require("../src/routes/auth");
const { closeCurrentCycleAndSpace } = require("../src/utils/houseworkLifecycle");

function handler(router, path, method) {
  return router.stack.find((entry) => entry.route?.path === path && entry.route.methods[method]).route.stack.at(-1).handle;
}
async function invoke(fn, body = {}) {
  let result, error;
  await fn({ userId: 4, body, params: {}, query: {} }, {
    json(value) { result = value; return this; },
    status() { return this; }
  }, (value) => { error = value; });
  return { result, error };
}

const passwordHash = bcrypt.hashSync("secret123", 4);
function deletionPool({ failUserDelete = false, missingCycle = false } = {}) {
  let state = {
    userExists: true, partnerStatus: "coupled", relationshipExists: true,
    cycleEnded: false, sharedStatus: "active", personalExists: true,
    personalTables: ["housework_record_participants", "housework_record_revisions", "housework_mutations",
      "housework_config_revisions", "housework_records", "housework_preferences", "housework_templates",
      "housework_categories", "housework_space_members"],
    sharedRecord: { recordId: "r-shared", createdBy: 4, quantity: "1.00" },
    sharedParticipants: [{ userId: 4, shareBps: 6000 }, { userId: 5, shareBps: 4000 }]
  };
  const rel = { relationship_id: 9, user_id_1: 4, user_id_2: 5, status: "active" };
  const calls = [];
  let before, committed = 0, rolledBack = 0;
  const execute = async (sql, params = []) => {
    calls.push({ sql, params });
    if (sql.startsWith("SELECT password")) return [[{ password: passwordHash }]];
    if (sql.startsWith("SELECT id FROM users")) return [[{ id: params[0] }]];
    if (sql.startsWith("SELECT") && sql.includes("FROM couple_relationships")) return [[rel]];
    if (sql.includes("FROM housework_relationship_cycles")) return [missingCycle ? [] : [{
      cycle_id: "c-shared", member_user_id_1: 4, member_user_id_2: 5, ended_at: null
    }]];
    if (sql.includes("FROM category_collections") && sql.includes("FOR UPDATE")) return [[]];
    if (sql.startsWith("SELECT space_id, status")) return [[{ space_id: "s-shared", status: "active" }]];
    if (sql.startsWith("SELECT user_id FROM housework_space_members")) return [[{ user_id: 4 }, { user_id: 5 }]];
    if (sql.startsWith("SELECT space_id FROM housework_spaces")) return [[{ space_id: "s-personal" }]];
    if (sql.startsWith("UPDATE housework_relationship_cycles")) state.cycleEnded = true;
    else if (sql.includes("UPDATE housework_spaces")) state.sharedStatus = "closed";
    else if (sql.startsWith("UPDATE couple_relationships")) state.relationshipExists = false;
    else if (sql.startsWith("UPDATE users")) state.partnerStatus = "single";
    else if (sql.startsWith("UPDATE inventory") || sql.startsWith("UPDATE bills")) {}
    else if (sql.startsWith("DELETE FROM item_template_preferences")) {}
    else if (sql.startsWith("DELETE FROM couple_relationships")) state.relationshipExists = false;
    else if (sql.startsWith("DELETE FROM users")) {
      if (failUserDelete) throw Object.assign(new Error("foreign key failure"), { code: "ER_ROW_IS_REFERENCED_2" });
      state.userExists = false;
    } else if (sql.startsWith("DELETE FROM housework_spaces")) state.personalExists = false;
    else if (sql.startsWith("DELETE")) {
      const table = sql.startsWith("DELETE p ") ? "housework_record_participants" : sql.match(/^DELETE FROM (\w+)/)?.[1];
      if (params[0] === "s-personal") state.personalTables = state.personalTables.filter((value) => value !== table);
    } else throw new Error("Unexpected SQL: " + sql);
    return [{ affectedRows: 1 }];
  };
  const conn = {
    execute,
    async beginTransaction() { before = structuredClone(state); },
    async commit() { committed++; },
    async rollback() { rolledBack++; state = before; },
    release() {}
  };
  return {
    execute, calls, async getConnection() { return conn; },
    state() { return state; }, counters() { return { committed, rolledBack }; }
  };
}

test("unbind without an open cycle returns SPACE_STATE_INVALID and rolls relationship back", async () => {
  const pool = deletionPool({ missingCycle: true });
  const result = await invoke(handler(createCoupleRouter({ pool }), "/unbind", "delete"));
  assert.equal(result.error?.code, "SPACE_STATE_INVALID");
  assert.equal(pool.state().relationshipExists, true);
  assert.equal(pool.state().partnerStatus, "coupled");
  assert.equal(pool.state().sharedStatus, "active");
  assert.deepEqual(pool.counters(), { committed: 0, rolledBack: 1 });
});

test("closing cycle rejects missing or mismatched active space before writes", async () => {
  for (const members of [null, [{ user_id: 4 }, { user_id: 6 }]]) {
    const calls = [];
    const conn = { async execute(sql) {
      calls.push(sql);
      if (sql.includes("FROM housework_relationship_cycles")) return [[{ cycle_id: "c", member_user_id_1: 4, member_user_id_2: 5 }]];
      if (sql.includes("FROM housework_spaces")) return [members === null ? [] : [{ space_id: "s", status: "active" }]];
      if (sql.includes("FROM housework_space_members")) return [members];
      throw new Error("Unexpected write");
    } };
    await assert.rejects(closeCurrentCycleAndSpace(conn, 9, { user_id_1: 4, user_id_2: 5 }), { code: "SPACE_STATE_INVALID" });
    assert.equal(calls.some((sql) => sql.startsWith("UPDATE")), false);
  }
});

function accountHandler(pool) {
  const pass = (_req, _res, next) => next();
  return handler(createAuthRouter({
    pool, config: { jwtSecret: "test-secret-long-enough-for-sessions" },
    authLimiter: pass, strictLimiter: pass, requireAuth: pass
  }), "/account", "delete");
}

test("account deletion removes own personal tree, freezes shared history and retains both shares", async () => {
  const pool = deletionPool();
  const result = await invoke(accountHandler(pool), { password: "secret123" });
  assert.equal(result.error, undefined);
  assert.equal(result.result?.ok, true);
  assert.equal(pool.state().userExists, false);
  assert.equal(pool.state().personalExists, false);
  assert.deepEqual(pool.state().personalTables, []);
  assert.equal(pool.state().relationshipExists, false);
  assert.equal(pool.state().partnerStatus, "single");
  assert.equal(pool.state().cycleEnded, true);
  assert.equal(pool.state().sharedStatus, "closed");
  assert.deepEqual(pool.state().sharedParticipants, [{ userId: 4, shareBps: 6000 }, { userId: 5, shareBps: 4000 }]);
  assert.equal(pool.state().sharedRecord.createdBy, 4);
  const deletes = pool.calls.filter(({ sql }) => sql.startsWith("DELETE") && sql.includes("housework"));
  assert.ok(deletes.every(({ params }) => params[0] === "s-personal" || params[0] === 4), "shared business rows are retained");
  assert.deepEqual(pool.counters(), { committed: 1, rolledBack: 0 });
});

test("account deletion failure rolls personal cleanup and shared freeze back atomically", async () => {
  const pool = deletionPool({ failUserDelete: true });
  const before = structuredClone(pool.state());
  const result = await invoke(accountHandler(pool), { password: "secret123" });
  assert.equal(result.error?.code, "ER_ROW_IS_REFERENCED_2");
  assert.deepEqual(pool.state(), before);
  assert.deepEqual(pool.counters(), { committed: 0, rolledBack: 1 });
});
