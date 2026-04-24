const assert = require("assert");
const { test } = require("node:test");

const { createBillsRouter } = require("../src/routes/bills");

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

test("bills query formats date columns as UTC+8 date-only strings", async () => {
  const pool = createPoolMock([
    [[]],
    (query) => {
      const returnsDateOnlyString = query.includes("DATE_FORMAT(date, '%Y-%m-%d') as date");
      return [[{
        billId: 88,
        userId: 7,
        sharedPlanId: null,
        title: "早餐",
        type: "餐饮",
        amount: 22,
        date: returnsDateOnlyString ? "2026-04-22" : "2026-04-21T16:00:00.000Z",
        time: "08:00:00",
        incomeType: 0,
        owner: 1,
        isHelp: 0,
        relationshipId: null
      }]];
    }
  ]);

  const router = createBillsRouter({ pool });
  const getHandler = getRouteHandler(router, "/", "get");

  const result = await invokeHandler(getHandler, {
    query: { userId: "7", year: "2026", month: "4" }
  });

  assert.equal(result.nextError, undefined);
  assert.equal(result.statusCode, 200);
  assert.equal(result.jsonPayload.ok, true);
  assert.equal(result.jsonPayload.data.bills[0].date, "2026-04-22");
});
