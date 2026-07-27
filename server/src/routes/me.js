const express = require("express");
const { cache, Keys, TTL } = require("../cache");
const { loadActiveRelationship, parseRequiredInteger } = require("../utils/queryHelpers");
const { buildCaloriePayload } = require("./calorie");
const { addExpirationFlags } = require("./inventory");

// 镜像自 bills.js:189（仅 SELECT + WHERE 部分，不含 ORDER BY）
const TODO_SELECT_FIELDS = `todo_id, user_id, relationship_id, title, content, priority, fuzzy_date_text, image_url, status,
                 is_repeatable, series_id, completed_count, created_at, updated_at`;

// 镜像自 inventory.js:11-18
const INVENTORY_SELECT_FIELDS = `inventory_id as inventoryId, user_id as userId, relationship_id as relationshipId,
                 name, category, image_url as imageUrl, quantity, unit, threshold,
                 created_at as createdAt, updated_at as updatedAt, last_consumed_at as lastConsumedAt,
                 note, ai_image_prompt as aiImagePrompt,
                 expiration_mode as expirationMode,
                 DATE_FORMAT(expiration_date, '%Y-%m-%d') as expirationDate,
                 DATE_FORMAT(production_date, '%Y-%m-%d') as productionDate,
                 shelf_life_days as shelfLifeDays`;

