const assert = require("assert");
const { test } = require("node:test");

const { createTodoRouter, mapTodo } = require("../src/routes/todos");
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

test("mapTodo returns repeat metadata", () => {
  const row = {
    todo_id: 7,
    user_id: 12,
    relationship_id: 9,
    title: "倒猫粮",
    content: null,
    priority: "medium",
    fuzzy_date_text: "今晚",
    image_url: null,
    status: "done",
    is_repeatable: 1,
    series_id: 1001,
    completed_count: 4,
    created_at: "2026-04-22 10:00:00",
    updated_at: "2026-04-22 12:00:00"
  };

  assert.equal(mapTodo(row).isRepeatable, true);
  assert.equal(mapTodo(row).seriesId, 1001);
  assert.equal(mapTodo(row).completedCount, 4);
});

test("post route persists repeat fields and backfills series id from inserted todo id", async () => {
  clearCache();
  const pool = createPoolMock([
    [[]],
    [{ insertId: 42 }],
    [{ affectedRows: 1 }]
  ]);
  const router = createTodoRouter({ pool });
  const postHandler = getRouteHandler(router, "/", "post");

  const result = await invokeHandler(postHandler, {
    body: {
      userId: 7,
      title: "倒猫粮",
      content: "晚上补一次",
      priority: "high",
      fuzzyDateText: "今晚",
      imageUrl: "/uploads/cat.png",
      status: "open",
      isRepeatable: true,
      completedCount: 3
    }
  });

  assert.equal(result.nextError, undefined);
  assert.equal(result.statusCode, 201);
  assert.equal(result.jsonPayload.ok, true);
  assert.equal(result.jsonPayload.data.todoId, 42);
  assert.match(pool.calls[1].query, /INSERT INTO todo_items \(user_id, relationship_id, title, content, priority, fuzzy_date_text, image_url, status, is_repeatable, series_id, completed_count\)/);
  assert.deepEqual(pool.calls[1].params, [7, null, "倒猫粮", "晚上补一次", "high", "今晚", "/uploads/cat.png", "open", 1, null, 3]);
  assert.match(pool.calls[2].query, /UPDATE todo_items SET series_id = \? WHERE todo_id = \?/);
  assert.deepEqual(pool.calls[2].params, [42, 42]);
});

test("put route clears repeat metadata when todo is not repeatable", async () => {
  clearCache();
  const pool = createPoolMock([
    [[]],
    [[{
      todo_id: 42,
      user_id: 7,
      relationship_id: null,
      title: "倒猫粮",
      content: null,
      priority: "medium",
      fuzzy_date_text: "今晚",
      image_url: null,
      status: "open",
      is_repeatable: 1,
      series_id: 1001,
      completed_count: 4,
      created_at: "2026-04-22 10:00:00",
      updated_at: "2026-04-22 12:00:00"
    }]],
    [{ affectedRows: 1 }]
  ]);
  const router = createTodoRouter({ pool });
  const putHandler = getRouteHandler(router, "/:id", "put");

  const result = await invokeHandler(putHandler, {
    params: { id: "42" },
    body: {
      userId: 7,
      isRepeatable: false,
      seriesId: 1001,
      completedCount: 4
    }
  });

  assert.equal(result.nextError, undefined);
  assert.equal(result.statusCode, 200);
  assert.match(pool.calls[2].query, /is_repeatable = \?, series_id = \?, completed_count = \?/);
  assert.deepEqual(pool.calls[2].params, [0, null, 0, 42]);
});

test("put route completing repeatable todo increments completed count", async () => {
  clearCache();
  const pool = createPoolMock([
    [[]],
    [[{
      todo_id: 42,
      user_id: 7,
      relationship_id: null,
      title: "倒猫粮",
      content: null,
      priority: "medium",
      fuzzy_date_text: "今晚",
      image_url: null,
      status: "open",
      is_repeatable: 1,
      series_id: 1001,
      completed_count: 3,
      created_at: "2026-04-22 10:00:00",
      updated_at: "2026-04-22 12:00:00"
    }]],
    [{ affectedRows: 1 }]
  ]);
  const router = createTodoRouter({ pool });
  const putHandler = getRouteHandler(router, "/:id", "put");

  const result = await invokeHandler(putHandler, {
    params: { id: "42" },
    body: {
      userId: 7,
      status: "done",
      isRepeatable: true,
      seriesId: 1001,
      completedCount: 4
    }
  });

  assert.equal(result.nextError, undefined);
  assert.equal(result.statusCode, 200);
  assert.match(pool.calls[2].query, /status = \?, is_repeatable = \?, series_id = \?, completed_count = \?/);
  assert.deepEqual(pool.calls[2].params, ["done", 1, 1001, 4, 42]);
});

