const assert = require("assert");
const { test } = require("node:test");

const {
  createInventoryRouter,
  deriveShelfLifeFields,
  addExpirationFlags,
  EXPIRING_WINDOW_DAYS
} = require("../src/routes/inventory");
const { cache } = require("../src/cache");

function clearCache() {
  cache.store.clear();
}

function createPoolMock(responses) {
  const calls = [];

  return {
    calls,
    async execute(query, params) {
      calls.push({ query, params });
      if (responses.length === 0) {
        throw new Error(`Unexpected query: ${query}`);
      }
      const next = responses.shift();
      if (typeof next === "function") {
        return next(query, params);
      }
      return next;
    }
  };
}

function getRouteHandler(router, path, method) {
  const layer = router.stack.find(entry => entry.route && entry.route.path === path && entry.route.methods[method]);
  return layer && layer.route.stack[0] && layer.route.stack[0].handle;
}

async function invokeHandler(handler, { params = {}, body = {}, query = {} } = {}) {
  let statusCode = 200;
  let jsonPayload;
  let nextError;

  const req = { params, body, query };
  const res = {
    status(code) {
      statusCode = code;
      return this;
    },
    json(payload) {
      jsonPayload = payload;
      return this;
    }
  };

  await handler(req, res, error => {
    nextError = error;
  });

  return { statusCode, jsonPayload, nextError };
}

test("deriveShelfLifeFields calculates canonical expiration date in calc mode", () => {
  assert.deepEqual(deriveShelfLifeFields({
    expirationMode: "calc",
    productionDate: "2026-04-10",
    shelfLifeDays: 5
  }), {
    expirationMode: "calc",
    expirationDate: "2026-04-15",
    productionDate: "2026-04-10",
    shelfLifeDays: 5
  });
});

test("addExpirationFlags marks items as expiring within the configured window", () => {
  const baseDate = new Date(Date.UTC(2026, 3, 21));
  const expirationDate = "2026-04-" + String(21 + EXPIRING_WINDOW_DAYS).padStart(2, "0");

  assert.deepEqual(addExpirationFlags({ inventoryId: 1, expirationDate }, baseDate), {
    inventoryId: 1,
    expirationDate,
    isExpiring: true,
    isExpired: false
  });
});

test("post route merge response uses persisted stored fields instead of unsaved request values", async () => {
  clearCache();
  const pool = createPoolMock([
    [[]],
    [[{
      inventory_id: 42,
      category: "Dairy",
      quantity: 2,
      unit: "box",
      threshold: 1,
      image_url: "https://example.com/old.png",
      note: "keep stored note",
      ai_image_prompt: "keep stored prompt",
      expiration_mode: "calc",
      expiration_date: "2026-04-30",
      production_date: "2026-04-20",
      shelf_life_days: 10
    }]],
    [{ affectedRows: 1 }]
  ]);
  const router = createInventoryRouter({ pool });
  const postHandler = getRouteHandler(router, "/", "post");

  assert.equal(typeof postHandler, "function");

  const result = await invokeHandler(postHandler, {
    body: {
      userId: 7,
      name: "Milk",
      category: "Produce",
      quantity: 3,
      unit: "bag",
      threshold: 9,
      note: null,
      aiImagePrompt: "ignored request prompt"
    }
  });

  assert.equal(result.nextError, undefined);
  assert.equal(result.statusCode, 200);
  assert.equal(result.jsonPayload.message, "已合并到同名物资");
  assert.match(pool.calls[2].query, /UPDATE inventory SET quantity = \?, updated_at = NOW\(\) WHERE inventory_id = \?/);
  assert.doesNotMatch(pool.calls[2].query, /expiration_mode|expiration_date|production_date|shelf_life_days/);
  assert.deepEqual(pool.calls[2].params, [5, 42]);
  assert.equal(result.jsonPayload.data.category, "Dairy");
  assert.equal(result.jsonPayload.data.unit, "box");
  assert.equal(result.jsonPayload.data.threshold, 1);
  assert.equal(result.jsonPayload.data.note, "keep stored note");
  assert.equal(result.jsonPayload.data.aiImagePrompt, "keep stored prompt");
  assert.equal(result.jsonPayload.data.expirationMode, "calc");
  assert.equal(result.jsonPayload.data.expirationDate, "2026-04-30");
  assert.equal(result.jsonPayload.data.productionDate, "2026-04-20");
  assert.equal(result.jsonPayload.data.shelfLifeDays, 10);
});

