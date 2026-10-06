const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { cache, Keys } = require("../src/cache");
const { requireAuthForBusiness, createRequireAuth } = require("../src/middleware/auth");
const { createBeadRouter } = require("../src/routes/beads");
const { createBillsRouter } = require("../src/routes/bills");
const { createCalorieRouter } = require("../src/routes/calorie");
const { createCoupleRouter } = require("../src/routes/couple");
const { fetchBillsSummary, fetchPeriodPrediction } = require("../src/routes/me");
const { createPeriodRouter } = require("../src/routes/period");
const { createRecipeRouter } = require("../src/routes/recipes");
const { createSharedPlansRouter } = require("../src/routes/sharedPlans");
const { buildCoupleOrPrivateScope } = require("../src/utils/queryHelpers");
const { signToken } = require("../src/utils/jwt");

function invokeMiddleware(middleware, req) {
  let nextCalled = false;
  let nextError;
  middleware(req, {}, (error) => {
    nextCalled = true;
    nextError = error;
  });
  return { nextCalled, nextError };
}

function getRouteHandler(router, routePath, method) {
  const layer = router.stack.find(
    (entry) => entry.route && entry.route.path === routePath && entry.route.methods[method]
  );
  return layer?.route?.stack?.[0]?.handle;
}

async function invokeRoute(
  handler,
  { userId, params = {}, body = {}, query = {} } = {}
) {
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
    },
  };
  await handler(req, res, (error) => {
    nextError = error;
  });
  return { statusCode, jsonPayload, nextError };
}

function createPoolMock(responder) {
  const calls = [];
  return {
    calls,
    async execute(sql, params) {
      const call = { sql, params };
      calls.push(call);
      return responder(call, calls.length - 1);
    },
  };
}

function createTransactionalPoolMock(responder) {
  const calls = [];
  const execute = async (sql, params) => {
    const call = { sql, params };
    calls.push(call);
    return responder(call, calls.length - 1);
  };
  const connection = {
    async beginTransaction() {},
    async commit() {},
    async rollback() {},
    release() {},
    execute,
  };
  return {
    calls,
    execute,
    async getConnection() {
      return connection;
    },
  };
}

test("business auth rejects query/body userId when bearer token is absent", () => {
  const req = {
    headers: {},
    query: { userId: "9" },
    body: { userId: 9 },
    method: "GET",
    originalUrl: "/api/bills",
  };

  const result = invokeMiddleware(requireAuthForBusiness, req);

  assert.equal(result.nextCalled, true);
  assert.equal(result.nextError?.status, 401);
  assert.equal(req.userId, undefined);
});

test("business auth always takes userId from the bearer token after DB session validation", async () => {
  const token = signToken({ userId: 7 });
  const req = {
    headers: { authorization: `Bearer ${token}` },
    query: { userId: "99" },
    body: { userId: 99 },
  };

  let nextError;
  await new Promise((resolve) => {
    createRequireAuth({
      async execute() {
        return [[{ id: 7, status: "active", auth_token_version: 0 }]];
      },
    })(req, {}, (error) => {
      nextError = error;
      resolve();
    });
  });

  assert.equal(nextError, undefined);
  assert.equal(req.userId, 7);
});

test("push registration sends bearer auth and does not serialize authority userId", () => {
  const sourcePath = path.resolve(
    __dirname,
    "../../app/src/main/java/com/example/couplecredit/utils/NotificationHelper.java"
  );
  const source = fs.readFileSync(sourcePath, "utf8");

  assert.match(
    source,
    /setRequestProperty\("Authorization",\s*"Bearer " \+ accessToken\)/
  );
  assert.doesNotMatch(source, /String json\s*=.*userId/);
});

