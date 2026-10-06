/**
 * 家务（housework）模块单元测试 —— mock 池风格，不依赖真实数据库。
 * 覆盖：迁移静态断言、授权拒绝、字段值校验（含 0/false 有效填写）、
 * 版本冲突、幂等重放/冲突、模板版本冲突、统计口径（spec 8.6 示例数字）、cursor 签名与 CURSOR_STALE。
 */
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { createHouseworkRouter } = require("../src/routes/housework");

/* ------------------------------------------------------------------ *
 * mock helpers（风格同 test/authorization.security.test.js）
 * ------------------------------------------------------------------ */

function getRouteHandler(router, routePath, method) {
  const layer = router.stack.find(
    (entry) => entry.route && entry.route.path === routePath && entry.route.methods[method]
  );
  return layer?.route?.stack?.[0]?.handle;
}

async function invokeRoute(handler, { userId, params = {}, body = {}, query = {} } = {}) {
  let statusCode = 200;
  let jsonPayload;
  let nextError;
  const req = { userId, params, body, query };
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
  await handler(req, res, (error) => {
    nextError = error;
  });
  // 模拟 index.js 全局错误处理：把 next(error) 转成 envelope 响应
  if (nextError && jsonPayload === undefined) {
    const status = nextError.status || 500;
    const payload = {
      code: nextError.code || "INTERNAL_ERROR",
      message: nextError.status ? nextError.message : "服务器内部错误"
    };
    if (nextError.details && typeof nextError.details === "object") Object.assign(payload, nextError.details);
    statusCode = status;
    jsonPayload = { ok: false, error: payload };
  }
  return { statusCode, jsonPayload, nextError };
}

/** 带场景处理器的 housework mock：handlers 在前、默认处理器兜底。 */
function createHouseworkMock(defaultSpace, defaultMembers, handlers) {
  const calls = [];
  const defaults = [
    {
      match: (call) => call.sql.includes("FROM housework_spaces s"),
      respond: () => [[defaultSpace]]
    },
    {
      match: (call) => call.sql.includes("FROM housework_space_members WHERE space_id = ?"),
      respond: () => [defaultMembers]
    },
    {
      match: (call) => call.sql.includes("FROM housework_categories") && call.sql.includes("status = 'active'"),
      respond: () => [[]]
    },
    {
      match: (call) => call.sql.includes("FROM housework_templates") && call.sql.includes("status = 'active'"),
      respond: () => [[]]
    },
    {
      match: (call) => call.sql.includes("FROM housework_preferences WHERE space_id = ?"),
      respond: () => [[]]
    },
    {
      match: (call) => call.sql.startsWith("UPDATE housework_spaces SET revision"),
      respond: () => [{ affectedRows: 1 }]
    },
    {
      match: (call) => call.sql.includes("FROM couple_relationships") && !call.sql.includes("housework_spaces"),
      respond: () => [[]]
    },
    {
      match: (call) => call.sql.startsWith("SELECT revision FROM housework_spaces"),
      respond: () => [[{ revision: Number(defaultSpace.revision) + 1 }]]
    }
  ];
  const all = [...handlers, ...defaults];
  const execute = async (sql, params) => {
    const call = { sql, params };
    calls.push(call);
    for (const handler of all) {
      if (handler.match(call)) return handler.respond(call);
    }
    throw new Error(`Unexpected query: ${sql}`);
  };
  const connection = {
    async beginTransaction() {},
    async commit() {},
    async rollback() {},
    release() {},
    execute
  };
  return {
    calls,
    execute,
    async getConnection() {
      return connection;
    }
  };
}

function spaceRow(overrides = {}) {
  return {
    space_id: "s1",
    scope: "couple",
    owner_user_id: null,
    cycle_id: "c1",
    status: "active",
    timezone: "Asia/Shanghai",
    revision: 5,
    version: 3,
    settings_json: JSON.stringify({ showDurationStatistics: true, showWorkloadStatistics: true }),
    closed_at: null,
    created_at: "2026-10-01T08:00:00.000000+08:00",
    cycle_ended_at: null,
    cycle_u1: 4,
    cycle_u2: 5,
    cycle_relationship_id: 9,
    relationship_status: "active",
    rel_u1: 4,
    rel_u2: 5,
    ...overrides
  };
}

const MEMBERS = [
  { user_id: 4, display_name_snapshot: "A", joined_at: null, ended_at: null },
  { user_id: 5, display_name_snapshot: "B", joined_at: null, ended_at: null }
];

const UUID_A = "11111111-1111-4111-8111-111111111111";
const UUID_B = "22222222-2222-4222-8222-222222222222";
const UUID_CAT = "33333333-3333-4333-8333-333333333333";
const UUID_TMPL = "44444444-4444-4444-8444-444444444444";
const UUID_REC = "55555555-5555-4555-8555-555555555555";
const UUID_M1 = "66666666-6666-4666-8666-666666666666";
const UUID_M2 = "77777777-7777-4777-8777-777777777777";

const TEMPLATE_FIELDS = [
  { fieldId: "f-text", label: "备注", type: "text", required: true, defaultValue: null, sortOrder: 0, status: "active" },
  { fieldId: "f-num", label: "数量", type: "number", required: true, min: "0.00", max: "99999.99", defaultValue: null, sortOrder: 1, status: "active" },
  {
    fieldId: "f-single", label: "区域", type: "single_select", required: false, defaultValue: null, sortOrder: 2, status: "active",
    options: [
      { optionId: "o1", label: "客厅", status: "active" },
      { optionId: "o2", label: "卧室", status: "disabled" }
    ]
  },
  {
    fieldId: "f-multi", label: "标签", type: "multi_select", required: true, defaultValue: null, sortOrder: 3, status: "active",
    options: [
      { optionId: "o3", label: "深度", status: "active" },
      { optionId: "o4", label: "快速", status: "active" }
    ]
  },
  { fieldId: "f-bool", label: "已完成", type: "boolean", required: true, defaultValue: null, sortOrder: 4, status: "active" }
];

function templateRow(overrides = {}) {
  return {
    template_id: UUID_TMPL,
    space_id: "s1",
    category_id: UUID_CAT,
    name: "清洗鱼缸",
    normalized_name: "清洗鱼缸",
    description: null,
    icon_json: null,
    color: null,
    sort_order: 0,
    measure_mode: "quantity",
    unit: "升",
    default_quantity: null,
    duration_enabled: 1,
    default_duration_minutes: null,
    weight: "0.50",
    fields_json: JSON.stringify(TEMPLATE_FIELDS),
    status: "active",
    preset_key: null,
    version: 3,
    ...overrides
  };
}

function categoryRow(overrides = {}) {
  return {
    category_id: UUID_CAT,
    space_id: "s1",
    name: "清洁",
    normalized_name: "清洁",
    icon_json: null,
    color: "#4A90D9",
    sort_order: 0,
    is_fallback: 0,
    status: "active",
    preset_category_key: null,
    version: 2,
    ...overrides
  };
}

