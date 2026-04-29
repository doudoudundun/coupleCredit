const express = require("express");
const https = require("https");
const http = require("http");
const { BEAD_COLORS } = require("../constants/beadColors");
const sharp = require("sharp");
const fs = require("fs");
const path = require("path");

const { ApiError } = require("../errors");
const { cache, Keys, TTL } = require("../cache");
const { trimValue, parseRequiredInteger, loadActiveRelationship } = require("../utils/queryHelpers");

const BEAD_COLOR_CODES = new Set(BEAD_COLORS.map(color => color.colorCode));
const DEFAULT_THRESHOLD = 200;
const MAX_DOWNLOAD_BYTES = 10 * 1024 * 1024;

const aiProviders = {
  primary: buildAiProviderConfig("AI", "https://open.bigmodel.cn/api/paas/v4", "glm-4v-flash"),
  fallback: buildAiProviderConfig("AI_FALLBACK", null, "gpt-5.5")
};

function resolveBeadThreshold(item, defaultThreshold) {
  return item.thresholdOverride === null || item.thresholdOverride === undefined
    ? Number(defaultThreshold)
    : Number(item.thresholdOverride);
}

function mapBeadInventoryRow(row) {
  return {
    colorCode: row.color_code,
    hexColor: row.hex_color,
    quantity: Number(row.quantity || 0),
    thresholdOverride: row.threshold_override === undefined ? null : row.threshold_override,
    defaultThreshold: Number(row.default_threshold || 0),
    totalConsumed: Number(row.total_consumed || 0),
    colorGroup: row.color_group,
    isTransparent: Boolean(row.is_transparent)
  };
}

function buildBeadSummary(items) {
  return {
    totalColors: items.length,
    lowStockCount: items.filter(item => item.quantity <= resolveBeadThreshold(item, item.defaultThreshold)).length,
    totalConsumptionReference: items.reduce((sum, item) => sum + Number(item.totalConsumed || 0), 0)
  };
}

function parseNonNegativeInteger(value, label) {
  const parsed = typeof value === "number" ? value : Number(value);
  if (!Number.isInteger(parsed) || parsed < 0) {
    throw new ApiError(400, "INVALID_REQUEST", `${label}格式不正确`);
  }
  return parsed;
}

function parsePositiveInteger(value, label) {
  const parsed = typeof value === "number" ? value : Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new ApiError(400, "INVALID_REQUEST", `${label}格式不正确`);
  }
  return parsed;
}

function parseOptionalThreshold(value) {
  if (value === undefined) {
    return undefined;
  }
  if (value === null || value === "") {
    return null;
  }
  return parseNonNegativeInteger(value, "阈值");
}

function normalizeColorCode(colorCode) {
  const raw = trimValue(colorCode).toUpperCase();
  const normalized = raw.replace(/^([A-Z])(\d)$/, (_match, group, number) => `${group}0${number}`);
  if (!BEAD_COLOR_CODES.has(normalized)) {
    throw new ApiError(400, "INVALID_REQUEST", "颜色编号不正确");
  }
  return normalized;
}

function normalizeBlueprintColors(colors) {
  if (!Array.isArray(colors) || colors.length === 0) {
    throw new ApiError(400, "INVALID_REQUEST", "至少需要一种颜色");
  }

  const merged = new Map();
  for (const item of colors) {
    const colorCode = normalizeColorCode(item.colorCode);
    const quantityValue = item.quantityPerBuild !== undefined ? item.quantityPerBuild : item.quantity;
    const quantity = parsePositiveInteger(quantityValue, "颜色数量");
    merged.set(colorCode, (merged.get(colorCode) || 0) + quantity);
  }

  return Array.from(merged.entries()).map(([colorCode, quantity]) => ({ colorCode, quantity }));
}

function invalidateBeadCache(userId) {
  cache.del(Keys.beads(userId));
  cache.del(Keys.beadBlueprints(userId));
}

function mapBeadInventoryItem(row) {
  const item = mapBeadInventoryRow(row);
  return {
    ...item,
    isLowStock: item.quantity <= resolveBeadThreshold(item, item.defaultThreshold)
  };
}

function mapBlueprintSummaryRow(row) {
  return {
    blueprintId: row.blueprint_id,
    userId: row.user_id,
    name: row.name,
    imageUrl: row.image_url || null,
    buildCount: Number(row.build_count || 0),
    colorCount: Number(row.color_count || 0),
    totalBeadsPerBuild: Number(row.total_beads_per_build || 0),
    createdAt: row.created_at,
    updatedAt: row.updated_at
  };
}

