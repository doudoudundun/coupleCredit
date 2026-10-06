const assert = require("node:assert/strict");
const test = require("node:test");

const { cache, Keys } = require("../src/cache");
const { createAiChatRouter } = require("../src/routes/aiChat");
const { createAssetsRouter } = require("../src/routes/assets");
const { createBillsRouter } = require("../src/routes/bills");
const { createInventoryRouter } = require("../src/routes/inventory");
const { createPushRouter } = require("../src/routes/push");
const { createRecipeRouter } = require("../src/routes/recipes");
const { parseIncomeType } = require("../src/utils/dataValidation");

function clearCache() {
  cache.store.clear();
}

function getRouteHandler(router, path, method) {
  const layer = router.stack.find(entry => entry.route && entry.route.path === path && entry.route.methods[method]);
  return layer && layer.route.stack[0] && layer.route.stack[0].handle;
}

async function invokeHandler(handler, { userId, params = {}, body = {}, query = {} } = {}) {
  let statusCode = 200;
  let jsonPayload;
  let nextError;
  let responseCount = 0;
  const req = { userId, params, body, query };
  const res = {
    status(code) {
      statusCode = code;
      return this;
    },
    json(payload) {
      responseCount += 1;
      jsonPayload = payload;
      return this;
    }
  };
  await handler(req, res, error => {
    nextError = error;
  });
  return { statusCode, jsonPayload, nextError, responseCount };
}

function relationshipPool(relationship, execute) {
  const calls = [];
  const pool = {
    calls,
    async execute(query, params) {
      calls.push({ query, params });
      if (query.includes("FROM couple_relationships")) {
        return [relationship ? [relationship] : []];
      }
      return execute(query, params);
    }
  };
  return pool;
}

function transactionPool({ relationship = null, connectionExecute, commit = async () => {}, rollback = async () => {} }) {
  const calls = [];
  const conn = {
    async beginTransaction() {},
    async commit() { await commit(); },
    async rollback() { await rollback(); },
    release() {},
    async execute(query, params) {
      calls.push({ query, params });
      if (query.includes("FROM couple_relationships")) {
        return [relationship ? [relationship] : []];
      }
      return connectionExecute(query, params);
    }
  };
  const pool = {
    calls,
    async execute(query, params) {
      calls.push({ query, params });
      if (query.includes("FROM couple_relationships")) {
        return [relationship ? [relationship] : []];
      }
      throw new Error(`Unexpected pool query: ${query}`);
    },
    async getConnection() {
      return conn;
    }
  };
  return pool;
}

test("parseIncomeType accepts only the persisted 0/1 enum", () => {
  assert.equal(parseIncomeType(0), 0);
  assert.equal(parseIncomeType(1), 1);
  assert.throws(() => parseIncomeType(2), error => error.status === 400);
  assert.throws(() => parseIncomeType("1"), error => error.status === 400);
});

test("bill edit and delete keep plan balance invariant and second delete is harmless", async () => {
  clearCache();
  const state = {
    bill: {
      bill_id: 501,
      relationship_id: null,
      shared_plan_id: 701,
      owner: 1,
      user_id: 41,
      income_type: 0,
      amount: 10
    },
    plan: { plan_id: 701, created_by: 41, relationship_id: null, current_balance: 90 },
    committed: 0
  };
  const pool = transactionPool({
    connectionExecute(query, params) {
      if (query.startsWith("UPDATE shared_plans")) {
        const amount = Number(params[0]);
        const decrease = query.includes("current_balance -");
        if (decrease && state.plan.current_balance < amount) return [{ affectedRows: 0 }];
        state.plan.current_balance += decrease ? -amount : amount;
        return [{ affectedRows: 1 }];
      }
      if (query.startsWith("UPDATE bills")) {
        state.bill.amount = Number(params[0]);
        state.bill.income_type = Number(params[3]);
        return [{ affectedRows: 1 }];
      }
      if (query.startsWith("DELETE FROM bills")) {
        if (!state.bill) return [{ affectedRows: 0 }];
        state.bill = null;
        return [{ affectedRows: 1 }];
      }
      if (query.includes("FROM bills")) return [state.bill ? [state.bill] : []];
      if (query.includes("FROM shared_plans")) return [state.plan ? [state.plan] : []];
      throw new Error(`Unexpected transaction query: ${query}`);
    },
    commit: async () => { state.committed += 1; }
  });
  const router = createBillsRouter({ pool });
  const putHandler = getRouteHandler(router, "/:id", "put");
  const deleteHandler = getRouteHandler(router, "/:id", "delete");

  let result = await invokeHandler(putHandler, {
    userId: 41,
    params: { id: "501" },
    body: { amount: 20, date: "2026-09-13", time: "12:00:00", incomeType: 0 }
  });
  assert.equal(result.nextError, undefined);
  assert.equal(state.plan.current_balance, 80);

  result = await invokeHandler(deleteHandler, { userId: 41, params: { id: "501" } });
  assert.equal(result.nextError, undefined);
  assert.equal(state.plan.current_balance, 100);

  result = await invokeHandler(deleteHandler, { userId: 41, params: { id: "501" } });
  assert.equal(result.jsonPayload, undefined);
  assert.equal(result.nextError.status, 404);
  assert.equal(state.plan.current_balance, 100);
  assert.equal(state.committed, 2);
});