function categoryOrderMock(categories, spaceOverrides = {}) {
  const space = spaceRow(spaceOverrides);
  const state = {
    categories: categories.map((row) => ({ ...row })),
    receipt: null,
    updates: [],
    configRevisions: []
  };
  const pool = createHouseworkMock(space, MEMBERS, [
    {
      match: (call) => call.sql.includes("FROM housework_mutations"),
      respond: () => [state.receipt ? [state.receipt] : []]
    },
    {
      match: (call) => call.sql.startsWith("SELECT * FROM housework_categories") && call.sql.includes("status = 'active'"),
      respond: () => [state.categories.filter((row) => row.status === "active").map((row) => ({ ...row }))]
    },
    {
      match: (call) => call.sql.startsWith("UPDATE housework_categories") && call.sql.includes("SET sort_order"),
      respond: (call) => {
        const [sortOrder, categoryId, spaceId, version] = call.params;
        const row = state.categories.find((category) => category.category_id === categoryId && category.space_id === spaceId);
        if (!row || Number(row.version) !== Number(version) || row.status !== "active") return [{ affectedRows: 0 }];
        state.updates.push({ categoryId, sortOrder, version });
        row.sort_order = sortOrder;
        row.version = Number(row.version) + 1;
        return [{ affectedRows: 1 }];
      }
    },
    {
      match: (call) => call.sql.startsWith("INSERT INTO housework_config_revisions"),
      respond: (call) => {
        state.configRevisions.push(call.params);
        return [{ insertId: state.configRevisions.length }];
      }
    },
    {
      match: (call) => call.sql.startsWith("UPDATE housework_spaces SET revision = revision + 1"),
      respond: () => {
        space.revision = Number(space.revision) + 1;
        return [{ affectedRows: 1 }];
      }
    },
    {
      match: (call) => call.sql.startsWith("SELECT revision FROM housework_spaces"),
      respond: () => [[{ revision: Number(space.revision) }]]
    },
    {
      match: (call) => call.sql.startsWith("INSERT INTO housework_mutations"),
      respond: (call) => {
        state.receipt = {
          request_hash: call.params[4],
          result_id: call.params[5],
          result_json: call.params[6]
        };
        return [{ insertId: 1 }];
      }
    }
  ]);
  return { pool, state, space };
}

function validRecordBody(fieldValues) {
  return {
    clientMutationId: UUID_M1,
    templateId: UUID_TMPL,
    templateVersion: 3,
    completedDate: "2026-10-01",
    completedTime: null,
    participants: [{ userId: 4, shareBps: 10000 }],
    quantity: "5.00",
    durationMinutes: 30,
    fieldValues,
    note: "测试"
  };
}

/** records.create（模板路径）的完整 mock handlers。 */
function recordCreateHandlers({ receiptPhase = false, captured = {}, recordId = UUID_REC, recordOverrides = {} } = {}) {
  const recordRow = {
    record_id: recordId,
    space_id: "s1",
    template_id: UUID_TMPL,
    template_version: 3,
    name: "清洗鱼缸",
    category_id_snapshot: UUID_CAT,
    snapshot_json: JSON.stringify({
      name: "清洗鱼缸",
      category: { categoryId: UUID_CAT, name: "清洁", icon: null, color: "#4A90D9" },
      measureMode: "quantity",
      unit: "升",
      durationEnabled: true,
      weight: "0.50",
      fields: TEMPLATE_FIELDS
    }),
    completed_date: "2026-10-01",
    completed_time: null,
    quantity: "5.00",
    duration_minutes: 30,
    weight_snapshot: "0.50",
    field_values_json: JSON.stringify({ "f-text": "干净", "f-num": "0.00", "f-single": "o1", "f-multi": ["o3"], "f-bool": false }),
    note: "测试",
    created_by: 4,
    updated_by: 4,
    client_mutation_id: UUID_M1,
    version: 1,
    created_at: "2026-10-01T10:00:00.000000+08:00",
    updated_at: "2026-10-01T10:00:00.000000+08:00",
    deleted_at: null,
    deleted_by: null,
    ...recordOverrides
  };
  return [
    {
      match: (call) => call.sql.includes("FROM housework_mutations"),
      respond: () => (receiptPhase && captured.receipt ? [[captured.receipt]] : [[]])
    },
    {
      match: (call) => call.sql.includes("FROM housework_templates WHERE template_id"),
      respond: () => [[templateRow()]]
    },
    {
      match: (call) => call.sql.startsWith("SELECT * FROM housework_categories WHERE category_id"),
      respond: () => [[categoryRow()]]
    },
    {
      match: (call) => call.sql.startsWith("INSERT INTO housework_records"),
      respond: (call) => {
        captured.recordInsert = call;
        return [{ affectedRows: 1 }];
      }
    },
    {
      match: (call) => call.sql.startsWith("INSERT INTO housework_record_participants"),
      respond: () => [{ affectedRows: 1 }]
    },
    {
      match: (call) => call.sql.includes("r.record_id = ? AND r.space_id = ?"),
      respond: () => [[recordRow]]
    },
    {
      match: (call) => call.sql.includes("FROM housework_record_participants p") && call.sql.includes(" IN ("),
      respond: () => [[{ record_id: recordId, user_id: 4, share_bps: 10000, display_name_snapshot: "A" }]]
    },
    {
      match: (call) => call.sql.startsWith("INSERT INTO housework_record_revisions"),
      respond: () => [{ affectedRows: 1 }]
    },
    {
      match: (call) => call.sql.startsWith("INSERT INTO housework_mutations"),
      respond: (call) => {
        captured.receipt = {
          request_hash: call.params[4],
          result_id: call.params[5],
          result_json: call.params[6]
        };
        return [{ affectedRows: 1 }];
      }
    }
  ];
}

/* ------------------------------------------------------------------ *
 * 迁移静态断言
 * ------------------------------------------------------------------ */

test("housework migration creates 11 tables with required keys and no relationship cascade", () => {
  const migrationPath = path.resolve(__dirname, "../migrations/029_create_housework.sql");
  assert.equal(fs.existsSync(migrationPath), true);
  const migration = fs.readFileSync(migrationPath, "utf8");

  const tables = [
    "housework_relationship_cycles",
    "housework_spaces",
    "housework_space_members",
    "housework_categories",
    "housework_templates",
    "housework_records",
    "housework_record_participants",
    "housework_preferences",
    "housework_record_revisions",
    "housework_config_revisions",
    "housework_mutations"
  ];
  for (const table of tables) {
    assert.match(migration, new RegExp(`CREATE TABLE IF NOT EXISTS ${table} \\(`), `missing table ${table}`);
  }

  // 关键唯一键/索引
  assert.match(migration, /uk_hw_cycles_relationship_open \(relationship_id, open_flag\)/);
  assert.match(migration, /uk_hw_spaces_owner \(owner_user_id\)/);
  assert.match(migration, /uk_hw_spaces_cycle \(cycle_id\)/);
  assert.match(migration, /uk_hw_categories_fallback \(space_id, fallback_key\)/);
  assert.match(migration, /uk_hw_templates_preset \(space_id, preset_key\)/);
  assert.match(migration, /PRIMARY KEY \(record_id, user_id\)/);
  assert.match(migration, /uk_hw_mutations \(space_id, actor_user_id, operation, client_mutation_id\)/);
  assert.match(migration, /idx_hw_records_space \(space_id, deleted_at, completed_date, record_id\)/);

  // 指向 couple_relationships 的外键不得带 ON DELETE CASCADE（解绑不删行）
  assert.doesNotMatch(migration, /REFERENCES couple_relationships \(relationship_id\) ON DELETE CASCADE/);

  // ID 一律 CHAR(36) UUID；业务数字定点数
  assert.match(migration, /cycle_id\s+CHAR\(36\)/);
  assert.match(migration, /quantity\s+DECIMAL\(10,2\)/);
  assert.match(migration, /completed_time\s+VARCHAR\(5\)/);
});

