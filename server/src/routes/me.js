const express = require("express");
const { cache, Keys, TTL } = require("../cache");
const {
  buildCoupleOrPrivateScope,
  buildSubjectOrCurrentRelationshipScope,
  loadActiveRelationship,
  loadPartnerProfile,
  parseRequiredInteger,
} = require("../utils/queryHelpers");
const { buildCaloriePayload } = require("./calorie");

function pad2(n) {
  return String(n).padStart(2, "0");
}

function todayString() {
  const d = new Date();
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}

function buildBillsSummary(rows) {
  const summary = { billCount: 0, income: 0, expense: 0 };
  for (const row of rows || []) {
    if (row.billCount !== undefined || row.income !== undefined || row.expense !== undefined) {
      summary.billCount += Number(row.billCount) || 0;
      summary.income += Number(row.income) || 0;
      summary.expense += Number(row.expense) || 0;
      continue;
    }
    const amount = Number(row.amount) || 0;
    if (Number(row.incomeType) === 1) summary.income += amount;
    else summary.expense += amount;
    summary.billCount += 1;
  }
  summary.income = Number(summary.income.toFixed(2));
  summary.expense = Number(summary.expense.toFixed(2));
  return summary;
}

function buildTodosSummary(rows) {
  const summary = { openCount: 0, doneCount: 0, missedCount: 0 };
  for (const row of rows || []) {
    if (row.openCount !== undefined || row.doneCount !== undefined || row.missedCount !== undefined) {
      summary.openCount += Number(row.openCount) || 0;
      summary.doneCount += Number(row.doneCount) || 0;
      summary.missedCount += Number(row.missedCount) || 0;
      continue;
    }
    if (row.status === "done") summary.doneCount += 1;
    else if (row.status === "missed") summary.missedCount += 1;
    else summary.openCount += 1;
  }
  return summary;
}

function buildInventorySummary(rows) {
  const summary = { itemCount: 0, lowStockCount: 0, expiringCount: 0 };
  for (const row of rows || []) {
    if (row.itemCount !== undefined || row.lowStockCount !== undefined || row.expiringCount !== undefined) {
      summary.itemCount += Number(row.itemCount) || 0;
      summary.lowStockCount += Number(row.lowStockCount) || 0;
      summary.expiringCount += Number(row.expiringCount) || 0;
      continue;
    }
    summary.itemCount += 1;
    if (Number(row.quantity) <= Number(row.threshold)) summary.lowStockCount += 1;
    if (row.isExpired || row.isExpiring) summary.expiringCount += 1;
  }
  return summary;
}

async function fetchBillsSummary(pool, userId, year, month) {
  const monthStr = pad2(month);
  const dateStart = `${year}-${monthStr}-01`;
  const dateEnd = month === 12 ? `${Number(year) + 1}-01-01` : `${year}-${pad2(Number(month) + 1)}-01`;
  const relationship = await loadActiveRelationship(pool, userId);
  const relationshipId = relationship ? relationship.relationship_id : null;
  const scope = buildCoupleOrPrivateScope(userId, relationship, "b");
  const query = `SELECT COUNT(*) AS billCount,
                        COALESCE(SUM(CASE WHEN b.income_type = 1 THEN b.amount ELSE 0 END), 0) AS income,
                        COALESCE(SUM(CASE WHEN b.income_type = 0 THEN b.amount ELSE 0 END), 0) AS expense
                 FROM bills b
                 WHERE ${scope.clause}
                 AND b.date >= ? AND b.date < ?`;
  const params = [...scope.params, dateStart, dateEnd];
  const [rows] = await pool.execute(query, params);
  const payload = {
    ok: true,
    message: "查询成功",
    data: { ...buildBillsSummary(rows), relationshipId, year, month },
  };
  return payload;
}

