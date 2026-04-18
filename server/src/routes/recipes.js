const express = require("express");
const { ApiError } = require("../errors");
const { loadActiveRelationship, trimValue } = require("../utils/queryHelpers");

function createRecipeRouter({ pool }) {
  const router = express.Router();

  // GET /api/recipes?userId=
  router.get("/", async (req, res, next) => {
    try {
      const userId = parseInt(req.query.userId, 10);
      if (!userId || userId <= 0) throw new ApiError(400, "INVALID_REQUEST", "userId 参数无效");

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      let query, params;
      if (relationshipId) {
        query = `SELECT r.recipe_id, r.user_id, r.title, r.description, r.image_url, r.steps,
                 r.created_at, r.updated_at,
                 (SELECT COUNT(*) FROM recipe_ingredients ri WHERE ri.recipe_id = r.recipe_id) AS ingredientCount
                 FROM recipes r
                 WHERE r.relationship_id = ? OR (r.user_id = ? AND r.relationship_id IS NULL)
                 ORDER BY r.updated_at DESC`;
        params = [relationshipId, userId];
      } else {
        query = `SELECT r.recipe_id, r.user_id, r.title, r.description, r.image_url, r.steps,
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
        title: r.title,
        description: r.description,
        imageUrl: r.image_url,
        steps: r.steps,
        ingredientCount: r.ingredientCount,
        createdAt: r.created_at,
        updatedAt: r.updated_at
      }));

      res.json({ ok: true, data: { items, relationshipId } });
    } catch (error) { next(error); }
  });

  // GET /api/recipes/:id?userId=
  router.get("/:id", async (req, res, next) => {
    try {
      const userId = parseInt(req.query.userId, 10);
      const recipeId = parseInt(req.params.id, 10);
      if (!userId || !recipeId) throw new ApiError(400, "INVALID_REQUEST", "参数无效");

      const [rows] = await pool.execute(
        `SELECT recipe_id, user_id, title, description, image_url, steps, created_at, updated_at
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
      const { userId, title, description, imageUrl, steps, ingredients } = req.body;
      if (!userId || !title) throw new ApiError(400, "INVALID_REQUEST", "userId 和 title 必填");

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      const [result] = await pool.execute(
        `INSERT INTO recipes (user_id, relationship_id, title, description, image_url, steps)
         VALUES (?, ?, ?, ?, ?, ?)`,
        [userId, relationshipId, trimValue(title), description || null, imageUrl || null, steps || null]
      );

      const recipeId = result.insertId;

      if (Array.isArray(ingredients) && ingredients.length > 0) {
        for (const ing of ingredients) {
          await pool.execute(
            `INSERT INTO recipe_ingredients (recipe_id, inventory_id, ingredient_name, quantity, unit)
             VALUES (?, ?, ?, ?, ?)`,
            [recipeId, ing.inventoryId || null, trimValue(ing.ingredientName), ing.quantity || 0, ing.unit || "个"]
          );
        }
      }

      res.json({ ok: true, data: { recipeId } });
    } catch (error) { next(error); }
  });

  // PUT /api/recipes/:id
  router.put("/:id", async (req, res, next) => {
    try {
      const recipeId = parseInt(req.params.id, 10);
      const { userId, title, description, imageUrl, steps, ingredients } = req.body;
      if (!userId || !recipeId) throw new ApiError(400, "INVALID_REQUEST", "参数无效");

      await pool.execute(
        `UPDATE recipes SET title = ?, description = ?, image_url = ?, steps = ? WHERE recipe_id = ?`,
        [trimValue(title), description || null, imageUrl || null, steps || null, recipeId]
      );

      // Replace ingredients
      await pool.execute(`DELETE FROM recipe_ingredients WHERE recipe_id = ?`, [recipeId]);
      if (Array.isArray(ingredients) && ingredients.length > 0) {
        for (const ing of ingredients) {
          await pool.execute(
            `INSERT INTO recipe_ingredients (recipe_id, inventory_id, ingredient_name, quantity, unit)
             VALUES (?, ?, ?, ?, ?)`,
            [recipeId, ing.inventoryId || null, trimValue(ing.ingredientName), ing.quantity || 0, ing.unit || "个"]
          );
        }
      }

      res.json({ ok: true, message: "菜谱更新成功" });
    } catch (error) { next(error); }
  });

  // DELETE /api/recipes/:id?userId=
  router.delete("/:id", async (req, res, next) => {
    try {
      const recipeId = parseInt(req.params.id, 10);
      const userId = parseInt(req.query.userId, 10);
      if (!recipeId || !userId) throw new ApiError(400, "INVALID_REQUEST", "参数无效");

      const [result] = await pool.execute(`DELETE FROM recipes WHERE recipe_id = ? AND user_id = ?`, [recipeId, userId]);
      if (result.affectedRows === 0) throw new ApiError(404, "NOT_FOUND", "菜谱不存在或无权删除");

      res.json({ ok: true, message: "菜谱删除成功" });
    } catch (error) { next(error); }
  });

  // POST /api/recipes/:id/cook — consume linked inventory items
  router.post("/:id/cook", async (req, res, next) => {
    try {
      const recipeId = parseInt(req.params.id, 10);
      const userId = parseInt(req.body.userId, 10);
      if (!recipeId || !userId) throw new ApiError(400, "INVALID_REQUEST", "参数无效");

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

      res.json({ ok: true, data: { results, warnings } });
    } catch (error) { next(error); }
  });

  return router;
}

module.exports = { createRecipeRouter };