/* ------------------------------------------------------------------ *
 * 授权
 * ------------------------------------------------------------------ */

test("non-member receives 403 SPACE_FORBIDDEN on config", async () => {
  const pool = createHouseworkMock(spaceRow(), [{ user_id: 4, display_name_snapshot: "A" }], []);
  const handler = getRouteHandler(createHouseworkRouter({ pool }), "/spaces/:spaceId/config", "get");
  const result = await invokeRoute(handler, { userId: 9, params: { spaceId: "s1" } });
  assert.equal(result.statusCode, 403);
  assert.equal(result.jsonPayload.error.code, "SPACE_FORBIDDEN");
});

test("unknown space returns 404 RESOURCE_NOT_FOUND", async () => {
  const pool = createHouseworkMock(spaceRow(), MEMBERS, [
    { match: (call) => call.sql.includes("FROM housework_spaces s"), respond: () => [[]] }
  ]);
  const handler = getRouteHandler(createHouseworkRouter({ pool }), "/spaces/:spaceId/config", "get");
  const result = await invokeRoute(handler, { userId: 4, params: { spaceId: "nope" } });
  assert.equal(result.statusCode, 404);
  assert.equal(result.jsonPayload.error.code, "RESOURCE_NOT_FOUND");
});

test("closed archived space rejects writes with 409 SPACE_CLOSED but stays readable", async () => {
  const closed = spaceRow({ status: "closed", cycle_ended_at: "2026-10-01T00:00:00.000Z", closed_at: "2026-10-01T00:00:00.000Z" });
  const pool = createHouseworkMock(closed, MEMBERS, []);
  const writeHandler = getRouteHandler(createHouseworkRouter({ pool }), "/spaces/:spaceId/categories", "post");
  const writeResult = await invokeRoute(writeHandler, {
    userId: 4,
    params: { spaceId: "s1" },
    body: { clientMutationId: UUID_M1, name: "新分类" }
  });
  assert.equal(writeResult.statusCode, 409);
  assert.equal(writeResult.jsonPayload.error.code, "SPACE_CLOSED");

  const readHandler = getRouteHandler(createHouseworkRouter({ pool }), "/spaces/:spaceId/config", "get");
  const readResult = await invokeRoute(readHandler, { userId: 4, params: { spaceId: "s1" } });
  assert.equal(readResult.statusCode, 200);
  assert.equal(readResult.jsonPayload.data.space.canWrite, false);
  assert.equal(readResult.jsonPayload.data.space.status, "closed");
});

test("archived template cannot be restored while its category is archived", async () => {
  const pool = createHouseworkMock(spaceRow(), MEMBERS, [
    { match: (call) => call.sql.includes("FROM housework_mutations"), respond: () => [[]] },
    {
      match: (call) => call.sql.startsWith("SELECT * FROM housework_templates WHERE template_id"),
      respond: () => [[templateRow({ status: "archived" })]]
    },
    {
      match: (call) => call.sql.startsWith("SELECT * FROM housework_categories WHERE category_id"),
      respond: () => [[categoryRow({ status: "archived" })]]
    }
  ]);
  const handler = getRouteHandler(createHouseworkRouter({ pool }), "/spaces/:spaceId/templates/:id", "patch");
  const result = await invokeRoute(handler, {
    userId: 4,
    params: { spaceId: "s1", id: UUID_TMPL },
    body: { clientMutationId: UUID_M1, expectedVersion: 3, status: "active" }
  });
  assert.equal(result.statusCode, 422);
  assert.equal(result.jsonPayload.error.fieldErrors[0].path, "categoryId");
  assert.equal(result.jsonPayload.error.fieldErrors[0].code, "INVALID_OPTION");
  assert.equal(pool.calls.some((call) => call.sql.startsWith("UPDATE housework_templates")), false);
});

test("category order atomically updates the complete active set, including fallback, and replays its receipt", async () => {
  const active = [
    categoryRow({ category_id: UUID_CAT, name: "未分类", is_fallback: 1, sort_order: 0, version: 1 }),
    categoryRow({ category_id: UUID_TMPL, name: "清洁", sort_order: 1, version: 2 }),
    categoryRow({ category_id: UUID_REC, name: "厨房", sort_order: 2, version: 4 })
  ];
  const archived = categoryRow({ category_id: UUID_A, name: "归档", status: "archived", sort_order: 3, version: 7 });
  const mock = categoryOrderMock([...active, archived]);
  const handler = getRouteHandler(createHouseworkRouter({ pool: mock.pool }), "/spaces/:spaceId/categories/order", "put");
  const body = {
    clientMutationId: UUID_M1,
    expectedRevision: 5,
    activeCategoryIds: [UUID_REC, UUID_CAT, UUID_TMPL]
  };
  const result = await invokeRoute(handler, { userId: 4, params: { spaceId: "s1" }, body });
  assert.equal(result.statusCode, 200, JSON.stringify({ payload: result.jsonPayload, error: result.nextError && result.nextError.stack }));
  assert.equal(result.jsonPayload.data.operation, "categories.order");
  assert.equal(result.jsonPayload.data.revision, 6);
  assert.deepEqual(result.jsonPayload.data.entity.activeCategoryIds, body.activeCategoryIds);
  assert.deepEqual(result.jsonPayload.data.entity.categories.map((category) => category.categoryId), body.activeCategoryIds);
  assert.deepEqual(result.jsonPayload.data.entity.categories.map((category) => category.sortOrder), [0, 1, 2]);
  assert.deepEqual(result.jsonPayload.data.entity.categories.map((category) => category.version), [5, 2, 3]);
  assert.equal(mock.state.updates.length, 3);
  assert.equal(mock.state.configRevisions.length, 3);
  assert.equal(mock.state.configRevisions[0][4], "order");
  assert.equal(mock.state.receipt.result_id, "s1");

  const replay = await invokeRoute(handler, { userId: 4, params: { spaceId: "s1" }, body });
  assert.equal(replay.statusCode, 200);
  assert.equal(replay.jsonPayload.data.replayed, true);
  assert.deepEqual(replay.jsonPayload.data.entity.activeCategoryIds, body.activeCategoryIds);
  assert.equal(mock.state.updates.length, 3);
  assert.equal(mock.state.configRevisions.length, 3);
});