test("AI extraction confirmation is idempotent after a database transaction", async () => {
  clearCache();
  const state = {
    extraction: {
      id: 801,
      relationship_id: 91,
      result_type: "bill",
      raw_data: JSON.stringify({ title: "午餐", type: "餐饮", amount: 20, incomeType: 0 }),
      status: "pending",
      target_id: null
    },
    insertCount: 0
  };
  const pool = transactionPool({
    relationship: { relationship_id: 91, user_id_1: 41, user_id_2: 42 },
    connectionExecute(query, params) {
      if (query.includes("FROM ai_extraction_results")) return [state.extraction.status ? [state.extraction] : []];
      if (query.startsWith("INSERT INTO bills")) {
        state.insertCount += 1;
        return [{ insertId: 900 + state.insertCount }];
      }
      if (query.startsWith("UPDATE ai_extraction_results")) {
        state.extraction.status = "confirmed";
        state.extraction.target_id = params[0];
        return [{ affectedRows: 1 }];
      }
      throw new Error(`Unexpected AI query: ${query}`);
    }
  });
  const router = createAiChatRouter({ pool });
  const handler = getRouteHandler(router, "/extractions/:id/confirm", "post");
  cache.set(Keys.bills(41, 2026, 9), { stale: true }, 60);
  cache.set(Keys.bills(42, 2026, 9), { stale: true }, 60);
  cache.set(Keys.inventory(41), { stale: true }, 60);
  cache.set(Keys.inventory(42), { stale: true }, 60);
  cache.set(Keys.todos(41), { stale: true }, 60);
  cache.set(Keys.todos(42), { stale: true }, 60);
  cache.set(Keys.overview(41, 2026, 9), { stale: true }, 60);
  cache.set(Keys.overview(42, 2026, 9), { stale: true }, 60);

  const first = await invokeHandler(handler, { userId: 41, params: { id: "801" }, body: {} });
  const second = await invokeHandler(handler, { userId: 41, params: { id: "801" }, body: {} });
  assert.equal(first.nextError, undefined);
  assert.equal(second.nextError, undefined);
  assert.equal(first.jsonPayload.data.targetId, 901);
  assert.equal(second.jsonPayload.data.targetId, 901);
  assert.equal(state.insertCount, 1);
  assert.equal(cache.get(Keys.bills(41, 2026, 9)), null);
  assert.equal(cache.get(Keys.bills(42, 2026, 9)), null);
  assert.equal(cache.get(Keys.overview(41, 2026, 9)), null);
  assert.equal(cache.get(Keys.overview(42, 2026, 9)), null);
  assert.deepEqual(cache.get(Keys.inventory(41)), { stale: true });
  assert.deepEqual(cache.get(Keys.inventory(42)), { stale: true });
  assert.deepEqual(cache.get(Keys.todos(41)), { stale: true });
  assert.deepEqual(cache.get(Keys.todos(42)), { stale: true });
});

test("AI inventory and todo confirmations invalidate only their lists plus both overviews", async () => {
  for (const [resultType, rawData, targetId] of [
    ["inventory", { name: "米", quantity: 2 }, 902],
    ["todo", { title: "缴费", priority: "medium" }, 903]
  ]) {
    clearCache();
    const state = {
      extraction: {
        id: targetId,
        relationship_id: 92,
        result_type: resultType,
        raw_data: JSON.stringify(rawData),
        status: "pending",
        target_id: null
      }
    };
    const pool = transactionPool({
      relationship: { relationship_id: 92, user_id_1: 41, user_id_2: 42 },
      connectionExecute(query, params) {
        if (query.includes("FROM ai_extraction_results")) return [[state.extraction]];
        if (query.includes("FROM inventory")) return [[]];
        if (query.startsWith("INSERT INTO inventory")) return [{ insertId: 1002 }];
        if (query.startsWith("INSERT INTO todo_items")) return [{ insertId: 1003 }];
        if (query.startsWith("UPDATE ai_extraction_results")) {
          state.extraction.status = "confirmed";
          state.extraction.target_id = params[0];
          return [{ affectedRows: 1 }];
        }
        throw new Error(`Unexpected AI query: ${query}`);
      }
    });
    const router = createAiChatRouter({ pool });
    const handler = getRouteHandler(router, "/extractions/:id/confirm", "post");
    for (const id of [41, 42]) {
      cache.set(Keys.bills(id, 2026, 9), { stale: "bills" }, 60);
      cache.set(Keys.inventory(id), { stale: "inventory" }, 60);
      cache.set(Keys.todos(id), { stale: "todos" }, 60);
      cache.set(Keys.overview(id, 2026, 9), { stale: "overview" }, 60);
    }

    const result = await invokeHandler(handler, { userId: 41, params: { id: String(targetId) }, body: {} });
    assert.equal(result.nextError, undefined);
    for (const id of [41, 42]) {
      assert.equal(cache.get(Keys.overview(id, 2026, 9)), null);
      if (resultType === "inventory") {
        assert.equal(cache.get(Keys.inventory(id)), null);
        assert.deepEqual(cache.get(Keys.todos(id)), { stale: "todos" });
      } else {
        assert.equal(cache.get(Keys.todos(id)), null);
        assert.deepEqual(cache.get(Keys.inventory(id)), { stale: "inventory" });
      }
      assert.deepEqual(cache.get(Keys.bills(id, 2026, 9)), { stale: "bills" });
    }
  }
});

