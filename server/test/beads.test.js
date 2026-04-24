const assert = require("assert");
const fs = require("fs");
const path = require("path");
const { test } = require("node:test");

const {
  BEAD_COLORS,
  mapBeadInventoryRow,
  resolveBeadThreshold,
  buildBeadSummary,
  createBeadRouter
} = require("../src/routes/beads");
const { cache, Keys, TTL } = require("../src/cache");

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

function createConnectionMock(responses) {
  const calls = [];
  let beginTransactionCount = 0;
  let commitCount = 0;
  let rollbackCount = 0;
  let releaseCount = 0;

  return {
    calls,
    get beginTransactionCount() {
      return beginTransactionCount;
    },
    get commitCount() {
      return commitCount;
    },
    get rollbackCount() {
      return rollbackCount;
    },
    get releaseCount() {
      return releaseCount;
    },
    async beginTransaction() {
      beginTransactionCount += 1;
    },
    async commit() {
      commitCount += 1;
    },
    async rollback() {
      rollbackCount += 1;
    },
    release() {
      releaseCount += 1;
    },
    async execute(query, params) {
      calls.push({ query, params });
      if (responses.length === 0) {
        throw new Error(`Unexpected connection query: ${query}`);
      }
      const next = responses.shift();
      if (typeof next === "function") {
        return next(query, params);
      }
      return next;
    }
  };
}