async function fetchTodosSummary(pool, userId) {
  const relationship = await loadActiveRelationship(pool, userId);
  const relationshipId = relationship ? relationship.relationship_id : null;

  let sql = `SELECT
               SUM(CASE WHEN status NOT IN ('done', 'missed') THEN 1 ELSE 0 END) AS openCount,
               SUM(CASE WHEN status = 'done' THEN 1 ELSE 0 END) AS doneCount,
               SUM(CASE WHEN status = 'missed' THEN 1 ELSE 0 END) AS missedCount
             FROM todo_items
             WHERE user_id = ? AND relationship_id IS NULL`;
  const params = [userId];
  if (relationshipId) {
    sql = `SELECT
             SUM(CASE WHEN status NOT IN ('done', 'missed') THEN 1 ELSE 0 END) AS openCount,
             SUM(CASE WHEN status = 'done' THEN 1 ELSE 0 END) AS doneCount,
             SUM(CASE WHEN status = 'missed' THEN 1 ELSE 0 END) AS missedCount
           FROM todo_items
           WHERE relationship_id = ? OR (user_id = ? AND relationship_id IS NULL)`;
    params.unshift(relationshipId);
  }
  const [rows] = await pool.execute(sql, params);
  return { ok: true, data: { ...buildTodosSummary(rows), relationshipId } };
}

async function fetchInventorySummary(pool, userId) {
  const relationship = await loadActiveRelationship(pool, userId);
  const relationshipId = relationship ? relationship.relationship_id : null;

  let query;
  let params;
  if (relationshipId) {
    query = `SELECT COUNT(*) AS itemCount,
                    SUM(CASE WHEN quantity <= threshold THEN 1 ELSE 0 END) AS lowStockCount,
                    SUM(CASE WHEN expiration_date IS NOT NULL
                              AND expiration_date <= DATE_ADD(UTC_DATE(), INTERVAL 3 DAY)
                             THEN 1 ELSE 0 END) AS expiringCount
             FROM inventory
             WHERE relationship_id = ? OR (user_id = ? AND relationship_id IS NULL)`;
    params = [relationshipId, userId];
  } else {
    query = `SELECT COUNT(*) AS itemCount,
                    SUM(CASE WHEN quantity <= threshold THEN 1 ELSE 0 END) AS lowStockCount,
                    SUM(CASE WHEN expiration_date IS NOT NULL
                              AND expiration_date <= DATE_ADD(UTC_DATE(), INTERVAL 3 DAY)
                             THEN 1 ELSE 0 END) AS expiringCount
             FROM inventory
             WHERE user_id = ? AND relationship_id IS NULL`;
    params = [userId];
  }
  const [rows] = await pool.execute(query, params);
  return { ok: true, data: { ...buildInventorySummary(rows), relationshipId } };
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
  const scope = buildSubjectOrCurrentRelationshipScope(userId, relationship);
  const [rows] = await pool.execute(
    `SELECT id, user_id, start_date, end_date
     FROM period_records
     WHERE ${scope.clause}
     ORDER BY start_date DESC`,
    scope.params
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

// 子查询：情侣信息 —— 与 auth.js GET /couple-info 共用 loadPartnerProfile，
// 保证两处对「头像是否已过审」的判断口径一致
async function fetchCoupleInfo(pool, userId) {
  const relationship = await loadActiveRelationship(pool, userId);
  if (!relationship) return { ok: true, data: { hasCouple: false } };
  const partnerId =
    relationship.user_id_1 === userId ? relationship.user_id_2 : relationship.user_id_1;
  const partner = await loadPartnerProfile(pool, partnerId);
  if (!partner) return { ok: true, data: { hasCouple: false } };
  return {
    ok: true,
    data: {
      hasCouple: true,
      partnerId: partner.id,
      partnerName: partner.username,
      partnerNickname: partner.nickname,
      partnerAvatarUrl: partner.avatarUrl,
      partnerAvatarPending: partner.avatarPending,
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

      const [billSummary, todoSummary, inventorySummary, assetStats, period, coupleInfo, calorie] = await Promise.all([
        safe("bills", fetchBillsSummary(pool, userId, year, month)),
        safe("todos", fetchTodosSummary(pool, userId)),
        safe("inventory", fetchInventorySummary(pool, userId)),
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
          billSummary,
          todoSummary,
          inventorySummary,
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

module.exports = {
  createMeRouter,
  fetchBillsSummary,
  fetchPeriodPrediction,
  buildBillsSummary,
  buildTodosSummary,
  buildInventorySummary,
};
