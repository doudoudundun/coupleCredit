const express = require("express");
const { ApiError } = require("../errors");
const { cache, Keys, TTL } = require("../cache");
const {
  loadActiveRelationship,
  normalizeNullableText,
  parseRequiredFloat,
  parseRequiredInteger,
  invalidateForUser
} = require("../utils/queryHelpers");

function normalizeGoal(value) {
  return value == null ? null : parseRequiredFloat(Number(value));
}

const MEAL_TYPES = ["cook", "eat_out", "manual"];
const CALORIE_SOURCE_MANUAL = "manual";
const CALORIE_SOURCE_AUTO = "auto";

function normalizeMealType(value) {
  return MEAL_TYPES.includes(value) ? value : null;
}

function normalizeCalorieSource(value) {
  return value === CALORIE_SOURCE_AUTO ? CALORIE_SOURCE_AUTO : CALORIE_SOURCE_MANUAL;
}

function formatDateString(date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function resolveDateString(input) {
  if (typeof input === "string" && /^\d{4}-\d{2}-\d{2}$/.test(input)) {
    return input;
  }
  return formatDateString(new Date());
}

function createDateRange(dateString) {
  const start = `${dateString} 00:00:00`;
  const endDate = new Date(`${dateString}T00:00:00`);
  endDate.setDate(endDate.getDate() + 1);
  const end = `${formatDateString(endDate)} 00:00:00`;
  return { start, end };
}

async function getVisibleUsers(pool, userId) {
  const relationship = await loadActiveRelationship(pool, userId);
  const ids = new Set([userId]);
  if (relationship) {
    ids.add(relationship.user_id_1);
    ids.add(relationship.user_id_2);
  }
  return { relationship, ids: [...ids] };
}

async function getUserDisplayMap(pool, userIds) {
  if (!userIds.length) return {};
  const placeholders = userIds.map(() => "?").join(",");
  const [rows] = await pool.execute(
    `SELECT id, username, nickname FROM users WHERE id IN (${placeholders})`,
    userIds
  );
  const map = {};
  for (const row of rows) {
    map[row.id] = row.nickname || row.username || `用户${row.id}`;
  }
  return map;
}

function mapMealRecord(row, userNameMap) {
  return {
    id: row.record_id,
    userId: row.user_id,
    userName: userNameMap[row.user_id] || `用户${row.user_id}`,
    mealType: row.meal_type,
    recipeId: row.recipe_id,
    restaurantId: row.restaurant_id,
    title: row.title,
    calories: Number(row.calories),
    calorieSource: row.calorie_source,
    note: row.note,
    eatenAt: row.eaten_at,
    createdAt: row.created_at
  };
}

function buildUserSummaries(userIds, userNameMap, goalRows, records) {
  const totalsByUser = {};
  for (const id of userIds) {
    totalsByUser[id] = 0;
  }
  for (const record of records) {
    totalsByUser[record.user_id] = (totalsByUser[record.user_id] || 0) + Number(record.calories);
  }
  const goalMap = {};
  for (const row of goalRows) {
    goalMap[row.user_id] = Number(row.daily_goal);
  }
  return userIds.map((id) => {
    const totalCalories = totalsByUser[id] || 0;
    const dailyGoal = goalMap[id] || 2000;
    return {
      userId: id,
      userName: userNameMap[id] || `用户${id}`,
      totalCalories,
      dailyGoal,
      progress: dailyGoal > 0 ? Number(((totalCalories / dailyGoal) * 100).toFixed(1)) : 0
    };
  });
}

async function buildCaloriePayload(pool, userId, dateString) {
  const { relationship, ids } = await getVisibleUsers(pool, userId);
  const cacheKey = Keys.calorieHistory(userId, dateString);
  const cached = cache.get(cacheKey);
  if (cached) return cached;

  const { start, end } = createDateRange(dateString);
  const placeholders = ids.map(() => "?").join(",");

  const [recordRows, goalRows, userNameMap] = await Promise.all([
    pool.execute(
      `SELECT record_id, user_id, meal_type, recipe_id, restaurant_id, title, calories, calorie_source, note, eaten_at, created_at
       FROM meal_records
       WHERE user_id IN (${placeholders}) AND eaten_at >= ? AND eaten_at < ?
       ORDER BY eaten_at DESC, record_id DESC`,
      [...ids, start, end]
    ),
    pool.execute(
      `SELECT user_id, daily_goal FROM user_calorie_goals WHERE user_id IN (${placeholders})`,
      ids
    ),
    getUserDisplayMap(pool, ids)
  ]);

  const records = recordRows[0];
  const goals = goalRows[0];
  const sourceTotals = { cook: 0, eatOut: 0, manual: 0 };
  let totalCalories = 0;
  for (const row of records) {
    const value = Number(row.calories);
    totalCalories += value;
    if (row.meal_type === "cook") sourceTotals.cook += value;
    if (row.meal_type === "eat_out") sourceTotals.eatOut += value;
    if (row.meal_type === "manual") sourceTotals.manual += value;
  }

  const userSummaries = buildUserSummaries(ids, userNameMap, goals, records);
  const selfGoal = (goals.find((row) => row.user_id === userId) || {}).daily_goal;
  const dailyGoal = selfGoal != null ? Number(selfGoal) : 2000;
  const payload = {
    ok: true,
    data: {
      date: dateString,
      totalCalories,
      dailyGoal,
      progress: dailyGoal > 0 ? Number(((totalCalories / dailyGoal) * 100).toFixed(1)) : 0,
      sourceTotals,
      userSummaries,
      relationshipId: relationship ? relationship.relationship_id : null,
      records: records.map((row) => mapMealRecord(row, userNameMap))
    }
  };
  cache.set(cacheKey, payload, TTL.CALORIE);
  if (dateString === formatDateString(new Date())) {
    cache.set(Keys.calorieToday(userId), payload, TTL.CALORIE);
  }
  return payload;
}

function createCalorieRouter({ pool }) {
  const router = express.Router();

  async function invalidateCalorieCache(userId) {
    const relationship = await loadActiveRelationship(pool, userId);
    invalidateForUser(cache, Keys.calorieToday, userId, relationship);
    const ids = new Set([userId]);
    if (relationship) {
      ids.add(relationship.user_id_1);
      ids.add(relationship.user_id_2);
    }
    for (const id of ids) {
      cache.delPrefix(`calorie:history:${id}:`);
    }
  }

  router.get("/today", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.userId);
      const cached = cache.get(Keys.calorieToday(userId));
      if (cached) return res.json(cached);
      const payload = await buildCaloriePayload(pool, userId, formatDateString(new Date()));
      res.json(payload);
    } catch (error) {
      next(error);
    }
  });

  router.get("/history", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.userId);
      const date = resolveDateString(req.query.date);
      const payload = await buildCaloriePayload(pool, userId, date);
      res.json(payload);
    } catch (error) {
      next(error);
    }
  });

  router.post("/record", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.userId);
      const mealType = normalizeMealType(req.body.mealType);
      const calories = parseRequiredFloat(Number(req.body.calories));
      if (!mealType) throw new ApiError(400, "INVALID_REQUEST", "mealType 参数无效");

      const title = normalizeNullableText(req.body.title) || (mealType === "eat_out" ? "外食" : "手动记录");
      const calorieSource = normalizeCalorieSource(req.body.calorieSource);
      const recipeId = req.body.recipeId != null ? parseRequiredInteger(Number(req.body.recipeId)) : null;
      const restaurantId = req.body.restaurantId != null ? parseRequiredInteger(Number(req.body.restaurantId)) : null;
      const note = normalizeNullableText(req.body.note);
      const eatenAt = normalizeNullableText(req.body.eatenAt) || null;

      const [result] = await pool.execute(
        `INSERT INTO meal_records (user_id, meal_type, recipe_id, restaurant_id, title, calories, calorie_source, note, eaten_at, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, NOW()), NOW())`,
        [userId, mealType, recipeId, restaurantId, title, calories, calorieSource, note, eatenAt]
      );

      await invalidateCalorieCache(userId);
      res.status(201).json({ ok: true, data: { recordId: result.insertId } });
    } catch (error) {
      next(error);
    }
  });

  router.delete("/record/:id", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.userId);
      const recordId = parseRequiredInteger(Number(req.params.id));
      const [rows] = await pool.execute(
        `SELECT record_id, user_id FROM meal_records WHERE record_id = ? LIMIT 1`,
        [recordId]
      );
      if (!rows.length) throw new ApiError(404, "NOT_FOUND", "记录不存在");
      if (rows[0].user_id !== userId) throw new ApiError(403, "FORBIDDEN", "只能删除自己的记录");

      await pool.execute(`DELETE FROM meal_records WHERE record_id = ?`, [recordId]);
      await invalidateCalorieCache(userId);
      res.json({ ok: true, message: "删除成功" });
    } catch (error) {
      next(error);
    }
  });

  router.get("/goal", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.userId);
      const [rows] = await pool.execute(
        `SELECT user_id, daily_goal, updated_at FROM user_calorie_goals WHERE user_id = ? LIMIT 1`,
        [userId]
      );
      const row = rows[0];
      res.json({
        ok: true,
        data: {
          userId,
          dailyGoal: row ? Number(row.daily_goal) : 2000,
          updatedAt: row ? row.updated_at : null
        }
      });
    } catch (error) {
      next(error);
    }
  });

  router.put("/goal", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.userId);
      const dailyGoal = normalizeGoal(req.body.dailyGoal);
      if (dailyGoal == null) throw new ApiError(400, "INVALID_REQUEST", "dailyGoal 参数无效");

      await pool.execute(
        `INSERT INTO user_calorie_goals (user_id, daily_goal, updated_at)
         VALUES (?, ?, NOW())
         ON DUPLICATE KEY UPDATE daily_goal = VALUES(daily_goal), updated_at = NOW()`,
        [userId, dailyGoal]
      );
      await invalidateCalorieCache(userId);
      res.json({ ok: true, message: "目标更新成功" });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