test("category order rejects a stale revision, incomplete set, and non-member before updating categories", async () => {
  const categories = [
    categoryRow({ category_id: UUID_CAT, name: "未分类", is_fallback: 1, sort_order: 0 }),
    categoryRow({ category_id: UUID_TMPL, name: "清洁", sort_order: 1 })
  ];
  const stale = categoryOrderMock(categories);
  const staleHandler = getRouteHandler(createHouseworkRouter({ pool: stale.pool }), "/spaces/:spaceId/categories/order", "put");
  const staleResult = await invokeRoute(staleHandler, {
    userId: 4, params: { spaceId: "s1" },
    body: { clientMutationId: UUID_M1, expectedRevision: 4, activeCategoryIds: [UUID_TMPL, UUID_CAT] }
  });
  assert.equal(staleResult.statusCode, 409);
  assert.equal(staleResult.jsonPayload.error.code, "VERSION_CONFLICT");
  assert.equal(staleResult.jsonPayload.error.currentVersion, 5);
  assert.equal(stale.state.updates.length, 0);

  const incomplete = categoryOrderMock(categories);
  const incompleteHandler = getRouteHandler(createHouseworkRouter({ pool: incomplete.pool }), "/spaces/:spaceId/categories/order", "put");
  const incompleteResult = await invokeRoute(incompleteHandler, {
    userId: 4, params: { spaceId: "s1" },
    body: { clientMutationId: UUID_M1, expectedRevision: 5, activeCategoryIds: [UUID_TMPL] }
  });
  assert.equal(incompleteResult.statusCode, 422);
  assert.equal(incompleteResult.jsonPayload.error.fieldErrors[0].path, "activeCategoryIds");
  assert.equal(incomplete.state.updates.length, 0);

  const forbiddenPool = createHouseworkMock(spaceRow(), MEMBERS, []);
  const forbiddenHandler = getRouteHandler(createHouseworkRouter({ pool: forbiddenPool }), "/spaces/:spaceId/categories/order", "put");
  const forbiddenResult = await invokeRoute(forbiddenHandler, {
    userId: 9, params: { spaceId: "s1" },
    body: { clientMutationId: UUID_M1, expectedRevision: 5, activeCategoryIds: [UUID_CAT] }
  });
  assert.equal(forbiddenResult.statusCode, 403);
  assert.equal(forbiddenResult.jsonPayload.error.code, "SPACE_FORBIDDEN");
  assert.equal(forbiddenPool.calls.some((call) => call.sql.startsWith("SELECT * FROM housework_categories")), false);
});

/* ------------------------------------------------------------------ *
 * 字段值校验：0 / false 是有效填写；非法值被拒
 * ------------------------------------------------------------------ */

test("field values accept required 0.00 and boolean false", async () => {
  const captured = {};
  const pool = createHouseworkMock(spaceRow(), MEMBERS, recordCreateHandlers({ captured }));
  const handler = getRouteHandler(createHouseworkRouter({ pool }), "/spaces/:spaceId/records", "post");
  const result = await invokeRoute(handler, {
    userId: 4,
    params: { spaceId: "s1" },
    body: validRecordBody({ "f-text": "干净", "f-num": "0.00", "f-single": "o1", "f-multi": ["o3"], "f-bool": false })
  });
  assert.equal(result.nextError, undefined);
  assert.equal(result.jsonPayload.data.replayed, false);
  assert.equal(result.jsonPayload.data.entity.version, 1);
  const fieldValues = JSON.parse(captured.recordInsert.params[12]);
  assert.equal(fieldValues["f-num"], "0.00");
  assert.equal(fieldValues["f-bool"], false);
  assert.equal(fieldValues["f-multi"][0], "o3");
});

test("field values reject unknown fieldId, wrong type, disabled option and null required", async () => {
  const cases = [
    [{ "f-text": "x", "f-num": "0.00", "f-multi": ["o3"], "f-bool": false, "f-ghost": "1" }, "fieldValues.f-ghost", "INVALID_OPTION"],
    [{ "f-text": "x", "f-num": 5, "f-multi": ["o3"], "f-bool": false }, "fieldValues.f-num", "INVALID_TYPE"],
    [{ "f-text": "x", "f-num": "1.00", "f-single": "o2", "f-multi": ["o3"], "f-bool": false }, "fieldValues.f-single", "INVALID_OPTION"],
    [{ "f-text": "x", "f-num": "1.00", "f-multi": ["o3"], "f-bool": null }, "fieldValues.f-bool", "REQUIRED"]
  ];
  for (const [fieldValues, path, code] of cases) {
    const pool = createHouseworkMock(spaceRow(), MEMBERS, recordCreateHandlers({}));
    const handler = getRouteHandler(createHouseworkRouter({ pool }), "/spaces/:spaceId/records", "post");
    const result = await invokeRoute(handler, {
      userId: 4,
      params: { spaceId: "s1" },
      body: validRecordBody(fieldValues)
    });
    assert.equal(result.statusCode, 422, JSON.stringify(result.jsonPayload));
    assert.equal(result.jsonPayload.error.code, "VALIDATION_ERROR");
    const fieldError = result.jsonPayload.error.fieldErrors.find((e) => e.path === path || e.path.startsWith(path));
    assert.ok(fieldError, `expected fieldError at ${path}: ${JSON.stringify(result.jsonPayload.error.fieldErrors)}`);
    assert.equal(fieldError.code, code);
    assert.ok(result.jsonPayload.error.requestId, "error should carry requestId");
  }
});

test("record create rejects third-person participant and bad share split", async () => {
  const badParticipants = [
    [{ userId: 9, shareBps: 10000 }],
    [{ userId: 4, shareBps: 6000 }, { userId: 5, shareBps: 3000 }],
    [{ userId: 4, shareBps: 10000 }, { userId: 5, shareBps: 10000 }]
  ];
  for (const participants of badParticipants) {
    const pool = createHouseworkMock(spaceRow(), MEMBERS, recordCreateHandlers({}));
    const handler = getRouteHandler(createHouseworkRouter({ pool }), "/spaces/:spaceId/records", "post");
    const result = await invokeRoute(handler, {
      userId: 4,
      params: { spaceId: "s1" },
      body: { ...validRecordBody({}), participants }
    });
    assert.equal(result.statusCode, 422, JSON.stringify(result.jsonPayload));
  }
});

test("adHoc record snapshots enable optional duration and are returned to the client", async () => {
  for (const durationMinutes of [null, 30]) {
    const captured = {};
    const snapshot = {
      name: "临时拖地",
      category: { categoryId: UUID_CAT, name: "清洁", icon: null, color: "#4A90D9" },
      measureMode: "event",
      unit: "次",
      durationEnabled: true,
      weight: "1.00",
      fields: []
    };
    const pool = createHouseworkMock(spaceRow(), MEMBERS, recordCreateHandlers({
      captured,
      recordOverrides: {
        template_id: null,
        template_version: null,
        name: snapshot.name,
        category_id_snapshot: UUID_CAT,
        snapshot_json: JSON.stringify(snapshot),
        quantity: "1.00",
        duration_minutes: durationMinutes,
        weight_snapshot: "1.00",
        field_values_json: "{}",
        note: null
      }
    }));
    const handler = getRouteHandler(createHouseworkRouter({ pool }), "/spaces/:spaceId/records", "post");
    const body = {
      clientMutationId: UUID_M1,
      adHoc: { name: snapshot.name, categoryId: UUID_CAT, measureMode: "event", unit: "次", weight: "1.00" },
      completedDate: "2026-10-01",
      participants: [{ userId: 4, shareBps: 10000 }],
      quantity: "1.00",
      durationMinutes
    };
    const result = await invokeRoute(handler, { userId: 4, params: { spaceId: "s1" }, body });
    assert.equal(result.statusCode, 200, JSON.stringify(result.jsonPayload));
    assert.equal(result.jsonPayload.data.entity.durationEnabled, true);
    assert.deepEqual(result.jsonPayload.data.entity.snapshot, snapshot);
    assert.equal(captured.recordInsert.params[10], durationMinutes);
    assert.deepEqual(JSON.parse(captured.recordInsert.params[6]), snapshot);
  }
});

