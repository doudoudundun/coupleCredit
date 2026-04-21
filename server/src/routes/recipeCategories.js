const express = require("express");
const { ApiError } = require("../errors");
const { cache, Keys, TTL } = require("../cache");
const { loadActiveRelationship, trimValue } = require("../utils/queryHelpers");

function createRecipeCategoryRouter({ pool }) {
  const router = express.Router();

  function invalidateCategoryCache(userId, relationship) {
    cache.del(Keys.recipeCategories(userId));
    if (relationship) {
      cache.del(Keys.recipeCategories(relationship.user_id_1));
      cache.del(Keys.recipeCategories(relationship.user_id_2));
    }
  }

  router.get("/", async (req, res, next) => {
    try {
      const userId = parseInt(req.query.userId, 10);
      if (!userId || userId <= 0) throw new ApiError(400, "INVALID_REQUEST", "userId 参数无效");

      const cached = cache.get(Keys.recipeCategories(userId));
      if (cached) return res.json(cached);

      const relationship = await loadActiveRelationship(pool, userId);
      if (!relationship) {
        const empty = { ok: true, data: { items: [] } };
        cache.set(Keys.recipeCategories(userId), empty, TTL.RECIPE_CATS);
        return res.json(empty);
      }

      const [rows] = await pool.execute(
        `SELECT category_id, relationship_id, name, sort_order, created_at
         FROM recipe_categories
         WHERE relationship_id = ?
         ORDER BY sort_order ASC, created_at ASC`,
        [relationship.relationship_id]
      );

      const items = rows.map(r => ({
        categoryId: r.category_id,
        relationshipId: r.relationship_id,
        name: r.name,
        sortOrder: r.sort_order,
        createdAt: r.created_at
      }));

      const responseData = { ok: true, data: { items } };
      cache.set(Keys.recipeCategories(userId), responseData, TTL.RECIPE_CATS);
      res.json(responseData);
    } catch (error) { next(error); }
  });

  router.post("/", async (req, res, next) => {
    try {
      const { userId, name, sortOrder } = req.body;
      if (!userId || !name) throw new ApiError(400, "INVALID_REQUEST", "userId 和 name 必填");

      const relationship = await loadActiveRelationship(pool, userId);
      if (!relationship) throw new ApiError(400, "NO_RELATIONSHIP", "需要情侣关系才能创建种类");

      const [result] = await pool.execute(
        `INSERT INTO recipe_categories (relationship_id, name, sort_order) VALUES (?, ?, ?)`,
        [relationship.relationship_id, trimValue(name), sortOrder || 0]
      );

      invalidateCategoryCache(userId, relationship);
      res.json({ ok: true, data: { categoryId: result.insertId } });
    } catch (error) {
      if (error.code === "ER_DUP_ENTRY") {
        return next(new ApiError(409, "DUPLICATE", "该种类名称已存在"));
      }
      next(error);
    }
  });

  router.put("/reorder/all", async (req, res, next) => {
    const connection = await pool.getConnection();
    try {
      const userId = parseInt(req.body.userId, 10);
      const orderedCategoryIds = Array.isArray(req.body.orderedCategoryIds) ? req.body.orderedCategoryIds : [];
      if (!userId || orderedCategoryIds.length === 0) {
        throw new ApiError(400, "INVALID_REQUEST", "userId 和 orderedCategoryIds 必填");
      }

      const relationship = await loadActiveRelationship(pool, userId);
      if (!relationship) throw new ApiError(403, "FORBIDDEN", "无权操作");

      const [rows] = await connection.execute(
        `SELECT category_id FROM recipe_categories WHERE relationship_id = ?`,
        [relationship.relationship_id]
      );
      const existingIds = rows.map(row => row.category_id).sort((a, b) => a - b);
      const requestIds = orderedCategoryIds.map(id => Number(id)).sort((a, b) => a - b);
      if (existingIds.length !== requestIds.length || existingIds.some((id, index) => id !== requestIds[index])) {
        throw new ApiError(400, "INVALID_REQUEST", "分类顺序数据不完整");
      }

      await connection.beginTransaction();
      for (let i = 0; i < orderedCategoryIds.length; i++) {
        await connection.execute(
          `UPDATE recipe_categories SET sort_order = ? WHERE category_id = ? AND relationship_id = ?`,
          [i, orderedCategoryIds[i], relationship.relationship_id]
        );
      }
      await connection.commit();

      invalidateCategoryCache(userId, relationship);
      cache.del(Keys.recipes(userId));
      if (relationship) {
        cache.del(Keys.recipes(relationship.user_id_1));
        cache.del(Keys.recipes(relationship.user_id_2));
      }
      res.json({ ok: true, message: "种类排序更新成功" });
    } catch (error) {
      await connection.rollback();
      next(error);
    } finally {
      connection.release();
    }
  });

  router.put("/:id", async (req, res, next) => {
    try {
      const categoryId = parseInt(req.params.id, 10);
      const { userId, name, sortOrder } = req.body;
      if (!categoryId || !userId) throw new ApiError(400, "INVALID_REQUEST", "参数无效");

      const relationship = await loadActiveRelationship(pool, userId);
      if (!relationship) throw new ApiError(403, "FORBIDDEN", "无权操作");

      const sets = [];
      const params = [];
      if (name !== undefined) { sets.push("name = ?"); params.push(trimValue(name)); }
      if (sortOrder !== undefined) { sets.push("sort_order = ?"); params.push(sortOrder); }
      if (sets.length === 0) throw new ApiError(400, "INVALID_REQUEST", "无更新内容");

      params.push(categoryId, relationship.relationship_id);
      await pool.execute(
        `UPDATE recipe_categories SET ${sets.join(", ")} WHERE category_id = ? AND relationship_id = ?`,
        params
      );

      invalidateCategoryCache(userId, relationship);
      res.json({ ok: true, message: "种类更新成功" });
    } catch (error) { next(error); }
  });

  router.delete("/:id", async (req, res, next) => {
    try {
      const categoryId = parseInt(req.params.id, 10);
      const userId = parseInt(req.query.userId, 10);
      if (!categoryId || !userId) throw new ApiError(400, "INVALID_REQUEST", "参数无效");

      const relationship = await loadActiveRelationship(pool, userId);
      if (!relationship) throw new ApiError(403, "FORBIDDEN", "无权操作");

      const [result] = await pool.execute(
        `DELETE FROM recipe_categories WHERE category_id = ? AND relationship_id = ?`,
        [categoryId, relationship.relationship_id]
      );
      if (result.affectedRows === 0) throw new ApiError(404, "NOT_FOUND", "种类不存在");

      invalidateCategoryCache(userId, relationship);
      res.json({ ok: true, message: "种类删除成功" });
    } catch (error) { next(error); }
  });

  return router;
}

module.exports = { createRecipeCategoryRouter };