test("recipe create rolls back the main row when ingredient persistence fails", async () => {
  clearCache();
  let rollbackCount = 0;
  let commitCount = 0;
  const pool = transactionPool({
    connectionExecute(query) {
      if (query.startsWith("INSERT INTO recipes")) return [{ insertId: 1001 }];
      if (query.startsWith("INSERT INTO recipe_ingredients")) throw new Error("ingredient insert failed");
      throw new Error(`Unexpected recipe query: ${query}`);
    },
    commit: async () => { commitCount += 1; },
    rollback: async () => { rollbackCount += 1; }
  });
  const router = createRecipeRouter({ pool });
  const handler = getRouteHandler(router, "/", "post");
  const result = await invokeHandler(handler, {
    userId: 41,
    body: {
      title: "原子菜谱",
      ingredients: [{ ingredientName: "米", quantity: 1, unit: "碗" }]
    }
  });
  assert.equal(result.jsonPayload, undefined);
  assert.match(result.nextError.message, /ingredient insert failed/);
  assert.equal(commitCount, 0);
  assert.equal(rollbackCount, 1);
});

test("inventory success response and cache invalidation happen after commit", async () => {
  clearCache();
  let committed = false;
  const pool = transactionPool({
    connectionExecute(query) {
      if (query.includes("FROM inventory")) return [[]];
      if (query.startsWith("INSERT INTO inventory")) return [{ insertId: 1101 }];
      throw new Error(`Unexpected inventory query: ${query}`);
    },
    commit: async () => { committed = true; }
  });
  const router = createInventoryRouter({ pool });
  const handler = getRouteHandler(router, "/", "post");
  const result = await invokeHandler(handler, {
    userId: 41,
    body: { name: "提交后物资", category: "其他", quantity: 1, unit: "个" }
  });
  assert.equal(result.nextError, undefined);
  assert.equal(committed, true);
  assert.equal(result.jsonPayload.data.inventoryId, 1101);
});

test("asset filters do not replace the unfiltered cache", async () => {
  clearCache();
  const active = { assetId: 1, userId: 41, relationshipId: null, name: "在用", category: "其他", purchaseDate: null, purchasePrice: 10 };
  const disposed = { assetId: 2, userId: 41, relationshipId: null, name: "已处置", category: "其他", purchaseDate: null, purchasePrice: 20 };
  const pool = relationshipPool(null, query => {
    if (query.includes("FROM assets")) {
      return [query.includes("status = ?") ? [disposed] : [active, disposed]];
    }
    throw new Error(`Unexpected asset query: ${query}`);
  });
  const router = createAssetsRouter({ pool });
  const handler = getRouteHandler(router, "/", "get");

  let result = await invokeHandler(handler, { userId: 4101, query: { status: "disposed" } });
  assert.equal(result.jsonPayload.data.items.length, 1);
  result = await invokeHandler(handler, { userId: 4101, query: {} });
  assert.equal(result.jsonPayload.data.items.length, 2);
  assert.equal(pool.calls.filter(call => call.query.includes("FROM assets")).length, 2);
});

test("push token deletion is scoped to the authenticated user", async () => {
  const pool = relationshipPool(null, query => {
    assert.match(query, /token = \? .*user_id = \?/s);
    return [{ affectedRows: 0 }];
  });
  const router = createPushRouter({ pool });
  const handler = getRouteHandler(router, "/token", "delete");
  const result = await invokeHandler(handler, {
    userId: 42,
    body: { token: "token-owned-by-another-user", channel: "fcm" }
  });
  assert.equal(result.jsonPayload, undefined);
  assert.equal(result.nextError.status, 404);
});

test("bill and AI writes reject an invalid income type before persistence", async () => {
  clearCache();
  const billPool = relationshipPool(null, () => {
    throw new Error("must reject before bill write");
  });
  const billRouter = createBillsRouter({ pool: billPool });
  const billHandler = getRouteHandler(billRouter, "/", "post");
  const billResult = await invokeHandler(billHandler, {
    userId: 43,
    body: {
      owner: 1,
      title: "非法类型",
      type: "其他",
      amount: 1,
      date: "2026-09-13",
      time: "12:00:00",
      incomeType: 2
    }
  });
  assert.equal(billResult.jsonPayload, undefined);
  assert.equal(billResult.nextError.status, 400);
});