function createTransactionalPoolMock({ poolResponses = [], connectionResponses = [] }) {
  const pool = createPoolMock(poolResponses);
  const connection = createConnectionMock(connectionResponses);

  return {
    ...pool,
    connection,
    async getConnection() {
      return connection;
    }
  };
}
function getRouteHandler(router, routePath, method) {
  const layer = router.stack.find(entry => entry.route && entry.route.path === routePath && entry.route.methods[method]);
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

function buildInventoryRows({
  quantities = {},
  thresholdOverrides = {},
  totalConsumed = {},
  defaultThreshold = 200
} = {}) {
  return BEAD_COLORS.map(color => ({
    color_code: color.colorCode,
    hex_color: color.hexColor,
    color_group: color.colorGroup,
    is_transparent: color.isTransparent ? 1 : 0,
    quantity: quantities[color.colorCode] ?? 0,
    threshold_override: Object.prototype.hasOwnProperty.call(thresholdOverrides, color.colorCode)
      ? thresholdOverrides[color.colorCode]
      : null,
    default_threshold: defaultThreshold,
    total_consumed: totalConsumed[color.colorCode] ?? 0
  }));
}

test("bead palette exposes exactly 221 colors and expected canonical endpoints", () => {
  assert.equal(BEAD_COLORS.length, 221);
  assert.deepEqual(BEAD_COLORS[0], {
    colorCode: "A01",
    hexColor: "#faf5cd",
    colorGroup: "A",
    isTransparent: false
  });
  assert.deepEqual(BEAD_COLORS[1], {
    colorCode: "A02",
    hexColor: "#f6d7d2",
    colorGroup: "A",
    isTransparent: false
  });
  assert.deepEqual(BEAD_COLORS[2], {
    colorCode: "A03",
    hexColor: "#f7efe3",
    colorGroup: "A",
    isTransparent: false
  });
  assert.deepEqual(BEAD_COLORS[3], {
    colorCode: "A04",
    hexColor: "#e8d6b8",
    colorGroup: "A",
    isTransparent: false
  });
  assert.deepEqual(BEAD_COLORS[4], {
    colorCode: "A05",
    hexColor: "#f5c266",
    colorGroup: "A",
    isTransparent: false
  });
  assert.deepEqual(BEAD_COLORS[220], {
    colorCode: "M15",
    hexColor: "#757D78",
    colorGroup: "M",
    isTransparent: false
  });
});

test("threshold override wins over default threshold", () => {
  assert.equal(resolveBeadThreshold({ thresholdOverride: 320 }, 200), 320);
  assert.equal(resolveBeadThreshold({ thresholdOverride: null }, 200), 200);
  assert.equal(resolveBeadThreshold({ thresholdOverride: undefined }, 200), 200);
});

test("summary marks low stock using override and reports total consumption", () => {
  const summary = buildBeadSummary(
    [
      mapBeadInventoryRow({ color_code: "A01", hex_color: "#faf5cd", quantity: 150, threshold_override: null, default_threshold: 200, total_consumed: 10 }),
      mapBeadInventoryRow({ color_code: "A02", hex_color: "#f6d7d2", quantity: 310, threshold_override: 320, default_threshold: 200, total_consumed: 30 }),
      mapBeadInventoryRow({ color_code: "A03", hex_color: "#f7efe3", quantity: 500, threshold_override: null, default_threshold: 200, total_consumed: 5 })
    ]
  );

  assert.deepEqual(summary, {
    totalColors: 3,
    lowStockCount: 2,
    totalConsumptionReference: 45
  });
});

test("migration creates bead tables, indexes, and palette seed data", () => {
  const sql = fs.readFileSync(
    path.join(__dirname, "../migrations/011_create_bead_tables.sql"),
    "utf8"
  );

  assert.match(sql, /CREATE TABLE bead_colors/i);
  assert.match(sql, /CREATE TABLE bead_inventory/i);
  assert.match(sql, /CREATE TABLE bead_settings/i);
  assert.match(sql, /CREATE TABLE bead_blueprints/i);
  assert.match(sql, /CREATE TABLE bead_blueprint_colors/i);
  assert.match(sql, /UNIQUE KEY uk_bead_inventory_user_color \(user_id, color_code\)/i);
  assert.match(sql, /UNIQUE KEY uk_bead_blueprint_color \(blueprint_id, color_code\)/i);
  assert.match(sql, /INSERT INTO bead_colors \(color_code, hex_color, color_group, is_transparent\) VALUES/i);
  assert.match(sql, /\('A01', '#faf5cd', 'A', 0\)/i);
  assert.match(sql, /\('A05', '#f5c266', 'A', 0\)/i);
  assert.match(sql, /\('M15', '#757D78', 'M', 0\)/i);
});

test("cache helpers expose bead keys and ttl", () => {
  assert.equal(Keys.beads(7), "beads:7");
  assert.equal(Keys.beadBlueprints(7), "bead-blueprints:7");
  assert.equal(TTL.BEADS, 15);
});

test("server index registers the bead router", () => {
  const source = fs.readFileSync(
    path.join(__dirname, "../src/index.js"),
    "utf8"
  );

  assert.match(source, /const\s+\{\s*createBeadRouter\s*\}\s*=\s*require\("\.\/routes\/beads"\);/);
  assert.match(source, /app\.use\("\/api\/beads",\s*createBeadRouter\(\{\s*pool\s*\}\)\);/);
});

test("bead router exposes all required endpoints", () => {
  const router = createBeadRouter({ pool: createPoolMock([]) });

  assert.equal(typeof getRouteHandler(router, "/colors", "get"), "function");
  assert.equal(typeof getRouteHandler(router, "/inventory", "get"), "function");
  assert.equal(typeof getRouteHandler(router, "/inventory/:colorCode", "put"), "function");
  assert.equal(typeof getRouteHandler(router, "/inventory/:colorCode/consume", "post"), "function");
  assert.equal(typeof getRouteHandler(router, "/inventory/:colorCode/replenish", "post"), "function");
  assert.equal(typeof getRouteHandler(router, "/inventory/low-stock", "get"), "function");
  assert.equal(typeof getRouteHandler(router, "/inventory/summary", "get"), "function");
  assert.equal(typeof getRouteHandler(router, "/settings", "get"), "function");
  assert.equal(typeof getRouteHandler(router, "/settings", "put"), "function");
  assert.equal(typeof getRouteHandler(router, "/blueprints", "get"), "function");
  assert.equal(typeof getRouteHandler(router, "/blueprints", "post"), "function");
  assert.equal(typeof getRouteHandler(router, "/blueprints/:id", "get"), "function");
  assert.equal(typeof getRouteHandler(router, "/blueprints/:id", "put"), "function");
  assert.equal(typeof getRouteHandler(router, "/blueprints/:id", "delete"), "function");
  assert.equal(typeof getRouteHandler(router, "/blueprints/:id/build", "post"), "function");
});

test("get inventory seeds missing rows, returns summary, and exposes relationship metadata while staying user-scoped", async () => {
  clearCache();
  const pool = createPoolMock([
    [{ affectedRows: 1 }],
    [{ affectedRows: 221 }],
    [buildInventoryRows({
      quantities: { A01: 5, A02: 320 },
      thresholdOverrides: { A02: 350 },
      totalConsumed: { A01: 12, A02: 40 }
    })],
    [[{ relationship_id: 9, user_id_1: 7, user_id_2: 8 }]]
  ]);
  const router = createBeadRouter({ pool });
  const handler = getRouteHandler(router, "/inventory", "get");

  assert.equal(typeof handler, "function");

  const result = await invokeHandler(handler, {
    query: { userId: "7" }
  });

  assert.equal(result.nextError, undefined);
  assert.equal(result.statusCode, 200);
  assert.equal(result.jsonPayload.ok, true);
  assert.equal(result.jsonPayload.data.relationshipId, 9);
  assert.deepEqual(result.jsonPayload.data.summary, {
    totalColors: 221,
    lowStockCount: 221,
    totalConsumptionReference: 52
  });
  assert.equal(result.jsonPayload.data.items.length, 221);
  assert.match(pool.calls[0].query, /INSERT INTO bead_settings/i);
  assert.match(pool.calls[1].query, /INSERT IGNORE INTO bead_inventory/i);
  assert.match(pool.calls[1].query, /FROM bead_colors/i);
  assert.match(pool.calls[1].query, /NULL/);
  assert.match(pool.calls[2].query, /FROM bead_colors bc/i);

  const firstItem = result.jsonPayload.data.items.find(item => item.colorCode === "A01");
  assert.deepEqual(firstItem, {
    colorCode: "A01",
    hexColor: "#faf5cd",
    quantity: 5,
    thresholdOverride: null,
    defaultThreshold: 200,
    totalConsumed: 12,
    colorGroup: "A",
    isTransparent: false,
    isLowStock: true
  });
});

test("put inventory updates quantity and threshold override and invalidates bead cache", async () => {
  clearCache();
  cache.set(Keys.beads(7), { ok: true }, TTL.BEADS);
  cache.set(Keys.beadBlueprints(7), { ok: true }, TTL.BEADS);

  const pool = createPoolMock([
    [{ affectedRows: 1 }],
    [{ affectedRows: 221 }],
    [{ affectedRows: 1 }]
  ]);
  const router = createBeadRouter({ pool });
  const handler = getRouteHandler(router, "/inventory/:colorCode", "put");

  const result = await invokeHandler(handler, {
    params: { colorCode: "a1" },
    body: {
      userId: 7,
      quantity: 88,
      thresholdOverride: 250
    }
  });

  assert.equal(result.nextError, undefined);
  assert.equal(result.statusCode, 200);
  assert.equal(result.jsonPayload.message, "库存更新成功");
  assert.match(pool.calls[2].query, /UPDATE bead_inventory SET quantity = \?, threshold_override = \?, updated_at = NOW\(\) WHERE color_code = \? AND user_id = \?/i);
  assert.deepEqual(pool.calls[2].params, [88, 250, "A01", 7]);
  assert.equal(cache.get(Keys.beads(7)), null);
  assert.equal(cache.get(Keys.beadBlueprints(7)), null);
});

test("consume route uses an atomic conditional update and reports insufficient stock when nothing is updated", async () => {
  clearCache();
  const pool = createPoolMock([
    [{ affectedRows: 1 }],
    [{ affectedRows: 221 }],
    [{ affectedRows: 0 }]
  ]);
  const router = createBeadRouter({ pool });
  const handler = getRouteHandler(router, "/inventory/:colorCode/consume", "post");

  const result = await invokeHandler(handler, {
    params: { colorCode: "A01" },
    body: {
      userId: 7,
      consumeAmount: 3
    }
  });

  assert.equal(result.statusCode, 200);
  assert.equal(result.jsonPayload, undefined);
  assert.equal(result.nextError.status, 400);
  assert.equal(result.nextError.code, "INSUFFICIENT_STOCK");
  assert.equal(result.nextError.message, "库存不足");
  assert.equal(pool.calls.length, 3);
  assert.match(pool.calls[2].query, /UPDATE bead_inventory[\s\S]*quantity = quantity - \?[\s\S]*quantity >= \?/i);
  assert.deepEqual(pool.calls[2].params, [3, "A01", 7, 3]);
});

test("consume route returns consumed color code after atomic success", async () => {
  clearCache();
  cache.set(Keys.beads(7), { ok: true }, TTL.BEADS);
  cache.set(Keys.beadBlueprints(7), { ok: true }, TTL.BEADS);

  const pool = createPoolMock([
    [{ affectedRows: 1 }],
    [{ affectedRows: 221 }],
    [{ affectedRows: 1 }]
  ]);
  const router = createBeadRouter({ pool });
  const handler = getRouteHandler(router, "/inventory/:colorCode/consume", "post");

  const result = await invokeHandler(handler, {
    params: { colorCode: "a1" },
    body: {
      userId: 7,
      consumeAmount: 2
    }
  });

  assert.equal(result.nextError, undefined);
  assert.equal(result.statusCode, 200);
  assert.deepEqual(result.jsonPayload, {
    ok: true,
    message: "消耗记录成功",
    data: {
      colorCode: "A01",
      consumed: 2
    }
  });
  assert.equal(cache.get(Keys.beads(7)), null);
  assert.equal(cache.get(Keys.beadBlueprints(7)), null);
});

test("get low-stock returns only colors at or below the effective threshold", async () => {
  clearCache();
  const pool = createPoolMock([
    [{ affectedRows: 1 }],
    [{ affectedRows: 3 }],
    [[
      { color_code: "A01", hex_color: "#faf5cd", color_group: "A", is_transparent: 0, quantity: 150, threshold_override: null, default_threshold: 200, total_consumed: 4 },
      { color_code: "A02", hex_color: "#f6d7d2", color_group: "A", is_transparent: 0, quantity: 310, threshold_override: 320, default_threshold: 200, total_consumed: 8 },
      { color_code: "A03", hex_color: "#f7efe3", color_group: "A", is_transparent: 0, quantity: 500, threshold_override: null, default_threshold: 200, total_consumed: 1 }
    ]],
    [[]]
  ]);
  const router = createBeadRouter({ pool });
  const handler = getRouteHandler(router, "/inventory/low-stock", "get");

  const result = await invokeHandler(handler, {
    query: { userId: "7" }
  });

  assert.equal(result.nextError, undefined);
  assert.equal(result.statusCode, 200);
  assert.deepEqual(result.jsonPayload.data.items.map(item => item.colorCode), ["A01", "A02"]);
  assert.equal(result.jsonPayload.data.count, 2);
});

test("get inventory summary reports low-stock count and total blueprint consumption reference", async () => {
  clearCache();
  const pool = createPoolMock([
    [{ affectedRows: 1 }],
    [{ affectedRows: 3 }],
    [[
      { color_code: "A01", hex_color: "#faf5cd", color_group: "A", is_transparent: 0, quantity: 150, threshold_override: null, default_threshold: 200, total_consumed: 10 },
      { color_code: "A02", hex_color: "#f6d7d2", color_group: "A", is_transparent: 0, quantity: 310, threshold_override: 320, default_threshold: 200, total_consumed: 30 },
      { color_code: "A03", hex_color: "#f7efe3", color_group: "A", is_transparent: 0, quantity: 500, threshold_override: null, default_threshold: 200, total_consumed: 5 }
    ]],
    [[]]
  ]);
  const router = createBeadRouter({ pool });
  const handler = getRouteHandler(router, "/inventory/summary", "get");

  const result = await invokeHandler(handler, {
    query: { userId: "7" }
  });

  assert.equal(result.nextError, undefined);
  assert.equal(result.statusCode, 200);
  assert.deepEqual(result.jsonPayload.data, {
    totalColors: 3,
    lowStockCount: 2,
    totalConsumptionReference: 45
  });
});

test("get settings ensures default row exists and returns the stored threshold", async () => {
  clearCache();
  const pool = createPoolMock([
    [{ affectedRows: 1 }],
    [[{ default_threshold: 260 }]]
  ]);
  const router = createBeadRouter({ pool });
  const handler = getRouteHandler(router, "/settings", "get");

  const result = await invokeHandler(handler, {
    query: { userId: "7" }
  });

  assert.equal(result.nextError, undefined);
  assert.equal(result.statusCode, 200);
  assert.equal(result.jsonPayload.data.defaultThreshold, 260);
  assert.match(pool.calls[0].query, /INSERT INTO bead_settings/i);
  assert.match(pool.calls[1].query, /SELECT default_threshold FROM bead_settings/i);
});

test("put settings updates the default threshold and invalidates bead cache", async () => {
  clearCache();
  cache.set(Keys.beads(7), { ok: true }, TTL.BEADS);
  cache.set(Keys.beadBlueprints(7), { ok: true }, TTL.BEADS);

  const pool = createPoolMock([
    [{ affectedRows: 1 }]
  ]);
  const router = createBeadRouter({ pool });
  const handler = getRouteHandler(router, "/settings", "put");

  const result = await invokeHandler(handler, {
    body: { userId: 7, defaultThreshold: 240 }
  });

  assert.equal(result.nextError, undefined);
  assert.equal(result.statusCode, 200);
  assert.equal(result.jsonPayload.message, "默认阈值更新成功");
  assert.match(pool.calls[0].query, /INSERT INTO bead_settings/i);
  assert.equal(cache.get(Keys.beads(7)), null);
  assert.equal(cache.get(Keys.beadBlueprints(7)), null);
});

test("list blueprints returns aggregate counts per blueprint", async () => {
  clearCache();
  const pool = createPoolMock([
    [[{
      blueprint_id: 8,
      user_id: 7,
      name: "Flower",
      build_count: 3,
      created_at: "2026-04-23 00:00:00",
      updated_at: "2026-04-23 00:00:00",
      color_count: 2,
      total_beads_per_build: 7
    }]]
  ]);
  const router = createBeadRouter({ pool });
  const handler = getRouteHandler(router, "/blueprints", "get");

  const result = await invokeHandler(handler, {
    query: { userId: "7" }
  });

  assert.equal(result.nextError, undefined);
  assert.equal(result.statusCode, 200);
  assert.deepEqual(result.jsonPayload.data.items, [{
    blueprintId: 8,
    userId: 7,
    name: "Flower",
    imageUrl: null,
    buildCount: 3,
    colorCount: 2,
    totalBeadsPerBuild: 7,
    createdAt: "2026-04-23 00:00:00",
    updatedAt: "2026-04-23 00:00:00"
  }]);
});

test("create blueprint accepts planned quantityPerBuild payload while preserving stored quantity semantics", async () => {
  clearCache();
  const pool = createTransactionalPoolMock({
    connectionResponses: [
      [{ insertId: 12 }],
      [{ affectedRows: 1 }]
    ]
  });
  const router = createBeadRouter({ pool });
  const handler = getRouteHandler(router, "/blueprints", "post");

  const result = await invokeHandler(handler, {
    body: {
      userId: 7,
      name: "Solo Flower",
      colors: [
        { colorCode: "A01", quantityPerBuild: 2 }
      ]
    }
  });

  assert.equal(result.nextError, undefined);
  assert.equal(result.statusCode, 201);
  assert.match(pool.connection.calls[1].query, /INSERT INTO bead_blueprint_colors/i);
  assert.deepEqual(pool.connection.calls[1].params, [12, "A01", 2]);
});


test("create blueprint rolls back the transaction when color insert fails", async () => {
  clearCache();
  const pool = createTransactionalPoolMock({
    connectionResponses: [
      [{ insertId: 12 }],
      () => {
        throw new Error("insert colors failed");
      }
    ]
  });
  const router = createBeadRouter({ pool });
  const handler = getRouteHandler(router, "/blueprints", "post");

  const result = await invokeHandler(handler, {
    body: {
      userId: 7,
      name: "Solo Flower",
      colors: [
        { colorCode: "A01", quantity: 2 }
      ]
    }
  });

  assert.equal(result.statusCode, 200);
  assert.equal(result.jsonPayload, undefined);
  assert.equal(result.nextError.message, "insert colors failed");
  assert.equal(pool.connection.beginTransactionCount, 1);
  assert.equal(pool.connection.commitCount, 0);
  assert.equal(pool.connection.rollbackCount, 1);
  assert.equal(pool.connection.releaseCount, 1);
});

test("get blueprint detail computes totalConsumed from quantityPerBuild and buildCount", async () => {
  clearCache();
  const pool = createPoolMock([
    [[{
      blueprint_id: 8,
      user_id: 7,
      name: "Flower",
      build_count: 3,
      created_at: "2026-04-23 00:00:00",
      updated_at: "2026-04-23 00:00:00"
    }]],
    [[
      { color_code: "A01", hex_color: "#faf5cd", color_group: "A", is_transparent: 0, quantity: 2 },
      { color_code: "A02", hex_color: "#f6d7d2", color_group: "A", is_transparent: 0, quantity: 5 }
    ]]
  ]);
  const router = createBeadRouter({ pool });
  const handler = getRouteHandler(router, "/blueprints/:id", "get");

  const result = await invokeHandler(handler, {
    params: { id: "8" },
    query: { userId: "7" }
  });

  assert.equal(result.nextError, undefined);
  assert.equal(result.statusCode, 200);
  assert.equal(result.jsonPayload.data.buildCount, 3);
  assert.equal(result.jsonPayload.data.totalBeadsPerBuild, 7);
  assert.equal(result.jsonPayload.data.totalConsumed, 21);
  assert.deepEqual(result.jsonPayload.data.colors, [
    {
      colorCode: "A01",
      hexColor: "#faf5cd",
      colorGroup: "A",
      isTransparent: false,
      quantityPerBuild: 2,
      totalConsumed: 6
    },
    {
      colorCode: "A02",
      hexColor: "#f6d7d2",
      colorGroup: "A",
      isTransparent: false,
      quantityPerBuild: 5,
      totalConsumed: 15
    }
  ]);
});

test("update blueprint replaces its name and color rows inside a transaction", async () => {
  clearCache();
  const pool = createTransactionalPoolMock({
    poolResponses: [
      [[{
        blueprint_id: 8,
        user_id: 7,
        name: "Old Name",
        build_count: 1,
        created_at: "2026-04-23 00:00:00",
        updated_at: "2026-04-23 00:00:00"
      }]]
    ],
    connectionResponses: [
      [{ affectedRows: 1 }],
      [{ affectedRows: 2 }],
      [{ affectedRows: 2 }]
    ]
  });
  const router = createBeadRouter({ pool });
  const handler = getRouteHandler(router, "/blueprints/:id", "put");

  const result = await invokeHandler(handler, {
    params: { id: "8" },
    body: {
      userId: 7,
      name: "New Name",
      colors: [
        { colorCode: "A01", quantity: 9 },
        { colorCode: "A03", quantity: 4 }
      ]
    }
  });

  assert.equal(result.nextError, undefined);
  assert.equal(result.statusCode, 200);
  assert.equal(result.jsonPayload.message, "图纸更新成功");
  assert.equal(pool.connection.beginTransactionCount, 1);
  assert.equal(pool.connection.commitCount, 1);
  assert.equal(pool.connection.rollbackCount, 0);
  assert.equal(pool.connection.releaseCount, 1);
  assert.match(pool.connection.calls[0].query, /UPDATE bead_blueprints SET name = \?, updated_at = NOW\(\) WHERE blueprint_id = \? AND user_id = \?/i);
  assert.deepEqual(pool.connection.calls[0].params, ["New Name", 8, 7]);
  assert.match(pool.connection.calls[1].query, /DELETE FROM bead_blueprint_colors WHERE blueprint_id = \?/i);
  assert.deepEqual(pool.connection.calls[1].params, [8]);
  assert.match(pool.connection.calls[2].query, /INSERT INTO bead_blueprint_colors/i);
});

test("update blueprint accepts planned quantityPerBuild payload while preserving stored quantity semantics", async () => {
  clearCache();
  const pool = createTransactionalPoolMock({
    poolResponses: [
      [[{
        blueprint_id: 8,
        user_id: 7,
        name: "Old Name",
        build_count: 1,
        created_at: "2026-04-23 00:00:00",
        updated_at: "2026-04-23 00:00:00"
      }]]
    ],
    connectionResponses: [
      [{ affectedRows: 1 }],
      [{ affectedRows: 2 }],
      [{ affectedRows: 1 }]
    ]
  });
  const router = createBeadRouter({ pool });
  const handler = getRouteHandler(router, "/blueprints/:id", "put");

  const result = await invokeHandler(handler, {
    params: { id: "8" },
    body: {
      userId: 7,
      colors: [
        { colorCode: "A03", quantityPerBuild: 4 }
      ]
    }
  });

  assert.equal(result.nextError, undefined);
  assert.equal(result.statusCode, 200);
  assert.match(pool.connection.calls[2].query, /INSERT INTO bead_blueprint_colors/i);
  assert.deepEqual(pool.connection.calls[2].params, [8, "A03", 4]);
});

test("delete blueprint removes the user-owned blueprint", async () => {
  clearCache();
  const pool = createPoolMock([
    [{ affectedRows: 1 }]
  ]);
  const router = createBeadRouter({ pool });
  const handler = getRouteHandler(router, "/blueprints/:id", "delete");

  const result = await invokeHandler(handler, {
    params: { id: "8" },
    query: { userId: "7" }
  });

  assert.equal(result.nextError, undefined);
  assert.equal(result.statusCode, 200);
  assert.equal(result.jsonPayload.message, "图纸删除成功");
  assert.match(pool.calls[0].query, /DELETE FROM bead_blueprints WHERE blueprint_id = \? AND user_id = \?/i);
  assert.deepEqual(pool.calls[0].params, [8, 7]);
});

test("build route returns planned buildCount alongside legacy counters", async () => {
  clearCache();
  const pool = createPoolMock([
    [[{
      blueprint_id: 8,
      user_id: 7,
      name: "Flower",
      build_count: 2,
      created_at: "2026-04-23 00:00:00",
      updated_at: "2026-04-23 00:00:00"
    }]],
    [{ affectedRows: 1 }]
  ]);
  const router = createBeadRouter({ pool });
  const handler = getRouteHandler(router, "/blueprints/:id/build", "post");

  const result = await invokeHandler(handler, {
    params: { id: "8" },
    body: {
      userId: 7,
      count: 2
    }
  });

  assert.equal(result.nextError, undefined);
  assert.equal(result.statusCode, 200);
  assert.deepEqual(result.jsonPayload.data, {
    buildCount: 4,
    previousBuildCount: 2,
    addedCount: 2,
    currentBuildCount: 4
  });
});