test("put route recalculates calc shelf life when only shelf-life days change", async () => {
  clearCache();
  const pool = createPoolMock([
    [[]],
    [[{ inventory_id: 42 }]],
    [[{
      expirationMode: "calc",
      expirationDate: "2026-04-25",
      productionDate: "2026-04-20",
      shelfLifeDays: 5
    }]],
    [{ affectedRows: 1 }]
  ]);
  const router = createInventoryRouter({ pool });
  const putHandler = getRouteHandler(router, "/:id", "put");

  assert.equal(typeof putHandler, "function");

  const result = await invokeHandler(putHandler, {
    params: { id: "42" },
    body: {
      userId: 7,
      shelfLifeDays: 7
    }
  });

  assert.equal(result.nextError, undefined);
  assert.equal(result.statusCode, 200);
  assert.equal(result.jsonPayload.message, "存货更新成功");
  assert.match(pool.calls[3].query, /expiration_mode = \?, expiration_date = \?, production_date = \?, shelf_life_days = \?, updated_at = NOW\(\) WHERE inventory_id = \?/);
  assert.deepEqual(pool.calls[3].params, ["calc", "2026-04-27", "2026-04-20", 7, 42]);
});

test("put route switches from date mode to calc mode with a recalculated expiration date", async () => {
  clearCache();
  const pool = createPoolMock([
    [[]],
    [[{ inventory_id: 42 }]],
    [[{
      expirationMode: "date",
      expirationDate: "2026-04-25",
      productionDate: null,
      shelfLifeDays: null
    }]],
    [{ affectedRows: 1 }]
  ]);
  const router = createInventoryRouter({ pool });
  const putHandler = getRouteHandler(router, "/:id", "put");

  const result = await invokeHandler(putHandler, {
    params: { id: "42" },
    body: {
      userId: 7,
      expirationMode: "calc",
      productionDate: "2026-04-20",
      shelfLifeDays: 6
    }
  });

  assert.equal(result.nextError, undefined);
  assert.equal(result.statusCode, 200);
  assert.deepEqual(pool.calls[3].params, ["calc", "2026-04-26", "2026-04-20", 6, 42]);
});

test("consume route subtracts quantity and updates last consumed timestamp", async () => {
  clearCache();
  const pool = createPoolMock([
    [[]],
    [[{ quantity: 10 }]],
    [{ affectedRows: 1 }]
  ]);
  const router = createInventoryRouter({ pool });
  const consumeHandler = getRouteHandler(router, "/:id/consume", "post");

  assert.equal(typeof consumeHandler, "function");

  const result = await invokeHandler(consumeHandler, {
    params: { id: "42" },
    body: { userId: 7, consumeAmount: 3 }
  });

  assert.equal(result.nextError, undefined);
  assert.equal(result.statusCode, 200);
  assert.equal(result.jsonPayload.ok, true);
  assert.equal(result.jsonPayload.message, "消耗记录成功");
  assert.deepEqual(result.jsonPayload.data, {
    previousQuantity: 10,
    consumed: 3,
    remainingQuantity: 7
  });
  assert.match(pool.calls[2].query, /quantity = quantity - \?, last_consumed_at = NOW\(\), updated_at = NOW\(\)/);
  assert.deepEqual(pool.calls[2].params, [3, 42]);
});

test("consume route rejects insufficient stock before updating", async () => {
  clearCache();
  const pool = createPoolMock([
    [[]],
    [[{ quantity: 2 }]]
  ]);
  const router = createInventoryRouter({ pool });
  const consumeHandler = getRouteHandler(router, "/:id/consume", "post");

  const result = await invokeHandler(consumeHandler, {
    params: { id: "5" },
    body: { userId: 9, consumeAmount: 3 }
  });

  assert.equal(result.jsonPayload, undefined);
  assert.equal(result.nextError.status, 400);
  assert.equal(result.nextError.code, "INSUFFICIENT_STOCK");
  assert.match(result.nextError.message, /存量不足/);
  assert.equal(pool.calls.length, 2);
});

test("replenish route exists and adds quantity", async () => {
  clearCache();
  const pool = createPoolMock([
    [[]],
    [[{ quantity: 4 }]],
    [{ affectedRows: 1 }]
  ]);
  const router = createInventoryRouter({ pool });
  const replenishHandler = getRouteHandler(router, "/:id/replenish", "post");

  assert.equal(typeof replenishHandler, "function");

  const result = await invokeHandler(replenishHandler, {
    params: { id: "11" },
    body: { userId: 12, addAmount: 6 }
  });

  assert.equal(result.nextError, undefined);
  assert.equal(result.statusCode, 200);
  assert.equal(result.jsonPayload.message, "补货成功");
  assert.deepEqual(result.jsonPayload.data, {
    previousQuantity: 4,
    added: 6,
    currentQuantity: 10
  });
  assert.match(pool.calls[2].query, /quantity = quantity \+ \?, updated_at = NOW\(\)/);
  assert.deepEqual(pool.calls[2].params, [6, 11]);
});