test("editing a legacy record with duration disabled still rejects a duration", async () => {
  const snapshot = {
    name: "旧临时家务",
    category: { categoryId: UUID_CAT, name: "清洁", icon: null, color: "#4A90D9" },
    measureMode: "event",
    unit: "次",
    durationEnabled: false,
    weight: "1.00",
    fields: []
  };
  const recordRow = {
    record_id: UUID_REC,
    space_id: "s1",
    template_id: null,
    template_version: null,
    name: snapshot.name,
    category_id_snapshot: UUID_CAT,
    snapshot_json: JSON.stringify(snapshot),
    field_values_json: "{}",
    completed_date: "2026-10-03",
    completed_time: null,
    quantity: "1.00",
    duration_minutes: null,
    weight_snapshot: "1.00",
    note: null,
    created_by: 4,
    updated_by: 4,
    client_mutation_id: UUID_M1,
    version: 1,
    created_at: "2026-10-03T10:00:00.000000+08:00",
    updated_at: "2026-10-03T10:00:00.000000+08:00",
    deleted_at: null,
    deleted_by: null
  };
  const pool = createHouseworkMock(spaceRow(), MEMBERS, [
    { match: (call) => call.sql.includes("FROM housework_mutations"), respond: () => [[]] },
    {
      match: (call) => call.sql.includes("FROM housework_records r") && call.sql.includes("WHERE r.record_id = ? AND r.space_id = ?"),
      respond: () => [[recordRow]]
    },
    {
      match: (call) => call.sql.includes("FROM housework_record_participants p") && call.sql.includes("WHERE p.record_id IN"),
      respond: () => [[{ record_id: UUID_REC, user_id: 4, share_bps: 10000, display_name_snapshot: "A" }]]
    }
  ]);
  const handler = getRouteHandler(createHouseworkRouter({ pool }), "/spaces/:spaceId/records/:id", "patch");
  const result = await invokeRoute(handler, {
    userId: 4,
    params: { spaceId: "s1", id: UUID_REC },
    body: { clientMutationId: UUID_M1, expectedVersion: 1, durationMinutes: 30 }
  });
  assert.equal(result.statusCode, 422);
  assert.equal(result.jsonPayload.error.fieldErrors[0].path, "durationMinutes");
  assert.equal(pool.calls.some((call) => call.sql.startsWith("UPDATE housework_records")), false);
});

test("record create rejects stale templateVersion with 409 TEMPLATE_VERSION_CONFLICT", async () => {
  const pool = createHouseworkMock(spaceRow(), MEMBERS, recordCreateHandlers({}));
  const handler = getRouteHandler(createHouseworkRouter({ pool }), "/spaces/:spaceId/records", "post");
  const result = await invokeRoute(handler, {
    userId: 4,
    params: { spaceId: "s1" },
    body: { ...validRecordBody({}), templateVersion: 2 }
  });
  assert.equal(result.statusCode, 409);
  assert.equal(result.jsonPayload.error.code, "TEMPLATE_VERSION_CONFLICT");
  assert.equal(result.jsonPayload.error.currentVersion, 3);
  assert.equal(result.jsonPayload.error.entityId, UUID_TMPL);
});

/* ------------------------------------------------------------------ *
 * 乐观锁
 * ------------------------------------------------------------------ */

test("category patch with stale expectedVersion returns 409 VERSION_CONFLICT with currentVersion", async () => {
  const pool = createHouseworkMock(spaceRow(), MEMBERS, [
    { match: (call) => call.sql.includes("FROM housework_mutations"), respond: () => [[]] },
    { match: (call) => call.sql.includes("FROM housework_categories WHERE category_id"), respond: () => [[categoryRow()]] },
    { match: (call) => call.sql.includes("normalized_name = ? AND status = 'active' AND category_id"), respond: () => [[]] },
    { match: (call) => call.sql.startsWith("UPDATE housework_categories"), respond: () => [{ affectedRows: 0 }] }
  ]);
  const handler = getRouteHandler(createHouseworkRouter({ pool }), "/spaces/:spaceId/categories/:id", "patch");
  const result = await invokeRoute(handler, {
    userId: 4,
    params: { spaceId: "s1", id: UUID_CAT },
    body: { clientMutationId: UUID_M1, expectedVersion: 1, name: "新名字" }
  });
  assert.equal(result.statusCode, 409);
  assert.equal(result.jsonPayload.error.code, "VERSION_CONFLICT");
  assert.equal(result.jsonPayload.error.currentVersion, 2);
  assert.equal(result.jsonPayload.error.entityType, "category");
});

/* ------------------------------------------------------------------ *
 * 幂等
 * ------------------------------------------------------------------ */

test("record create replay returns original result with replayed true and no second insert", async () => {
  const captured = {};
  const pool = createHouseworkMock(spaceRow(), MEMBERS, recordCreateHandlers({ captured }));
  const handler = getRouteHandler(createHouseworkRouter({ pool }), "/spaces/:spaceId/records", "post");
  const body = validRecordBody({ "f-text": "x", "f-num": "1.00", "f-multi": ["o3"], "f-bool": true });

  const first = await invokeRoute(handler, { userId: 4, params: { spaceId: "s1" }, body });
  assert.equal(first.nextError, undefined);
  assert.equal(first.jsonPayload.data.replayed, false);
  const insertCalls = pool.calls.filter((c) => c.sql.startsWith("INSERT INTO housework_records")).length;
  assert.equal(insertCalls, 1);

  // 第二次提交：同一 mutationId + 同一规范化 body → 回放
  const replayHandlers = recordCreateHandlers({ receiptPhase: true, captured });
  const pool2 = createHouseworkMock(spaceRow(), MEMBERS, replayHandlers);
  const handler2 = getRouteHandler(createHouseworkRouter({ pool: pool2 }), "/spaces/:spaceId/records", "post");
  const second = await invokeRoute(handler2, { userId: 4, params: { spaceId: "s1" }, body });
  assert.equal(second.nextError, undefined);
  assert.equal(second.jsonPayload.data.replayed, true);
  assert.equal(second.jsonPayload.data.entity.recordId, first.jsonPayload.data.entity.recordId);
  assert.equal(
    pool2.calls.some((c) => c.sql.startsWith("INSERT INTO housework_records")),
    false,
    "replay must not insert again"
  );
});

