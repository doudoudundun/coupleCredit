const express = require("express");
const sharp = require("sharp");
const path = require("path");
const fsPromises = require("fs").promises;
const { ApiError } = require("../errors");
const { cache, Keys, TTL } = require("../cache");
const { loadActiveRelationship, trimValue, parseRequiredInteger, normalizeNullableText, invalidateForUser } = require("../utils/queryHelpers");

const ASSET_SELECT_FIELDS = `asset_id as assetId, user_id as userId, relationship_id as relationshipId,
    name, category, image_url as imageUrl, original_image_url as originalImageUrl,
    DATE_FORMAT(purchase_date, '%Y-%m-%d') as purchaseDate,
    purchase_price as purchasePrice, current_value as currentValue,
    status, note, disposed_at as disposedAt,
    created_at as createdAt, updated_at as updatedAt`;

const DEFAULT_CATEGORIES = [
  '电子设备', '衣物', '包包', '鞋履', '家具', '配饰', '美妆', '书籍', '其他'
];

function createAssetsRouter({ pool }) {
  const router = express.Router();

  function invalidateAssetsCache(userId, relationship) {
    invalidateForUser(cache, Keys.assets, userId, relationship);
    invalidateForUser(cache, Keys.assetStats, userId, relationship);
    invalidateForUser(cache, Keys.assetCategories, userId, relationship);
  }

  // GET /api/assets - List assets
  router.get("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(parseInt(req.query.userId, 10));
      const category = req.query.category ? trimValue(req.query.category) : null;
      const status = req.query.status ? trimValue(req.query.status) : null;

      const cacheKey = Keys.assets(userId) + (category ? `:${category}` : '') + (status ? `:${status}` : '');
      const cached = cache.get(cacheKey);
      if (cached) return res.json(cached);

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      let query;
      let params = [];

      if (relationshipId) {
        query = `SELECT ${ASSET_SELECT_FIELDS} FROM assets WHERE (relationship_id = ? OR (user_id = ? AND relationship_id IS NULL))`;
        params = [relationshipId, userId];
      } else {
        query = `SELECT ${ASSET_SELECT_FIELDS} FROM assets WHERE user_id = ? AND relationship_id IS NULL`;
        params = [userId];
      }

      if (category) {
        query += " AND category = ?";
        params.push(category);
      }
      if (status) {
        query += " AND status = ?";
        params.push(status);
      }

      query += " ORDER BY created_at DESC";

      const [rows] = await pool.execute(query, params);

      const now = new Date();
      const items = rows.map(row => {
        const purchaseDate = row.purchaseDate ? new Date(row.purchaseDate) : null;
        const holdDays = purchaseDate ? Math.floor((now - purchaseDate) / (1000 * 60 * 60 * 24)) : 0;
        const dailyCost = (holdDays > 0 && row.purchasePrice) ? row.purchasePrice / holdDays : 0;
        const monthlyCost = dailyCost * 30;

        return {
          ...row,
          holdDays,
          dailyCost: Math.round(dailyCost * 100) / 100,
          monthlyCost: Math.round(monthlyCost * 100) / 100
        };
      });

      const responseData = {
        ok: true,
        message: "查询成功",
        data: { items, relationshipId }
      };
      cache.set(cacheKey, responseData, TTL.ASSETS);
      res.json(responseData);
    } catch (error) {
      next(error);
    }
  });

  // GET /api/assets/stats - Asset statistics
  router.get("/stats", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(parseInt(req.query.userId, 10));

      const cached = cache.get(Keys.assetStats(userId));
      if (cached) return res.json(cached);

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      let query;
      let params = [];

      if (relationshipId) {
        query = `SELECT ${ASSET_SELECT_FIELDS} FROM assets WHERE relationship_id = ? OR (user_id = ? AND relationship_id IS NULL)`;
        params = [relationshipId, userId];
      } else {
        query = `SELECT ${ASSET_SELECT_FIELDS} FROM assets WHERE user_id = ? AND relationship_id IS NULL`;
        params = [userId];
      }

      const [rows] = await pool.execute(query, params);

      const now = new Date();
      let totalValue = 0;
      let totalCount = rows.length;
      let categoryBreakdown = {};
      let statusBreakdown = { active: 0, idle: 0, disposed: 0 };
      let latestItem = null;
      let totalDailyCost = 0;

      rows.forEach(row => {
        const purchaseDate = row.purchaseDate ? new Date(row.purchaseDate) : null;
        const holdDays = purchaseDate ? Math.floor((now - purchaseDate) / (1000 * 60 * 60 * 24)) : 0;
        const dailyCost = (holdDays > 0 && row.purchasePrice) ? row.purchasePrice / holdDays : 0;

        if (row.status === 'active' || row.status === 'idle') {
          totalValue += Number(row.purchasePrice) || 0;
        }
        totalDailyCost += dailyCost;

        if (!categoryBreakdown[row.category]) {
          categoryBreakdown[row.category] = { count: 0, totalValue: 0 };
        }
        categoryBreakdown[row.category].count++;
        categoryBreakdown[row.category].totalValue += Number(row.purchasePrice) || 0;

        if (row.status) {
          statusBreakdown[row.status] = (statusBreakdown[row.status] || 0) + 1;
        }

        if (!latestItem || new Date(row.createdAt) > new Date(latestItem.createdAt)) {
          latestItem = row;
        }
      });

      const dailyAvgCost = totalCount > 0 ? totalDailyCost / totalCount : 0;
      const monthlyAvgCost = dailyAvgCost * 30;

      const responseData = {
        ok: true,
        message: "查询成功",
        data: {
          totalValue: Math.round(totalValue * 100) / 100,
          totalCount,
          dailyAvgCost: Math.round(dailyAvgCost * 100) / 100,
          monthlyAvgCost: Math.round(monthlyAvgCost * 100) / 100,
          categoryBreakdown: Object.entries(categoryBreakdown).map(([category, data]) => ({
            category,
            count: data.count,
            totalValue: Math.round(data.totalValue * 100) / 100
          })),
          statusBreakdown,
          latestItem
        }
      };

      cache.set(Keys.assetStats(userId), responseData, TTL.ASSETS);
      res.json(responseData);
    } catch (error) {
      next(error);
    }
  });

  // GET /api/assets/categories - List categories
  router.get("/categories", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(parseInt(req.query.userId, 10));

      const cached = cache.get(Keys.assetCategories(userId));
      if (cached) return res.json(cached);

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      let query;
      let params = [];

      if (relationshipId) {
        query = `SELECT name, is_default as isDefault FROM asset_categories
                 WHERE is_default = TRUE OR (relationship_id = ? OR (user_id = ? AND relationship_id IS NULL))
                 ORDER BY is_default DESC, created_at ASC`;
        params = [relationshipId, userId];
      } else {
        query = `SELECT name, is_default as isDefault FROM asset_categories
                 WHERE is_default = TRUE OR (user_id = ? AND relationship_id IS NULL)
                 ORDER BY is_default DESC, created_at ASC`;
        params = [userId];
      }

      const [rows] = await pool.execute(query, params);

      const responseData = {
        ok: true,
        message: "查询成功",
        data: { categories: rows.map(r => r.name) }
      };

      cache.set(Keys.assetCategories(userId), responseData, TTL.ASSET_CATS);
      res.json(responseData);
    } catch (error) {
      next(error);
    }
  });

  // POST /api/assets/categories - Add custom category
  router.post("/categories", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.body.userId);
      const name = trimValue(req.body.name);

      if (!name) {
        throw new ApiError(400, "INVALID_REQUEST", "分类名称不能为空");
      }

      if (DEFAULT_CATEGORIES.includes(name)) {
        throw new ApiError(400, "INVALID_REQUEST", "该分类已存在");
      }

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      await pool.execute(
        "INSERT INTO asset_categories (user_id, relationship_id, name, is_default) VALUES (?, ?, ?, FALSE)",
        [userId, relationshipId, name]
      );

      invalidateAssetsCache(userId, relationship);
      res.status(201).json({
        ok: true,
        message: "分类添加成功",
        data: { name }
      });
    } catch (error) {
      if (error.code === 'ER_DUP_ENTRY') {
        return res.status(400).json({
          ok: false,
          error: { code: "DUPLICATE", message: "该分类已存在" }
        });
      }
      next(error);
    }
  });

  // POST /api/assets - Create asset
  router.post("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.body.userId);
      const name = trimValue(req.body.name);
      const category = trimValue(req.body.category);

      if (!name || !category) {
        throw new ApiError(400, "INVALID_REQUEST", "资产名称和分类不能为空");
      }

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;
      const imageUrl = normalizeNullableText(req.body.imageUrl);
      const originalImageUrl = normalizeNullableText(req.body.originalImageUrl);
      const purchaseDate = normalizeNullableText(req.body.purchaseDate);
      const purchasePrice = req.body.purchasePrice != null ? Number(req.body.purchasePrice) : null;
      const currentValue = req.body.currentValue != null ? Number(req.body.currentValue) : null;
      const status = req.body.status || 'active';
      const note = normalizeNullableText(req.body.note);

      const [result] = await pool.execute(
        `INSERT INTO assets (user_id, relationship_id, name, category, image_url, original_image_url,
         purchase_date, purchase_price, current_value, status, note)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        [userId, relationshipId, name, category, imageUrl, originalImageUrl,
         purchaseDate, purchasePrice, currentValue, status, note]
      );

      invalidateAssetsCache(userId, relationship);
      res.status(201).json({
        ok: true,
        message: "资产添加成功",
        data: { assetId: result.insertId }
      });
    } catch (error) {
      next(error);
    }
  });

  // GET /api/assets/:id - Get single asset
  router.get("/:id", async (req, res, next) => {
    try {
      const assetId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(parseInt(req.query.userId, 10));

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      let query;
      let params;

      if (relationshipId) {
        query = `SELECT ${ASSET_SELECT_FIELDS} FROM assets WHERE asset_id = ? AND (relationship_id = ? OR (user_id = ? AND relationship_id IS NULL))`;
        params = [assetId, relationshipId, userId];
      } else {
        query = `SELECT ${ASSET_SELECT_FIELDS} FROM assets WHERE asset_id = ? AND user_id = ? AND relationship_id IS NULL`;
        params = [assetId, userId];
      }

      const [rows] = await pool.execute(query, params);
      if (rows.length === 0) {
        throw new ApiError(404, "NOT_FOUND", "资产不存在");
      }

      const row = rows[0];
      const now = new Date();
      const purchaseDate = row.purchaseDate ? new Date(row.purchaseDate) : null;
      const holdDays = purchaseDate ? Math.floor((now - purchaseDate) / (1000 * 60 * 60 * 24)) : 0;
      const dailyCost = (holdDays > 0 && row.purchasePrice) ? row.purchasePrice / holdDays : 0;
      const monthlyCost = dailyCost * 30;

      res.json({
        ok: true,
        message: "查询成功",
        data: {
          ...row,
          holdDays,
          dailyCost: Math.round(dailyCost * 100) / 100,
          monthlyCost: Math.round(monthlyCost * 100) / 100
        }
      });
    } catch (error) {
      next(error);
    }
  });

  // PUT /api/assets/:id - Update asset
  router.put("/:id", async (req, res, next) => {
    try {
      const assetId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(req.body.userId);

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      let checkQuery;
      let checkParams;

      if (relationshipId) {
        checkQuery = "SELECT asset_id FROM assets WHERE asset_id = ? AND (relationship_id = ? OR (user_id = ? AND relationship_id IS NULL))";
        checkParams = [assetId, relationshipId, userId];
      } else {
        checkQuery = "SELECT asset_id FROM assets WHERE asset_id = ? AND user_id = ? AND relationship_id IS NULL";
        checkParams = [assetId, userId];
      }

      const [existing] = await pool.execute(checkQuery, checkParams);
      if (existing.length === 0) {
        throw new ApiError(404, "NOT_FOUND", "资产不存在或无权修改");
      }

      const updates = [];
      const params = [];

      if (req.body.name !== undefined) {
        const name = trimValue(req.body.name);
        if (!name) throw new ApiError(400, "INVALID_REQUEST", "资产名称不能为空");
        updates.push("name = ?");
        params.push(name);
      }

      if (req.body.category !== undefined) {
        const category = trimValue(req.body.category);
        if (!category) throw new ApiError(400, "INVALID_REQUEST", "分类不能为空");
        updates.push("category = ?");
        params.push(category);
      }

      if (req.body.imageUrl !== undefined) {
        updates.push("image_url = ?");
        params.push(normalizeNullableText(req.body.imageUrl));
      }

      if (req.body.purchaseDate !== undefined) {
        updates.push("purchase_date = ?");
        params.push(normalizeNullableText(req.body.purchaseDate));
      }

      if (req.body.purchasePrice !== undefined) {
        updates.push("purchase_price = ?");
        params.push(req.body.purchasePrice != null ? Number(req.body.purchasePrice) : null);
      }

      if (req.body.currentValue !== undefined) {
        updates.push("current_value = ?");
        params.push(req.body.currentValue != null ? Number(req.body.currentValue) : null);
      }

      if (req.body.note !== undefined) {
        updates.push("note = ?");
        params.push(normalizeNullableText(req.body.note));
      }

      if (updates.length === 0) {
        throw new ApiError(400, "INVALID_REQUEST", "没有提供要更新的字段");
      }

      updates.push("updated_at = NOW()");
      params.push(assetId);

      await pool.execute(`UPDATE assets SET ${updates.join(", ")} WHERE asset_id = ?`, params);

      invalidateAssetsCache(userId, relationship);
      res.json({ ok: true, message: "资产更新成功" });
    } catch (error) {
      next(error);
    }
  });

  // PATCH /api/assets/:id/status - Update status
  router.patch("/:id/status", async (req, res, next) => {
    try {
      const assetId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(req.body.userId);
      const status = trimValue(req.body.status);

      if (!['active', 'idle', 'disposed'].includes(status)) {
        throw new ApiError(400, "INVALID_REQUEST", "状态值不正确");
      }

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      let checkQuery;
      let checkParams;

      if (relationshipId) {
        checkQuery = "SELECT asset_id FROM assets WHERE asset_id = ? AND (relationship_id = ? OR (user_id = ? AND relationship_id IS NULL))";
        checkParams = [assetId, relationshipId, userId];
      } else {
        checkQuery = "SELECT asset_id FROM assets WHERE asset_id = ? AND user_id = ? AND relationship_id IS NULL";
        checkParams = [assetId, userId];
      }

      const [existing] = await pool.execute(checkQuery, checkParams);
      if (existing.length === 0) {
        throw new ApiError(404, "NOT_FOUND", "资产不存在或无权修改");
      }

      if (status === 'disposed') {
        await pool.execute(
          "UPDATE assets SET status = ?, disposed_at = NOW(), updated_at = NOW() WHERE asset_id = ?",
          [status, assetId]
        );
      } else {
        await pool.execute(
          "UPDATE assets SET status = ?, disposed_at = NULL, updated_at = NOW() WHERE asset_id = ?",
          [status, assetId]
        );
      }

      invalidateAssetsCache(userId, relationship);
      res.json({ ok: true, message: "状态更新成功" });
    } catch (error) {
      next(error);
    }
  });

  // DELETE /api/assets/:id - Delete asset
  router.delete("/:id", async (req, res, next) => {
    try {
      const assetId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(parseInt(req.query.userId, 10));

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      let deleteQuery;
      let deleteParams;

      if (relationshipId) {
        deleteQuery = "DELETE FROM assets WHERE asset_id = ? AND (relationship_id = ? OR (user_id = ? AND relationship_id IS NULL))";
        deleteParams = [assetId, relationshipId, userId];
      } else {
        deleteQuery = "DELETE FROM assets WHERE asset_id = ? AND user_id = ? AND relationship_id IS NULL";
        deleteParams = [assetId, userId];
      }

      const [result] = await pool.execute(deleteQuery, deleteParams);
      if (result.affectedRows === 0) {
        throw new ApiError(404, "NOT_FOUND", "资产不存在或无权删除");
      }

      invalidateAssetsCache(userId, relationship);
      res.json({ ok: true, message: "资产删除成功" });
    } catch (error) {
      next(error);
    }
  });

  // POST /api/assets/remove-bg - Remove background from image
  router.post("/remove-bg", async (req, res, next) => {
    try {
      const { imageUrl } = req.body;
      if (!imageUrl) {
        throw new ApiError(400, "INVALID_REQUEST", "请提供图片URL");
      }

      const imagePath = path.resolve(__dirname, "../../uploads", path.basename(imageUrl));
      const outputFilename = `nobg_${Date.now()}.png`;
      const outputPath = path.resolve(__dirname, "../../uploads", outputFilename);

      try {
        await fsPromises.access(imagePath);
      } catch {
        throw new ApiError(404, "NOT_FOUND", "图片文件不存在");
      }

      const image = sharp(imagePath);
      const metadata = await image.metadata();

      const { data, info } = await image
        .ensureAlpha()
        .raw()
        .toBuffer({ resolveWithObject: true });

      const pixels = Buffer.alloc(data.length);
      for (let i = 0; i < data.length; i += info.channels) {
        const r = data[i];
        const g = data[i + 1];
        const b = data[i + 2];
        const a = info.channels === 4 ? data[i + 3] : 255;

        const isLight = r > 200 && g > 200 && b > 200;
        const isWhiteish = Math.abs(r - g) < 30 && Math.abs(g - b) < 30 && Math.abs(r - b) < 30;

        if (isLight && isWhiteish) {
          pixels[i] = 0;
          pixels[i + 1] = 0;
          pixels[i + 2] = 0;
          pixels[i + 3] = 0;
        } else {
          pixels[i] = r;
          pixels[i + 1] = g;
          pixels[i + 2] = b;
          pixels[i + 3] = a;
        }
      }

      await sharp(pixels, {
        raw: {
          width: info.width,
          height: info.height,
          channels: 4
        }
      })
        .png()
        .toFile(outputPath);

      res.json({
        ok: true,
        message: "抠图成功",
        data: { imageUrl: `/uploads/${outputFilename}` }
      });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createAssetsRouter };
