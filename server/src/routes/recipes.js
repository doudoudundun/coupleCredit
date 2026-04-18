const express = require("express");
const { ApiError } = require("../errors");
const { cache, Keys, TTL } = require("../cache");
const { loadActiveRelationship, trimValue, parseRequiredInteger } = require("../utils/queryHelpers");

function createRecipeRouter({ pool }) {
  const router = express.Router();

  function invalidateRecipeCache(userId) {
    cache.del(Keys.recipes(userId));
    cache.del(Keys.recipeCategories(userId));
  }

  async function replaceRecipeIngredients(recipeId, ingredients) {
    if (!Array.isArray(ingredients) || ingredients.length === 0) return;
    for (const ing of ingredients) {
      await pool.execute(
        `INSERT INTO recipe_ingredients (recipe_id, inventory_id, ingredient_name, quantity, unit)
         VALUES (?, ?, ?, ?, ?)`,
        [recipeId, ing.inventoryId || null, trimValue(ing.ingredientName), ing.quantity || 0, ing.unit || "个"]
      );
    }
  }

  // GET /api/recipes?userId=
  router.get("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(Number(req.query.userId));

      const cached = cache.get(Keys.recipes(userId));
      if (cached) return res.json(cached);

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      let query, params;
      if (relationshipId) {
        query = `SELECT r.recipe_id, r.user_id, r.category_id, r.title, r.description, r.image_url, r.steps,
                 r.created_at, r.updated_at,
                 (SELECT COUNT(*) FROM recipe_ingredients ri WHERE ri.recipe_id = r.recipe_id) AS ingredientCount
                 FROM recipes r
                 WHERE r.relationship_id = ? OR (r.user_id = ? AND r.relationship_id IS NULL)
                 ORDER BY r.updated_at DESC`;
        params = [relationshipId, userId];
      } else {
        query = `SELECT r.recipe_id, r.user_id, r.category_id, r.title, r.description, r.image_url, r.steps,
                 r.created_at, r.updated_at,
                 (SELECT COUNT(*) FROM recipe_ingredients ri WHERE ri.recipe_id = r.recipe_id) AS ingredientCount
                 FROM recipes r
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
        ingredientCount: r.ingredientCount,
        createdAt: r.created_at,
        updatedAt: r.updated_at
      }));

      const responseData = { ok: true, data: { items, relationshipId } };
      cache.set(Keys.recipes(userId), responseData, TTL.RECIPES);
      res.json(responseData);
    } catch (error) { next(error); }
  });

  // GET /api/recipes/:id?userId=
  router.get("/:id", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(Number(req.query.userId));
      const recipeId = parseRequiredInteger(Number(req.params.id));

      const [rows] = await pool.execute(
        `SELECT recipe_id, user_id, category_id, title, description, image_url, steps, created_at, updated_at
         FROM recipes WHERE recipe_id = ?`, [recipeId]);
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
      const { userId, title, description, imageUrl, steps, ingredients, categoryId } = req.body;
      const parsedUserId = parseRequiredInteger(Number(userId));
      if (!title) throw new ApiError(400, "INVALID_REQUEST", "userId 和 title 必填");

      const relationship = await loadActiveRelationship(pool, parsedUserId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      const [result] = await pool.execute(
        `INSERT INTO recipes (user_id, relationship_id, category_id, title, description, image_url, steps)
         VALUES (?, ?, ?, ?, ?, ?, ?)`,
        [parsedUserId, relationshipId, categoryId || null, trimValue(title), description || null, imageUrl || null, steps || null]
      );

      const recipeId = result.insertId;

      await replaceRecipeIngredients(recipeId, ingredients);

      invalidateRecipeCache(parsedUserId);
      res.json({ ok: true, data: { recipeId } });
    } catch (error) { next(error); }
  });

  // PUT /api/recipes/:id
  router.put("/:id", async (req, res, next) => {
    try {
      const recipeId = parseRequiredInteger(Number(req.params.id));
      const { userId, title, description, imageUrl, steps, ingredients, categoryId } = req.body;
      const parsedUserId = parseRequiredInteger(Number(userId));

      await pool.execute(
        `UPDATE recipes SET title = ?, description = ?, image_url = ?, steps = ?, category_id = ? WHERE recipe_id = ?`,
        [trimValue(title), description || null, imageUrl || null, steps || null, categoryId !== undefined ? categoryId : null, recipeId]
      );

      // Replace ingredients
      await pool.execute(`DELETE FROM recipe_ingredients WHERE recipe_id = ?`, [recipeId]);
      await replaceRecipeIngredients(recipeId, ingredients);

      invalidateRecipeCache(parsedUserId);
      res.json({ ok: true, message: "菜谱更新成功" });
    } catch (error) { next(error); }
  });

  // DELETE /api/recipes/:id?userId=
  router.delete("/:id", async (req, res, next) => {
    try {
      const recipeId = parseRequiredInteger(Number(req.params.id));
      const userId = parseRequiredInteger(Number(req.query.userId));

      const [result] = await pool.execute(`DELETE FROM recipes WHERE recipe_id = ? AND user_id = ?`, [recipeId, userId]);
      if (result.affectedRows === 0) throw new ApiError(404, "NOT_FOUND", "菜谱不存在或无权删除");

      invalidateRecipeCache(userId);
      res.json({ ok: true, message: "菜谱删除成功" });
    } catch (error) { next(error); }
  });

  // POST /api/recipes/:id/cook — consume linked inventory items
  router.post("/:id/cook", async (req, res, next) => {
    try {
      const recipeId = parseRequiredInteger(Number(req.params.id));
      const userId = parseRequiredInteger(Number(req.body.userId));

      const [ingredients] = await pool.execute(
        `SELECT ri.id, ri.inventory_id, ri.ingredient_name, ri.quantity, ri.unit, i.quantity AS stock
         FROM recipe_ingredients ri
         LEFT JOIN inventory i ON ri.inventory_id = i.inventory_id
         WHERE ri.recipe_id = ? AND ri.inventory_id IS NOT NULL`, [recipeId]);

      const results = [];
      const warnings = [];

      for (const ing of ingredients) {
        const needed = parseFloat(ing.quantity) || 0;
        const stock = parseFloat(ing.stock) || 0;
        if (stock < needed) {
          warnings.push(`${ing.ingredient_name}: 需 ${needed} ${ing.unit}，仅剩 ${stock} ${ing.unit}`);
        }
        await pool.execute(
          `UPDATE inventory SET quantity = GREATEST(quantity - ?, 0), last_consumed_at = NOW() WHERE inventory_id = ?`,
          [needed, ing.inventory_id]);
        results.push({ name: ing.ingredient_name, consumed: needed, unit: ing.unit, hadEnough: stock >= needed });
      }

      invalidateRecipeCache(userId);
      res.json({ ok: true, data: { results, warnings } });
    } catch (error) { next(error); }
  });

  return router;
}

module.exports = { createRecipeRouter };