test("same mutationId with different payload returns 409 IDEMPOTENCY_CONFLICT", async () => {
  const captured = {};
  const pool = createHouseworkMock(spaceRow(), MEMBERS, recordCreateHandlers({ captured }));
  const handler = getRouteHandler(createHouseworkRouter({ pool }), "/spaces/:spaceId/records", "post");
  const first = await invokeRoute(handler, {
    userId: 4,
    params: { spaceId: "s1" },
    body: validRecordBody({ "f-text": "x", "f-num": "1.00", "f-multi": ["o3"], "f-bool": true })
  });
  assert.equal(first.nextError, undefined);

  const pool2 = createHouseworkMock(spaceRow(), MEMBERS, recordCreateHandlers({ receiptPhase: true, captured }));
  const handler2 = getRouteHandler(createHouseworkRouter({ pool: pool2 }), "/spaces/:spaceId/records", "post");
  const second = await invokeRoute(handler2, {
    userId: 4,
    params: { spaceId: "s1" },
    body: { ...validRecordBody({ "f-text": "篡改", "f-num": "1.00", "f-multi": ["o3"], "f-bool": true }), quantity: "9.00" }
  });
  assert.equal(second.statusCode, 409);
  assert.equal(second.jsonPayload.error.code, "IDEMPOTENCY_CONFLICT");
});

/* ------------------------------------------------------------------ *
 * 统计口径（spec 7 / 8.6 示例数字）
 * ------------------------------------------------------------------ */

function shanghaiMonthRangeForTest() {
  const today = new Date(Date.now() + 8 * 60 * 60 * 1000).toISOString().slice(0, 10);
  const [year, month] = today.slice(0, 7).split("-").map(Number);
  const lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate();
  return {
    dateFrom: `${today.slice(0, 7)}-01`,
    dateTo: `${today.slice(0, 7)}-${String(lastDay).padStart(2, "0")}`
  };
}

function emptyStatisticsPool() {
  return createHouseworkMock(spaceRow(), MEMBERS, [
    { match: (call) => call.sql.includes("FROM housework_records r WHERE") && !call.sql.includes("JOIN"), respond: () => [[]] },
    { match: (call) => call.sql.includes("JOIN housework_records"), respond: () => [[]] },
    { match: (call) => call.sql.startsWith("SELECT category_id, name, status FROM housework_categories"), respond: () => [[]] },
    { match: (call) => call.sql.startsWith("SELECT template_id, name, status"), respond: () => [[]] }
  ]);
}

test("statistics fills either missing date boundary from the server month and limits one-sided ranges", async () => {
  const range = shanghaiMonthRangeForTest();
  for (const query of [{ dateFrom: range.dateFrom }, { dateTo: range.dateTo }]) {
    const pool = emptyStatisticsPool();
    const handler = getRouteHandler(createHouseworkRouter({ pool }), "/spaces/:spaceId/statistics", "get");
    const result = await invokeRoute(handler, { userId: 4, params: { spaceId: "s1" }, query });
    assert.equal(result.statusCode, 200, JSON.stringify(result.jsonPayload));
    assert.equal(result.jsonPayload.data.dateFrom, range.dateFrom);
    assert.equal(result.jsonPayload.data.dateTo, range.dateTo);
  }

  const pool = emptyStatisticsPool();
  const handler = getRouteHandler(createHouseworkRouter({ pool }), "/spaces/:spaceId/statistics", "get");
  const tooLong = await invokeRoute(handler, {
    userId: 4, params: { spaceId: "s1" }, query: { dateFrom: "2000-01-01" }
  });
  assert.equal(tooLong.statusCode, 422);
  assert.equal(tooLong.jsonPayload.error.fieldErrors[0].path, "dateTo");
  assert.equal(tooLong.jsonPayload.error.fieldErrors[0].code, "OUT_OF_RANGE");
  assert.equal(pool.calls.some((call) => call.sql.includes("FROM housework_records r WHERE")), false);
});

test("statistics aggregates full filter scope with spec 8.6 example numbers", async () => {
  const snapshotSweep = JSON.stringify({
    name: "扫地", category: { categoryId: "c1", name: "清洁", icon: null, color: null },
    measureMode: "event", unit: "次", durationEnabled: false, weight: "2.00", fields: []
  });
  const snapshotLaundry = JSON.stringify({
    name: "洗衣", category: { categoryId: "c2", name: "洗护", icon: null, color: null },
    measureMode: "quantity", unit: "件", durationEnabled: true, weight: "0.50", fields: []
  });
  const records = [
    {
      record_id: "r1", space_id: "s1", template_id: "t1", template_version: 1, name: "扫地",
      category_id_snapshot: "c1", snapshot_json: snapshotSweep, completed_date: "2026-10-01", completed_time: null,
      quantity: "1.00", duration_minutes: null, weight_snapshot: "2.00", field_values_json: "{}", note: null,
      created_by: 4, updated_by: 4, client_mutation_id: UUID_M1, version: 1,
      created_at: "2026-10-01T09:00:00.000000+08:00", updated_at: "2026-10-01T09:00:00.000000+08:00",
      deleted_at: null, deleted_by: null
    },
    {
      record_id: "r2", space_id: "s1", template_id: "t2", template_version: 1, name: "洗衣",
      category_id_snapshot: "c2", snapshot_json: snapshotLaundry, completed_date: "2026-10-01", completed_time: null,
      quantity: "5.00", duration_minutes: 30, weight_snapshot: "0.50", field_values_json: "{}", note: null,
      created_by: 4, updated_by: 4, client_mutation_id: UUID_M2, version: 1,
      created_at: "2026-10-01T10:00:00.000000+08:00", updated_at: "2026-10-01T10:00:00.000000+08:00",
      deleted_at: null, deleted_by: null
    }
  ];
  const participants = [
    { record_id: "r1", user_id: 4, share_bps: 10000, display_name_snapshot: "A" },
    { record_id: "r2", user_id: 4, share_bps: 6000, display_name_snapshot: "A" },
    { record_id: "r2", user_id: 5, share_bps: 4000, display_name_snapshot: "B" }
  ];
  const pool = createHouseworkMock(spaceRow(), MEMBERS, [
    {
      match: (call) => call.sql.includes("FROM housework_records r WHERE") && !call.sql.includes("JOIN"),
      respond: () => [records]
    },
    { match: (call) => call.sql.includes("JOIN housework_records"), respond: () => [participants] },
    {
      match: (call) => call.sql.startsWith("SELECT category_id, name, status FROM housework_categories"),
      respond: () => [[{ category_id: "c1", name: "清洁", status: "active" }, { category_id: "c2", name: "洗护", status: "active" }]]
    },
    {
      match: (call) => call.sql.startsWith("SELECT template_id, name, status"),
      respond: () => [[{ template_id: "t1", name: "扫地", status: "active" }, { template_id: "t2", name: "洗衣", status: "active" }]]
    }
  ]);
  const handler = getRouteHandler(createHouseworkRouter({ pool }), "/spaces/:spaceId/statistics", "get");
  const result = await invokeRoute(handler, { userId: 4, params: { spaceId: "s1" }, query: {} });
  assert.equal(result.nextError, undefined);
  const data = result.jsonPayload.data;

  assert.equal(data.householdCount, 2);
  assert.equal(data.equivalentTotal, "2.00");
  assert.equal(data.durationTotalMinutes, "30.00");
  assert.deepEqual(data.durationCoverage, { count: 1, total: 2 });
  assert.equal(data.workloadTotal, "4.50");

  const memberA = data.members.find((m) => m.userId === "4");
  const memberB = data.members.find((m) => m.userId === "5");
  assert.equal(memberA.participationCount, 2);
  assert.equal(memberA.equivalentCount, "1.60");
  assert.equal(memberA.allocatedDurationMinutes, "18.00");
  assert.equal(memberA.workload, "3.50");
  assert.equal(memberB.participationCount, 1);
  assert.equal(memberB.equivalentCount, "0.40");
  assert.equal(memberB.allocatedDurationMinutes, "12.00");
  assert.equal(memberB.workload, "1.00");

  const sweep = data.quantityGroups.find((g) => g.templateId === "t1");
  const laundry = data.quantityGroups.find((g) => g.templateId === "t2");
  assert.equal(sweep.quantity, "1.00");
  assert.equal(sweep.unit, "次");
  assert.equal(laundry.quantity, "5.00");
  assert.equal(laundry.unit, "件");
  const allocA = laundry.allocations.find((a) => a.userId === "4");
  const allocB = laundry.allocations.find((a) => a.userId === "5");
  assert.equal(allocA.quantity, "3.00");
  assert.equal(allocB.quantity, "2.00");

  assert.equal(data.categoryGroups.length, 2);
  assert.equal(data.templateGroups.length, 2);
  assert.deepEqual(data.adHocGroups, []);
  assert.equal(data.daily.length, 1);
  assert.equal(data.daily[0].householdCount, 2);
  assert.equal(data.daily[0].durationMinutes, "30.00");
  assert.equal(data.daily[0].workload, "4.50");
});

