const express = require("express");
const path = require("path");
const fsPromises = require("fs").promises;
const { ApiError } = require("../errors");
const { cache, Keys, TTL } = require("../cache");
const { loadActiveRelationship, trimValue, parseRequiredInteger, parseRequiredFloat, normalizeNullableText, invalidateForUser } = require("../utils/queryHelpers");
const { withTransaction } = require("../utils/transactions");
const { callImageApi } = require("../utils/imageApi");
const { resolveWritableCategory } = require("../utils/categoryScope");

const EXPIRING_WINDOW_DAYS = 3;
const INVENTORY_SELECT_FIELDS = `inventory.inventory_id as inventoryId, inventory.user_id as userId, inventory.relationship_id as relationshipId,
                 inventory.name, inventory.category, inventory.image_url as imageUrl, inventory.quantity, inventory.unit, inventory.threshold,
                 inventory.created_at as createdAt, inventory.updated_at as updatedAt, inventory.last_consumed_at as lastConsumedAt,
                 inventory.note, inventory.ai_image_prompt as aiImagePrompt,
                 inventory.expiration_mode as expirationMode,
                 DATE_FORMAT(inventory.expiration_date, '%Y-%m-%d') as expirationDate,
                 DATE_FORMAT(inventory.production_date, '%Y-%m-%d') as productionDate,
                 inventory.shelf_life_days as shelfLifeDays`;
// 分类读路径（spec 9）：category_id + 分类当前信息；历史 NULL 行 category 为 null，
// 归档分类仍随引用可读（status: "archived"）。
const INVENTORY_CATEGORY_SELECT = `inventory.category_id as categoryId,
                 ic.name as categoryName, ic.icon_type as categoryIconType, ic.icon_value as categoryIconValue,
                 ic.color as categoryColor, ic.status as categoryStatus`;
const INVENTORY_CATEGORY_JOIN = "LEFT JOIN item_categories ic ON ic.id = inventory.category_id";

/** 把 JOIN 出的分类列折叠成 categoryInfo 对象；categoryId 为 NULL 的历史行返回 null。 */
function withCategoryInfo(row) {
  const {
    categoryName, categoryIconType, categoryIconValue, categoryColor, categoryStatus,
    ...rest
  } = row;
  return {
    ...rest,
    categoryId: row.categoryId || null,
    // legacy `category` 字符串列保持不变（spec 9：继续返回 legacy category），
    // 分类当前信息放在 categoryInfo，避免覆盖旧客户端依赖的字符串字段
    categoryInfo: row.categoryId
      ? {
          categoryId: row.categoryId,
          name: categoryName || null,
          icon: categoryIconType ? { type: categoryIconType, value: categoryIconValue } : null,
          color: categoryColor || null,
          status: categoryStatus || null
        }
      : null
  };
}

function normalizeExpirationMode(value) {
  if (value === undefined || value === null) {
    return null;
  }
  if (typeof value !== "string") {
    throw new ApiError(400, "INVALID_REQUEST", "保质期模式不正确");
  }

  const trimmed = value.trim();
  if (trimmed === "") {
    return null;
  }
  if (trimmed !== "date" && trimmed !== "calc") {
    throw new ApiError(400, "INVALID_REQUEST", "保质期模式不正确");
  }
  return trimmed;
}

function createUtcDateOnly(year, month, day) {
  return new Date(Date.UTC(year, month - 1, day));
}

function formatUtcDateOnly(date) {
  const year = date.getUTCFullYear();
  const month = String(date.getUTCMonth() + 1).padStart(2, "0");
  const day = String(date.getUTCDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function parseDateOnlyString(value, fieldLabel) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    throw new ApiError(400, "INVALID_REQUEST", `${fieldLabel}格式不正确`);
  }

  const [year, month, day] = value.split("-").map(Number);
  const parsed = createUtcDateOnly(year, month, day);
  if (
    parsed.getUTCFullYear() !== year ||
    parsed.getUTCMonth() !== month - 1 ||
    parsed.getUTCDate() !== day
  ) {
    throw new ApiError(400, "INVALID_REQUEST", `${fieldLabel}格式不正确`);
  }

  return parsed;
}