function createBeadRouter({ pool }) {
  const router = express.Router();
  const initializedUsers = new Set();

  async function ensureBeadSettings(userId, defaultThreshold = DEFAULT_THRESHOLD) {
    await pool.execute(
      `INSERT INTO bead_settings (user_id, default_threshold)
       VALUES (?, ?)
       ON DUPLICATE KEY UPDATE default_threshold = default_threshold`,
      [userId, defaultThreshold]
    );
  }

  async function ensureBeadInventory(userId) {
    await pool.execute(
      `INSERT IGNORE INTO bead_inventory (user_id, relationship_id, color_code, quantity, threshold_override)
       SELECT ?, NULL, bc.color_code, 0, NULL
       FROM bead_colors bc`,
      [userId]
    );
    initializedUsers.add(userId);
  }

  async function ensureBeadData(userId) {
    if (initializedUsers.has(userId)) return;
    await ensureBeadSettings(userId);
    await ensureBeadInventory(userId);
  }

  async function loadBeadScope(userId) {
    const relationship = await loadActiveRelationship(pool, userId);
    return {
      relationshipId: relationship ? relationship.relationship_id : null
    };
  }

  async function loadInventoryRows(userId) {
    await ensureBeadData(userId);

    const [rows] = await pool.execute(
      `SELECT bc.color_code, bc.hex_color, bc.color_group, bc.is_transparent,
              COALESCE(bi.quantity, 0) AS quantity,
              bi.threshold_override,
              bs.default_threshold,
              COALESCE(consumption.total_consumed, 0) AS total_consumed
       FROM bead_colors bc
       LEFT JOIN bead_inventory bi
         ON bi.user_id = ? AND bi.color_code = bc.color_code
       INNER JOIN bead_settings bs
         ON bs.user_id = ?
       LEFT JOIN (
         SELECT bbc.color_code, COALESCE(SUM(bbc.quantity * bb.build_count), 0) AS total_consumed
         FROM bead_blueprint_colors bbc
         INNER JOIN bead_blueprints bb ON bb.blueprint_id = bbc.blueprint_id
         WHERE bb.user_id = ?
         GROUP BY bbc.color_code
       ) consumption ON consumption.color_code = bc.color_code
       ORDER BY bc.color_code ASC`,
      [userId, userId, userId]
    );

    return rows.map(mapBeadInventoryItem);
  }

  async function loadBlueprintForUser(blueprintId, userId) {
    const [rows] = await pool.execute(
      `SELECT blueprint_id, user_id, relationship_id, name, image_url, build_count, created_at, updated_at
       FROM bead_blueprints
       WHERE blueprint_id = ? AND user_id = ?
       LIMIT 1`,
      [blueprintId, userId]
    );
    return rows[0] || null;
  }

  async function loadBlueprintForCouple(blueprintId, userId) {
    const relationship = await loadActiveRelationship(pool, userId);
    if (!relationship) {
      return loadBlueprintForUser(blueprintId, userId);
    }
    const partnerId = relationship.user_id_1 === userId ? relationship.user_id_2 : relationship.user_id_1;
    const [rows] = await pool.execute(
      `SELECT blueprint_id, user_id, relationship_id, name, image_url, build_count, created_at, updated_at
       FROM bead_blueprints
       WHERE blueprint_id = ? AND user_id IN (?, ?)
       LIMIT 1`,
      [blueprintId, userId, partnerId]
    );
    return rows[0] || null;
  }

  async function invalidateBeadCacheForCouple(userId) {
    invalidateBeadCache(userId);
    const relationship = await loadActiveRelationship(pool, userId);
    if (relationship) {
      const partnerId = relationship.user_id_1 === userId ? relationship.user_id_2 : relationship.user_id_1;
      cache.del(Keys.beads(partnerId));
      cache.del(Keys.beadBlueprints(partnerId));
    }
  }

  async function insertBlueprintColors(executor, blueprintId, colors) {
    if (!colors.length) {
      return;
    }

    const placeholders = colors.map(() => "(?, ?, ?)").join(", ");
    const params = [];
    for (const color of colors) {
      params.push(blueprintId, color.colorCode, color.quantity);
    }

    await executor.execute(
      `INSERT INTO bead_blueprint_colors (blueprint_id, color_code, quantity)
       VALUES ${placeholders}`,
      params
    );
  }

  router.get("/colors", async (_req, res, next) => {
    try {
      res.json({ ok: true, data: { items: BEAD_COLORS } });
    } catch (error) {
      next(error);
    }
  });

  async function getCachedOrFreshInventoryResponse(userId) {
    const cacheKey = Keys.beads(userId);
    const cached = cache.get(cacheKey);
    if (cached) {
      return cached;
    }

    const items = await loadInventoryRows(userId);
    const scope = await loadBeadScope(userId);
    const response = {
      ok: true,
      data: {
        items,
        summary: buildBeadSummary(items),
        relationshipId: scope.relationshipId
      }
    };
    cache.set(cacheKey, response, TTL.BEADS);
    return response;
  }

  router.get("/inventory", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(parseInt(req.query.userId, 10));
      const response = await getCachedOrFreshInventoryResponse(userId);
      res.json(response);
    } catch (error) {
      next(error);
    }
  });

  router.get("/inventory/low-stock", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(parseInt(req.query.userId, 10));
      const response = await getCachedOrFreshInventoryResponse(userId);
      const lowStockItems = response.data.items.filter(item => item.isLowStock);
      res.json({
        ok: true,
        data: {
          items: lowStockItems,
          count: lowStockItems.length
        }
      });
    } catch (error) {
      next(error);
    }
  });

  router.get("/inventory/summary", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(parseInt(req.query.userId, 10));
      const response = await getCachedOrFreshInventoryResponse(userId);
      res.json({ ok: true, data: response.data.summary });
    } catch (error) {
      next(error);
    }
  });

  router.put("/inventory/:colorCode", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.body.userId);
      const colorCode = normalizeColorCode(req.params.colorCode);
      const updates = [];
      const params = [];

      await ensureBeadData(userId);

      if (req.body.quantity !== undefined) {
        updates.push("quantity = ?");
        params.push(parseNonNegativeInteger(req.body.quantity, "库存数量"));
      }

      const thresholdOverride = parseOptionalThreshold(req.body.thresholdOverride);
      if (thresholdOverride !== undefined) {
        updates.push("threshold_override = ?");
        params.push(thresholdOverride);
      }

      if (updates.length === 0) {
        throw new ApiError(400, "INVALID_REQUEST", "无更新内容");
      }

      params.push(colorCode, userId);
      await pool.execute(
        `UPDATE bead_inventory SET ${updates.join(", ")}, updated_at = NOW() WHERE color_code = ? AND user_id = ?`,
        params
      );

      invalidateBeadCache(userId);
      res.json({ ok: true, message: "库存更新成功" });
    } catch (error) {
      next(error);
    }
  });

  router.post("/inventory/:colorCode/consume", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.body.userId);
      const colorCode = normalizeColorCode(req.params.colorCode);
      const consumeAmount = parsePositiveInteger(req.body.consumeAmount, "消耗数量");

      await ensureBeadData(userId);

      const [updateResult] = await pool.execute(
        `UPDATE bead_inventory
         SET quantity = quantity - ?, updated_at = NOW()
         WHERE color_code = ? AND user_id = ? AND quantity >= ?`,
        [consumeAmount, colorCode, userId, consumeAmount]
      );

      if (updateResult.affectedRows === 0) {
        throw new ApiError(400, "INSUFFICIENT_STOCK", "库存不足");
      }

      invalidateBeadCache(userId);
      res.json({
        ok: true,
        message: "消耗记录成功",
        data: {
          colorCode,
          consumed: consumeAmount
        }
      });
    } catch (error) {
      next(error);
    }
  });

  router.post("/inventory/:colorCode/replenish", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.body.userId);
      const colorCode = normalizeColorCode(req.params.colorCode);
      const addAmount = parsePositiveInteger(req.body.addAmount, "补货数量");

      await ensureBeadData(userId);

      const [rows] = await pool.execute(
        `SELECT quantity FROM bead_inventory WHERE color_code = ? AND user_id = ? LIMIT 1`,
        [colorCode, userId]
      );
      const currentQuantity = rows.length > 0 ? Number(rows[0].quantity || 0) : 0;

      await pool.execute(
        `UPDATE bead_inventory SET quantity = quantity + ?, updated_at = NOW() WHERE color_code = ? AND user_id = ?`,
        [addAmount, colorCode, userId]
      );

      invalidateBeadCache(userId);
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

  router.get("/settings", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(parseInt(req.query.userId, 10));
      await ensureBeadSettings(userId);
      const [rows] = await pool.execute(
        `SELECT default_threshold FROM bead_settings WHERE user_id = ? LIMIT 1`,
        [userId]
      );
      res.json({
        ok: true,
        data: {
          defaultThreshold: rows.length > 0 ? Number(rows[0].default_threshold) : DEFAULT_THRESHOLD
        }
      });
    } catch (error) {
      next(error);
    }
  });

  router.put("/settings", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.body.userId);
      const defaultThreshold = parsePositiveInteger(req.body.defaultThreshold, "默认阈值");
      await pool.execute(
        `INSERT INTO bead_settings (user_id, default_threshold)
         VALUES (?, ?)
         ON DUPLICATE KEY UPDATE default_threshold = VALUES(default_threshold), updated_at = NOW()`,
        [userId, defaultThreshold]
      );
      invalidateBeadCache(userId);
      res.json({ ok: true, message: "默认阈值更新成功" });
    } catch (error) {
      next(error);
    }
  });

  router.get("/blueprints", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(parseInt(req.query.userId, 10));
      const cacheKey = Keys.beadBlueprints(userId);
      const cached = cache.get(cacheKey);
      if (cached) {
        return res.json(cached);
      }

      // 查找情侣关系，获取对方的userId
      const relationship = await loadActiveRelationship(pool, userId);
      let partnerId = null;
      if (relationship) {
        partnerId = relationship.user_id_1 === userId ? relationship.user_id_2 : relationship.user_id_1;
      }

      // 查询自己的图纸 + 情侣的图纸
      const queryParams = partnerId ? [userId, partnerId] : [userId];
      const [rows] = await pool.execute(
        `SELECT bb.blueprint_id, bb.user_id, bb.name, bb.image_url, bb.build_count, bb.created_at, bb.updated_at,
                COUNT(bbc.id) AS color_count,
                COALESCE(SUM(bbc.quantity), 0) AS total_beads_per_build
         FROM bead_blueprints bb
         LEFT JOIN bead_blueprint_colors bbc ON bbc.blueprint_id = bb.blueprint_id
         WHERE bb.user_id IN (${queryParams.map(() => '?').join(',')})
         GROUP BY bb.blueprint_id, bb.user_id, bb.name, bb.build_count, bb.created_at, bb.updated_at
         ORDER BY bb.updated_at DESC, bb.blueprint_id DESC`,
        queryParams
      );

      const response = {
        ok: true,
        data: {
          items: rows.map(row => ({
            ...mapBlueprintSummaryRow(row),
            isPartner: row.user_id !== userId
          }))
        }
      };
      cache.set(cacheKey, response, TTL.BEADS);
      res.json(response);
    } catch (error) {
      next(error);
    }
  });

  router.post("/blueprints", async (req, res, next) => {
    let connection;

    try {
      const userId = parseRequiredInteger(req.body.userId);
      const name = trimValue(req.body.name);
      if (!name) {
        throw new ApiError(400, "INVALID_REQUEST", "图纸名称不能为空");
      }
      const colors = normalizeBlueprintColors(req.body.colors);
      const imageUrl = req.body.imageUrl || null;

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      connection = await pool.getConnection();
      await connection.beginTransaction();

      const [result] = await connection.execute(
        `INSERT INTO bead_blueprints (user_id, relationship_id, name, image_url, build_count)
         VALUES (?, ?, ?, ?, 0)`,
        [userId, relationshipId, name, imageUrl]
      );
      await insertBlueprintColors(connection, result.insertId, colors);

      await connection.commit();
      await invalidateBeadCacheForCouple(userId);
      res.status(201).json({ ok: true, data: { blueprintId: result.insertId } });
    } catch (error) {
      if (connection) {
        try {
          await connection.rollback();
        } catch (_rollbackError) {
        }
      }
      next(error);
    } finally {
      if (connection) {
        connection.release();
      }
    }
  });

  router.get("/blueprints/:id", async (req, res, next) => {
    try {
      const blueprintId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(parseInt(req.query.userId, 10));
      const blueprint = await loadBlueprintForCouple(blueprintId, userId);
      if (!blueprint) {
        throw new ApiError(404, "NOT_FOUND", "图纸不存在或无权查看");
      }

      const [rows] = await pool.execute(
        `SELECT bbc.color_code, bc.hex_color, bc.color_group, bc.is_transparent, bbc.quantity
         FROM bead_blueprint_colors bbc
         INNER JOIN bead_colors bc ON bc.color_code = bbc.color_code
         WHERE bbc.blueprint_id = ?
         ORDER BY bbc.id ASC`,
        [blueprintId]
      );

      const buildCount = Number(blueprint.build_count || 0);
      const colors = rows.map(row => ({
        colorCode: row.color_code,
        hexColor: row.hex_color,
        colorGroup: row.color_group,
        isTransparent: Boolean(row.is_transparent),
        quantityPerBuild: Number(row.quantity || 0),
        totalConsumed: Number(row.quantity || 0) * buildCount
      }));
      const totalBeadsPerBuild = colors.reduce((sum, color) => sum + color.quantityPerBuild, 0);
      const totalConsumed = colors.reduce((sum, color) => sum + color.totalConsumed, 0);

      res.json({
        ok: true,
        data: {
          blueprintId: blueprint.blueprint_id,
          userId: blueprint.user_id,
          isPartner: blueprint.user_id !== userId,
          name: blueprint.name,
          imageUrl: blueprint.image_url || null,
          buildCount,
          totalBeadsPerBuild,
          totalConsumed,
          createdAt: blueprint.created_at,
          updatedAt: blueprint.updated_at,
          colors
        }
      });
    } catch (error) {
      next(error);
    }
  });

  router.put("/blueprints/:id", async (req, res, next) => {
    let connection;

    try {
      const blueprintId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(req.body.userId);
      const blueprint = await loadBlueprintForCouple(blueprintId, userId);
      if (!blueprint) {
        throw new ApiError(404, "NOT_FOUND", "图纸不存在或无权修改");
      }

      const ownerId = blueprint.user_id;

      const updates = [];
      const params = [];
      if (req.body.name !== undefined) {
        const name = trimValue(req.body.name);
        if (!name) {
          throw new ApiError(400, "INVALID_REQUEST", "图纸名称不能为空");
        }
        updates.push("name = ?");
        params.push(name);
      }

      if (req.body.imageUrl !== undefined) {
        updates.push("image_url = ?");
        params.push(req.body.imageUrl || null);
      }

      let colors;
      if (req.body.colors !== undefined) {
        colors = normalizeBlueprintColors(req.body.colors);
      }

      if (updates.length === 0 && colors === undefined) {
        throw new ApiError(400, "INVALID_REQUEST", "无更新内容");
      }

      connection = await pool.getConnection();
      await connection.beginTransaction();

      if (updates.length > 0) {
        params.push(blueprintId);
        await connection.execute(
          `UPDATE bead_blueprints SET ${updates.join(", ")}, updated_at = NOW() WHERE blueprint_id = ?`,
          params
        );
      } else if (colors !== undefined) {
        await connection.execute(
          `UPDATE bead_blueprints SET updated_at = NOW() WHERE blueprint_id = ?`,
          [blueprintId]
        );
      }

      if (colors !== undefined) {
        await connection.execute(`DELETE FROM bead_blueprint_colors WHERE blueprint_id = ?`, [blueprintId]);
        await insertBlueprintColors(connection, blueprintId, colors);
      }

      await connection.commit();
      await invalidateBeadCacheForCouple(ownerId);
      res.json({ ok: true, message: "图纸更新成功" });
    } catch (error) {
      if (connection) {
        try {
          await connection.rollback();
        } catch (_rollbackError) {
        }
      }
      next(error);
    } finally {
      if (connection) {
        connection.release();
      }
    }
  });

  router.delete("/blueprints/:id", async (req, res, next) => {
    try {
      const blueprintId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(parseInt(req.query.userId, 10));
      const blueprint = await loadBlueprintForCouple(blueprintId, userId);
      if (!blueprint) {
        throw new ApiError(404, "NOT_FOUND", "图纸不存在或无权删除");
      }
      const ownerId = blueprint.user_id;
      await pool.execute(`DELETE FROM bead_blueprints WHERE blueprint_id = ?`, [blueprintId]);

      await invalidateBeadCacheForCouple(ownerId);
      res.json({ ok: true, message: "图纸删除成功" });
    } catch (error) {
      next(error);
    }
  });

  router.post("/blueprints/:id/build", async (req, res, next) => {
    try {
      const blueprintId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(req.body.userId);
      const count = req.body.count === undefined ? 1 : parsePositiveInteger(req.body.count, "制作次数");
      const blueprint = await loadBlueprintForCouple(blueprintId, userId);
      if (!blueprint) {
        throw new ApiError(404, "NOT_FOUND", "图纸不存在或无权操作");
      }

      const ownerId = blueprint.user_id;
      const previousBuildCount = Number(blueprint.build_count || 0);
      const currentBuildCount = previousBuildCount + count;
      await pool.execute(
        `UPDATE bead_blueprints SET build_count = build_count + ?, updated_at = NOW() WHERE blueprint_id = ?`,
        [count, blueprintId]
      );

      await invalidateBeadCacheForCouple(ownerId);
      res.json({
        ok: true,
        message: "制作记录成功",
        data: {
          buildCount: currentBuildCount,
          previousBuildCount,
          addedCount: count,
          currentBuildCount
        }
      });
    } catch (error) {
      next(error);
    }
  });

  router.post("/recognize-colors", async (req, res, next) => {
    try {
      const { imageUrl } = req.body;
      if (!imageUrl || typeof imageUrl !== "string" || imageUrl.trim().length === 0) {
        throw new ApiError(400, "INVALID_REQUEST", "需要提供图片地址");
      }

      const primaryProvider = aiProviders.primary;
      const fallbackProvider = aiProviders.fallback;

      if (!primaryProvider.apiKey) {
        throw new ApiError(503, "AI_NOT_CONFIGURED", "AI 服务未配置");
      }

      const serverBaseUrl = process.env.PUBLIC_SERVER_URL || `${req.protocol}://${req.get("host")}`;
      const resolvedUrl = imageUrl.startsWith("/") ? `${serverBaseUrl}${imageUrl}` : imageUrl;

      let imageBase64;
      try {
        if (imageUrl.startsWith("/uploads/")) {
          const localPath = path.join(__dirname, "../../uploads", path.basename(imageUrl));
          try {
            const rawBuffer = await fs.promises.readFile(localPath);
            imageBase64 = await compressToBase64(rawBuffer);
          } catch (localReadError) {
            if (localReadError && localReadError.code !== "ENOENT") {
              throw localReadError;
            }
            const rawBuffer = await downloadImageAsBuffer(resolvedUrl);
            imageBase64 = await compressToBase64(rawBuffer);
          }
        } else {
          const rawBuffer = await downloadImageAsBuffer(resolvedUrl);
          imageBase64 = await compressToBase64(rawBuffer);
        }
      } catch (readError) {
        throw new ApiError(502, "IMAGE_READ_FAILED", `图片读取失败: ${readError.message}`);
      }

      const prompt = `你是一个拼豆图纸色号识别专家。请识别这张拼豆图纸中每种颜色使用的 MARD 色号及所需数量。

关键说明：
- 这是一张标准拼豆方格图纸，图纸上每个色块内或旁边会标注对应的 MARD 色号（如 A01、B05 等）
- 图纸边缘可能有白色空白区域，请忽略这些区域，只关注有图案的部分
- 请仔细查看图纸上的色号标注文字，优先按标注识别

输出规则：
1. 严格按照以下格式输出，每行一种颜色：色号+空格+数量
2. 色号格式：A01~H23、M01~M15（字母+两位数字）
3. 数量为该颜色所需的拼豆颗粒总数
4. 不要输出任何解释、标题、列表符号或其他多余内容
5. 相同颜色只输出一行，数量合并
6. 如果完全无法识别任何色号，才输出 UNABLE_TO_RECOGNIZE

正确的输出示例：
A01 24
B05 8
H07 2
M03 15`;

      const aiPayload = {
        messages: [
          {
            role: "user",
            content: [
              { type: "text", text: prompt },
              { type: "image_url", image_url: { url: imageBase64 } }
            ]
          }
        ],
        temperature: 0.1,
        max_tokens: 1200,
        stream: false
      };

      if (primaryProvider.model.includes("Kimi") || primaryProvider.model.includes("kimi")) {
        aiPayload.thinking = { type: "disabled" };
      }

      const aiResult = await callAiWithFallback(aiPayload, primaryProvider, fallbackProvider);
      const rawText = extractAiResponseText(aiResult);
      const preprocessedText = preprocessRecognitionText(rawText);
      const unableToRecognizeOnly = isUnableToRecognizeOnly(preprocessedText);

      if (unableToRecognizeOnly) {
        return res.json({
          ok: true,
          data: {
            colors: [],
            rawText,
            recognized: false,
            debug: {
              unableToRecognizeOnly,
              preprocessedText,
              parsedCount: 0
            }
          }
        });
      }

      const colors = parseRecognizedColors(preprocessedText);
      res.json({
        ok: true,
        data: {
          colors,
          rawText,
          recognized: colors.length > 0,
          debug: {
            unableToRecognizeOnly,
            preprocessedText,
            parsedCount: colors.length
          }
        }
      });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

async function compressToBase64(buffer) {
  const compressed = await sharp(buffer)
    .rotate()
    .resize(1536, 1536, { fit: "inside", withoutEnlargement: true })
    .jpeg({ quality: 85, mozjpeg: true })
    .toBuffer();
  return `data:image/jpeg;base64,${compressed.toString("base64")}`;
}

function downloadImageAsBuffer(imageUrl) {
  return new Promise((resolve, reject) => {
    const url = new URL(imageUrl);
    const isHttps = url.protocol === "https:";
    const requester = isHttps ? https : http;
    const options = {
      hostname: url.hostname,
      port: url.port || (isHttps ? 443 : 80),
      path: url.pathname + url.search,
      method: "GET",
      timeout: 15000,
      headers: { "User-Agent": "Mozilla/5.0 (compatible; ImageDownloader/1.0)" }
    };
    const req = requester.request(options, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        downloadImageAsBuffer(res.headers.location).then(resolve).catch(reject);
        return;
      }
      if (res.statusCode >= 400) {
        reject(new ApiError(502, "IMAGE_DOWNLOAD_ERROR", `无法下载图片，HTTP ${res.statusCode}`));
        return;
      }
      const chunks = [];
      let totalBytes = 0;
      res.on("data", (chunk) => {
        totalBytes += chunk.length;
        if (totalBytes > MAX_DOWNLOAD_BYTES) {
          req.destroy();
          reject(new ApiError(413, "IMAGE_TOO_LARGE", "下载图片超过大小限制"));
          return;
        }
        chunks.push(chunk);
      });
      res.on("end", () => resolve(Buffer.concat(chunks)));
      res.on("error", reject);
    });
    req.on("error", (e) => reject(new ApiError(502, "IMAGE_DOWNLOAD_ERROR", `下载图片失败: ${e.message}`)));
    req.on("timeout", () => { req.destroy(); reject(new ApiError(504, "IMAGE_DOWNLOAD_TIMEOUT", "下载图片超时")); });
    req.end();
  });
}

function buildAiProviderConfig(envPrefix, defaultBaseUrl, defaultModel) {
  return {
    apiKey: process.env[`${envPrefix}_API_KEY`],
    baseUrl: process.env[`${envPrefix}_BASE_URL`] || defaultBaseUrl || null,
    model: process.env[`${envPrefix}_MODEL`] || defaultModel
  };
}

async function callAiWithFallback(payload, primaryProvider, fallbackProvider) {
  const primaryPayload = { ...payload, model: primaryProvider.model };
  try {
    return await callAiApi(primaryProvider.baseUrl, primaryProvider.apiKey, primaryPayload);
  } catch (primaryError) {
    if (!fallbackProvider || !fallbackProvider.apiKey || !fallbackProvider.baseUrl) {
      throw primaryError;
    }
    console.warn(`Primary AI recognition failed (${primaryError.message}), trying fallback provider`);
    const fallbackPayload = { ...payload, model: fallbackProvider.model };
    return callAiApi(fallbackProvider.baseUrl, fallbackProvider.apiKey, fallbackPayload);
  }
}

function parseRecognizedColors(text) {
  const results = [];
  const merged = new Map();
  const lines = text.split("\n");
  for (const line of lines) {
    const normalizedLine = normalizeRecognitionLine(line);
    if (!normalizedLine) continue;

    const match = normalizedLine.match(/^([A-HM]\d{1,2})\s+(\d+)/i);
    if (!match) continue;

    let code = match[1].toUpperCase();
    if (code.length === 2 && /^[A-HM]\d$/.test(code)) {
      code = code[0] + "0" + code[1];
    }
    if (!BEAD_COLOR_CODES.has(code)) continue;

    const quantity = parseInt(match[2], 10);
    if (!Number.isFinite(quantity) || quantity <= 0) continue;
    merged.set(code, (merged.get(code) || 0) + quantity);
  }

  for (const [colorCode, quantityPerBuild] of merged.entries()) {
    results.push({ colorCode, quantityPerBuild });
  }
  return results;
}

function extractAiResponseText(aiResult) {
  const content = aiResult && aiResult.choices && aiResult.choices[0] && aiResult.choices[0].message
    ? aiResult.choices[0].message.content
    : "";

  if (typeof content === "string") {
    return content;
  }

  if (Array.isArray(content)) {
    return content.map(extractContentPartText).filter(Boolean).join("\n");
  }

  if (content && typeof content === "object") {
    if (typeof content.text === "string") return content.text;
    return JSON.stringify(content);
  }

  return "";
}

function extractContentPartText(part) {
  if (typeof part === "string") return part;
  if (!part || typeof part !== "object") return "";
  if (typeof part.text === "string") return part.text;
  if (Array.isArray(part.content)) {
    return part.content.map(extractContentPartText).filter(Boolean).join("\n");
  }
  return "";
}

function preprocessRecognitionText(text) {
  let output = String(text || "");

  const codeBlocks = Array.from(output.matchAll(/```(?:[a-zA-Z0-9_-]+)?\s*([\s\S]*?)```/g)).map(match => match[1]);
  if (codeBlocks.length > 0) {
    output = codeBlocks.join("\n");
  }

  output = output
    .replace(/[：]/g, ":")
    .replace(/[，]/g, ",")
    .replace(/[×✕✖]/g, "x")
    .replace(/\r/g, "\n")
    .replace(/\t/g, " ")
    .replace(/[•·]/g, "-");

  output = output
    .split("\n")
    .map((line) => line.trim())
    .map((line) => line.replace(/^[-*+]+\s+/, ""))
    .map((line) => line.replace(/^\d+[.)、]\s+/, ""))
    .join("\n");

  return output;
}

