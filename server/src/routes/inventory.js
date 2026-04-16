const express = require("express");
const { ApiError } = require("../errors");
const { loadActiveRelationship, trimValue, parseRequiredInteger, parseRequiredFloat } = require("../utils/queryHelpers");

function normalizeNullableText(value) {
  if (value === undefined) {
    return undefined;
  }
  if (value === null) {
    return null;
  }
  if (typeof value !== "string") {
    return null;
  }
  const trimmed = value.trim();
  return trimmed === "" ? null : trimmed;
}

function createInventoryRouter({ pool }) {
  const router = express.Router();

  router.get("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(parseInt(req.query.userId, 10));
      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      let query;
      let params;

      if (relationshipId) {
        query = `SELECT inventory_id as inventoryId, user_id as userId, relationship_id as relationshipId,
                 name, category, image_url as imageUrl, quantity, unit, threshold,
                 created_at as createdAt, updated_at as updatedAt, last_consumed_at as lastConsumedAt,
                 note, ai_image_prompt as aiImagePrompt
                 FROM inventory WHERE relationship_id = ? OR (user_id = ? AND relationship_id IS NULL) ORDER BY updated_at DESC`;
        params = [relationshipId, userId];
      } else {
        query = `SELECT inventory_id as inventoryId, user_id as userId, relationship_id as relationshipId,
                 name, category, image_url as imageUrl, quantity, unit, threshold,
                 created_at as createdAt, updated_at as updatedAt, last_consumed_at as lastConsumedAt,
                 note, ai_image_prompt as aiImagePrompt
                 FROM inventory WHERE user_id = ? AND relationship_id IS NULL ORDER BY updated_at DESC`;
        params = [userId];
      }

      const [rows] = await pool.execute(query, params);
      const items = rows.map(row => ({
        ...row,
        isLowStock: row.quantity <= row.threshold
      }));

      res.json({
        ok: true,
        message: "查询成功",
        data: {
          items,
          relationshipId
        }
      });
    } catch (error) {
      next(error);
    }
  });

  router.get("/low-stock", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(parseInt(req.query.userId, 10));
      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      let query;
      let params;

      if (relationshipId) {
        query = `SELECT inventory_id as inventoryId, user_id as userId, relationship_id as relationshipId,
                 name, category, image_url as imageUrl, quantity, unit, threshold,
                 created_at as createdAt, updated_at as updatedAt, last_consumed_at as lastConsumedAt,
                 note, ai_image_prompt as aiImagePrompt
                 FROM inventory WHERE (relationship_id = ? OR (user_id = ? AND relationship_id IS NULL)) AND quantity <= threshold ORDER BY quantity ASC`;
        params = [relationshipId, userId];
      } else {
        query = `SELECT inventory_id as inventoryId, user_id as userId, relationship_id as relationshipId,
                 name, category, image_url as imageUrl, quantity, unit, threshold,
                 created_at as createdAt, updated_at as updatedAt, last_consumed_at as lastConsumedAt,
                 note, ai_image_prompt as aiImagePrompt
                 FROM inventory WHERE user_id = ? AND relationship_id IS NULL AND quantity <= threshold ORDER BY quantity ASC`;
        params = [userId];
      }

      const [rows] = await pool.execute(query, params);
      const items = rows.map(row => ({
        ...row,
        isLowStock: row.quantity <= row.threshold
      }));

      res.json({
        ok: true,
        message: "查询成功",
        data: {
          items,
          count: items.length,
          relationshipId
        }
      });
    } catch (error) {
      next(error);
    }
  });

  router.post("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.body.userId);
      const name = trimValue(req.body.name);
      const category = trimValue(req.body.category);
      const quantity = parseRequiredFloat(req.body.quantity);
      const unit = trimValue(req.body.unit);
      const threshold = parseRequiredFloat(req.body.threshold ?? 1);

      if (!name || !category || !unit) {
        throw new ApiError(400, "INVALID_REQUEST", "存货名称、类别和单位不能为空");
      }

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;
      const imageUrl = normalizeNullableText(req.body.imageUrl);
      const note = normalizeNullableText(req.body.note);
      const aiImagePrompt = normalizeNullableText(req.body.aiImagePrompt);

      const [result] = await pool.execute(
        `INSERT INTO inventory (user_id, relationship_id, name, category, image_url, quantity, unit, threshold, note, ai_image_prompt, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())`,
        [userId, relationshipId, name, category, imageUrl, quantity, unit, threshold, note, aiImagePrompt]
      );

      res.status(201).json({
        ok: true,
        message: "存货添加成功",
        data: {
          inventoryId: result.insertId,
          userId,
          relationshipId,
          name,
          category,
          imageUrl,
          quantity,
          unit,
          threshold,
          note,
          aiImagePrompt
        }
      });
    } catch (error) {
      next(error);
    }
  });

  router.put("/:id", async (req, res, next) => {
    try {
      const inventoryId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(req.body.userId);
      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      let checkQuery;
      let checkParams;

      if (relationshipId) {
        checkQuery = "SELECT inventory_id FROM inventory WHERE inventory_id = ? AND (relationship_id = ? OR (user_id = ? AND relationship_id IS NULL))";
        checkParams = [inventoryId, relationshipId, userId];
      } else {
        checkQuery = "SELECT inventory_id FROM inventory WHERE inventory_id = ? AND user_id = ? AND relationship_id IS NULL";
        checkParams = [inventoryId, userId];
      }

      const [existing] = await pool.execute(checkQuery, checkParams);
      if (existing.length === 0) {
        throw new ApiError(404, "NOT_FOUND", "存货不存在或无权修改");
      }

      const updates = [];
      const params = [];

      if (req.body.name !== undefined) {
        const name = trimValue(req.body.name);
        if (!name) {
          throw new ApiError(400, "INVALID_REQUEST", "存货名称不能为空");
        }
        updates.push("name = ?");
        params.push(name);
      }

      if (req.body.category !== undefined) {
        const category = trimValue(req.body.category);
        if (!category) {
          throw new ApiError(400, "INVALID_REQUEST", "类别不能为空");
        }
        updates.push("category = ?");
        params.push(category);
      }

      if (req.body.imageUrl !== undefined) {
        updates.push("image_url = ?");
        params.push(normalizeNullableText(req.body.imageUrl));
      }

      if (req.body.quantity !== undefined) {
        updates.push("quantity = ?");
        params.push(parseRequiredFloat(req.body.quantity));
      }

      if (req.body.unit !== undefined) {
        const unit = trimValue(req.body.unit);
        if (!unit) {
          throw new ApiError(400, "INVALID_REQUEST", "单位不能为空");
        }
        updates.push("unit = ?");
        params.push(unit);
      }

      if (req.body.threshold !== undefined) {
        updates.push("threshold = ?");
        params.push(parseRequiredFloat(req.body.threshold));
      }

      if (req.body.note !== undefined) {
        updates.push("note = ?");
        params.push(normalizeNullableText(req.body.note));
      }

      if (req.body.aiImagePrompt !== undefined) {
        updates.push("ai_image_prompt = ?");
        params.push(normalizeNullableText(req.body.aiImagePrompt));
      }

      if (updates.length === 0) {
        throw new ApiError(400, "INVALID_REQUEST", "没有提供要更新的字段");
      }

      updates.push("updated_at = NOW()");
      params.push(inventoryId);

      await pool.execute(`UPDATE inventory SET ${updates.join(", ")} WHERE inventory_id = ?`, params);

      res.json({ ok: true, message: "存货更新成功" });
    } catch (error) {
      next(error);
    }
  });

  router.post("/:id/consume", async (req, res, next) => {
    try {
      const inventoryId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(req.body.userId);
      const consumeAmount = parseRequiredFloat(req.body.consumeAmount);

      if (consumeAmount <= 0) {
        throw new ApiError(400, "INVALID_REQUEST", "消耗数量必须大于0");
      }

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      let checkQuery;
      let checkParams;

      if (relationshipId) {
        checkQuery = "SELECT quantity FROM inventory WHERE inventory_id = ? AND (relationship_id = ? OR (user_id = ? AND relationship_id IS NULL))";
        checkParams = [inventoryId, relationshipId, userId];
      } else {
        checkQuery = "SELECT quantity FROM inventory WHERE inventory_id = ? AND user_id = ? AND relationship_id IS NULL";
        checkParams = [inventoryId, userId];
      }

      const [existing] = await pool.execute(checkQuery, checkParams);
      if (existing.length === 0) {
        throw new ApiError(404, "NOT_FOUND", "存货不存在或无权操作");
      }

      const currentQuantity = existing[0].quantity;
      if (currentQuantity < consumeAmount) {
        throw new ApiError(400, "INSUFFICIENT_STOCK", `存量不足，当前存量: ${currentQuantity}`);
      }

      await pool.execute(
        "UPDATE inventory SET quantity = quantity - ?, last_consumed_at = NOW(), updated_at = NOW() WHERE inventory_id = ?",
        [consumeAmount, inventoryId]
      );

      res.json({
        ok: true,
        message: "消耗记录成功",
        data: {
          previousQuantity: currentQuantity,
          consumed: consumeAmount,
          remainingQuantity: currentQuantity - consumeAmount
        }
      });
    } catch (error) {
      next(error);
    }
  });

  router.post("/:id/replenish", async (req, res, next) => {
    try {
      const inventoryId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(req.body.userId);
      const addAmount = parseRequiredFloat(req.body.addAmount);

      if (addAmount <= 0) {
        throw new ApiError(400, "INVALID_REQUEST", "补货数量必须大于0");
      }

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      let checkQuery;
      let checkParams;

      if (relationshipId) {
        checkQuery = "SELECT quantity FROM inventory WHERE inventory_id = ? AND (relationship_id = ? OR (user_id = ? AND relationship_id IS NULL))";
        checkParams = [inventoryId, relationshipId, userId];
      } else {
        checkQuery = "SELECT quantity FROM inventory WHERE inventory_id = ? AND user_id = ? AND relationship_id IS NULL";
        checkParams = [inventoryId, userId];
      }

      const [existing] = await pool.execute(checkQuery, checkParams);
      if (existing.length === 0) {
        throw new ApiError(404, "NOT_FOUND", "存货不存在或无权操作");
      }

      const currentQuantity = existing[0].quantity;
      await pool.execute(
        "UPDATE inventory SET quantity = quantity + ?, updated_at = NOW() WHERE inventory_id = ?",
        [addAmount, inventoryId]
      );

      res.json({
        ok: true,
        message: "补货成功",
        data: {
          previousQuantity: currentQuantity,
          added: addAmount,
          currentQuantity: currentQuantity + addAmount
        }
      });
    } catch (error) {
      next(error);
    }
  });

  router.delete("/:id", async (req, res, next) => {
    try {
      const inventoryId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(parseInt(req.query.userId, 10));
      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      let deleteQuery;
      let deleteParams;

      if (relationshipId) {
        deleteQuery = "DELETE FROM inventory WHERE inventory_id = ? AND (relationship_id = ? OR (user_id = ? AND relationship_id IS NULL))";
        deleteParams = [inventoryId, relationshipId, userId];
      } else {
        deleteQuery = "DELETE FROM inventory WHERE inventory_id = ? AND user_id = ? AND relationship_id IS NULL";
        deleteParams = [inventoryId, userId];
      }

      const [result] = await pool.execute(deleteQuery, deleteParams);
      if (result.affectedRows === 0) {
        throw new ApiError(404, "NOT_FOUND", "存货不存在或无权删除");
      }

      res.json({ ok: true, message: "存货删除成功" });
    } catch (error) {
      next(error);
    }
  });

  router.post("/generate-image", async (req, res, next) => {
    try {
      const prompt = trimValue(req.body.prompt);
      const category = trimValue(req.body.category);
      const name = trimValue(req.body.name);

      if (!prompt && !name) {
        throw new ApiError(400, "INVALID_REQUEST", "需要提供生成提示或物品名称");
      }

      const generatedPrompt = prompt || `${category} ${name} 产品图`;

      res.json({
        ok: true,
        message: "AI生图接口已预留，请配置生图服务",
        data: {
          prompt: generatedPrompt,
          imageUrl: null,
          note: "此接口为预留接口，需要配置具体的AI生图服务后才能使用"
        }
      });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createInventoryRouter };