// 镜像自 todos.js 内的 mapTodo
function mapTodo(row) {
  return {
    todoId: row.todo_id,
    userId: row.user_id,
    relationshipId: row.relationship_id,
    title: row.title,
    content: row.content,
    priority: row.priority,
    fuzzyDateText: row.fuzzy_date_text,
    imageUrl: row.image_url,
    status: row.status,
    isRepeatable: !!row.is_repeatable,
    seriesId: row.series_id,
    completedCount: row.completed_count,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

function pad2(n) {
  return String(n).padStart(2, "0");
}

function todayString() {
  const d = new Date();
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}

// 子查询：账单 —— 镜像自 bills.js GET / (line 164-222)
async function fetchBills(pool, userId, year, month) {
  const cached = cache.get(Keys.bills(userId, year, month));
  if (cached) return cached;
  const monthStr = pad2(month);
  const dateStart = `${year}-${monthStr}-01`;
  const dateEnd = month === 12 ? `${Number(year) + 1}-01-01` : `${year}-${pad2(Number(month) + 1)}-01`;
  const relationship = await loadActiveRelationship(pool, userId);
  const relationshipId = relationship ? relationship.relationship_id : null;

  let query;
  let params;
  if (relationshipId) {
    query = `SELECT b.bill_id as billId, b.user_id as userId, b.shared_plan_id as sharedPlanId, sp.name as sharedPlanName, b.title, b.type, b.amount, DATE_FORMAT(b.date, '%Y-%m-%d') as date, b.time, b.income_type as incomeType, b.owner, b.is_help as isHelp, b.relationship_id as relationshipId
             FROM bills b
             LEFT JOIN shared_plans sp ON sp.plan_id = b.shared_plan_id
             WHERE (b.user_id = ? OR b.relationship_id = ?)
             AND b.date >= ? AND b.date < ?`;
    params = [userId, relationshipId, dateStart, dateEnd];
  } else {
    query = `SELECT b.bill_id as billId, b.user_id as userId, b.shared_plan_id as sharedPlanId, sp.name as sharedPlanName, b.title, b.type, b.amount, DATE_FORMAT(b.date, '%Y-%m-%d') as date, b.time, b.income_type as incomeType, b.owner, b.is_help as isHelp, b.relationship_id as relationshipId
             FROM bills b
             LEFT JOIN shared_plans sp ON sp.plan_id = b.shared_plan_id
             WHERE b.user_id = ? AND b.date >= ? AND b.date < ?`;
    params = [userId, dateStart, dateEnd];
  }
  const [rows] = await pool.execute(query, params);
  const payload = {
    ok: true,
    message: "查询成功",
    data: { bills: rows, relationshipId, year, month },
  };
  cache.set(Keys.bills(userId, year, month), payload, TTL.BILLS);
  return payload;
}

// 子查询：Todo —— 镜像自 todos.js GET / (line 122-153)
async function fetchTodos(pool, userId) {
  const cached = cache.get(Keys.todos(userId));
  if (cached) return cached;
  const relationship = await loadActiveRelationship(pool, userId);
  const relationshipId = relationship ? relationship.relationship_id : null;

  let sql = `SELECT ${TODO_SELECT_FIELDS}
             FROM todo_items
             WHERE user_id = ? AND relationship_id IS NULL`;
  const params = [userId];
  if (relationshipId) {
    sql = `SELECT ${TODO_SELECT_FIELDS}
           FROM todo_items
           WHERE relationship_id = ? OR (user_id = ? AND relationship_id IS NULL)`;
    params.unshift(relationshipId);
  }
  sql += ` ORDER BY CASE status WHEN 'open' THEN 0 WHEN 'missed' THEN 1 ELSE 2 END ASC,
                  FIELD(priority, 'high', 'medium', 'low') ASC,
                  updated_at DESC,
                  todo_id DESC`;
  const [rows] = await pool.execute(sql, params);
  const payload = { ok: true, data: { items: rows.map(mapTodo), relationshipId } };
  cache.set(Keys.todos(userId), payload, TTL.TODOS);
  return payload;
}

// 子查询：库存 —— 镜像自 inventory.js GET / (line 202-244)
async function fetchInventory(pool, userId) {
  const cached = cache.get(Keys.inventory(userId));
  if (cached) return cached;
  const relationship = await loadActiveRelationship(pool, userId);
  const relationshipId = relationship ? relationship.relationship_id : null;

  let query;
  let params;
  if (relationshipId) {
    query = `SELECT ${INVENTORY_SELECT_FIELDS}
             FROM inventory WHERE relationship_id = ? OR (user_id = ? AND relationship_id IS NULL)`;
    params = [relationshipId, userId];
  } else {
    query = `SELECT ${INVENTORY_SELECT_FIELDS}
             FROM inventory WHERE user_id = ? AND relationship_id IS NULL`;
    params = [userId];
  }
  const [rows] = await pool.execute(query, params);
  const items = rows.map((row) =>
    addExpirationFlags({ ...row, isLowStock: Number(row.quantity) <= Number(row.threshold) })
  );
  const payload = {
    ok: true,
    message: "查询成功",
    data: { items, relationshipId },
  };
  cache.set(Keys.inventory(userId), payload, TTL.INVENTORY);
  return payload;
}

// 子查询：资产概要 —— 轻量版（仅 totalValue/totalCount，不做 dailyAvgCost/categoryBreakdown）
async function fetchAssetStats(pool, userId) {
  const cached = cache.get(Keys.assetStats(userId));
  if (cached) return cached;
  const relationship = await loadActiveRelationship(pool, userId);
  const relationshipId = relationship ? relationship.relationship_id : null;
  let where;
  let params;
  if (relationshipId) {
    where = "(relationship_id = ? OR (user_id = ? AND relationship_id IS NULL))";
    params = [relationshipId, userId];
  } else {
    where = "user_id = ? AND relationship_id IS NULL";
    params = [userId];
  }
  const [rows] = await pool.execute(
    `SELECT COUNT(*) as totalCount,
            COALESCE(SUM(CASE WHEN status IN ('active','idle') THEN purchase_price END), 0) as totalValue
     FROM assets WHERE ${where}`,
    params
  );
  const row = rows[0] || {};
  const payload = {
    ok: true,
    message: "查询成功",
    data: {
      totalValue: Number(row.totalValue) || 0,
      totalCount: Number(row.totalCount) || 0,
      dailyAvgCost: 0,
      categoryBreakdown: [],
      statusBreakdown: { active: 0, idle: 0, disposed: 0 },
      latestItem: null,
    },
  };
  cache.set(Keys.assetStats(userId), payload, TTL.ASSETS);
  return payload;
}

// 子查询：经期预测 —— 镜像自 period.js GET / (line 72-125)，仅保留 prediction 字段
async function fetchPeriodPrediction(pool, userId) {
  const relationship = await loadActiveRelationship(pool, userId);
  const userIds = [userId];
  if (relationship) {
    if (relationship.user_id_1 !== userId) userIds.push(relationship.user_id_1);
    if (relationship.user_id_2 !== userId) userIds.push(relationship.user_id_2);
  }
  const placeholders = userIds.map(() => "?").join(",");
  const [rows] = await pool.execute(
    `SELECT id, user_id, start_date, end_date
     FROM period_records
     WHERE user_id IN (${placeholders})
     ORDER BY start_date DESC
     LIMIT 24`,
    userIds
  );

  const completed = rows.filter((r) => r.user_id === userId && r.end_date !== null);
  const prediction = computePrediction(completed);

  let partnerPrediction = { averageCycleDays: null, predictedNextStart: null };
  const partnerRecords = rows.filter((r) => r.user_id !== userId && r.end_date !== null);
  if (partnerRecords.length > 0) {
    partnerPrediction = computePrediction(partnerRecords);
  }
  return {
    ok: true,
    data: {
      records: [],
      averageCycleDays: prediction.averageCycleDays,
      predictedNextStart: prediction.predictedNextStart,
      partnerAverageCycleDays: partnerPrediction.averageCycleDays,
      partnerPredictedNextStart: partnerPrediction.predictedNextStart,
      relationshipId: relationship ? relationship.relationship_id : null,
    },
  };
}

function computePrediction(records) {
  if (records.length < 2) return { averageCycleDays: null, predictedNextStart: null };
  const sorted = [...records].sort((a, b) => new Date(a.start_date) - new Date(b.start_date));
  const cycles = [];
  for (let i = 1; i < sorted.length; i++) {
    const diff =
      (new Date(sorted[i].start_date) - new Date(sorted[i - 1].start_date)) /
      (1000 * 60 * 60 * 24);
    cycles.push(Math.round(diff));
  }
  const avg = Math.round(cycles.reduce((s, v) => s + v, 0) / cycles.length);
  const lastStart = new Date(sorted[sorted.length - 1].start_date);
  lastStart.setDate(lastStart.getDate() + avg);
  const predictedStr = `${lastStart.getFullYear()}-${pad2(lastStart.getMonth() + 1)}-${pad2(
    lastStart.getDate()
  )}`;
  return { averageCycleDays: avg, predictedNextStart: predictedStr };
}

// 子查询：情侣信息 —— 镜像自 auth.js GET /couple-info (line 214-254)
async function fetchCoupleInfo(pool, userId) {
  const relationship = await loadActiveRelationship(pool, userId);
  if (!relationship) return { ok: true, data: { hasCouple: false } };
  const partnerId =
    relationship.user_id_1 === userId ? relationship.user_id_2 : relationship.user_id_1;
  const [partners] = await pool.execute(
    "SELECT id, username, nickname, avatar FROM users WHERE id = ? LIMIT 1",
    [partnerId]
  );
  if (partners.length === 0) return { ok: true, data: { hasCouple: false } };
  const partner = partners[0];
  return {
    ok: true,
    data: {
      hasCouple: true,
      partnerId: partner.id,
      partnerName: partner.username,
      partnerNickname: partner.nickname || null,
      partnerAvatarUrl: partner.avatar || null,
      relationshipId: relationship.relationship_id,
    },
  };
}

// 子查询：今日热量 —— 直接复用 calorie.js 已导出的 buildCaloriePayload
async function fetchCalorieToday(pool, userId) {
  return buildCaloriePayload(pool, userId, todayString());
}

// 包装器：单子查询失败不阻断整体聚合
async function safe(label, promise) {
  try {
    return await promise;
  } catch (e) {
    console.warn(`[me/overview] 子查询 ${label} 失败:`, e && e.message);
    return null;
  }
}

function createMeRouter({ pool }) {
  const router = express.Router();

  // 聚合接口：一次返回 MyFragment 首页所需的全部概要数据
  router.get("/overview", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.userId);
      const now = new Date();
      const year = req.query.year ? parseInt(req.query.year, 10) : now.getFullYear();
      const month = req.query.month ? parseInt(req.query.month, 10) : now.getMonth() + 1;

      const cacheKey = Keys.overview(userId, year, month);
      const cached = cache.get(cacheKey);
      if (cached) return res.json(cached);

      const [bills, todos, inventory, assetStats, period, coupleInfo, calorie] = await Promise.all([
        safe("bills", fetchBills(pool, userId, year, month)),
        safe("todos", fetchTodos(pool, userId)),
        safe("inventory", fetchInventory(pool, userId)),
        safe("assetStats", fetchAssetStats(pool, userId)),
        safe("period", fetchPeriodPrediction(pool, userId)),
        safe("coupleInfo", fetchCoupleInfo(pool, userId)),
        safe("calorie", fetchCalorieToday(pool, userId)),
      ]);

      const payload = {
        ok: true,
        data: {
          year,
          month,
          bills,
          todos,
          inventory,
          assetStats,
          period,
          coupleInfo,
          calorie,
        },
      };
      cache.set(cacheKey, payload, TTL.OVERVIEW);
      res.json(payload);
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createMeRouter };
