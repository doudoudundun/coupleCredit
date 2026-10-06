/**
 * 月报本人范围的路由单测。使用 fake pool 检查 SQL 过滤条件和聚合输出，
 * 不注册账号、不写入本地/生产数据库。
 */
const assert = require("node:assert/strict");
const { test } = require("node:test");
const { cache, Keys } = require("../src/cache");
const { createBillsRouter } = require("../src/routes/bills");

function toDecimal(cents) {
  return `${Math.trunc(cents / 100)}.${String(cents % 100).padStart(2, "0")}`;
}

function makeBills(relationshipId, userId, otherUserId, selfOwner) {
  const partnerOwner = 3 - selfOwner;
  return [
    { billId: 801, date: "2025-12-12", ym: "2025-12", amount: 800, incomeType: 0, owner: selfOwner, relationshipId, userId: otherUserId, type: "本人旧月", title: "本人旧月最大单" },
    { billId: 802, date: "2025-12-12", ym: "2025-12", amount: 400, incomeType: 0, owner: partnerOwner, relationshipId, userId, type: "伴侣旧月", title: "伴侣旧月" },
    { billId: 803, date: "2025-12-13", ym: "2025-12", amount: 800, incomeType: 0, owner: 3, relationshipId, userId: otherUserId, type: "共同旧月", title: "共同旧月" },
    { billId: 804, date: "2025-12-14", ym: "2025-12", amount: 200, incomeType: 1, owner: partnerOwner, relationshipId, userId, type: "兼职", title: "伴侣收入" },
    { billId: 805, date: "2026-01-10", ym: "2026-01", amount: 2400, incomeType: 0, owner: selfOwner, relationshipId, userId: otherUserId, type: "本人餐饮", title: "本人最大单" },
    { billId: 806, date: "2026-01-10", ym: "2026-01", amount: 950, incomeType: 1, owner: selfOwner, relationshipId, userId, type: "本人收入", title: "本人收入" },
    { billId: 807, date: "2026-01-05", ym: "2026-01", amount: 400, incomeType: 0, owner: 1, relationshipId: null, userId, type: "本人其他", title: "绑定前私人账单" },
    { billId: 808, date: "2026-01-08", ym: "2026-01", amount: 1000, incomeType: 0, owner: partnerOwner, relationshipId, userId, type: "伴侣购物", title: "伴侣最大单" },
    { billId: 809, date: "2026-01-09", ym: "2026-01", amount: 5000, incomeType: 1, owner: partnerOwner, relationshipId, userId, type: "兼职", title: "伴侣收入" },
    { billId: 810, date: "2026-01-04", ym: "2026-01", amount: 5200, incomeType: 0, owner: 3, relationshipId, userId: otherUserId, type: "共同旅行", title: "共同最大单" },
    { billId: 811, date: "2026-01-06", ym: "2026-01", amount: 4050, incomeType: 1, owner: 3, relationshipId, userId, type: "共同收入", title: "共同收入" }
  ];
}

function filterRowsForSql(sql, params, bills) {
  let matches;
  let dateStart;
  let dateEnd;
  if (sql.includes("b.relationship_id = ? AND b.owner = ?")) {
    const [relationshipId, selfOwner, userId, start, end] = params;
    matches = (bill) => (bill.relationshipId === relationshipId && bill.owner === selfOwner)
      || (bill.userId === userId && bill.relationshipId === null && bill.owner === 1);
    dateStart = start;
    dateEnd = end;
  } else if (sql.includes("b.user_id = ? AND b.relationship_id IS NULL AND b.owner = 1")) {
    const [userId, start, end] = params;
    matches = (bill) => bill.userId === userId && bill.relationshipId === null && bill.owner === 1;
    dateStart = start;
    dateEnd = end;
  } else if (sql.includes("b.relationship_id = ?")) {
    const [relationshipId, userId, start, end] = params;
    matches = (bill) => bill.relationshipId === relationshipId
      || (bill.userId === userId && bill.relationshipId === null);
    dateStart = start;
    dateEnd = end;
  } else {
    const [userId, start, end] = params;
    matches = (bill) => bill.userId === userId && bill.relationshipId === null;
    dateStart = start;
    dateEnd = end;
  }
  return bills.filter((bill) => matches(bill) && bill.date >= dateStart && bill.date < dateEnd);
}

function groupBy(rows, keyOf) {
  const groups = new Map();
  for (const row of rows) {
    const key = keyOf(row);
    const group = groups.get(key) || [];
    group.push(row);
    groups.set(key, group);
  }
  return groups;
}