test("statistics nulls workload and duration metrics when space settings disable them", async () => {
  const pool = createHouseworkMock(
    spaceRow({
      settings_json: JSON.stringify({ showDurationStatistics: false, showWorkloadStatistics: false })
    }),
    MEMBERS,
    [
      {
        match: (call) => call.sql.includes("FROM housework_records r WHERE") && !call.sql.includes("JOIN"),
        respond: () => [[{
          record_id: "r1", space_id: "s1", template_id: null, template_version: null, name: "临时",
          category_id_snapshot: UUID_CAT,
          snapshot_json: JSON.stringify({ name: "临时", category: { categoryId: UUID_CAT }, measureMode: "event", unit: "次", durationEnabled: false, weight: "1.00", fields: [] }),
          completed_date: "2026-10-01", completed_time: null, quantity: "1.00", duration_minutes: null,
          weight_snapshot: "1.00", field_values_json: "{}", note: null, created_by: 4, updated_by: 4,
          client_mutation_id: UUID_M1, version: 1,
          created_at: "2026-10-01T09:00:00.000000+08:00", updated_at: "2026-10-01T09:00:00.000000+08:00",
          deleted_at: null, deleted_by: null
        }]]
      },
      { match: (call) => call.sql.includes("JOIN housework_records"), respond: () => [[{ record_id: "r1", user_id: 4, share_bps: 10000, display_name_snapshot: "A" }]] },
      { match: (call) => call.sql.startsWith("SELECT category_id, name, status FROM housework_categories"), respond: () => [[]] },
      { match: (call) => call.sql.startsWith("SELECT template_id, name, status"), respond: () => [[]] }
    ]
  );
  const handler = getRouteHandler(createHouseworkRouter({ pool }), "/spaces/:spaceId/statistics", "get");
  const result = await invokeRoute(handler, { userId: 4, params: { spaceId: "s1" }, query: {} });
  const data = result.jsonPayload.data;
  assert.equal(data.workloadTotal, null);
  assert.equal(data.durationTotalMinutes, null);
  assert.equal(data.durationCoverage, null);
  assert.equal(data.members[0].workload, null);
  assert.equal(data.members[0].allocatedDurationMinutes, null);
  assert.equal(data.householdCount, 1);
  assert.equal(data.daily[0].workload, null);
  assert.equal(data.daily[0].durationMinutes, null);
});

/* ------------------------------------------------------------------ *
 * cursor 签名与 CURSOR_STALE
 * ------------------------------------------------------------------ */

function pageRows(count) {
  const rows = [];
  for (let i = 0; i < count; i++) {
    const date = i < 25 ? "2026-10-02" : "2026-10-01";
    rows.push({
      record_id: `r${String(i).padStart(3, "0")}`,
      space_id: "s1",
      template_id: null,
      template_version: null,
      name: `家务${i}`,
      category_id_snapshot: UUID_CAT,
      snapshot_json: JSON.stringify({ name: `家务${i}`, category: { categoryId: UUID_CAT }, measureMode: "event", unit: "次", durationEnabled: false, weight: "1.00", fields: [] }),
      completed_date: date,
      completed_time: null,
      quantity: "1.00",
      duration_minutes: null,
      weight_snapshot: "1.00",
      field_values_json: "{}",
      note: null,
      created_by: 4,
      updated_by: 4,
      client_mutation_id: UUID_M1,
      version: 1,
      created_at: `2026-10-02T09:${String(i % 60).padStart(2, "0")}:00.000000+08:00`,
      updated_at: "2026-10-02T09:00:00.000000+08:00",
      deleted_at: null,
      deleted_by: null,
      cursor_time: `2026-10-02 09:${String(i % 60).padStart(2, "0")}:00.000000`
    });
  }
  return rows;
}

function recordsListMock(spaceOverrides, total) {
  const allRows = pageRows(45);
  return createHouseworkMock(spaceRow(spaceOverrides), MEMBERS, [
    { match: (call) => call.sql.startsWith("SELECT COUNT(*) AS total FROM housework_records"), respond: () => [[{ total }]] },
    {
      match: (call) => call.sql.includes("FROM housework_records r WHERE") && !call.sql.startsWith("SELECT COUNT"),
      respond: () => [allRows.slice(0, 21)]
    },
    { match: (call) => call.sql.includes("FROM housework_record_participants p") && call.sql.includes(" IN ("), respond: () => [[]] }
  ]);
}

test("records list paginates with signed cursor and rejects tampered cursor", async () => {
  const pool = recordsListMock({}, 45);
  const handler = getRouteHandler(createHouseworkRouter({ pool }), "/spaces/:spaceId/records", "get");
  const first = await invokeRoute(handler, { userId: 4, params: { spaceId: "s1" }, query: { limit: "20" } });
  assert.equal(first.nextError, undefined);
  assert.equal(first.jsonPayload.data.items.length, 20);
  assert.equal(first.jsonPayload.data.total, 45);
  assert.ok(first.jsonPayload.data.nextCursor, "expected nextCursor");
  assert.equal(first.jsonPayload.data.revision, 5);

  // 篡改签名
  const tampered = first.jsonPayload.data.nextCursor.slice(0, -2) + (first.jsonPayload.data.nextCursor.endsWith("AA") ? "BB" : "AA");
  const second = await invokeRoute(handler, { userId: 4, params: { spaceId: "s1" }, query: { limit: "20", cursor: tampered } });
  assert.equal(second.statusCode, 400);

  // 翻页期间 revision 变化 → 409 CURSOR_STALE
  const stalePool = recordsListMock({ revision: 6 }, 45);
  const staleHandler = getRouteHandler(createHouseworkRouter({ pool: stalePool }), "/spaces/:spaceId/records", "get");
  const third = await invokeRoute(staleHandler, { userId: 4, params: { spaceId: "s1" }, query: { limit: "20", cursor: first.jsonPayload.data.nextCursor } });
  assert.equal(third.statusCode, 409);
  assert.equal(third.jsonPayload.error.code, "CURSOR_STALE");
});

