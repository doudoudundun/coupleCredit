const express = require("express");
const { BEAD_COLORS } = require("../constants/beadColors");
const { ApiError } = require("../errors");
const { cache, Keys, TTL } = require("../cache");
const { trimValue, parseRequiredInteger, loadActiveRelationship } = require("../utils/queryHelpers");

const BEAD_COLOR_CODES = new Set(BEAD_COLORS.map(color => color.colorCode));
const DEFAULT_THRESHOLD = 200;

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
    buildCount: Number(row.build_count || 0),
    colorCount: Number(row.color_count || 0),
    totalBeadsPerBuild: Number(row.total_beads_per_build || 0),
    createdAt: row.created_at,
    updatedAt: row.updated_at
  };
}

function createBeadRouter({ pool }) {
  const router = express.Router();

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
  }

  async function loadBeadScope(userId) {
    const relationship = await loadActiveRelationship(pool, userId);
    return {
      relationshipId: relationship ? relationship.relationship_id : null
    };
  }

  async function loadInventoryRows(userId) {
    await ensureBeadSettings(userId);
    await ensureBeadInventory(userId);

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
      `SELECT blueprint_id, user_id, relationship_id, name, build_count, created_at, updated_at
       FROM bead_blueprints
       WHERE blueprint_id = ? AND user_id = ?
       LIMIT 1`,
      [blueprintId, userId]
    );
    return rows[0] || null;
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

      await ensureBeadSettings(userId);
      await ensureBeadInventory(userId);

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

      await ensureBeadSettings(userId);
      await ensureBeadInventory(userId);

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

      await ensureBeadSettings(userId);
      await ensureBeadInventory(userId);

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

      const [rows] = await pool.execute(
        `SELECT bb.blueprint_id, bb.user_id, bb.name, bb.build_count, bb.created_at, bb.updated_at,
                COUNT(bbc.id) AS color_count,
                COALESCE(SUM(bbc.quantity), 0) AS total_beads_per_build
         FROM bead_blueprints bb
         LEFT JOIN bead_blueprint_colors bbc ON bbc.blueprint_id = bb.blueprint_id
         WHERE bb.user_id = ?
         GROUP BY bb.blueprint_id, bb.user_id, bb.name, bb.build_count, bb.created_at, bb.updated_at
         ORDER BY bb.updated_at DESC, bb.blueprint_id DESC`,
        [userId]
      );

      const response = {
        ok: true,
        data: {
          items: rows.map(mapBlueprintSummaryRow)
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

      connection = await pool.getConnection();
      await connection.beginTransaction();

      const [result] = await connection.execute(
        `INSERT INTO bead_blueprints (user_id, relationship_id, name, build_count)
         VALUES (?, NULL, ?, 0)`,
        [userId, name]
      );
      await insertBlueprintColors(connection, result.insertId, colors);

      await connection.commit();
      invalidateBeadCache(userId);
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
      const blueprint = await loadBlueprintForUser(blueprintId, userId);
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
          name: blueprint.name,
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
      const blueprint = await loadBlueprintForUser(blueprintId, userId);
      if (!blueprint) {
        throw new ApiError(404, "NOT_FOUND", "图纸不存在或无权修改");
      }

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
        params.push(blueprintId, userId);
        await connection.execute(
          `UPDATE bead_blueprints SET ${updates.join(", ")}, updated_at = NOW() WHERE blueprint_id = ? AND user_id = ?`,
          params
        );
      } else if (colors !== undefined) {
        await connection.execute(
          `UPDATE bead_blueprints SET updated_at = NOW() WHERE blueprint_id = ? AND user_id = ?`,
          [blueprintId, userId]
        );
      }

      if (colors !== undefined) {
        await connection.execute(`DELETE FROM bead_blueprint_colors WHERE blueprint_id = ?`, [blueprintId]);
        await insertBlueprintColors(connection, blueprintId, colors);
      }

      await connection.commit();
      invalidateBeadCache(userId);
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
      const [result] = await pool.execute(
        `DELETE FROM bead_blueprints WHERE blueprint_id = ? AND user_id = ?`,
        [blueprintId, userId]
      );
      if (result.affectedRows === 0) {
        throw new ApiError(404, "NOT_FOUND", "图纸不存在或无权删除");
      }

      invalidateBeadCache(userId);
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
      const blueprint = await loadBlueprintForUser(blueprintId, userId);
      if (!blueprint) {
        throw new ApiError(404, "NOT_FOUND", "图纸不存在或无权操作");
      }

      const previousBuildCount = Number(blueprint.build_count || 0);
      const currentBuildCount = previousBuildCount + count;
      await pool.execute(
        `UPDATE bead_blueprints SET build_count = build_count + ?, updated_at = NOW() WHERE blueprint_id = ? AND user_id = ?`,
        [count, blueprintId, userId]
      );

      invalidateBeadCache(userId);
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

  return router;
}

module.exports = {
  BEAD_COLORS,
  resolveBeadThreshold,
  mapBeadInventoryRow,
  buildBeadSummary,
  createBeadRouter
};