function aggregateRows(sql, rows) {
  if (sql.includes("AS weekendExpense")) {
    return Array.from(groupBy(rows, (row) => row.ym), ([ym, group]) => {
      const expenses = group.filter((row) => row.incomeType === 0);
      const incomes = group.filter((row) => row.incomeType === 1);
      const weekendExpense = expenses
        .filter((row) => [0, 6].includes(new Date(`${row.date}T00:00:00Z`).getUTCDay()))
        .reduce((sum, row) => sum + row.amount, 0);
      return {
        ym,
        expense: toDecimal(expenses.reduce((sum, row) => sum + row.amount, 0)),
        income: toDecimal(incomes.reduce((sum, row) => sum + row.amount, 0)),
        cnt: group.length,
        recordDays: new Set(group.map((row) => row.date)).size,
        weekendExpense: toDecimal(weekendExpense)
      };
    });
  }
  if (sql.includes("COALESCE(ic.name")) {
    const expenses = rows.filter((row) => row.incomeType === 0);
    return Array.from(groupBy(expenses, (row) => `${row.ym}\u0000${row.type}`), ([key, group]) => {
      const [ym, name] = key.split("\u0000");
      return { ym, name, amount: toDecimal(group.reduce((sum, row) => sum + row.amount, 0)) };
    });
  }
  if (sql.includes("b.owner AS owner")) {
    return Array.from(groupBy(rows, (row) => `${row.ym}\u0000${row.owner}\u0000${row.relationshipId ?? "null"}`), ([, group]) => ({
      ym: group[0].ym,
      owner: group[0].owner,
      relationshipId: group[0].relationshipId,
      cnt: group.length,
      amount: toDecimal(group.filter((row) => row.incomeType === 0).reduce((sum, row) => sum + row.amount, 0))
    }));
  }
  if (sql.includes("sameDayDays")) {
    const sameDays = [];
    const days = groupBy(rows, (row) => row.date);
    for (const [date, group] of days) {
      if (new Set(group.map((row) => row.userId)).size > 1) {
        const ym = date.slice(0, 7);
        const previous = sameDays.find((row) => row.ym === ym);
        if (previous) previous.sameDayDays += 1;
        else sameDays.push({ ym, sameDayDays: 1 });
      }
    }
    return sameDays;
  }
  if (sql.includes("b.bill_id AS billId")) {
    return rows.filter((row) => row.incomeType === 0)
      .sort((a, b) => b.amount - a.amount || a.date.localeCompare(b.date) || a.billId - b.billId)
      .map((row) => ({ ym: row.ym, billId: row.billId, date: row.date, amount: toDecimal(row.amount), title: row.title }));
  }
  throw new Error(`未处理的 fake SQL 聚合: ${sql}`);
}

function makePool({ relationship, userId, otherUserId = null, relationshipId = null }) {
  const calls = [];
  const selfOwner = relationship && Number(relationship.user_id_1) === Number(userId) ? 1 : 2;
  // 无 active relation 时仍模拟旧 relationship_id 账单；它们必须被身份边界排除。
  const bills = makeBills(relationship ? relationshipId : 999999, userId, otherUserId ?? userId + 1, selfOwner);
  const pool = {
    calls,
    async execute(sql, params) {
      calls.push({ sql, params });
      if (sql.includes("FROM couple_relationships")) return [relationship ? [{ ...relationship }] : []];
      const rows = filterRowsForSql(sql, params, bills);
      return [aggregateRows(sql, rows)];
    }
  };
  return pool;
}

function getMonthlyHandler(pool) {
  const router = createBillsRouter({ pool });
  const layer = router.stack.find((item) => item.route && item.route.path === "/monthly-stats");
  assert.ok(layer, "monthly-stats 路由存在");
  return layer.route.stack.find((item) => item.method === "get").handle;
}

async function invokeMonthly(pool, userId, query) {
  const handler = getMonthlyHandler(pool);
  let response;
  let error;
  await handler(
    { userId, query },
    { json(body) { response = body; return this; } },
    (nextError) => { error = nextError; }
  );
  if (error) throw error;
  return response;
}

function aggregationCalls(pool) {
  return pool.calls.filter((call) => !call.sql.includes("FROM couple_relationships"));
}

function assertEveryCallUsesSelfFilter(pool, expectedParams, expectedPredicate) {
  const calls = aggregationCalls(pool);
  assert.equal(calls.length, 5, "总额、分类、归属、同日、最大单笔共5种聚合都执行");
  for (const { sql, params } of calls) {
    assert.match(sql, /b\.date >= \? AND b\.date < \?/);
    assert.ok(sql.includes(expectedPredicate), `聚合SQL缺少本人范围条件: ${sql}`);
    assert.deepEqual(params, expectedParams, "每种聚合使用相同的归属过滤和月份窗口");
  }
}