function normalizeRecognitionLine(line) {
  if (!line) return "";
  let normalized = line.trim();
  if (!normalized) return "";

  normalized = normalized
    .replace(/[：]/g, ":")
    .replace(/[，]/g, ",")
    .replace(/[×✕✖]/g, "x")
    .replace(/^\|+/, "")
    .replace(/\|+$/, "")
    .replace(/\|/g, " ")
    .replace(/^[-*+]+\s+/, "")
    .replace(/^\d+[.)、]\s+/, "")
    .replace(/([A-HM]\d{1,2})\s*[xX]\s*(\d+)/gi, "$1 $2")
    .replace(/([A-HM]\d{1,2})\s*[,，]\s*(\d+)/gi, "$1 $2")
    .replace(/\s*[:=]\s*/g, " ")
    .replace(/\s+[xX]\s+/g, " ")
    .replace(/\s+/g, " ");

  return normalized;
}

function isUnableToRecognizeOnly(text) {
  const normalized = String(text || "")
    .replace(/```[\s\S]*?```/g, " ")
    .replace(/[\s\.,，。!！?？:：;；\-_/|]+/g, "")
    .toUpperCase();
  return normalized === "UNABLETORECOGNIZE";
}

function resolveChatApiUrl(baseUrl) {
  const trimmed = String(baseUrl || "").replace(/\/$/, "");
  if (trimmed.endsWith("/chat/completions")) return trimmed;
  if (trimmed.endsWith("/v1")) return `${trimmed}/chat/completions`;
  return `${trimmed}/v1/chat/completions`;
}