function normalizeNullableDate(value, fieldLabel) {
  if (value === undefined || value === null) {
    return null;
  }
  if (typeof value !== "string") {
    throw new ApiError(400, "INVALID_REQUEST", `${fieldLabel}格式不正确`);
  }

  const trimmed = value.trim();
  if (trimmed === "") {
    return null;
  }

  parseDateOnlyString(trimmed, fieldLabel);
  return trimmed;
}

function parsePositiveInteger(value, fieldLabel) {
  if (value === undefined || value === null || value === "") {
    return null;
  }

  const parsed = typeof value === "number" ? value : Number(String(value).trim());
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new ApiError(400, "INVALID_REQUEST", `${fieldLabel}必须是正整数`);
  }

  return parsed;
}

function addDaysToDateString(dateString, days) {
  const parsed = parseDateOnlyString(dateString, "日期");
  parsed.setUTCDate(parsed.getUTCDate() + days);
  return formatUtcDateOnly(parsed);
}

function hasShelfLifeFields(source) {
  return ["expirationMode", "expirationDate", "productionDate", "shelfLifeDays"]
    .some(field => Object.prototype.hasOwnProperty.call(source, field));
}

function deriveShelfLifeFields(input) {
  const expirationMode = normalizeExpirationMode(input.expirationMode);
  const expirationDate = normalizeNullableDate(input.expirationDate, "到期日期");
  const productionDate = normalizeNullableDate(input.productionDate, "生产日期");
  const shelfLifeDays = parsePositiveInteger(input.shelfLifeDays, "保质期天数");

  const hasAnyShelfLifeValue = expirationMode !== null || expirationDate !== null || productionDate !== null || shelfLifeDays !== null;
  if (!hasAnyShelfLifeValue) {
    return {
      expirationMode: null,
      expirationDate: null,
      productionDate: null,
      shelfLifeDays: null
    };
  }

  if (expirationMode === "date") {
    if (!expirationDate) {
      throw new ApiError(400, "INVALID_REQUEST", "请选择到期日期");
    }

    return {
      expirationMode,
      expirationDate,
      productionDate: null,
      shelfLifeDays: null
    };
  }

  if (expirationMode === "calc") {
    if (!productionDate) {
      throw new ApiError(400, "INVALID_REQUEST", "请选择生产日期");
    }
    if (shelfLifeDays === null) {
      throw new ApiError(400, "INVALID_REQUEST", "请输入保质期天数");
    }

    return {
      expirationMode,
      expirationDate: addDaysToDateString(productionDate, shelfLifeDays),
      productionDate,
      shelfLifeDays
    };
  }

  throw new ApiError(400, "INVALID_REQUEST", "请先选择保质期方式");
}

function resolveShelfLifeUpdate(existingShelfLife, input) {
  const requestedMode = input.expirationMode !== undefined ? normalizeExpirationMode(input.expirationMode) : existingShelfLife.expirationMode;
  const switchedModes = input.expirationMode !== undefined && requestedMode !== existingShelfLife.expirationMode;

  return deriveShelfLifeFields({
    expirationMode: requestedMode,
    expirationDate: input.expirationDate !== undefined
      ? input.expirationDate
      : (!switchedModes && requestedMode === "date" ? existingShelfLife.expirationDate : null),
    productionDate: input.productionDate !== undefined
      ? input.productionDate
      : (!switchedModes && requestedMode === "calc" ? existingShelfLife.productionDate : null),
    shelfLifeDays: input.shelfLifeDays !== undefined
      ? input.shelfLifeDays
      : (!switchedModes && requestedMode === "calc" ? existingShelfLife.shelfLifeDays : null)
  });
}

function addExpirationFlags(item, referenceDate = new Date()) {
  if (!item.expirationDate) {
    return {
      ...item,
      isExpiring: false,
      isExpired: false
    };
  }

  const today = formatUtcDateOnly(referenceDate);
  const expiringThreshold = addDaysToDateString(today, EXPIRING_WINDOW_DAYS);
  const isExpired = today > item.expirationDate;
  const isExpiring = !isExpired && item.expirationDate <= expiringThreshold;

  return {
    ...item,
    isExpiring,
    isExpired
  };
}