test("scope=self 按关系成员位映射本人，跨月收入/支出/分类/最大单笔均排除伴侣和共同", async () => {
  for (const [userId, otherUserId, selfOwner] of [[710001, 710002, 1], [710002, 710001, 2]]) {
    const relationshipId = 720001 + selfOwner;
    const relationship = { relationship_id: relationshipId, user_id_1: 710001, user_id_2: 710002 };
    cache.del(Keys.relationship(userId));
    cache.delPrefix(`bills:${userId}:`);
    const pool = makePool({ relationship, userId, otherUserId, relationshipId });
    const response = await invokeMonthly(pool, userId, { months: "2", endYm: "2026-01", scope: "self" });

    assert.equal(response.ok, true);
    assert.equal(response.data.scope, "self");
    assert.deepEqual(response.data.months.map((month) => month.ym), ["2025-12", "2026-01"]);
    const december = response.data.months[0];
    const january = response.data.months[1];
    assert.equal(december.count, 1);
    assert.equal(december.expense, 800);
    assert.equal(december.income, 0);
    assert.deepEqual(december.byCategory, { 本人旧月: 800 });
    assert.equal(december.topBill.title, "本人旧月最大单");
    assert.equal(january.expense, 2800);
    assert.equal(january.income, 950);
    assert.equal(january.count, 3);
    assert.deepEqual(january.byCategory, { 本人餐饮: 2400, 本人其他: 400 });
    assert.equal(Object.values(january.byCategory).reduce((sum, value) => sum + value, 0), january.expense);
    assert.deepEqual(january.byOwner, {
      self: { count: 3, amount: 2800 },
      partner: { count: 0, amount: 0 },
      common: { count: 0, amount: 0 }
    });
    assert.deepEqual(january.topBill, {
      billId: 805,
      date: "2026-01-10",
      amount: 2400,
      title: "本人最大单"
    });
    assert.equal(january.sameDayDays, 1);
    assert.equal(january.weekendRatio, 0.8571);

    // relationship bill filter deliberately has no creator restriction, so partner-entered self-owned bills remain eligible.
    const expected = [relationshipId, selfOwner, userId, "2025-12-01", "2026-02-01"];
    assertEveryCallUsesSelfFilter(
      pool,
      expected,
      "((b.relationship_id = ? AND b.owner = ?) OR (b.user_id = ? AND b.relationship_id IS NULL AND b.owner = 1))"
    );
  }
});

test("无有效关系时本人月报只读当前账号 owner=1 私人账单，不沿用已解绑关系", async () => {
  const userId = 730001;
  cache.del(Keys.relationship(userId));
  cache.delPrefix(`bills:${userId}:`);
  const pool = makePool({ relationship: null, userId });
  const response = await invokeMonthly(pool, userId, { months: "2", endYm: "2026-01", scope: "self" });
  assert.equal(response.data.months[1].expense, 400);
  assert.equal(response.data.months[1].income, 0);
  assert.equal(response.data.months[1].count, 1);
  assertEveryCallUsesSelfFilter(
    pool,
    [userId, "2025-12-01", "2026-02-01"],
    "b.user_id = ? AND b.relationship_id IS NULL AND b.owner = 1"
  );
  for (const { sql } of aggregationCalls(pool)) {
    assert.match(sql, /b\.user_id = \? AND b\.relationship_id IS NULL AND b\.owner = 1/);
  }
});

test("默认共享口径保持既有聚合，并与 scope=self 分开缓存", async () => {
  const userId = 740001;
  const relationshipId = 740002;
  const relationship = { relationship_id: relationshipId, user_id_1: userId, user_id_2: 740003 };
  cache.del(Keys.relationship(userId));
  cache.delPrefix(`bills:${userId}:`);
  const pool = makePool({ relationship, userId, otherUserId: 740003, relationshipId });

  const shared = await invokeMonthly(pool, userId, { months: "1", endYm: "2026-01" });
  assert.equal(shared.data.scope, "shared");
  assert.equal(shared.data.months[0].expense, 9000);
  assert.equal(shared.data.months[0].income, 10000);
  assert.equal(shared.data.months[0].count, 7);
  assert.equal(shared.data.months[0].byCategory["共同旅行"], 5200);
  assert.equal(shared.data.months[0].topBill.title, "共同最大单");

  const afterSharedCalls = aggregationCalls(pool).length;
  const personal = await invokeMonthly(pool, userId, { months: "1", endYm: "2026-01", scope: "self" });
  assert.equal(personal.data.scope, "self");
  assert.equal(personal.data.months[0].expense, 2800);
  assert.equal(aggregationCalls(pool).length, afterSharedCalls + 5, "本人范围不能命中共享月报缓存");
  await invokeMonthly(pool, userId, { months: "1", endYm: "2026-01", scope: "self" });
  assert.equal(aggregationCalls(pool).length, afterSharedCalls + 5, "相同本人范围后续请求应复用本人缓存");
});

test("scope 仅接受 shared 或 self", async () => {
  const userId = 750001;
  cache.delPrefix(`bills:${userId}:`);
  const pool = makePool({ relationship: null, userId });
  await assert.rejects(
    invokeMonthly(pool, userId, { scope: "partner" }),
    (error) => error && error.status === 400 && error.code === "INVALID_REQUEST"
  );
});