test("statistics rejects state filter and adHocOnly with templateIds", async () => {
  const pool = recordsListMock({}, 0);
  const handler = getRouteHandler(createHouseworkRouter({ pool }), "/spaces/:spaceId/statistics", "get");
  const withState = await invokeRoute(handler, { userId: 4, params: { spaceId: "s1" }, query: { state: "deleted" } });
  assert.equal(withState.statusCode, 422);

  const listHandler = getRouteHandler(createHouseworkRouter({ pool }), "/spaces/:spaceId/records", "get");
  const conflict = await invokeRoute(listHandler, {
    userId: 4,
    params: { spaceId: "s1" },
    query: { adHocOnly: "true", templateIds: "1,2" }
  });
  assert.equal(conflict.statusCode, 422);
  assert.equal(conflict.jsonPayload.error.fieldErrors[0].path, "adHocOnly");
});

/* Stable identities must survive full-array replacement and legacy deletion. */
function fieldTemplatePatchPool(existingFields, history = [], recordHistory = []) {
  let fields = existingFields;
  let version = 3;
  const captured = {};
  const pool = createHouseworkMock(spaceRow(), MEMBERS, [
    { match: ({ sql }) => sql.includes("FROM housework_mutations"), respond: () => [[]] },
    { match: ({ sql }) => sql.startsWith("SELECT * FROM housework_templates WHERE template_id"), respond: () => [[templateRow({ fields_json: JSON.stringify(fields), version })]] },
    { match: ({ sql }) => sql.startsWith("SELECT * FROM housework_categories WHERE category_id"), respond: () => [[categoryRow()]] },
    { match: ({ sql }) => sql.startsWith("SELECT template_id FROM housework_templates"), respond: () => [[]] },
    { match: ({ sql }) => sql.startsWith("SELECT before_json, after_json"), respond: () => [history.map((definition) => ({ before_json: null, after_json: JSON.stringify({ template: { fields: definition } }) }))] },
    { match: ({ sql }) => sql.startsWith("SELECT snapshot_json FROM housework_records"), respond: () => [recordHistory.map((definition) => ({ snapshot_json: JSON.stringify({ fields: definition }) }))] },
    { match: ({ sql }) => sql.startsWith("UPDATE housework_templates"), respond: ({ params }) => {
      fields = JSON.parse(params[12]); version++; captured.fields = fields;
      return [{ affectedRows: 1 }];
    } },
    { match: ({ sql }) => sql.startsWith("INSERT INTO housework_config_revisions") || sql.startsWith("INSERT INTO housework_mutations"), respond: () => [{ insertId: 1 }] }
  ]);
  return { pool, captured };
}
const ID_FIELD = "88888888-8888-4888-8888-888888888888";
const ID_OPTION = "99999999-9999-4999-8999-999999999999";
const ID_OPTION2 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const textDefinition = { fieldId: ID_FIELD, label: "补充", type: "text", required: true, defaultValue: null, sortOrder: 0, status: "active" };
const selectDefinition = { ...textDefinition, type: "single_select", options: [
  { optionId: ID_OPTION, label: "客厅", status: "active" },
  { optionId: ID_OPTION2, label: "卧室", status: "active" }
] };
async function patchFields(mock, fields) {
  return invokeRoute(getRouteHandler(createHouseworkRouter({ pool: mock.pool }), "/spaces/:spaceId/templates/:id", "patch"), {
    userId: 4, params: { spaceId: "s1", id: UUID_TMPL },
    body: { clientMutationId: UUID_M1, expectedVersion: 3, fields }
  });
}
test("template replacement cannot remove field or option IDs or change field type", async () => {
  for (const [existing, replacement] of [
    [[textDefinition], []],
    [[selectDefinition], [{ ...selectDefinition, options: [selectDefinition.options[0]] }]],
    [[textDefinition], [{ ...textDefinition, type: "boolean" }]]
  ]) {
    const mock = fieldTemplatePatchPool(existing);
    const result = await patchFields(mock, replacement);
    assert.equal(result.statusCode, 422, JSON.stringify(result.jsonPayload));
    assert.equal(result.jsonPayload.error.code, "VALIDATION_ERROR");
    assert.equal(mock.pool.calls.some(({ sql }) => sql.startsWith("UPDATE housework_templates")), false);
  }
});
test("template cannot reuse removed field ID from audit or record snapshots", async () => {
  for (const [history, recordHistory] of [
    [[[textDefinition]], []], [[], [[textDefinition]]]
  ]) {
    const result = await patchFields(fieldTemplatePatchPool([], history, recordHistory), [{ ...textDefinition, type: "boolean" }]);
    assert.equal(result.statusCode, 422);
    assert.equal(result.jsonPayload.error.fieldErrors[0].code, "ID_REUSE_FORBIDDEN");
  }
});
test("template cannot reuse a legacy removed option ID", async () => {
  const existing = { ...selectDefinition, options: [selectDefinition.options[0]] };
  const result = await patchFields(fieldTemplatePatchPool([existing], [[selectDefinition]]), [selectDefinition]);
  assert.equal(result.statusCode, 422);
  assert.equal(result.jsonPayload.error.fieldErrors[0].code, "ID_REUSE_FORBIDDEN");
});
test("template saves disabled IDs and accepts null required defaults", async () => {
  const disabled = { ...selectDefinition, status: "disabled", options: selectDefinition.options.map((option) => ({ ...option, status: "disabled" })) };
  const mock = fieldTemplatePatchPool([selectDefinition]);
  const result = await patchFields(mock, [disabled]);
  assert.equal(result.statusCode, 200, JSON.stringify(result.jsonPayload));
  assert.equal(mock.captured.fields[0].status, "disabled");
  assert.equal(mock.captured.fields[0].defaultValue, null);
  assert.equal(mock.captured.fields[0].required, true);
  assert.deepEqual(mock.captured.fields[0].options.map((option) => option.status), ["disabled", "disabled"]);
});

test("personal config returns its owner as the only permitted participant", async () => {
  const pool = createHouseworkMock(spaceRow({ scope: "personal", owner_user_id: 4, cycle_id: null }), [MEMBERS[0]], []);
  const result = await invokeRoute(getRouteHandler(createHouseworkRouter({ pool }), "/spaces/:spaceId/config", "get"), {
    userId: 4, params: { spaceId: "s1" }
  });
  assert.equal(result.statusCode, 200);
  assert.deepEqual(result.jsonPayload.data.members, [{ userId: "4", displayName: "A" }]);
});

test("closed archive remains readable after relationship and member login accounts are deleted", async () => {
  const pool = createHouseworkMock(spaceRow({ status: "closed", cycle_ended_at: "2026-10-01", relationship_status: null,
    rel_u1: null, rel_u2: null }), MEMBERS, []);
  const result = await invokeRoute(getRouteHandler(createHouseworkRouter({ pool }), "/spaces/:spaceId/config", "get"), {
    userId: 5, params: { spaceId: "s1" }
  });
  assert.equal(result.statusCode, 200);
  assert.equal(result.jsonPayload.data.space.canWrite, false);
  assert.equal(result.jsonPayload.data.members.length, 2);
});