test("Android local chat cache and pending sync are isolated by relationship", () => {
  const appRoot = path.resolve(__dirname, "../../app/src/main/java/com/example/couplecredit");
  const daoSource = fs.readFileSync(path.join(appRoot, "database/ChatMessageDao.java"), "utf8");
  const databaseSource = fs.readFileSync(path.join(appRoot, "database/ChatDatabase.java"), "utf8");
  const repositorySource = fs.readFileSync(path.join(appRoot, "repository/ChatRepository.java"), "utf8");
  const cloudSource = fs.readFileSync(path.join(appRoot, "repository/CloudChatRepository.java"), "utf8");
  const viewModelSource = fs.readFileSync(path.join(appRoot, "viewmodel/ChatViewModel.java"), "utf8");

  assert.match(daoSource, /getMessagesForRelationship\(long relationshipId\)/);
  assert.match(daoSource, /getPendingMessages\(long relationshipId\)/);
  assert.match(daoSource, /searchMessages\(long relationshipId, String keyword\)/);
  assert.match(daoSource, /deleteAllMessagesForLogout\(\)/);
  assert.match(repositorySource, /private (?:volatile )?long currentRelationshipId = -1/);
  assert.match(repositorySource, /getPendingMessages\((?:currentRelationshipId|relationshipId)\)/);
  assert.match(repositorySource, /getMessagesForRelationship\((?:currentRelationshipId|relationshipId)\)/);
  assert.match(repositorySource, /private boolean isCurrentRelationship\(long relationshipId, long generation\)/);
  assert.match(repositorySource, /updateLocalCache\(relationshipId, generation, batchData\.messages/);
  assert.match(repositorySource, /if \(!isCurrentRelationship\(relationshipId, generation\)\) return;/);
  assert.match(cloudSource, /message\.getRelationshipId\(\)/);
  assert.match(cloudSource, /messageRelationshipId != currentRelationshipId/);
  assert.doesNotMatch(viewModelSource, /relationshipId != null \? relationshipId : 1/);
  assert.doesNotMatch(viewModelSource, /currentRelationshipId = 1/);
  assert.match(databaseSource, /version = 8/);
  assert.match(databaseSource, /index_chat_messages_relationshipId/);
});

test("bill creation ignores a spoofed body userId", async () => {
  cache.store.clear();
  const authenticatedUserId = 7001;
  const partnerId = 7002;
  const relationshipId = 71;
  const pool = createPoolMock((_call, index) => {
    if (index === 0) {
      return [[{
        relationship_id: relationshipId,
        user_id_1: authenticatedUserId,
        user_id_2: partnerId,
      }]];
    }
    return [{ insertId: 501 }];
  });
  const handler = getRouteHandler(createBillsRouter({ pool }), "/", "post");

  const result = await invokeRoute(handler, {
    userId: authenticatedUserId,
    body: {
      userId: 9999,
      billOwner: "自己",
      title: "早餐",
      type: "餐饮",
      amount: 10,
      date: "2026-08-09",
      time: "08:00:00",
      incomeType: 0,
    },
  });

  assert.equal(result.nextError, undefined);
  assert.deepEqual(pool.calls[0].params, [authenticatedUserId, authenticatedUserId]);
  assert.equal(pool.calls[1].params[0], relationshipId);
  assert.equal(pool.calls[1].params[3], authenticatedUserId);
});

test("legacy bill payload cannot select an arbitrary relationship", async () => {
  cache.store.clear();
  const authenticatedUserId = 7011;
  const partnerId = 7012;
  const relationshipId = 72;
  const pool = createPoolMock((_call, index) => {
    if (index === 0) {
      return [[{
        relationship_id: relationshipId,
        user_id_1: authenticatedUserId,
        user_id_2: partnerId,
      }]];
    }
    return [{ insertId: 502 }];
  });
  const handler = getRouteHandler(createBillsRouter({ pool }), "/", "post");

  const result = await invokeRoute(handler, {
    userId: authenticatedUserId,
    body: {
      userId: 9999,
      relationshipId: 999,
      owner: 3,
      isHelp: 0,
      title: "晚餐",
      type: "餐饮",
      amount: 20,
      date: "2026-08-09",
      time: "18:00:00",
      incomeType: 0,
    },
  });

  assert.equal(result.nextError, undefined);
  assert.match(pool.calls[0].sql, /couple_relationships/);
  assert.deepEqual(pool.calls[0].params, [authenticatedUserId, authenticatedUserId]);
  assert.equal(pool.calls[1].params[0], relationshipId);
});

test("bill list combines the active relationship with private self rows", async () => {
  cache.store.clear();
  const authenticatedUserId = 7021;
  const relationshipId = 73;
  const pool = createPoolMock((_call, index) => {
    if (index === 0) {
      return [[{
        relationship_id: relationshipId,
        user_id_1: authenticatedUserId,
        user_id_2: 7022,
      }]];
    }
    return [[]];
  });
  const handler = getRouteHandler(createBillsRouter({ pool }), "/", "get");

  const result = await invokeRoute(handler, {
    userId: authenticatedUserId,
    query: { year: "2026", month: "8" },
  });

  assert.equal(result.nextError, undefined);
  assert.match(
    pool.calls[1].sql,
    /b\.relationship_id = \? OR \(b\.user_id = \? AND b\.relationship_id IS NULL\)/
  );
  assert.deepEqual(pool.calls[1].params.slice(0, 2), [relationshipId, authenticatedUserId]);
});

test("creating a shared bill invalidates the partner bill cache", async () => {
  cache.store.clear();
  const authenticatedUserId = 7031;
  const partnerId = 7032;
  const relationshipId = 74;
  const partnerCacheKey = Keys.bills(partnerId, 2026, 8);
  const partnerOverviewKey = Keys.overview(partnerId, 2026, 8);
  cache.set(partnerCacheKey, { stale: true }, 60);
  cache.set(partnerOverviewKey, { stale: true }, 60);
  const pool = createPoolMock((_call, index) => {
    if (index === 0) {
      return [[{
        relationship_id: relationshipId,
        user_id_1: authenticatedUserId,
        user_id_2: partnerId,
      }]];
    }
    return [{ insertId: 503 }];
  });
  const handler = getRouteHandler(createBillsRouter({ pool }), "/", "post");

  const result = await invokeRoute(handler, {
    userId: authenticatedUserId,
    body: {
      billOwner: "共同",
      title: "电影",
      type: "娱乐",
      amount: 88,
      date: "2026-08-09",
      time: "20:00:00",
      incomeType: 0,
    },
  });

  assert.equal(result.nextError, undefined);
  assert.equal(cache.get(partnerCacheKey), null);
  assert.equal(cache.get(partnerOverviewKey), null);
});

test("a bill subject can update a partner-created bill in the active relationship", async () => {
  cache.store.clear();
  const subjectId = 7042;
  const creatorId = 7041;
  const relationshipId = 75;
  const pool = createTransactionalPoolMock((call) => {
    if (call.sql.includes("couple_relationships")) {
      return [[{ relationship_id: relationshipId, user_id_1: creatorId, user_id_2: subjectId }]];
    }
    if (call.sql.includes("FROM bills") && call.sql.includes("bill_id = ?")) {
      if (call.sql.includes("AND user_id = ?")) return [[]];
      return [[{
        bill_id: 504,
        relationship_id: relationshipId,
        user_id: creatorId,
        owner: 2,
        shared_plan_id: null,
      }]];
    }
    if (call.sql.startsWith("UPDATE bills")) {
      return [{ affectedRows: call.sql.includes("AND user_id = ?") ? 0 : 1 }];
    }
    throw new Error(`Unexpected query: ${call.sql}`);
  });
  const handler = getRouteHandler(createBillsRouter({ pool }), "/:id", "put");

  const result = await invokeRoute(handler, {
    userId: subjectId,
    params: { id: "504" },
    body: {
      title: "主体修正",
      type: "餐饮",
      amount: 30,
      date: "2026-08-09",
      time: "12:00:00",
      incomeType: 0,
    },
  });

  assert.equal(result.nextError, undefined);
  const update = pool.calls.find((call) => call.sql.startsWith("UPDATE bills"));
  assert.doesNotMatch(update.sql, /AND user_id = \?/);
});

test("a bill creator cannot update a bill from a former relationship", async () => {
  cache.store.clear();
  const userId = 7051;
  const relationshipId = 76;
  const formerRelationshipId = 75;
  const pool = createTransactionalPoolMock((call) => {
    if (call.sql.includes("couple_relationships")) {
      return [[{ relationship_id: relationshipId, user_id_1: userId, user_id_2: 7052 }]];
    }
    if (call.sql.includes("FROM bills") && call.sql.includes("bill_id = ?")) {
      return [[{
        bill_id: 505,
        relationship_id: formerRelationshipId,
        user_id: userId,
        owner: 1,
        shared_plan_id: null,
      }]];
    }
    if (call.sql.startsWith("UPDATE bills")) return [{ affectedRows: 1 }];
    throw new Error(`Unexpected query: ${call.sql}`);
  });
  const handler = getRouteHandler(createBillsRouter({ pool }), "/:id", "put");

  const result = await invokeRoute(handler, {
    userId,
    params: { id: "505" },
    body: {
      title: "旧关系账单",
      type: "餐饮",
      amount: 30,
      date: "2026-08-09",
      time: "12:00:00",
      incomeType: 0,
    },
  });

  assert.equal(result.nextError?.status, 404);
  assert.equal(pool.calls.some((call) => call.sql.startsWith("UPDATE bills")), false);
});

test("a bill subject can delete a partner-created bill in the active relationship", async () => {
  cache.store.clear();
  const subjectId = 7062;
  const creatorId = 7061;
  const relationshipId = 77;
  const pool = createTransactionalPoolMock((call) => {
    if (call.sql.includes("couple_relationships")) {
      return [[{ relationship_id: relationshipId, user_id_1: creatorId, user_id_2: subjectId }]];
    }
    if (call.sql.includes("FROM bills") && call.sql.includes("bill_id = ?")) {
      if (call.sql.includes("AND user_id = ?")) return [[]];
      return [[{
        bill_id: 506,
        relationship_id: relationshipId,
        user_id: creatorId,
        owner: 2,
        shared_plan_id: null,
        income_type: 0,
        amount: 30,
      }]];
    }
    if (call.sql.startsWith("DELETE FROM bills")) return [{ affectedRows: 1 }];
    throw new Error(`Unexpected query: ${call.sql}`);
  });
  const handler = getRouteHandler(createBillsRouter({ pool }), "/:id", "delete");

  const result = await invokeRoute(handler, { userId: subjectId, params: { id: "506" } });

  assert.equal(result.nextError, undefined);
  const deletion = pool.calls.find((call) => call.sql.startsWith("DELETE FROM bills"));
  assert.doesNotMatch(deletion.sql, /AND user_id = \?/);
});

test("shared-or-private scope includes active relationship rows and private self rows", () => {
  const scope = buildCoupleOrPrivateScope(
    8001,
    { relationship_id: 81, user_id_1: 8001, user_id_2: 8002 },
    "r"
  );

  assert.equal(
    scope.clause,
    "(r.relationship_id = ? OR (r.user_id = ? AND r.relationship_id IS NULL))"
  );
  assert.deepEqual(scope.params, [81, 8001]);
});

test("recipe detail hides records outside the active relationship", async () => {
  cache.store.clear();
  const userId = 8011;
  const relationshipId = 82;
  const pool = createPoolMock((call) => {
    if (call.sql.includes("couple_relationships")) {
      return [[{ relationship_id: relationshipId, user_id_1: userId, user_id_2: 8012 }]];
    }
    if (call.sql.includes("FROM recipes")) {
      if (call.sql.includes("relationship_id = ?")) return [[]];
      return [[{
        recipe_id: 44,
        user_id: 9999,
        title: "外部菜谱",
        description: null,
        image_url: null,
        steps: null,
        total_calories: null,
        calorie_source: null,
      }]];
    }
    if (call.sql.includes("FROM recipe_ingredients")) return [[]];
    throw new Error(`Unexpected query: ${call.sql}`);
  });
  const handler = getRouteHandler(createRecipeRouter({ pool }), "/:id", "get");

  const result = await invokeRoute(handler, { userId, params: { id: "44" } });

  assert.equal(result.nextError?.status, 404);
  assert.equal(result.nextError?.code, "NOT_FOUND");
});

test("either partner can update a relationship recipe", async () => {
  cache.store.clear();
  const userId = 8021;
  const relationshipId = 83;
  const pool = createTransactionalPoolMock((call) => {
    if (call.sql.includes("couple_relationships")) {
      return [[{ relationship_id: relationshipId, user_id_1: 8022, user_id_2: userId }]];
    }
    if (call.sql.includes("SELECT recipe_id FROM recipes")) {
      return [[{ recipe_id: 45 }]];
    }
    if (call.sql.includes("UPDATE recipes SET title")) return [{ affectedRows: 1 }];
    if (call.sql.includes("DELETE FROM recipe_ingredients")) return [{ affectedRows: 0 }];
    if (call.sql.includes("UPDATE recipes SET total_calories")) return [{ affectedRows: 1 }];
    throw new Error(`Unexpected query: ${call.sql}`);
  });
  const handler = getRouteHandler(createRecipeRouter({ pool }), "/:id", "put");

  const result = await invokeRoute(handler, {
    userId,
    params: { id: "45" },
    body: {
      title: "共同菜谱",
      description: "两个人都能维护",
      steps: "完成",
      ingredients: [],
      categoryId: null,
      totalCalories: 500,
    },
  });

  assert.equal(result.nextError, undefined);
  const update = pool.calls.find((call) => call.sql.includes("UPDATE recipes SET title"));
  assert.match(
    update.sql,
    /relationship_id = \? OR \(user_id = \? AND relationship_id IS NULL\)/
  );
  assert.deepEqual(update.params.slice(-3), [45, relationshipId, userId]);
});

test("either partner can delete a relationship recipe", async () => {
  cache.store.clear();
  const userId = 8031;
  const relationshipId = 84;
  const pool = createPoolMock((call) => {
    if (call.sql.includes("couple_relationships")) {
      return [[{ relationship_id: relationshipId, user_id_1: userId, user_id_2: 8032 }]];
    }
    if (call.sql.includes("DELETE FROM recipes")) return [{ affectedRows: 1 }];
    throw new Error(`Unexpected query: ${call.sql}`);
  });
  const handler = getRouteHandler(createRecipeRouter({ pool }), "/:id", "delete");

  const result = await invokeRoute(handler, { userId, params: { id: "46" } });

  assert.equal(result.nextError, undefined);
  const deletion = pool.calls.find((call) => call.sql.includes("DELETE FROM recipes"));
  assert.match(
    deletion.sql,
    /relationship_id = \? OR \(user_id = \? AND relationship_id IS NULL\)/
  );
  assert.deepEqual(deletion.params, [46, relationshipId, userId]);
});

test("recipe create rejects a category from another relationship", async () => {
  cache.store.clear();
  const userId = 8041;
  const relationshipId = 85;
  const pool = createPoolMock((call) => {
    if (call.sql.includes("couple_relationships")) {
      return [[{ relationship_id: relationshipId, user_id_1: userId, user_id_2: 8042 }]];
    }
    if (call.sql.includes("FROM recipe_categories")) return [[]];
    if (call.sql.includes("INSERT INTO recipes")) return [{ insertId: 47 }];
    if (call.sql.includes("FROM recipe_ingredients")) return [[]];
    if (call.sql.includes("UPDATE recipes SET total_calories")) return [{ affectedRows: 1 }];
    throw new Error(`Unexpected query: ${call.sql}`);
  });
  const handler = getRouteHandler(createRecipeRouter({ pool }), "/", "post");

  const result = await invokeRoute(handler, {
    userId,
    body: { title: "越界分类", categoryId: 999, ingredients: [] },
  });

  assert.equal(result.nextError?.status, 404);
  assert.equal(pool.calls.some((call) => call.sql.includes("INSERT INTO recipes")), false);
});

test("cook rejects a recipe outside the active relationship", async () => {
  cache.store.clear();
  const userId = 8051;
  const relationshipId = 86;
  const pool = createPoolMock((call) => {
    if (call.sql.includes("couple_relationships")) {
      return [[{ relationship_id: relationshipId, user_id_1: userId, user_id_2: 8052 }]];
    }
    if (call.sql.includes("FROM recipes")) return [[]];
    if (call.sql.includes("FROM recipe_ingredients")) return [[]];
    throw new Error(`Unexpected query: ${call.sql}`);
  });
  const handler = getRouteHandler(createRecipeRouter({ pool }), "/:id/cook", "post");

  const result = await invokeRoute(handler, { userId, params: { id: "48" } });

  assert.equal(result.nextError?.status, 404);
  assert.equal(result.nextError?.code, "NOT_FOUND");
});

test("calorie history is visible to both active partners", async () => {
  cache.store.clear();
  const userId = 9011;
  const partnerId = 9012;
  const relationshipId = 91;
  const pool = createPoolMock((call) => {
    if (call.sql.includes("couple_relationships")) {
      return [[{ relationship_id: relationshipId, user_id_1: userId, user_id_2: partnerId }]];
    }
    if (call.sql.includes("FROM meal_records")) {
      return [[
        {
          record_id: 1,
          user_id: userId,
          meal_type: "manual",
          recipe_id: null,
          restaurant_id: null,
          title: "我的早餐",
          calories: 300,
          calorie_source: "manual",
          note: null,
          eaten_at: "2026-08-09 08:00:00",
          created_at: "2026-08-09 08:00:00",
        },
        {
          record_id: 2,
          user_id: partnerId,
          meal_type: "manual",
          recipe_id: null,
          restaurant_id: null,
          title: "对方早餐",
          calories: 250,
          calorie_source: "manual",
          note: null,
          eaten_at: "2026-08-09 08:10:00",
          created_at: "2026-08-09 08:10:00",
        },
      ]];
    }
    if (call.sql.includes("FROM user_calorie_goals")) {
      return [[
        { user_id: userId, daily_goal: 1800 },
        { user_id: partnerId, daily_goal: 2000 },
      ]];
    }
    if (call.sql.includes("FROM users")) {
      return [[
        { id: userId, username: "self", nickname: "我" },
        { id: partnerId, username: "partner", nickname: "对方" },
      ]];
    }
    throw new Error(`Unexpected query: ${call.sql}`);
  });
  const handler = getRouteHandler(createCalorieRouter({ pool }), "/history", "get");

  const result = await invokeRoute(handler, {
    userId,
    query: { date: "2026-08-09" },
  });

  assert.equal(result.nextError, undefined);
  assert.deepEqual(
    result.jsonPayload.data.records.map((record) => record.userId).sort(),
    [userId, partnerId].sort()
  );
  const recordQuery = pool.calls.find((call) => call.sql.includes("FROM meal_records"));
  assert.match(recordQuery.sql, /user_id = \? OR \(relationship_id = \? AND user_id IN \(\?, \?\)\)/);
  assert.deepEqual(recordQuery.params.slice(0, 4), [userId, relationshipId, userId, partnerId]);
});

test("calorie record creation ignores a spoofed body userId", async () => {
  cache.store.clear();
  const userId = 9021;
  const partnerId = 9022;
  const selfOverviewKey = Keys.overview(userId, 2026, 8);
  const partnerOverviewKey = Keys.overview(partnerId, 2026, 8);
  cache.set(selfOverviewKey, { stale: true }, 60);
  cache.set(partnerOverviewKey, { stale: true }, 60);
  const pool = createPoolMock((call) => {
    if (call.sql.includes("INSERT INTO meal_records")) return [{ insertId: 10 }];
    if (call.sql.includes("couple_relationships")) {
      return [[{ relationship_id: 92, user_id_1: userId, user_id_2: partnerId }]];
    }
    throw new Error(`Unexpected query: ${call.sql}`);
  });
  const handler = getRouteHandler(createCalorieRouter({ pool }), "/record", "post");

  const result = await invokeRoute(handler, {
    userId,
    body: {
      userId: partnerId,
      mealType: "manual",
      title: "本人记录",
      calories: 400,
    },
  });

  assert.equal(result.nextError, undefined);
  const insertion = pool.calls.find((call) => call.sql.includes("INSERT INTO meal_records"));
  assert.match(insertion.sql, /\(user_id, relationship_id, meal_type/);
  assert.equal(insertion.params[0], userId);
  assert.equal(insertion.params[1], 92);
  assert.equal(cache.get(selfOverviewKey), null);
  assert.equal(cache.get(partnerOverviewKey), null);
});

test("calorie schema and recipe cooking persist relationship ownership", () => {
  const migrationPath = path.resolve(
    __dirname,
    "../migrations/026_add_meal_record_relationship.sql"
  );
  const recipePath = path.resolve(__dirname, "../src/routes/recipes.js");

  assert.equal(fs.existsSync(migrationPath), true);
  const migration = fs.readFileSync(migrationPath, "utf8");
  const recipeSource = fs.readFileSync(recipePath, "utf8");
  assert.match(migration, /ADD COLUMN relationship_id INT UNSIGNED NULL/);
  assert.match(migration, /FOREIGN KEY \(relationship_id\) REFERENCES couple_relationships\(relationship_id\)/);
  assert.match(recipeSource, /INSERT INTO meal_records \(user_id, relationship_id, meal_type/);
});

test("a partner cannot delete the other partner calorie record", async () => {
  cache.store.clear();
  const userId = 9031;
  const partnerId = 9032;
  const pool = createPoolMock((call) => {
    if (call.sql.includes("SELECT record_id, user_id FROM meal_records")) {
      return [[{ record_id: 11, user_id: partnerId }]];
    }
    throw new Error(`Unexpected query: ${call.sql}`);
  });
  const handler = getRouteHandler(createCalorieRouter({ pool }), "/record/:id", "delete");

  const result = await invokeRoute(handler, { userId, params: { id: "11" } });

  assert.equal(result.nextError?.status, 403);
  assert.equal(
    pool.calls.some((call) => call.sql.includes("DELETE FROM meal_records")),
    false
  );
});

test("period history is visible to both active partners", async () => {
  cache.store.clear();
  const userId = 9041;
  const partnerId = 9042;
  const relationshipId = 94;
  const pool = createPoolMock((call) => {
    if (call.sql.includes("couple_relationships")) {
      return [[{ relationship_id: relationshipId, user_id_1: userId, user_id_2: partnerId }]];
    }
    if (call.sql.includes("FROM period_records")) {
      return [[
        { id: 1, user_id: userId, start_date: "2026-07-01", end_date: "2026-07-05", note: null },
        { id: 2, user_id: partnerId, start_date: "2026-07-03", end_date: "2026-07-07", note: null },
      ]];
    }
    if (call.sql.includes("FROM users")) {
      return [[
        { id: userId, username: "self", nickname: "我" },
        { id: partnerId, username: "partner", nickname: "对方" },
      ]];
    }
    throw new Error(`Unexpected query: ${call.sql}`);
  });
  const handler = getRouteHandler(createPeriodRouter({ pool }), "/", "get");

  const result = await invokeRoute(handler, { userId });

  assert.equal(result.nextError, undefined);
  assert.deepEqual(
    result.jsonPayload.data.records.map((record) => record.userId).sort(),
    [userId, partnerId].sort()
  );
  const recordQuery = pool.calls.find((call) => call.sql.includes("FROM period_records"));
  assert.match(recordQuery.sql, /user_id = \? OR \(relationship_id = \? AND user_id IN \(\?, \?\)\)/);
  assert.doesNotMatch(recordQuery.sql, /LIMIT 24/);
  assert.deepEqual(recordQuery.params, [userId, relationshipId, userId, partnerId]);
});

test("overview period prediction excludes a partner's former relationship rows", async () => {
  cache.store.clear();
  const userId = 9043;
  const partnerId = 9044;
  const relationshipId = 95;
  const pool = createPoolMock((call) => {
    if (call.sql.includes("couple_relationships")) {
      return [[{ relationship_id: relationshipId, user_id_1: userId, user_id_2: partnerId }]];
    }
    if (call.sql.includes("FROM period_records")) return [[]];
    throw new Error(`Unexpected query: ${call.sql}`);
  });

  await fetchPeriodPrediction(pool, userId);

  const recordQuery = pool.calls.find((call) => call.sql.includes("FROM period_records"));
  assert.match(recordQuery.sql, /user_id = \? OR \(relationship_id = \? AND user_id IN \(\?, \?\)\)/);
  assert.doesNotMatch(recordQuery.sql, /LIMIT 24/);
  assert.deepEqual(recordQuery.params, [userId, relationshipId, userId, partnerId]);
});

test("period writes invalidate both partners' overview caches", async () => {
  cache.store.clear();
  const userId = 9045;
  const partnerId = 9046;
  const relationshipId = 96;
  const selfOverviewKey = Keys.overview(userId, 2026, 8);
  const partnerOverviewKey = Keys.overview(partnerId, 2026, 8);
  cache.set(selfOverviewKey, { stale: true }, 60);
  cache.set(partnerOverviewKey, { stale: true }, 60);
  const pool = createPoolMock((call) => {
    if (call.sql.includes("couple_relationships")) {
      return [[{ relationship_id: relationshipId, user_id_1: userId, user_id_2: partnerId }]];
    }
    if (call.sql.startsWith("UPDATE period_records")) return [{ affectedRows: 1 }];
    if (call.sql.startsWith("INSERT INTO period_records")) return [{ insertId: 13 }];
    throw new Error(`Unexpected query: ${call.sql}`);
  });
  const handler = getRouteHandler(createPeriodRouter({ pool }), "/", "post");

  const result = await invokeRoute(handler, {
    userId,
    body: { startDate: "2026-08-09", note: "开始" },
  });

  assert.equal(result.nextError, undefined);
  assert.equal(cache.get(selfOverviewKey), null);
  assert.equal(cache.get(partnerOverviewKey), null);
});

test("a partner cannot update the other partner period record", async () => {
  cache.store.clear();
  const userId = 9051;
  const partnerId = 9052;
  const pool = createPoolMock((call) => {
    if (call.sql.includes("SELECT id, user_id FROM period_records")) {
      return [[{ id: 12, user_id: partnerId }]];
    }
    throw new Error(`Unexpected query: ${call.sql}`);
  });
  const handler = getRouteHandler(createPeriodRouter({ pool }), "/:id", "put");

  const result = await invokeRoute(handler, {
    userId,
    params: { id: "12" },
    body: { note: "不能代改" },
  });

  assert.equal(result.nextError?.status, 403);
  assert.equal(
    pool.calls.some((call) => call.sql.startsWith("UPDATE period_records")),
    false
  );
});

test("couple bind derives the invitee from JWT and inviter from invite code", async () => {
  cache.store.clear();
  const authenticatedUserId = 9061;
  const inviterId = 9062;
  const pool = createTransactionalPoolMock((call) => {
    if (call.sql.includes("FROM users") && call.sql.includes("invite_code")) {
      return [[{ id: inviterId }]];
    }
    if (call.sql.includes("FROM users") && call.sql.includes("FOR UPDATE")) {
      return [[{ id: call.params[0] }]];
    }
    if (call.sql.includes("FROM users") && call.sql.includes("username")) {
      return [[
        { id: inviterId, username: "inviter", nickname: null },
        { id: authenticatedUserId, username: "invitee", nickname: null },
      ]];
    }
    if (call.sql.includes("FROM couple_relationships")) return [[]];
    if (call.sql.includes("INSERT INTO couple_relationships")) return [{ insertId: 96 }];
    if (call.sql.includes("UPDATE users")) return [{ affectedRows: 2 }];
    if (call.sql.includes("FROM housework_relationship_cycles")) return [[]];
    if (call.sql.includes("INSERT INTO housework_relationship_cycles")) return [{ affectedRows: 1 }];
    if (call.sql.includes("INSERT INTO housework_spaces")) return [{ affectedRows: 1 }];
    if (call.sql.includes("INSERT INTO housework_space_members")) return [{ affectedRows: 1 }];
    if (call.sql.includes("FROM housework_categories")) return [[]];
    if (call.sql.includes("INSERT INTO housework_categories")) return [{ affectedRows: 1 }];
    // 基础包播撒（创建即播撒，spec §5.0）：读存量后逐项 INSERT 分类/模板 + 配置审计
    if (call.sql.includes("FROM housework_templates")) return [[]];
    if (call.sql.includes("INSERT INTO housework_templates")) return [{ affectedRows: 1 }];
    if (call.sql.includes("INSERT INTO housework_config_revisions")) return [{ affectedRows: 1 }];
    throw new Error(`Unexpected query: ${call.sql}`);
  });
  const handler = getRouteHandler(createCoupleRouter({ pool }), "/bind", "post");

  const result = await invokeRoute(handler, {
    userId: authenticatedUserId,
    body: {
      inviteCode: "ABC123",
      inviterId: 9991,
      inviteeId: 9992,
    },
  });

  assert.equal(result.nextError, undefined);
  const insertion = pool.calls.find((call) => call.sql.includes("INSERT INTO couple_relationships"));
  assert.deepEqual(insertion.params, [inviterId, authenticatedUserId]);
});

test("couple bind rejects an invalid invite code before creating a relationship", async () => {
  cache.store.clear();
  const pool = createTransactionalPoolMock((call) => {
    if (call.sql.includes("FROM users") && call.sql.includes("invite_code")) return [[]];
    throw new Error(`Unexpected query: ${call.sql}`);
  });
  const handler = getRouteHandler(createCoupleRouter({ pool }), "/bind", "post");

  const result = await invokeRoute(handler, {
    userId: 9071,
    body: { inviteCode: "BAD999" },
  });

  assert.equal(result.nextError?.status, 404);
  assert.equal(
    pool.calls.some((call) => call.sql.includes("INSERT INTO couple_relationships")),
    false
  );
});

test("couple unbind clears relationship-scoped caches for both former partners", async () => {
  cache.store.clear();
  const userId = 9073;
  const partnerId = 9074;
  const relationshipId = 97;
  const staleKeys = [
    Keys.bills(userId, 2026, 8),
    Keys.bills(partnerId, 2026, 8),
    Keys.recipes(partnerId),
    Keys.beadBlueprints(userId),
    Keys.sharedPlans(partnerId),
    Keys.calorieToday(userId),
    Keys.calorieHistory(partnerId, "2026-08-09"),
    Keys.overview(userId, 2026, 8),
    Keys.overview(partnerId, 2026, 8),
  ];
  staleKeys.forEach((key) => cache.set(key, { stale: true }, 60));
  const pool = createTransactionalPoolMock((call) => {
    if (call.sql.includes("FROM users") && call.sql.includes("FOR UPDATE")) {
      return [[{ id: call.params[0] }]];
    }
    if (call.sql.includes("UPDATE couple_relationships")) return [{ affectedRows: 1 }];
    if (call.sql.includes("FROM couple_relationships")) {
      return [[{ relationship_id: relationshipId, user_id_1: userId, user_id_2: partnerId }]];
    }
    if (call.sql.includes("FROM housework_relationship_cycles")) {
      return [[{ cycle_id: "cycle-cache-regression", relationship_id: relationshipId,
        member_user_id_1: userId, member_user_id_2: partnerId, ended_at: null }]];
    }
    if (call.sql.includes("FROM housework_spaces")) {
      return [[{ space_id: "space-cache-regression", status: "active" }]];
    }
    if (call.sql.includes("FROM housework_space_members")) {
      return [[{ user_id: userId }, { user_id: partnerId }]];
    }
    if (call.sql.includes("UPDATE housework_relationship_cycles") || call.sql.includes("UPDATE housework_spaces")) {
      return [{ affectedRows: 1 }];
    }
    if (call.sql.includes("UPDATE users")) return [{ affectedRows: 2 }];
    throw new Error(`Unexpected query: ${call.sql}`);
  });
  const handler = getRouteHandler(createCoupleRouter({ pool }), "/unbind", "delete");

  const result = await invokeRoute(handler, { userId });

  assert.equal(result.nextError, undefined);
  staleKeys.forEach((key) => assert.equal(cache.get(key), null, `cache should be cleared: ${key}`));
});

test("Android couple binding submits the invite code instead of authority user IDs", () => {
  const clientPath = path.resolve(
    __dirname,
    "../../app/src/main/java/com/example/couplecredit/api/AuthApiClient.java"
  );
  const activityPath = path.resolve(
    __dirname,
    "../../app/src/main/java/com/example/couplecredit/activity/CoupleBindingActivity.java"
  );
  const clientSource = fs.readFileSync(clientPath, "utf8");
  const activitySource = fs.readFileSync(activityPath, "utf8");

  assert.match(
    clientSource,
    /bindCouple\(Context context, String inviteCode, SimpleIdCallback callback\)/
  );
  assert.match(clientSource, /singletonMap\("inviteCode", inviteCode\)/);
  assert.doesNotMatch(activitySource, /resolveUsername\(/);
});

test("overview bills use the active relationship plus private self rows", async () => {
  const userId = 9081;
  const relationshipId = 98;
  const pool = createPoolMock((call) => {
    if (call.sql.includes("FROM couple_relationships")) {
      return [[{ relationship_id: relationshipId, user_id_1: userId, user_id_2: 9082 }]];
    }
    if (call.sql.includes("FROM bills")) {
      return [[{ billCount: 0, income: 0, expense: 0 }]];
    }
    throw new Error(`Unexpected query: ${call.sql}`);
  });

  await fetchBillsSummary(pool, userId, 2026, 8);

  const billQuery = pool.calls.find((call) => call.sql.includes("FROM bills"));
  assert.match(
    billQuery.sql,
    /b\.relationship_id = \? OR \(b\.user_id = \? AND b\.relationship_id IS NULL\)/
  );
  assert.deepEqual(billQuery.params, [relationshipId, userId, "2026-08-01", "2026-09-01"]);
});

test("overview bills exclude former relationship rows for a single user", async () => {
  const userId = 9091;
  const pool = createPoolMock((call) => {
    if (call.sql.includes("FROM couple_relationships")) return [[]];
    if (call.sql.includes("FROM bills")) {
      return [[{ billCount: 0, income: 0, expense: 0 }]];
    }
    throw new Error(`Unexpected query: ${call.sql}`);
  });

  await fetchBillsSummary(pool, userId, 2026, 8);

  const billQuery = pool.calls.find((call) => call.sql.includes("FROM bills"));
  assert.match(billQuery.sql, /b\.user_id = \? AND b\.relationship_id IS NULL/);
  assert.deepEqual(billQuery.params, [userId, "2026-08-01", "2026-09-01"]);
});

test("bead blueprints use the active relationship plus private self rows", async () => {
  cache.store.clear();
  const userId = 9101;
  const relationshipId = 101;
  const pool = createPoolMock((call) => {
    if (call.sql.includes("FROM couple_relationships")) {
      return [[{ relationship_id: relationshipId, user_id_1: userId, user_id_2: 9102 }]];
    }
    if (call.sql.includes("FROM bead_blueprints bb")) return [[]];
    throw new Error(`Unexpected query: ${call.sql}`);
  });
  const aiLimiter = (_req, _res, next) => next();
  const handler = getRouteHandler(createBeadRouter({ pool, aiLimiter }), "/blueprints", "get");

  const result = await invokeRoute(handler, { userId });

  assert.equal(result.nextError, undefined);
  const blueprintQuery = pool.calls.find((call) => call.sql.includes("FROM bead_blueprints bb"));
  assert.match(
    blueprintQuery.sql,
    /bb\.relationship_id = \? OR \(bb\.user_id = \? AND bb\.relationship_id IS NULL\)/
  );
  assert.match(blueprintQuery.sql, /GROUP BY[^\n]*bb\.image_url/);
  assert.deepEqual(blueprintQuery.params, [relationshipId, userId]);
});

test("shared plans use current relationship rows plus private self rows", async () => {
  cache.store.clear();
  const userId = 9111;
  const relationshipId = 111;
  const pool = createPoolMock((call) => {
    if (call.sql.includes("FROM couple_relationships")) {
      return [[{ relationship_id: relationshipId, user_id_1: userId, user_id_2: 9112 }]];
    }
    if (call.sql.includes("FROM shared_plans")) return [[]];
    throw new Error(`Unexpected query: ${call.sql}`);
  });
  const handler = getRouteHandler(createSharedPlansRouter({ pool }), "/", "get");

  const result = await invokeRoute(handler, { userId });

  assert.equal(result.nextError, undefined);
  const planQuery = pool.calls.find((call) => call.sql.includes("FROM shared_plans"));
  assert.match(planQuery.sql, /relationship_id = \? AND visibility = 'both'/);
  assert.match(planQuery.sql, /created_by = \? AND \(visibility = 'self' OR relationship_id IS NULL\)/);
  assert.deepEqual(planQuery.params, [relationshipId, userId]);
});

test("creating a private plan does not attach it to the current relationship", async () => {
  cache.store.clear();
  const userId = 9121;
  const relationshipId = 121;
  const pool = createPoolMock((call) => {
    if (call.sql.includes("FROM couple_relationships")) {
      return [[{ relationship_id: relationshipId, user_id_1: userId, user_id_2: 9122 }]];
    }
    if (call.sql.includes("INSERT INTO shared_plans")) return [{ insertId: 17 }];
    throw new Error(`Unexpected query: ${call.sql}`);
  });
  const handler = getRouteHandler(createSharedPlansRouter({ pool }), "/", "post");

  const result = await invokeRoute(handler, {
    userId,
    body: { name: "我的计划", initialAmount: 100, visibility: "self" },
  });

  assert.equal(result.nextError, undefined);
  const insertion = pool.calls.find((call) => call.sql.includes("INSERT INTO shared_plans"));
  assert.equal(insertion.params[0], null);
});

test("either current partner can delete a shared plan", async () => {
  cache.store.clear();
  const userId = 9131;
  const partnerId = 9132;
  const relationshipId = 131;
  const pool = createPoolMock((call) => {
    if (call.sql.includes("FROM couple_relationships")) {
      return [[{ relationship_id: relationshipId, user_id_1: userId, user_id_2: partnerId }]];
    }
    if (call.sql.includes("FROM shared_plans")) {
      return [[{
        plan_id: 19,
        relationship_id: relationshipId,
        created_by: partnerId,
        visibility: "both",
      }]];
    }
    if (call.sql.includes("DELETE FROM shared_plans")) return [{ affectedRows: 1 }];
    throw new Error(`Unexpected query: ${call.sql}`);
  });
  const handler = getRouteHandler(createSharedPlansRouter({ pool }), "/:id", "delete");

  const result = await invokeRoute(handler, { userId, params: { id: "19" } });

  assert.equal(result.nextError, undefined);
  assert.equal(
    pool.calls.some((call) => call.sql.includes("DELETE FROM shared_plans")),
    true
  );
});