test("duplicate endpoint creates next open todo in same series for completed repeatable todo", async () => {
  clearCache();
  const pool = createPoolMock([
    [[]],
    [[{
      todo_id: 7,
      user_id: 12,
      relationship_id: 9,
      title: "倒猫粮",
      content: "晚上补一次",
      priority: "medium",
      fuzzy_date_text: "今晚",
      image_url: "/uploads/cat.png",
      status: "done",
      is_repeatable: 1,
      series_id: 1001,
      completed_count: 3,
      created_at: "2026-04-22 10:00:00",
      updated_at: "2026-04-22 12:00:00"
    }]],
    [{ insertId: 8 }],
    [[{
      todo_id: 8,
      user_id: 12,
      relationship_id: 9,
      title: "倒猫粮",
      content: "晚上补一次",
      priority: "medium",
      fuzzy_date_text: "今晚",
      image_url: "/uploads/cat.png",
      status: "open",
      is_repeatable: 1,
      series_id: 1001,
      completed_count: 3,
      created_at: "2026-04-22 12:30:00",
      updated_at: "2026-04-22 12:30:00"
    }]]
  ]);
  const router = createTodoRouter({ pool });
  const duplicateHandler = getRouteHandler(router, "/:id/duplicate", "post");

  const result = await invokeHandler(duplicateHandler, {
    params: { id: "7" },
    body: { userId: 12 }
  });

  assert.equal(result.nextError, undefined);
  assert.equal(result.statusCode, 200);
  assert.equal(result.jsonPayload.ok, true);
  assert.equal(result.jsonPayload.data.todoId, 8);
  assert.equal(result.jsonPayload.data.seriesId, 1001);
  assert.equal(result.jsonPayload.data.status, "open");
  assert.equal(result.jsonPayload.data.completedCount, 3);
  assert.equal(result.jsonPayload.data.createdAt, "2026-04-22 12:30:00");
});

test("duplicate endpoint rejects open repeatable todos", async () => {
  clearCache();
  const pool = createPoolMock([
    [[]],
    [[{
      todo_id: 7,
      user_id: 12,
      relationship_id: 9,
      title: "倒猫粮",
      content: "晚上补一次",
      priority: "medium",
      fuzzy_date_text: "今晚",
      image_url: "/uploads/cat.png",
      status: "open",
      is_repeatable: 1,
      series_id: 1001,
      completed_count: 3,
      created_at: "2026-04-22 10:00:00",
      updated_at: "2026-04-22 12:00:00"
    }]]
  ]);
  const router = createTodoRouter({ pool });
  const duplicateHandler = getRouteHandler(router, "/:id/duplicate", "post");

  const result = await invokeHandler(duplicateHandler, {
    params: { id: "7" },
    body: { userId: 12 }
  });

  assert.equal(result.jsonPayload, undefined);
  assert.equal(result.nextError.status, 400);
  assert.equal(result.nextError.code, "INVALID_REQUEST");
  assert.match(result.nextError.message, /已完成/);
});

test("duplicate endpoint rejects non-repeatable todos", async () => {
  clearCache();
  const pool = createPoolMock([
    [[]],
    [[{
      todo_id: 7,
      user_id: 12,
      relationship_id: 9,
      title: "倒猫粮",
      content: "晚上补一次",
      priority: "medium",
      fuzzy_date_text: "今晚",
      image_url: "/uploads/cat.png",
      status: "done",
      is_repeatable: 0,
      series_id: null,
      completed_count: 0,
      created_at: "2026-04-22 10:00:00",
      updated_at: "2026-04-22 12:00:00"
    }]]
  ]);
  const router = createTodoRouter({ pool });
  const duplicateHandler = getRouteHandler(router, "/:id/duplicate", "post");

  const result = await invokeHandler(duplicateHandler, {
    params: { id: "7" },
    body: { userId: 12 }
  });

  assert.equal(result.jsonPayload, undefined);
  assert.equal(result.nextError.status, 400);
  assert.equal(result.nextError.code, "INVALID_REQUEST");
  assert.match(result.nextError.message, /重复/);
});