function createNutritionRouter({ pool }) {
  const router = express.Router();

  function invalidateNutritionCache() {
    cache.delPrefix("nutrition:search:");
  }

  router.get("/search", async (req, res, next) => {
    try {
      const query = normalizeNullableText(req.query.q) || "";
      const cacheKey = Keys.nutritionSearch(query.toLowerCase());
      const cached = cache.get(cacheKey);
      if (cached) return res.json(cached);

      const like = `%${query}%`;
      const [rows] = await pool.execute(
        `SELECT id, name, calories_per_unit, unit, category, created_at, updated_at
         FROM ingredient_nutrition
         WHERE name LIKE ?
         ORDER BY name ASC
         LIMIT 30`,
        [like]
      );
      const payload = {
        ok: true,
        data: {
          items: rows.map((row) => ({
            id: row.id,
            name: row.name,
            caloriesPerUnit: Number(row.calories_per_unit),
            unit: row.unit,
            category: row.category,
            createdAt: row.created_at,
            updatedAt: row.updated_at
          }))
        }
      };
      cache.set(cacheKey, payload, TTL.NUTRITION);
      res.json(payload);
    } catch (error) {
      next(error);
    }
  });

  router.get("/", async (req, res, next) => {
    try {
      const name = normalizeNullableText(req.query.name);
      if (!name) throw new ApiError(400, "INVALID_REQUEST", "name 参数无效");
      const [rows] = await pool.execute(
        `SELECT id, name, calories_per_unit, unit, category, created_at, updated_at
         FROM ingredient_nutrition WHERE name = ? LIMIT 1`,
        [name]
      );
      const row = rows[0] || null;
      res.json({
        ok: true,
        data: row
          ? {
              item: {
                id: row.id,
                name: row.name,
                caloriesPerUnit: Number(row.calories_per_unit),
                unit: row.unit,
                category: row.category,
                createdAt: row.created_at,
                updatedAt: row.updated_at
              }
            }
          : { item: null }
      });
    } catch (error) {
      next(error);
    }
  });

  router.post("/", async (req, res, next) => {
    try {
      const name = normalizeNullableText(req.body.name);
      const unit = normalizeNullableText(req.body.unit);
      const category = normalizeNullableText(req.body.category);
      const caloriesPerUnit = parseRequiredFloat(Number(req.body.caloriesPerUnit));
      if (!name || !unit) throw new ApiError(400, "INVALID_REQUEST", "name 和 unit 必填");

      await pool.execute(
        `INSERT INTO ingredient_nutrition (name, calories_per_unit, unit, category, created_at, updated_at)
         VALUES (?, ?, ?, ?, NOW(), NOW())
         ON DUPLICATE KEY UPDATE calories_per_unit = VALUES(calories_per_unit), unit = VALUES(unit), category = VALUES(category), updated_at = NOW()`,
        [name, caloriesPerUnit, unit, category]
      );
      invalidateNutritionCache();
      res.json({ ok: true, message: "营养信息已保存" });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createCalorieRouter, createNutritionRouter, buildCaloriePayload };