function callAiApi(baseUrl, apiKey, payload) {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 120000);

  return fetch(resolveChatApiUrl(baseUrl), {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Authorization": `Bearer ${apiKey}`
    },
    body: JSON.stringify(payload),
    signal: controller.signal
  }).then(async (res) => {
    const data = await res.text();
    if (!res.ok) {
      const preview = data.substring(0, 300).replace(/\s+/g, " ");
      throw new ApiError(502, "AI_API_ERROR", `AI API HTTP ${res.status}: ${preview}`);
    }
    try {
      return JSON.parse(data);
    } catch (_e) {
      const preview = data.substring(0, 200).replace(/\s+/g, " ");
      throw new ApiError(502, "AI_API_ERROR", `AI API response parse error: ${preview}`);
    }
  }).catch((error) => {
    if (error && error.name === "AbortError") {
      throw new ApiError(504, "AI_API_TIMEOUT", "AI API request timeout");
    }
    throw error instanceof ApiError
      ? error
      : new ApiError(502, "AI_API_ERROR", `AI API request failed: ${error.message}`);
  }).finally(() => {
    clearTimeout(timeoutId);
  });
}

module.exports = {
  BEAD_COLORS,
  resolveBeadThreshold,
  mapBeadInventoryRow,
  buildBeadSummary,
  createBeadRouter
};
