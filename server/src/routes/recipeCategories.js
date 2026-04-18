const express = require("express");
const { ApiError } = require("../errors");
const { loadActiveRelationship, trimValue } = require("../utils/queryHelpers");

function createRecipeCategoryRouter({ pool }) {
  const router = express.Router();

  // GET /api/recipe-categories?userId=
  router.get("/", async (req, res, next) => {
    try {
      const userId = parseInt(req.query.userId, 10);
      if (!userId || userId <= 0) throw new ApiError(400, "INVALID_REQUEST", "userId 参数无效");

      const relationship = await loadActiveRelationship(pool, userId);
      if (!relationship) {
        return res.json({ ok: true, data: { items: [] } });
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

      res.json({ ok: true, data: { items } });
    } catch (error) { next(error); }
  });

  // POST /api/recipe-categories
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

      res.json({ ok: true, data: { categoryId: result.insertId } });
    } catch (error) {
      if (error.code === "ER_DUP_ENTRY") {
        return next(new ApiError(409, "DUPLICATE", "该种类名称已存在"));
      }
      next(error);
    }
  });

  // PUT /api/recipe-categories/:id
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

      res.json({ ok: true, message: "种类更新成功" });
    } catch (error) { next(error); }
  });

  // DELETE /api/recipe-categories/:id?userId=
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

      res.json({ ok: true, message: "种类删除成功" });
    } catch (error) { next(error); }
  });

  return router;
}

module.exports = { createRecipeCategoryRouter };