function createInventoryRouter({ pool }) {
  const router = express.Router();

  function invalidateInventoryCache(userId, relationship) {
    invalidateForUser(cache, Keys.inventory, userId, relationship);
  }

  router.get("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.userId);

      const cached = cache.get(Keys.inventory(userId));
      if (cached) return res.json(cached);

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      let query;
      let params;

      if (relationshipId) {
        query = `SELECT ${INVENTORY_SELECT_FIELDS}, ${INVENTORY_CATEGORY_SELECT}
                 FROM inventory ${INVENTORY_CATEGORY_JOIN} WHERE inventory.relationship_id = ? OR (inventory.user_id = ? AND inventory.relationship_id IS NULL) ORDER BY inventory.updated_at DESC`;
        params = [relationshipId, userId];
      } else {
        query = `SELECT ${INVENTORY_SELECT_FIELDS}, ${INVENTORY_CATEGORY_SELECT}
                 FROM inventory ${INVENTORY_CATEGORY_JOIN} WHERE inventory.user_id = ? AND inventory.relationship_id IS NULL ORDER BY inventory.updated_at DESC`;
        params = [userId];
      }

      const [rows] = await pool.execute(query, params);
      const items = rows.map(row => addExpirationFlags(withCategoryInfo({
        ...row,
        isLowStock: Number(row.quantity) <= Number(row.threshold)
      })));

      const responseData = {
        ok: true,
        message: "查询成功",
        data: {
          items,
          relationshipId
        }
      };
      cache.set(Keys.inventory(userId), responseData, TTL.INVENTORY);
      res.json(responseData);
    } catch (error) {
      next(error);
    }
  });

  router.get("/low-stock", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.userId);

      const cached = cache.get(Keys.inventory(userId));
      if (cached && cached.data && cached.data.items) {
        const lowItems = cached.data.items.filter(item => item.isLowStock);
        return res.json({
          ok: true,
          message: "查询成功",
          data: {
            items: lowItems,
            count: lowItems.length,
            relationshipId: cached.data.relationshipId
          }
        });
      }

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      let query;
      let params;

      if (relationshipId) {
        query = `SELECT ${INVENTORY_SELECT_FIELDS}, ${INVENTORY_CATEGORY_SELECT}
                 FROM inventory ${INVENTORY_CATEGORY_JOIN} WHERE (inventory.relationship_id = ? OR (inventory.user_id = ? AND inventory.relationship_id IS NULL)) AND inventory.quantity <= inventory.threshold ORDER BY inventory.quantity ASC`;
        params = [relationshipId, userId];
      } else {
        query = `SELECT ${INVENTORY_SELECT_FIELDS}, ${INVENTORY_CATEGORY_SELECT}
                 FROM inventory ${INVENTORY_CATEGORY_JOIN} WHERE inventory.user_id = ? AND inventory.relationship_id IS NULL AND inventory.quantity <= inventory.threshold ORDER BY inventory.quantity ASC`;
        params = [userId];
      }

      const [rows] = await pool.execute(query, params);
      const items = rows.map(row => addExpirationFlags(withCategoryInfo({
        ...row,
        isLowStock: Number(row.quantity) <= Number(row.threshold)
      })));

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
      const userId = parseRequiredInteger(req.userId);
      const name = trimValue(req.body.name);
      const legacyCategory = trimValue(req.body.category);
      const quantity = parseRequiredFloat(req.body.quantity);
      const unit = trimValue(req.body.unit);
      const threshold = parseRequiredFloat(req.body.threshold ?? 1);

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      // categoryId（spec 第 9 节）：显式提供时优先于 legacy 字符串 category，
      // 服务端按分类解析显示名称；未提供时走旧字符串路径，行为不变。
      let categoryEntity = null;
      if (req.body.categoryId !== undefined && req.body.categoryId !== null) {
        categoryEntity = await resolveWritableCategory(pool, {
          userId,
          relationshipId,
          domain: "inventory",
          direction: null,
          categoryId: req.body.categoryId
        });
      }
      const category = categoryEntity ? categoryEntity.name : legacyCategory;

      if (!name || !category || !unit) {
        throw new ApiError(400, "INVALID_REQUEST", "存货名称、类别和单位不能为空");
      }

      const imageUrl = normalizeNullableText(req.body.imageUrl);
      const note = normalizeNullableText(req.body.note);
      const aiImagePrompt = normalizeNullableText(req.body.aiImagePrompt);
      const shelfLife = deriveShelfLifeFields(req.body);
      const includesShelfLifeFields = hasShelfLifeFields(req.body);

      // 同名合并策略（spec 第 9 节物资模板兼容）：默认 'merge' 保持旧行为；
      // 模板流程用户显式选择「作为新物资保存」时传 'create'，跳过合并强制新建，
      // 避免不同单位的数量被相加。旧客户端不传此字段，行为完全不变。
      const duplicateStrategy = trimValue(req.body.duplicateStrategy) || "merge";
      if (duplicateStrategy !== "merge" && duplicateStrategy !== "create") {
        throw new ApiError(400, "INVALID_REQUEST", "duplicateStrategy 只支持 merge 或 create");
      }

      let findQuery;
      let findParams;
      if (relationshipId) {
        findQuery = "SELECT inventory_id, category, category_id, quantity, unit, threshold, image_url, note, ai_image_prompt, expiration_mode, DATE_FORMAT(expiration_date, '%Y-%m-%d') as expiration_date, DATE_FORMAT(production_date, '%Y-%m-%d') as production_date, shelf_life_days FROM inventory WHERE name = ? AND (relationship_id = ? OR (user_id = ? AND relationship_id IS NULL)) LIMIT 1 FOR UPDATE";
        findParams = [name, relationshipId, userId];
      } else {
        findQuery = "SELECT inventory_id, category, category_id, quantity, unit, threshold, image_url, note, ai_image_prompt, expiration_mode, DATE_FORMAT(expiration_date, '%Y-%m-%d') as expiration_date, DATE_FORMAT(production_date, '%Y-%m-%d') as production_date, shelf_life_days FROM inventory WHERE name = ? AND user_id = ? AND relationship_id IS NULL LIMIT 1 FOR UPDATE";
        findParams = [name, userId];
      }

      let responsePayload;
      let responseStatus = 200;
      await withTransaction(pool, async (conn) => {
        const [existing] = await conn.execute(findQuery, findParams);

        if (existing.length > 0 && duplicateStrategy !== "create") {
          const existingItem = existing[0];
          const newQuantity = Number(existingItem.quantity) + quantity;
          const updates = ["quantity = ?", "updated_at = NOW()"];
          const params = [newQuantity];

          if (imageUrl) {
            updates.push("image_url = ?");
            params.push(imageUrl);
          }
          if (note) {
            updates.push("note = ?");
            params.push(note);
          }

          if (includesShelfLifeFields) {
            updates.push("expiration_mode = ?");
            params.push(shelfLife.expirationMode);
            updates.push("expiration_date = ?");
            params.push(shelfLife.expirationDate);
            updates.push("production_date = ?");
            params.push(shelfLife.productionDate);
            updates.push("shelf_life_days = ?");
            params.push(shelfLife.shelfLifeDays);
          }

          params.push(existingItem.inventory_id);
          await conn.execute(
            `UPDATE inventory SET ${updates.join(", ")} WHERE inventory_id = ?`,
            params
          );

          const mergedShelfLife = includesShelfLifeFields
            ? shelfLife
            : {
                expirationMode: existingItem.expiration_mode,
                expirationDate: existingItem.expiration_date,
                productionDate: existingItem.production_date,
                shelfLifeDays: existingItem.shelf_life_days
              };

          const mergedItem = addExpirationFlags({
            inventoryId: existingItem.inventory_id,
            userId,
            relationshipId,
            name,
            category: existingItem.category,
            // 同名合并保持既有分类语义（spec 9：合并不改变既有条目的分类归属）
            categoryId: existingItem.category_id || null,
            imageUrl: imageUrl || existingItem.image_url,
            quantity: newQuantity,
            unit: existingItem.unit,
            threshold: Number(existingItem.threshold),
            note: note !== null ? note : existingItem.note,
            aiImagePrompt: existingItem.ai_image_prompt,
            expirationMode: mergedShelfLife.expirationMode,
            expirationDate: mergedShelfLife.expirationDate,
            productionDate: mergedShelfLife.productionDate,
            shelfLifeDays: mergedShelfLife.shelfLifeDays,
            isLowStock: newQuantity <= Number(existingItem.threshold)
          });

          responsePayload = {
            ok: true,
            message: "已合并到同名物资",
            data: mergedItem
          };
        } else {
          const [result] = await conn.execute(
            `INSERT INTO inventory (user_id, relationship_id, name, category, category_id, image_url, quantity, unit, threshold, note, ai_image_prompt, expiration_mode, expiration_date, production_date, shelf_life_days, created_at, updated_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())`,
            [
              userId,
              relationshipId,
              name,
              category,
              categoryEntity ? categoryEntity.id : null,
              imageUrl,
              quantity,
              unit,
              threshold,
              note,
              aiImagePrompt,
              shelfLife.expirationMode,
              shelfLife.expirationDate,
              shelfLife.productionDate,
              shelfLife.shelfLifeDays
            ]
          );

          const createdItem = addExpirationFlags({
            inventoryId: result.insertId,
            userId,
            relationshipId,
            name,
            category,
            categoryId: categoryEntity ? categoryEntity.id : null,
            imageUrl,
            quantity,
            unit,
            threshold,
            note,
            aiImagePrompt,
            expirationMode: shelfLife.expirationMode,
            expirationDate: shelfLife.expirationDate,
            productionDate: shelfLife.productionDate,
            shelfLifeDays: shelfLife.shelfLifeDays,
            isLowStock: quantity <= threshold
          });

          responseStatus = 201;
          responsePayload = {
            ok: true,
            message: "存货添加成功",
            data: createdItem
          };
        }
      });
      // withTransaction resolves only after COMMIT. Do cache invalidation and
      // send the success response afterwards so commit failures remain errors.
      invalidateInventoryCache(userId, relationship);
      res.status(responseStatus).json(responsePayload);
    } catch (error) {
      next(error);
    }
  });

  router.put("/:id", async (req, res, next) => {
    try {
      const inventoryId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(req.userId);
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

      let existingFieldsQuery;
      let existingFieldsParams;

      if (relationshipId) {
        existingFieldsQuery = "SELECT expiration_mode as expirationMode, DATE_FORMAT(expiration_date, '%Y-%m-%d') as expirationDate, DATE_FORMAT(production_date, '%Y-%m-%d') as productionDate, shelf_life_days as shelfLifeDays, relationship_id as relationshipId FROM inventory WHERE inventory_id = ? AND (relationship_id = ? OR (user_id = ? AND relationship_id IS NULL))";
        existingFieldsParams = [inventoryId, relationshipId, userId];
      } else {
        existingFieldsQuery = "SELECT expiration_mode as expirationMode, DATE_FORMAT(expiration_date, '%Y-%m-%d') as expirationDate, DATE_FORMAT(production_date, '%Y-%m-%d') as productionDate, shelf_life_days as shelfLifeDays, relationship_id as relationshipId FROM inventory WHERE inventory_id = ? AND user_id = ? AND relationship_id IS NULL";
        existingFieldsParams = [inventoryId, userId];
      }

      const [existingShelfLifeRows] = await pool.execute(existingFieldsQuery, existingFieldsParams);
      const existingShelfLife = existingShelfLifeRows[0] || {
        expirationMode: null,
        expirationDate: null,
        productionDate: null,
        shelfLifeDays: null
      };

      // categoryId（spec 第 9 节）：显式提供时优先于 legacy 字符串 category，
      // 服务端按分类解析显示名称；null 表示显式清除分类关联。
      const categoryIdProvided = Object.prototype.hasOwnProperty.call(req.body, "categoryId");
      if (categoryIdProvided) {
        const rawCategoryId = req.body.categoryId;
        if (rawCategoryId === null) {
          updates.push("category_id = ?");
          params.push(null);
        } else {
          const itemRelationshipId = existingShelfLifeRows[0] ? existingShelfLifeRows[0].relationshipId : null;
          const categoryEntity = await resolveWritableCategory(pool, {
            userId,
            relationshipId: itemRelationshipId === undefined ? null : itemRelationshipId,
            domain: "inventory",
            direction: null,
            categoryId: rawCategoryId
          });
          updates.push("category_id = ?");
          params.push(categoryEntity.id);
          updates.push("category = ?");
          params.push(categoryEntity.name);
        }
      }

      if (hasShelfLifeFields(req.body)) {
        const shelfLife = resolveShelfLifeUpdate(existingShelfLife, req.body);

        updates.push("expiration_mode = ?");
        params.push(shelfLife.expirationMode);
        updates.push("expiration_date = ?");
        params.push(shelfLife.expirationDate);
        updates.push("production_date = ?");
        params.push(shelfLife.productionDate);
        updates.push("shelf_life_days = ?");
        params.push(shelfLife.shelfLifeDays);
      }

      if (req.body.name !== undefined) {
        const name = trimValue(req.body.name);
        if (!name) {
          throw new ApiError(400, "INVALID_REQUEST", "存货名称不能为空");
        }
        updates.push("name = ?");
        params.push(name);
      }

      if (req.body.category !== undefined && !(categoryIdProvided && req.body.categoryId !== null)) {
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

      invalidateInventoryCache(userId, relationship);
      res.json({ ok: true, message: "存货更新成功" });
    } catch (error) {
      next(error);
    }
  });

  router.post("/:id/consume", async (req, res, next) => {
    try {
      const inventoryId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(req.userId);
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

      const [updateResult] = await pool.execute(
        "UPDATE inventory SET quantity = quantity - ?, last_consumed_at = NOW(), updated_at = NOW() WHERE inventory_id = ? AND quantity >= ?",
        [consumeAmount, inventoryId, consumeAmount]
      );
      if (updateResult.affectedRows === 0) {
        throw new ApiError(400, "INSUFFICIENT_STOCK", "存量不足");
      }

      const [afterRows] = await pool.execute(
        "SELECT quantity FROM inventory WHERE inventory_id = ?",
        [inventoryId]
      );
      const remainingQuantity = afterRows.length > 0 ? Number(afterRows[0].quantity) : 0;

      invalidateInventoryCache(userId, relationship);
      res.json({
        ok: true,
        message: "消耗记录成功",
        data: {
          consumed: consumeAmount,
          remainingQuantity
        }
      });
    } catch (error) {
      next(error);
    }
  });

  router.post("/:id/replenish", async (req, res, next) => {
    try {
      const inventoryId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(req.userId);
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

      const currentQuantity = Number(existing[0].quantity);
      await pool.execute(
        "UPDATE inventory SET quantity = quantity + ?, updated_at = NOW() WHERE inventory_id = ?",
        [addAmount, inventoryId]
      );

      invalidateInventoryCache(userId, relationship);
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
      const userId = parseRequiredInteger(req.userId);
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

      invalidateInventoryCache(userId, relationship);
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

      const apiKey = process.env.AI_IMAGE_API_KEY || process.env.AI_API_KEY;
      const baseUrl = process.env.AI_IMAGE_BASE_URL || "";
      const model = process.env.AI_IMAGE_MODEL || "gpt-image-2";

      if (!apiKey || !baseUrl || !model) {
        throw new ApiError(503, "AI_NOT_CONFIGURED", "AI 生图服务未配置");
      }

      const generatedPrompt = prompt || `${category || ""} ${name} 产品图，高清实物照片风格`;

      const payload = {
        model,
        prompt: generatedPrompt,
        n: 1,
        size: "1024x1024"
      };

      const aiResult = await callImageApi(baseUrl, apiKey, payload);
      const firstItem = aiResult.data && aiResult.data[0];
      let imageUrl = null;
      if (firstItem) {
        if (firstItem.url) {
          imageUrl = firstItem.url;
        } else if (firstItem.b64_json) {
          const buffer = Buffer.from(firstItem.b64_json, "base64");
          const filename = `ai_${Date.now()}.png`;
          const savePath = path.join(__dirname, "../../uploads", filename);
          await fsPromises.writeFile(savePath, buffer);
          imageUrl = `/uploads/${filename}`;
        }
      }

      if (!imageUrl) {
        throw new ApiError(502, "AI_ERROR", "AI 生图未返回有效图片");
      }

      res.json({
        ok: true,
        data: {
          prompt: generatedPrompt,
          imageUrl
        }
      });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = {
  createInventoryRouter,
  deriveShelfLifeFields,
  addExpirationFlags,
  EXPIRING_WINDOW_DAYS,
  INVENTORY_SELECT_FIELDS
};
