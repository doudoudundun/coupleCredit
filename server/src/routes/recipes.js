const express = require("express");
const { ApiError } = require("../errors");
const { cache, Keys, TTL } = require("../cache");
const {
  loadActiveRelationship,
  trimValue,
  parseRequiredInteger,
  buildCoupleOrPrivateScope,
  invalidateForUser
} = require("../utils/queryHelpers");
const { withTransaction } = require("../utils/transactions");

const SOURCE_MANUAL = "manual";
const SOURCE_AUTO = "auto";
const MEAL_COOK = "cook";

function createRecipeRouter({ pool }) {
  const router = express.Router();

  async function invalidateRecipeCache(userId, relationship) {
    const ids = new Set([userId]);
    if (relationship) {
      ids.add(relationship.user_id_1);
      ids.add(relationship.user_id_2);
    }
    for (const id of ids) {
      cache.del(Keys.recipes(id));
      cache.del(Keys.recipeCategories(id));
      cache.delPrefix(Keys.recipeRecommend(id));
    }
    invalidateForUser(cache, Keys.calorieToday, userId, relationship || null);
    for (const id of ids) {
      cache.delPrefix(Keys.calorieHistoryByUser(id));
    }
  }

  function normalizeManualCalories(value) {
    if (value === undefined || value === null || value === "") return null;
    const number = Number(value);
    if (!Number.isFinite(number) || number < 0) {
      throw new ApiError(400, "INVALID_REQUEST", "热量参数格式不正确");
    }
    return number;
  }

  async function replaceRecipeIngredients(db, recipeId, ingredients) {
    if (ingredients.length === 0) return;
    const placeholders = ingredients.map(() => "(?, ?, ?, ?, ?)").join(", ");
    const params = [];
    for (const ing of ingredients) {
      params.push(recipeId, ing.inventoryId, ing.ingredientName, ing.quantity, ing.unit);
    }
    await db.execute(
      `INSERT INTO recipe_ingredients (recipe_id, inventory_id, ingredient_name, quantity, unit) VALUES ${placeholders}`,
      params
    );
  }

  async function calculateRecipeCalories(db, recipeId) {
    const [ingredients] = await db.execute(
      `SELECT ingredient_name, quantity, unit FROM recipe_ingredients WHERE recipe_id = ?`,
      [recipeId]
    );
    if (!ingredients.length) {
      return null;
    }

    const names = ingredients.map(i => i.ingredient_name);
    const placeholders = names.map(() => "?").join(",");
    const [nutritionRows] = await db.execute(
      `SELECT name, calories_per_unit, unit FROM ingredient_nutrition WHERE name IN (${placeholders})`,
      names
    );
    const nutritionByIngredient = {};
    for (const row of nutritionRows) {
      nutritionByIngredient[row.name] = row;
    }

    let total = 0;
    for (const ingredient of ingredients) {
      const nutrition = nutritionByIngredient[ingredient.ingredient_name];
      if (!nutrition) return null;
      if ((nutrition.unit || "").trim() !== (ingredient.unit || "").trim()) {
        return null;
      }
      total += (Number(ingredient.quantity) || 0) * Number(nutrition.calories_per_unit);
    }

    return {
      totalCalories: Number(total.toFixed(2)),
      calorieSource: SOURCE_AUTO
    };
  }

  async function applyRecipeCalories(db, recipeId, manualCalories) {
    if (manualCalories != null) {
      await db.execute(
        `UPDATE recipes SET total_calories = ?, calorie_source = '${SOURCE_MANUAL}' WHERE recipe_id = ?`,
        [manualCalories, recipeId]
      );
      return;
    }

    const autoCalories = await calculateRecipeCalories(db, recipeId);
    if (autoCalories) {
      await db.execute(
        `UPDATE recipes SET total_calories = ?, calorie_source = ? WHERE recipe_id = ?`,
        [autoCalories.totalCalories, autoCalories.calorieSource, recipeId]
      );
    } else {
      await db.execute(
        `UPDATE recipes SET total_calories = NULL, calorie_source = NULL WHERE recipe_id = ?`,
        [recipeId]
      );
    }
  }

  async function insertCookMealRecord(db, recipe, userId, relationshipId) {
    if (recipe.total_calories == null) return null;

    const [result] = await db.execute(
      `INSERT INTO meal_records (user_id, relationship_id, meal_type, recipe_id, restaurant_id, title, calories, calorie_source, note, eaten_at, created_at)
       VALUES (?, ?, '${MEAL_COOK}', ?, NULL, ?, ?, ?, NULL, NOW(), NOW())`,
      [userId, relationshipId, recipe.recipe_id, recipe.title, Number(recipe.total_calories), recipe.calorie_source || SOURCE_MANUAL]
    );
    return {
      recordId: result.insertId,
      title: recipe.title,
      calories: Number(recipe.total_calories),
      calorieSource: recipe.calorie_source || "manual"
    };
  }

  function normalizeRecipeText(value, fieldName, { required = false, maxLength = null } = {}) {
    if (value === undefined || value === null) {
      if (required) throw new ApiError(400, "INVALID_REQUEST", `${fieldName}不能为空`);
      return null;
    }
    if (typeof value !== "string") {
      throw new ApiError(400, "INVALID_REQUEST", `${fieldName}格式不正确`);
    }
    const trimmed = value.trim();
    if (required && !trimmed) {
      throw new ApiError(400, "INVALID_REQUEST", `${fieldName}不能为空`);
    }
    if (maxLength !== null && trimmed.length > maxLength) {
      throw new ApiError(400, "INVALID_REQUEST", `${fieldName}长度不能超过${maxLength}个字符`);
    }
    return trimmed || null;
  }

  function normalizeRecipeIngredients(value) {
    if (value === undefined || value === null) return [];
    if (!Array.isArray(value)) {
      throw new ApiError(400, "INVALID_REQUEST", "食材列表格式不正确");
    }

    return value.map((ingredient) => {
      if (!ingredient || typeof ingredient !== "object" || Array.isArray(ingredient)) {
        throw new ApiError(400, "INVALID_REQUEST", "食材格式不正确");
      }

      let inventoryId = null;
      if (ingredient.inventoryId !== undefined && ingredient.inventoryId !== null && ingredient.inventoryId !== "") {
        const parsedInventoryId = Number(ingredient.inventoryId);
        if (!Number.isInteger(parsedInventoryId) || parsedInventoryId <= 0) {
          throw new ApiError(400, "INVALID_REQUEST", "食材物资ID格式不正确");
        }
        inventoryId = parsedInventoryId;
      }

      const ingredientName = normalizeRecipeText(ingredient.ingredientName, "食材名称", { required: true, maxLength: 100 });
      const quantity = Number(ingredient.quantity ?? 0);
      if (!Number.isFinite(quantity) || quantity < 0) {
        throw new ApiError(400, "INVALID_REQUEST", "食材数量格式不正确");
      }
      const unit = normalizeRecipeText(ingredient.unit === undefined ? "个" : ingredient.unit, "食材单位", { required: true, maxLength: 20 });

      return { inventoryId, ingredientName, quantity, unit };
    });
  }

  function normalizeRecipePayload(body) {
    return {
      title: normalizeRecipeText(body.title, "菜谱标题", { required: true, maxLength: 100 }),
      description: normalizeRecipeText(body.description, "菜谱描述"),
      imageUrl: normalizeRecipeText(body.imageUrl, "菜谱图片地址", { maxLength: 500 }),
      steps: normalizeRecipeText(body.steps, "菜谱步骤"),
      ingredients: normalizeRecipeIngredients(body.ingredients),
      totalCalories: normalizeManualCalories(body.totalCalories),
      categoryId: body.categoryId
    };
  }

  async function validateCategory(categoryId, relationship) {
    if (categoryId === undefined || categoryId === null || categoryId === "") return null;
    const parsedCategoryId = parseRequiredInteger(Number(categoryId));
    if (!relationship) {
      throw new ApiError(404, "NOT_FOUND", "菜谱分类不存在或无权使用");
    }
    const [rows] = await pool.execute(
      `SELECT category_id FROM recipe_categories
       WHERE category_id = ? AND relationship_id = ? LIMIT 1`,
      [parsedCategoryId, relationship.relationship_id]
    );
    if (!rows.length) {
      throw new ApiError(404, "NOT_FOUND", "菜谱分类不存在或无权使用");
    }
    return parsedCategoryId;
  }

  // GET /api/recipes?userId=
  router.get("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.userId);

      const cached = cache.get(Keys.recipes(userId));
      if (cached) return res.json(cached);

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      let query, params;
      if (relationshipId) {
        query = `SELECT r.recipe_id, r.user_id, r.category_id, r.title, r.description, r.image_url, r.steps,
                 r.total_calories, r.calorie_source,
                 r.created_at, r.updated_at,
                 COALESCE(ri_agg.cnt, 0) AS ingredientCount
                 FROM recipes r
                 LEFT JOIN (SELECT recipe_id, COUNT(*) AS cnt FROM recipe_ingredients GROUP BY recipe_id) ri_agg ON ri_agg.recipe_id = r.recipe_id
                 WHERE r.relationship_id = ? OR (r.user_id = ? AND r.relationship_id IS NULL)
                 ORDER BY r.updated_at DESC`;
        params = [relationshipId, userId];
      } else {
        query = `SELECT r.recipe_id, r.user_id, r.category_id, r.title, r.description, r.image_url, r.steps,
                 r.total_calories, r.calorie_source,
                 r.created_at, r.updated_at,
                 COALESCE(ri_agg.cnt, 0) AS ingredientCount
                 FROM recipes r
                 LEFT JOIN (SELECT recipe_id, COUNT(*) AS cnt FROM recipe_ingredients GROUP BY recipe_id) ri_agg ON ri_agg.recipe_id = r.recipe_id
                 WHERE r.user_id = ? AND r.relationship_id IS NULL
                 ORDER BY r.updated_at DESC`;
        params = [userId];
      }

      const [rows] = await pool.execute(query, params);
      const items = rows.map(r => ({
        recipeId: r.recipe_id,
        userId: r.user_id,
        categoryId: r.category_id,
        title: r.title,
        description: r.description,
        imageUrl: r.image_url,
        steps: r.steps,
        totalCalories: r.total_calories !== null ? Number(r.total_calories) : null,
        calorieSource: r.calorie_source,
        ingredientCount: r.ingredientCount,
        createdAt: r.created_at,
        updatedAt: r.updated_at
      }));

      const responseData = { ok: true, data: { items, relationshipId } };
      cache.set(Keys.recipes(userId), responseData, TTL.RECIPES);
      res.json(responseData);
    } catch (error) { next(error); }
  });

  // GET /api/recipes/recommend?userId=&mode=&ingredient=
  router.get("/recommend", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.userId);
      const mode = req.query.mode || "recommend";
      const ingredient = req.query.ingredient || null;

      const cacheKey = Keys.recipeRecommend(userId) + ":" + mode + ":" + (ingredient || "");
      const cached = cache.get(cacheKey);
      if (cached) return res.json(cached);

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      let recipeQuery, recipeParams;
      if (relationshipId) {
        recipeQuery = `SELECT r.recipe_id, r.title, r.description, r.image_url, r.category_id, r.total_calories, r.calorie_source
                       FROM recipes r
                       WHERE r.relationship_id = ? OR (r.user_id = ? AND r.relationship_id IS NULL)`;
        recipeParams = [relationshipId, userId];
      } else {
        recipeQuery = `SELECT r.recipe_id, r.title, r.description, r.image_url, r.category_id, r.total_calories, r.calorie_source
                       FROM recipes r WHERE r.user_id = ? AND r.relationship_id IS NULL`;
        recipeParams = [userId];
      }
      const [recipes] = await pool.execute(recipeQuery, recipeParams);

      if (recipes.length === 0) {
        const emptyResult = { ok: true, data: { recipes: [], availableIngredients: [] } };
        cache.set(cacheKey, emptyResult, TTL.RECIPE_RECOMMEND);
        return res.json(emptyResult);
      }

      const recipeIds = recipes.map(r => r.recipe_id);
      const riPlaceholders = recipeIds.map(() => "?").join(",");
      const [riRows] = await pool.execute(
        `SELECT recipe_id, ingredient_name FROM recipe_ingredients WHERE recipe_id IN (${riPlaceholders})`,
        recipeIds
      );
      const ingredientsByRecipe = {};
      for (const ri of riRows) {
        if (!ingredientsByRecipe[ri.recipe_id]) ingredientsByRecipe[ri.recipe_id] = [];
        ingredientsByRecipe[ri.recipe_id].push(ri.ingredient_name);
      }

      let invQuery, invParams;
      if (relationshipId) {
        const inventoryScope = buildCoupleOrPrivateScope(userId, relationship);
        invQuery = `SELECT name FROM inventory WHERE ${inventoryScope.clause}`;
        invParams = inventoryScope.params;
      } else {
        const inventoryScope = buildCoupleOrPrivateScope(userId, relationship);
        invQuery = `SELECT name FROM inventory WHERE ${inventoryScope.clause}`;
        invParams = inventoryScope.params;
      }
      const [invRows] = await pool.execute(invQuery, invParams);
      const inventoryNames = new Set(invRows.map(r => r.name));

      const results = recipes.map(r => {
        const recipeIngredients = ingredientsByRecipe[r.recipe_id] || [];
        const matched = [];
        const missing = [];
        for (const name of recipeIngredients) {
          if (inventoryNames.has(name)) matched.push(name);
          else missing.push(name);
        }
        return {
          recipeId: r.recipe_id,
          title: r.title,
          description: r.description,
          imageUrl: r.image_url,
          categoryId: r.category_id,
          totalCalories: r.total_calories !== null ? Number(r.total_calories) : null,
          calorieSource: r.calorie_source,
          matchInfo: {
            total: recipeIngredients.length,
            matched: matched.length,
            matchedIngredients: matched,
            missingIngredients: missing
          }
        };
      });

      results.sort((a, b) => a.matchInfo.missingIngredients.length - b.matchInfo.missingIngredients.length);

      let filtered = results;
      if (mode === "ingredient" && ingredient) {
        filtered = results.filter(r =>
          r.matchInfo.matchedIngredients.includes(ingredient) ||
          r.matchInfo.missingIngredients.includes(ingredient)
        );
      }

      const availableIngredients = [...inventoryNames].sort();

      const responseData = { ok: true, data: { recipes: filtered, availableIngredients } };
      cache.set(cacheKey, responseData, TTL.RECIPE_RECOMMEND);
      res.json(responseData);
    } catch (error) { next(error); }
  });

  // GET /api/recipes/:id?userId=
  router.get("/:id", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.userId);
      const recipeId = parseRequiredInteger(Number(req.params.id));

      const relationship = await loadActiveRelationship(pool, userId);
      const scope = buildCoupleOrPrivateScope(userId, relationship);

      const [rows] = await pool.execute(
        `SELECT recipe_id, user_id, relationship_id, category_id, title, description, image_url, steps, total_calories, calorie_source, created_at, updated_at
         FROM recipes WHERE recipe_id = ? AND ${scope.clause}`,
        [recipeId, ...scope.params]
      );
      if (rows.length === 0) throw new ApiError(404, "NOT_FOUND", "菜谱不存在");

      const recipe = rows[0];
      const [ingredients] = await pool.execute(
        `SELECT id, recipe_id, inventory_id, ingredient_name, quantity, unit
         FROM recipe_ingredients WHERE recipe_id = ?`, [recipeId]);

      res.json({
        ok: true,
        data: {
          recipeId: recipe.recipe_id,
          userId: recipe.user_id,
          categoryId: recipe.category_id,
          title: recipe.title,
          description: recipe.description,
          imageUrl: recipe.image_url,
          steps: recipe.steps,
          totalCalories: recipe.total_calories !== null ? Number(recipe.total_calories) : null,
          calorieSource: recipe.calorie_source,
          createdAt: recipe.created_at,
          updatedAt: recipe.updated_at,
          ingredients: ingredients.map(i => ({
            id: i.id,
            inventoryId: i.inventory_id,
            ingredientName: i.ingredient_name,
            quantity: i.quantity,
            unit: i.unit
          }))
        }
      });
    } catch (error) { next(error); }
  });

  // POST /api/recipes
  router.post("/", async (req, res, next) => {
    try {
      const parsedUserId = parseRequiredInteger(req.userId);
      const payload = normalizeRecipePayload(req.body);

      const relationship = await loadActiveRelationship(pool, parsedUserId);
      const relationshipId = relationship ? relationship.relationship_id : null;
      const validatedCategoryId = await validateCategory(payload.categoryId, relationship);
      let recipeId;

      await withTransaction(pool, async (conn) => {
        const [result] = await conn.execute(
          `INSERT INTO recipes (user_id, relationship_id, category_id, title, description, image_url, steps)
           VALUES (?, ?, ?, ?, ?, ?, ?)`,
          [parsedUserId, relationshipId, validatedCategoryId, payload.title, payload.description, payload.imageUrl, payload.steps]
        );

        recipeId = result.insertId;
        await replaceRecipeIngredients(conn, recipeId, payload.ingredients);
        await applyRecipeCalories(conn, recipeId, payload.totalCalories);
      });

      await invalidateRecipeCache(parsedUserId, relationship);
      res.json({ ok: true, data: { recipeId } });
    } catch (error) { next(error); }
  });

  // PUT /api/recipes/:id
  router.put("/:id", async (req, res, next) => {
    try {
      const recipeId = parseRequiredInteger(Number(req.params.id));
      const parsedUserId = parseRequiredInteger(req.userId);
      const payload = normalizeRecipePayload(req.body);
      const relationship = await loadActiveRelationship(pool, parsedUserId);
      const validatedCategoryId = await validateCategory(payload.categoryId, relationship);
      const scope = buildCoupleOrPrivateScope(parsedUserId, relationship);

      await withTransaction(pool, async (conn) => {
        const [rows] = await conn.execute(
          `SELECT recipe_id FROM recipes WHERE recipe_id = ? AND ${scope.clause} LIMIT 1 FOR UPDATE`,
          [recipeId, ...scope.params]
        );
        if (rows.length === 0) {
          throw new ApiError(404, "NOT_FOUND", "菜谱不存在或无权修改");
        }

        await conn.execute(
          `UPDATE recipes SET title = ?, description = ?, image_url = ?, steps = ?, category_id = ?
           WHERE recipe_id = ? AND ${scope.clause}`,
          [payload.title, payload.description, payload.imageUrl, payload.steps, validatedCategoryId, recipeId, ...scope.params]
        );

        await conn.execute(`DELETE FROM recipe_ingredients WHERE recipe_id = ?`, [recipeId]);
        await replaceRecipeIngredients(conn, recipeId, payload.ingredients);
        await applyRecipeCalories(conn, recipeId, payload.totalCalories);
      });

      await invalidateRecipeCache(parsedUserId, relationship);
      res.json({ ok: true, message: "菜谱更新成功" });
    } catch (error) { next(error); }
  });

  // DELETE /api/recipes/:id?userId=
  router.delete("/:id", async (req, res, next) => {
    try {
      const recipeId = parseRequiredInteger(Number(req.params.id));
      const userId = parseRequiredInteger(req.userId);

      const relationship = await loadActiveRelationship(pool, userId);
      const scope = buildCoupleOrPrivateScope(userId, relationship);
      const [result] = await pool.execute(
        `DELETE FROM recipes WHERE recipe_id = ? AND ${scope.clause}`,
        [recipeId, ...scope.params]
      );
      if (result.affectedRows === 0) throw new ApiError(404, "NOT_FOUND", "菜谱不存在或无权删除");

      await invalidateRecipeCache(userId, relationship);
      res.json({ ok: true, message: "菜谱删除成功" });
    } catch (error) { next(error); }
  });

  // POST /api/recipes/:id/cook — consume inventory by ingredient name matching
  router.post("/:id/cook", async (req, res, next) => {
    try {
      const recipeId = parseRequiredInteger(Number(req.params.id));
      const userId = parseRequiredInteger(req.userId);

      const relationship = await loadActiveRelationship(pool, userId);
      const recipeScope = buildCoupleOrPrivateScope(userId, relationship);
      const [recipeRows] = await pool.execute(
        `SELECT recipe_id, title, total_calories, calorie_source
         FROM recipes WHERE recipe_id = ? AND ${recipeScope.clause} LIMIT 1`,
        [recipeId, ...recipeScope.params]
      );
      if (!recipeRows.length) {
        throw new ApiError(404, "NOT_FOUND", "菜谱不存在或无权使用");
      }
      const recipe = recipeRows[0];

      // Get recipe ingredients
      const [ingredients] = await pool.execute(
        `SELECT ri.ingredient_name, ri.quantity, ri.unit FROM recipe_ingredients ri WHERE ri.recipe_id = ?`,
        [recipeId]);

      if (ingredients.length === 0) {
        return res.json({ ok: true, data: { results: [], warnings: [] } });
      }

      // Get user's inventory (including partner's shared items)
      const relationshipId = relationship ? relationship.relationship_id : null;

      let invQuery, invParams;
      if (relationshipId) {
        const inventoryScope = buildCoupleOrPrivateScope(userId, relationship);
        invQuery = `SELECT inventory_id, name, quantity, unit FROM inventory WHERE ${inventoryScope.clause}`;
        invParams = inventoryScope.params;
      } else {
        const inventoryScope = buildCoupleOrPrivateScope(userId, relationship);
        invQuery = `SELECT inventory_id, name, quantity, unit FROM inventory WHERE ${inventoryScope.clause}`;
        invParams = inventoryScope.params;
      }
      const [inventory] = await pool.execute(invQuery, invParams);

      const results = [];
      const warnings = [];

      const updates = [];
      for (const ing of ingredients) {
        const needed = parseFloat(ing.quantity) || 0;
        if (needed <= 0) continue;

        const match = inventory.find(inv => inv.name === ing.ingredient_name);
        if (!match) continue;

        const stock = parseFloat(match.quantity) || 0;
        if (stock < needed) {
          warnings.push(`${ing.ingredient_name}: 需 ${needed} ${ing.unit}，仅剩 ${stock} ${match.unit}`);
        }
        updates.push([needed, match.inventory_id]);
        results.push({ name: ing.ingredient_name, consumed: needed, unit: ing.unit, hadEnough: stock >= needed });
      }

      if (updates.length > 0) {
        const whens = [];
        const caseParams = [];
        for (const [needed, invId] of updates) {
          whens.push(`WHEN inventory_id = ? THEN GREATEST(quantity - ?, 0)`);
          caseParams.push(invId, needed);
        }
        const ids = updates.map(u => u[1]);
        await pool.execute(
          `UPDATE inventory SET quantity = CASE ${whens.join(" ")} END, last_consumed_at = NOW() WHERE inventory_id IN (${ids.map(() => "?").join(",")})`,
          [...caseParams, ...ids]
        );
      }

      const mealRecord = await insertCookMealRecord(pool, recipe, userId, relationshipId);
      await invalidateRecipeCache(userId, relationship);
      invalidateForUser(cache, Keys.inventory, userId, relationship);
      res.json({ ok: true, data: { results, warnings, mealRecord } });
    } catch (error) { next(error); }
  });

  return router;
}

module.exports = { createRecipeRouter };
