/**
 * 分类集合 + 预设导入（物资/账单）路由 —— spec: docs/CATEGORY_PRESETS_SPEC_20261002.md（小程序仓库）第 8.2 节。
 *
 * 关键不变量：
 *   - 身份只来自 req.userId（JWT）；客户端不得凭 userId/relationshipId 自行构造授权范围；
 *   - domain 只用白名单值查固定表名，绝不用客户端 domain 拼 SQL 表名（spec 8.1）；
 *   - 所有写路径：授权（事务内锁集合行）→ 幂等回放 → 版本校验 → 写；审计与 receipt 同事务；
 *   - preset_bindings 是预设身份唯一权威：删除记 tombstone，恢复必须找回原 entity_id；
 *   - 集合创建同事务播撒基础预设包（spec 5.0，见 services/categorySeed.js），
 *     baseline_seeded_at 标志非空后绝不重复播撒；读路径不带「空就补」逻辑；
 *   - 归档分类不迁移账单/物资历史数据，只迁移启用模板；历史保留原 category_id 由快照兜底；
 *   - 新增分类/模板、归档、模板迁移、预设导入都会递增集合 version，使排序冲突可检测。
 */
const express = require("express");
const crypto = require("crypto");

const { ApiError } = require("../errors");
const { withTransaction } = require("../utils/transactions");
const {
  PACKS,
  normalizeNameKey,
  listPacks,
  getCategoryPreset,
  getTemplatePreset
} = require("../services/categoryPresets");
const { seedBaselinePack } = require("../services/categorySeed");

const MAX_ACTIVE_CATEGORIES = 50;
const MAX_ACTIVE_TEMPLATES = 200;
const MAX_PINNED = 12;
const DEFAULT_LIMIT = 20;
const MAX_LIMIT = 50;
const MAX_PRESET_KEYS = 200;

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const COLOR_RE = /^#[0-9a-fA-F]{6}$/;
const DEC2_RE = /^\d{1,8}(\.\d{1,2})?$/;

const OPERATIONS = Object.freeze({
  CATEGORIES_CREATE: "categories.create",
  CATEGORIES_UPDATE: "categories.update",
  CATEGORIES_ARCHIVE: "categories.archive",
  CATEGORIES_UNARCHIVE: "categories.unarchive",
  ORDER_UPDATE: "order.update",
  PRESETS_APPLY: "presets.apply",
  TEMPLATES_CREATE: "templates.create",
  TEMPLATES_UPDATE: "templates.update",
  PREFERENCES_UPDATE: "preferences.update"
});

// domain → 业务引用表（白名单映射，禁止用客户端输入拼表名，spec 8.1）
const REFERENCE_TABLE_BY_DOMAIN = Object.freeze({
  inventory: { table: "inventory", column: "category_id" },
  bills: { table: "bills", column: "category_id" }
});

function uuid() {
  return crypto.randomUUID();
}

function codePointLength(value) {
  return [...String(value)].length;
}

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function isUuid(value) {
  return typeof value === "string" && UUID_RE.test(value);
}

function reject(req, status, code, message, details) {
  return new ApiError(status, code, message, { ...(details || {}), requestId: req.requestId || uuid() });
}

function validationError(req, message, fieldErrors) {
  return reject(req, 422, "VALIDATION_ERROR", message, { fieldErrors });
}

function fieldError(path, code, message) {
  return { path, code, message };
}

function requireMutationId(req, body) {
  const mutationId = body && body.clientMutationId;
  if (mutationId === undefined || mutationId === null || mutationId === "") {
    throw validationError(req, "缺少 clientMutationId", [fieldError("clientMutationId", "REQUIRED", "clientMutationId 必填")]);
  }
  if (!isUuid(mutationId)) {
    throw validationError(req, "clientMutationId 必须是 UUID", [fieldError("clientMutationId", "INVALID_TYPE", "clientMutationId 必须是 UUID 字符串")]);
  }
  return mutationId;
}

function parseExpectedVersion(req, raw, path = "expectedVersion") {
  if (raw === undefined || raw === null || raw === "") {
    throw validationError(req, `缺少 ${path}`, [fieldError(path, "REQUIRED", `${path} 必填`)]);
  }
  const value = typeof raw === "number" ? raw : Number(String(raw).trim());
  if (!Number.isSafeInteger(value) || value < 1) {
    throw validationError(req, `${path} 无效`, [fieldError(path, "INVALID_TYPE", `${path} 必须是正整数`)]);
  }
  return value;
}

function parseLimit(req, raw) {
  if (raw === undefined || raw === null || raw === "") return DEFAULT_LIMIT;
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value < 1 || value > MAX_LIMIT) {
    throw validationError(req, "limit 无效", [fieldError("limit", "OUT_OF_RANGE", `limit 必须是 1–${MAX_LIMIT} 的整数`)]);
  }
  return value;
}

function parseStatusFilter(req, raw) {
  if (raw === undefined || raw === null || raw === "") return "active";
  if (raw !== "active" && raw !== "archived" && raw !== "all") {
    throw validationError(req, "status 无效", [fieldError("status", "INVALID_OPTION", "status 只能是 active/archived/all")]);
  }
  return raw;
}

function validateColor(req, value, path) {
  if (value === undefined || value === null) return null;
  if (typeof value !== "string" || !COLOR_RE.test(value)) {
    throw validationError(req, "颜色格式错误", [fieldError(path, "INVALID_TYPE", "颜色必须是 #RRGGBB")]);
  }
  return value;
}

function isSingleGrapheme(value) {
  if (typeof value !== "string" || value === "") return false;
  if (codePointLength(value) > 8) return false;
  if (typeof Intl !== "undefined" && Intl.Segmenter) {
    const segmenter = new Intl.Segmenter("zh", { granularity: "grapheme" });
    return [...segmenter.segment(value)].length === 1;
  }
  return codePointLength(value) === 1;
}

function validateIcon(req, value, path) {
  if (value === undefined || value === null) return null;
  if (!isPlainObject(value)) {
    throw validationError(req, "图标格式错误", [fieldError(path, "INVALID_TYPE", "图标必须是 {type,value} 对象")]);
  }
  const type = value.type;
  const iconValue = value.value;
  if (type !== "iconKey" && type !== "emoji") {
    throw validationError(req, "图标类型错误", [fieldError(`${path}.type`, "INVALID_OPTION", "图标 type 只能是 iconKey/emoji")]);
  }
  if (typeof iconValue !== "string" || iconValue.trim() === "") {
    throw validationError(req, "图标内容错误", [fieldError(`${path}.value`, "REQUIRED", "图标 value 必填")]);
  }
  if (codePointLength(iconValue) > 40) {
    throw validationError(req, "图标内容过长", [fieldError(`${path}.value`, "OUT_OF_RANGE", "图标 value 最多 40 字符")]);
  }
  if (type === "emoji" && !isSingleGrapheme(iconValue)) {
    throw validationError(req, "emoji 图标必须是单个图形簇", [fieldError(`${path}.value`, "INVALID_TYPE", "emoji 必须是单个图形簇")]);
  }
  return { type, value: iconValue };
}

function validateCategoryName(req, raw) {
  if (typeof raw !== "string") {
    throw validationError(req, "分类名称必须是字符串", [fieldError("name", "INVALID_TYPE", "name 必须是字符串")]);
  }
  const name = raw.normalize("NFC").trim();
  if (codePointLength(name) < 1 || codePointLength(name) > 20) {
    throw validationError(req, "分类名称长度错误", [fieldError("name", "OUT_OF_RANGE", "名称 trim 后 1–20 字符")]);
  }
  return name;
}

/** 建议数值（金额/告警线）：十进制字符串最多两位小数；null 表示无建议值，不补 0。 */
function validateSuggestedDecimal(req, raw, path, { min, max, label }) {
  if (raw === undefined || raw === null || raw === "") return null;
  if (typeof raw !== "string" || !DEC2_RE.test(raw.trim())) {
    throw validationError(req, `${label}格式错误`, [fieldError(path, "INVALID_TYPE", `${label}必须是最多两位小数的十进制字符串`)]);
  }
  const value = Number(raw.trim());
  if (value < min || value > max) {
    throw validationError(req, `${label}超出范围`, [fieldError(path, "OUT_OF_RANGE", `${label}必须在 ${min}–${max} 之间`)]);
  }
  const [intPart, fracPart = ""] = raw.trim().split(".");
  return `${intPart}.${(fracPart + "00").slice(0, 2)}`;
}

/** 排序数组整体校验（spec 7）：无重复、恰为同集合全部启用普通分类。返回 null 或错误数组。 */
function validateOrderIds(categoryIds, activeIds) {
  const errors = [];
  if (!Array.isArray(categoryIds)) {
    return [fieldError("categoryIds", "INVALID_TYPE", "categoryIds 必须是数组")];
  }
  const seen = new Set();
  categoryIds.forEach((id, index) => {
    if (!isUuid(id)) {
      errors.push(fieldError(`categoryIds.${index}`, "INVALID_TYPE", "分类 ID 必须是 UUID"));
    } else if (seen.has(id)) {
      errors.push(fieldError(`categoryIds.${index}`, "DUPLICATE", "categoryIds 不能重复"));
    } else {
      seen.add(id);
    }
  });
  if (errors.length > 0) return errors;
  const activeSet = new Set(activeIds);
  const missing = activeIds.filter((id) => !seen.has(id));
  const foreign = categoryIds.filter((id) => !activeSet.has(id));
  if (missing.length > 0) {
    errors.push(fieldError("categoryIds", "INCOMPLETE", `排序数组缺少 ${missing.length} 个启用分类，必须包含同集合全部启用分类`));
  }
  if (foreign.length > 0) {
    errors.push(fieldError("categoryIds", "FOREIGN_ID", "排序数组包含不属于本集合（或已归档）的分类 ID"));
  }
  return errors.length > 0 ? errors : null;
}

/** 规范化 body（递归排序键、剔除 clientMutationId）后参与 requestHash。 */
function canonicalize(value) {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (isPlainObject(value)) {
    const out = {};
    for (const key of Object.keys(value).sort()) {
      if (key === "clientMutationId" || value[key] === undefined) continue;
      out[key] = canonicalize(value[key]);
    }
    return out;
  }
  return value;
}

function buildRequestHash({ method, resourcePath, targetId, body }) {
  const canonical = JSON.stringify({
    method,
    path: resourcePath,
    targetId: targetId || null,
    body: canonicalize(body || {})
  });
  return crypto.createHash("sha256").update(canonical, "utf8").digest("hex");
}

function parseReceiptJson(value) {
  if (value === null || value === undefined) return null;
  if (typeof value === "string") return JSON.parse(value);
  return value;
}

function dbDecimal(value) {
  if (value === null || value === undefined) return null;
  if (typeof value === "string") return value;
  return Number(value).toFixed(2);
}

function collectionToApi(row, canWrite) {
  return {
    collectionId: row.id,
    domain: row.domain,
    direction: row.direction || null,
    scope: row.scope,
    version: Number(row.version),
    status: row.status,
    canWrite: Boolean(canWrite)
  };
}

function categoryToApi(row) {
  return {
    categoryId: row.id,
    collectionId: row.collection_id,
    name: row.name,
    icon: row.icon_type ? { type: row.icon_type, value: row.icon_value } : null,
    color: row.color,
    sortOrder: Number(row.sort_order),
    status: row.status,
    version: Number(row.version),
    source: row.source_preset_key
      ? { packKey: row.source_pack_key, presetKey: row.source_preset_key, packVersion: Number(row.source_pack_version) }
      : null
  };
}

function templateToApi(row) {
  return {
    templateId: row.id,
    collectionId: row.collection_id,
    categoryId: row.category_id,
    name: row.name,
    icon: row.icon_type ? { type: row.icon_type, value: row.icon_value } : null,
    defaultUnit: row.default_unit,
    suggestedAlertLine: dbDecimal(row.suggested_alert_line),
    remark: row.remark,
    note: row.note,
    suggestedAmount: dbDecimal(row.suggested_amount),
    sortOrder: Number(row.sort_order),
    status: row.status,
    version: Number(row.version),
    source: row.source_preset_key
      ? { packKey: row.source_pack_key, presetKey: row.source_preset_key, packVersion: Number(row.source_pack_version) }
      : null
  };
}

function createCategoryCollectionsRouter({ pool }) {
  const router = express.Router();

  router.use((req, _res, next) => {
    req.requestId = uuid();
    next();
  });

  /* ================================================================ *
   * 授权
   * ================================================================ */

  /**
   * 集合授权：个人集合仅本人可读可写；共享集合要求本人是当前有效关系成员。
   * 失权一律 404（不泄露集合存在性）；关系失效的共享集合可读但 canWrite=false，
   * 写操作 409 RELATIONSHIP_CHANGED（spec 3：关系失效时禁止继续编辑其集合）。
   */
  async function authorizeCollection(req, conn, userId, collectionId, { forWrite = false, lock = false } = {}) {
    // Account deletion locks member users before relationship and collection rows.
    // Take the actor's user lock first here too, so a category write cannot hold
    // collection/relationship locks while account deletion waits on an audit FK.
    if (lock) {
      const [users] = await conn.execute("SELECT id FROM users WHERE id = ? FOR UPDATE", [userId]);
      if (users.length === 0) {
        throw reject(req, 404, "RESOURCE_NOT_FOUND", "分类集合不存在");
      }
    }
    const lockClause = lock ? " FOR UPDATE" : "";
    const [rows] = await conn.execute(
      `SELECT c.*, r.status AS relationship_status, r.user_id_1 AS rel_u1, r.user_id_2 AS rel_u2
       FROM category_collections c
       LEFT JOIN couple_relationships r ON r.relationship_id = c.relationship_id
       WHERE c.id = ?${lockClause}`,
      [collectionId]
    );
    const collection = rows[0];
    if (!collection) {
      throw reject(req, 404, "RESOURCE_NOT_FOUND", "分类集合不存在");
    }
    let canWrite = false;
    if (collection.scope === "personal") {
      if (Number(collection.owner_user_id) !== Number(userId)) {
        throw reject(req, 404, "RESOURCE_NOT_FOUND", "分类集合不存在");
      }
      canWrite = collection.status === "active";
    } else {
      const isArchivedMember = collection.status === "closed" && collection.relationship_id === null
        && Number(collection.archived_for_user_id) === Number(userId);
      if (isArchivedMember) {
        // Account deletion detaches the immutable collection history from its
        // relationship; only the surviving member can read this closed copy.
        canWrite = false;
      } else {
        const isMember = collection.relationship_id !== null &&
          (Number(collection.rel_u1) === Number(userId) || Number(collection.rel_u2) === Number(userId));
        if (!isMember) {
          throw reject(req, 404, "RESOURCE_NOT_FOUND", "分类集合不存在");
        }
        canWrite = collection.status === "active" && collection.relationship_status === "active";
      }
    }
    if (forWrite && !canWrite) {
      if (collection.scope === "couple" && collection.relationship_status !== "active") {
        throw reject(req, 409, "RELATIONSHIP_CHANGED", "关系已失效，该共享集合禁止编辑");
      }
      throw reject(req, 409, "COLLECTION_CLOSED", "集合已关闭，禁止编辑");
    }
    return { collection, canWrite };
  }

  async function bumpCollectionVersion(conn, collectionId) {
    await conn.execute(
      "UPDATE category_collections SET version = version + 1 WHERE id = ?",
      [collectionId]
    );
    const [rows] = await conn.execute(
      "SELECT version FROM category_collections WHERE id = ?",
      [collectionId]
    );
    return rows[0] ? Number(rows[0].version) : 0;
  }

  async function writeAudit(conn, { collectionId, actorUserId, action, entityType, entityId, beforeJson, afterJson }) {
    await conn.execute(
      `INSERT INTO collection_audits
         (collection_id, actor_user_id, action, entity_type, entity_id, before_json, after_json)
       VALUES (?, ?, ?, ?, ?, ?, ?)`,
      [
        collectionId,
        actorUserId,
        action,
        entityType,
        entityId,
        beforeJson ? JSON.stringify(beforeJson) : null,
        afterJson ? JSON.stringify(afterJson) : null
      ]
    );
  }

  /**
   * 幂等写：唯一范围 collectionId + actor + operation + mutationId。
   * 同 ID 同 hash → 回放原 result（replayed:true，不写库）；同 ID 异 hash → 409。
   */
  async function runMutation({ req, conn, userId, collectionId, operation, mutationId, requestHash, executeWrite }) {
    const selectReceipt = async () => {
      const [rows] = await conn.execute(
        `SELECT request_hash, result_json FROM collection_mutations
         WHERE collection_id = ? AND actor_user_id = ? AND operation = ? AND client_mutation_id = ?`,
        [collectionId, userId, operation, mutationId]
      );
      return rows[0] || null;
    };

    const existing = await selectReceipt();
    if (existing) {
      if (existing.request_hash === requestHash) {
        return { replayed: true, result: parseReceiptJson(existing.result_json) };
      }
      throw reject(req, 409, "IDEMPOTENCY_CONFLICT", "相同 clientMutationId 的请求内容不一致，请核对原提交");
    }

    const outcome = await executeWrite();
    try {
      await conn.execute(
        `INSERT INTO collection_mutations
           (collection_id, actor_user_id, operation, client_mutation_id, request_hash, result_json)
         VALUES (?, ?, ?, ?, ?, ?)`,
        [collectionId, userId, operation, mutationId, requestHash, JSON.stringify(outcome.result)]
      );
    } catch (error) {
      if (error && error.code === "ER_DUP_ENTRY") {
        const raced = await selectReceipt();
        if (raced && raced.request_hash === requestHash) {
          return { replayed: true, result: parseReceiptJson(raced.result_json) };
        }
        throw reject(req, 409, "IDEMPOTENCY_CONFLICT", "相同 clientMutationId 的请求内容不一致，请核对原提交");
      }
      throw error;
    }
    return { replayed: false, result: outcome.result };
  }

  /** 写端点公共骨架：事务 + 授权（先于幂等回放）+ 幂等执行。 */
  async function performWrite(req, collectionId, operation, mutationId, requestHash, executeWrite) {
    return withTransaction(pool, async (conn) => {
      const auth = await authorizeCollection(req, conn, req.userId, collectionId, { forWrite: true, lock: true });
      return runMutation({
        req,
        conn,
        userId: req.userId,
        collectionId,
        operation,
        mutationId,
        requestHash,
        executeWrite: () => executeWrite(conn, auth)
      });
    });
  }

  async function loadCategoryRow(conn, collectionId, categoryId, { lock = false } = {}) {
    const [rows] = await conn.execute(
      `SELECT * FROM item_categories WHERE id = ? AND collection_id = ? LIMIT 1${lock ? " FOR UPDATE" : ""}`,
      [categoryId, collectionId]
    );
    return rows[0] || null;
  }

  async function loadTemplateRow(conn, collectionId, templateId, { lock = false } = {}) {
    const [rows] = await conn.execute(
      `SELECT * FROM item_templates WHERE id = ? AND collection_id = ? LIMIT 1${lock ? " FOR UPDATE" : ""}`,
      [templateId, collectionId]
    );
    return rows[0] || null;
  }

  /** 引用计数：启用模板 + 业务表历史引用（按 domain 白名单选表）。 */
  async function countCategoryReferences(conn, collection, categoryId) {
    const [templateRows] = await conn.execute(
      "SELECT COUNT(*) AS total FROM item_templates WHERE collection_id = ? AND category_id = ? AND status = 'active'",
      [collection.id, categoryId]
    );
    const ref = REFERENCE_TABLE_BY_DOMAIN[collection.domain];
    // legacy 行 category_id 为 NULL，不参与引用计数（只读提示见 delete-preview 响应 legacyNote）
    const [refRows] = await conn.execute(
      `SELECT COUNT(*) AS total FROM \`${ref.table}\` WHERE \`${ref.column}\` = ?`,
      [categoryId]
    );
    return {
      templates: Number(templateRows[0].total),
      records: Number(refRows[0].total)
    };
  }

  /* ================================================================ *
   * 集合发现（惰性创建）
   * ================================================================ */

  const DIRECTIONS_BY_DOMAIN = Object.freeze({
    inventory: [null],
    bills: ["expense", "income"]
  });

  async function ensureCollection(conn, { domain, direction, scope, ownerUserId, relationshipId, actorUserId }) {
    const selectSql = scope === "personal"
      ? `SELECT * FROM category_collections
         WHERE domain = ? AND scope = 'personal' AND owner_user_id = ?
           AND ${direction === null ? "direction IS NULL" : "direction = ?"} LIMIT 1`
      : `SELECT * FROM category_collections
         WHERE domain = ? AND scope = 'couple' AND relationship_id = ?
           AND ${direction === null ? "direction IS NULL" : "direction = ?"} LIMIT 1`;
    const selectParams = direction === null
      ? [domain, scope === "personal" ? ownerUserId : relationshipId]
      : [domain, scope === "personal" ? ownerUserId : relationshipId, direction];

    const insert = async () => {
      const id = uuid();
      // 创建即播撒（spec 5.0）：baseline_seeded_at 随 INSERT 写 NOW(3)，同事务播撒基础包；
      // 播撒失败整个事务回滚，集合不会以未播撒状态落库
      await conn.execute(
        `INSERT INTO category_collections (id, domain, direction, scope, owner_user_id, relationship_id, baseline_seeded_at, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, NOW(3), NOW(3), NOW(3))`,
        [id, domain, direction, scope, ownerUserId, relationshipId]
      );
      return id;
    };

    let [rows] = await conn.execute(selectSql, selectParams);
    if (rows.length === 0) {
      let createdId = null;
      try {
        createdId = await insert();
      } catch (error) {
        // 并发惰性创建撞唯一键：重读已有行（users/relationship 行锁已兜底，这里是双保险）
        if (!error || error.code !== "ER_DUP_ENTRY") throw error;
      }
      [rows] = await conn.execute(selectSql, selectParams);
      // 只有本事务真正插入成功才播撒；撞唯一键说明对方事务负责播撒
      if (createdId !== null && rows[0] && rows[0].id === createdId) {
        await seedBaselinePack(conn, rows[0], actorUserId);
      }
    }
    return rows[0];
  }

  router.get("/category-collections", async (req, res, next) => {
    try {
      const userId = req.userId;
      const domain = req.query.domain;
      if (domain !== "inventory" && domain !== "bills") {
        throw validationError(req, "domain 无效", [fieldError("domain", "INVALID_OPTION", "domain 只能是 inventory/bills")]);
      }
      const directions = DIRECTIONS_BY_DOMAIN[domain];

      const collections = await withTransaction(pool, async (conn) => {
        // 锁本人 users 行：串行化同一用户的惰性创建，避免并发撞唯一键
        const [meRows] = await conn.execute("SELECT id FROM users WHERE id = ? FOR UPDATE", [userId]);
        if (meRows.length === 0) {
          throw reject(req, 401, "UNAUTHORIZED", "用户不存在");
        }
        const out = [];
        for (const direction of directions) {
          const personal = await ensureCollection(conn, {
            domain, direction, scope: "personal", ownerUserId: userId, relationshipId: null, actorUserId: userId
          });
          out.push({ row: personal, canWrite: personal.status === "active" });
        }
        // 当前有效关系的共享集合（无关系则不返回共享集合，也不创建）
        const [relRows] = await conn.execute(
          `SELECT relationship_id, user_id_1, user_id_2 FROM couple_relationships
           WHERE status = 'active' AND (user_id_1 = ? OR user_id_2 = ?)
           ORDER BY relationship_id DESC LIMIT 1 FOR UPDATE`,
          [userId, userId]
        );
        if (relRows.length > 0) {
          const rel = relRows[0];
          for (const direction of directions) {
            const shared = await ensureCollection(conn, {
              domain, direction, scope: "couple", ownerUserId: null, relationshipId: rel.relationship_id, actorUserId: userId
            });
            out.push({ row: shared, canWrite: shared.status === "active" });
          }
        }
        return out;
      });

      res.json({
        ok: true,
        data: { collections: collections.map(({ row, canWrite }) => collectionToApi(row, canWrite)) }
      });
    } catch (error) {
      next(error);
    }
  });

  /* ================================================================ *
   * 预设目录 / 预览 / 导入
   * ================================================================ */

  router.get("/category-presets", async (req, res, next) => {
    try {
      const domain = req.query.domain;
      if (domain !== "inventory" && domain !== "bills") {
        throw validationError(req, "domain 无效", [fieldError("domain", "INVALID_OPTION", "domain 只能是 inventory/bills")]);
      }
      let direction = null;
      if (domain === "bills") {
        if (req.query.direction !== "expense" && req.query.direction !== "income") {
          throw validationError(req, "direction 无效", [fieldError("direction", "INVALID_OPTION", "账单必须指定 direction=expense/income")]);
        }
        direction = req.query.direction;
      }
      const packs = listPacks({ domain, direction }).map((pack) => ({
        packKey: pack.packKey,
        packVersion: pack.packVersion,
        domain: pack.domain,
        direction: pack.direction,
        name: pack.name,
        categories: pack.categories.map((category) => ({
          presetKey: category.presetKey,
          name: category.name,
          icon: category.icon,
          color: category.color,
          templates: category.templates.map((template) => ({ ...template }))
        }))
      }));
      res.json({ ok: true, data: { packs } });
    } catch (error) {
      next(error);
    }
  });

  /** 解析并校验 selectedPresetKeys：已知、且属于该集合的 domain/direction。 */
  function parseSelectedPresetKeys(req, raw, collection) {
    if (!Array.isArray(raw)) {
      throw validationError(req, "selectedPresetKeys 必须是数组", [fieldError("selectedPresetKeys", "INVALID_TYPE", "selectedPresetKeys 必须是 presetKey 数组")]);
    }
    if (raw.length > MAX_PRESET_KEYS) {
      throw validationError(req, "selectedPresetKeys 过多", [fieldError("selectedPresetKeys", "OUT_OF_RANGE", `一次最多 ${MAX_PRESET_KEYS} 个 presetKey`)]);
    }
    const keys = [];
    const seen = new Set();
    raw.forEach((key, index) => {
      if (typeof key !== "string" || key.trim() === "") {
        throw validationError(req, "presetKey 无效", [fieldError(`selectedPresetKeys.${index}`, "INVALID_TYPE", "presetKey 必须是非空字符串")]);
      }
      if (seen.has(key)) {
        throw validationError(req, "presetKey 重复", [fieldError(`selectedPresetKeys.${index}`, "DUPLICATE", "selectedPresetKeys 不能重复")]);
      }
      seen.add(key);
      const categoryPreset = getCategoryPreset(key);
      const templatePreset = getTemplatePreset(key);
      const entry = categoryPreset || templatePreset;
      if (!entry) {
        throw validationError(req, "未知 presetKey", [fieldError(`selectedPresetKeys.${index}`, "INVALID_OPTION", `未知 presetKey：${key}`)]);
      }
      const pack = entry.pack;
      const matchesDomain = pack.domain === collection.domain;
      const matchesDirection = collection.domain !== "bills" || pack.direction === collection.direction;
      if (!matchesDomain || !matchesDirection) {
        throw validationError(req, "presetKey 不属于该集合", [
          fieldError(`selectedPresetKeys.${index}`, "WRONG_COLLECTION", `presetKey ${key} 不属于本集合的 domain/direction`)
        ]);
      }
      keys.push(key);
    });
    return keys;
  }

  async function loadBindings(conn, collectionId) {
    const [rows] = await conn.execute(
      "SELECT * FROM preset_bindings WHERE collection_id = ?",
      [collectionId]
    );
    return rows;
  }

  /** 预览各项状态（不写库）。自建同名（无绑定）的启用分类 → name_conflict。 */
  async function previewItems(conn, collection, keys) {
    const bindings = await loadBindings(conn, collection.id);
    const bindingByKey = new Map(bindings.map((b) => [`${b.entity_type}:${b.preset_key}`, b]));
    const [activeCategories] = await conn.execute(
      "SELECT id, name_key FROM item_categories WHERE collection_id = ? AND status = 'active'",
      [collection.id]
    );
    const activeCategoryByNameKey = new Map(activeCategories.map((c) => [c.name_key, c.id]));

    const items = [];
    for (const key of keys) {
      const categoryPreset = getCategoryPreset(key);
      const entityType = categoryPreset ? "category" : "template";
      const preset = categoryPreset || getTemplatePreset(key);
      const binding = bindingByKey.get(`${entityType}:${key}`);
      if (binding && binding.status === "active") {
        items.push({ presetKey: key, entityType, status: "already_imported", entityId: binding.entity_id });
        continue;
      }
      if (binding && binding.status === "tombstone") {
        items.push({ presetKey: key, entityType, status: "deleted_tombstone", entityId: binding.entity_id });
        continue;
      }
      if (entityType === "category") {
        const nameKey = normalizeNameKey(preset.category.name);
        const conflictId = activeCategoryByNameKey.get(nameKey);
        if (conflictId) {
          items.push({ presetKey: key, entityType, status: "name_conflict", existingCategoryId: conflictId });
          continue;
        }
      }
      items.push({ presetKey: key, entityType, status: "will_create" });
    }
    return items;
  }

  router.post("/category-collections/:id/preset-preview", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { id } = req.params;
      const body = req.body || {};
      const data = await withTransaction(pool, async (conn) => {
        const auth = await authorizeCollection(req, conn, userId, id, { forWrite: false });
        const keys = parseSelectedPresetKeys(req, body.selectedPresetKeys, auth.collection);
        const items = await previewItems(conn, auth.collection, keys);
        return {
          collectionVersion: Number(auth.collection.version),
          canWrite: auth.canWrite,
          items
        };
      });
      res.json({ ok: true, data });
    } catch (error) {
      next(error);
    }
  });

  router.post("/category-collections/:id/presets/apply", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { id } = req.params;
      const body = req.body || {};
      const mutationId = requireMutationId(req, body);
      const expectedCollectionVersion = parseExpectedVersion(req, body.expectedCollectionVersion, "expectedCollectionVersion");
      const requestHash = buildRequestHash({
        method: "POST",
        resourcePath: "/category-collections/:id/presets/apply",
        targetId: null,
        body
      });

      const outcome = await performWrite(req, id, OPERATIONS.PRESETS_APPLY, mutationId, requestHash, async (conn, auth) => {
        const collection = auth.collection;
        // 先做完参数级校验（422 定位字段优先于 409 版本冲突），再复核集合版本
        const keys = parseSelectedPresetKeys(req, body.selectedPresetKeys, collection);

        const restoreKeys = body.restoreKeys === undefined || body.restoreKeys === null ? [] : body.restoreKeys;
        if (!Array.isArray(restoreKeys) || restoreKeys.some((k) => typeof k !== "string")) {
          throw validationError(req, "restoreKeys 必须是数组", [fieldError("restoreKeys", "INVALID_TYPE", "restoreKeys 必须是 presetKey 数组")]);
        }
        const restoreSet = new Set(restoreKeys);
        for (const key of restoreSet) {
          if (!keys.includes(key)) {
            throw validationError(req, "restoreKeys 必须属于选中项", [fieldError("restoreKeys", "INVALID_OPTION", `restoreKeys 中的 ${key} 不在 selectedPresetKeys 内`)]);
          }
        }

        // restoreRenames：恢复时名称被占，用户显式改名（{presetKey: 新名称}）
        const restoreRenames = body.restoreRenames === undefined || body.restoreRenames === null ? {} : body.restoreRenames;
        if (!isPlainObject(restoreRenames)) {
          throw validationError(req, "restoreRenames 必须是对象", [fieldError("restoreRenames", "INVALID_TYPE", "restoreRenames 必须是 {presetKey: 新名称}")]);
        }

        // existingCategoryMappings：自建同名分类的显式映射（{presetKey: categoryId}）
        const mappings = body.existingCategoryMappings === undefined || body.existingCategoryMappings === null ? {} : body.existingCategoryMappings;
        if (!isPlainObject(mappings)) {
          throw validationError(req, "existingCategoryMappings 必须是对象", [fieldError("existingCategoryMappings", "INVALID_TYPE", "existingCategoryMappings 必须是 {presetKey: categoryId}")]);
        }

        if (Number(collection.version) !== expectedCollectionVersion) {
          throw reject(req, 409, "VERSION_CONFLICT", "集合已被修改，请刷新后重试", {
            currentVersion: Number(collection.version),
            entityType: "collection",
            entityId: id
          });
        }
        const bindings = await loadBindings(conn, id);
        const bindingByKey = new Map(bindings.map((b) => [`${b.entity_type}:${b.preset_key}`, b]));
        const [activeCategoryRows] = await conn.execute(
          "SELECT * FROM item_categories WHERE collection_id = ? AND status = 'active'",
          [id]
        );
        const activeCategoryByNameKey = new Map(activeCategoryRows.map((c) => [c.name_key, c]));
        const activeCategoryById = new Map(activeCategoryRows.map((c) => [c.id, c]));

        // 展开的选中集：模板选中时所属分类自动视为选中（spec 5.1：勾选模板会选择其所属分类）
        const categoryKeys = new Set();
        const templateKeys = new Set();
        for (const key of keys) {
          const categoryPreset = getCategoryPreset(key);
          if (categoryPreset) {
            categoryKeys.add(key);
          } else {
            const templatePreset = getTemplatePreset(key);
            templateKeys.add(key);
            categoryKeys.add(templatePreset.category.presetKey);
          }
        }

        // 校验映射目标与恢复墓碑
        for (const [presetKey, categoryId] of Object.entries(mappings)) {
          if (!categoryKeys.has(presetKey)) {
            throw validationError(req, "映射不属于选中项", [fieldError(`existingCategoryMappings.${presetKey}`, "INVALID_OPTION", "映射的 presetKey 不在选中范围内")]);
          }
          if (!isUuid(categoryId) || !activeCategoryById.has(categoryId)) {
            throw validationError(req, "映射目标分类无效", [fieldError(`existingCategoryMappings.${presetKey}`, "INVALID_OPTION", "目标必须是本集合启用分类")]);
          }
        }
        const fieldErrors = [];
        const tombstoned = new Map();
        for (const key of [...categoryKeys, ...templateKeys]) {
          const entityType = categoryKeys.has(key) && getCategoryPreset(key) ? "category" : "template";
          const binding = bindingByKey.get(`${entityType}:${key}`);
          if (!binding || binding.status !== "tombstone") continue;
          tombstoned.set(key, { binding, entityType });
          if (restoreSet.has(key) && entityType === "category") {
            const entity = await loadCategoryRow(conn, id, binding.entity_id, { lock: true });
            const renamed = restoreRenames[key];
            const targetName = renamed !== undefined ? validateCategoryName(req, renamed) : (entity ? entity.name : null);
            const nameKey = normalizeNameKey(targetName || "");
            const conflict = activeCategoryByNameKey.get(nameKey);
            if (conflict && conflict.id !== binding.entity_id) {
              fieldErrors.push(fieldError(`restoreRenames.${key}`, "NAME_CONFLICT", `恢复名称「${targetName}」已被占用，请改名或取消恢复`));
            }
          }
        }
        // name_conflict 未解决 → 422
        for (const key of categoryKeys) {
          const binding = bindingByKey.get(`category:${key}`);
          if (binding) continue;
          if (mappings[key]) continue;
          const preset = getCategoryPreset(key);
          const conflict = activeCategoryByNameKey.get(normalizeNameKey(preset.category.name));
          if (conflict) {
            fieldErrors.push(fieldError(`existingCategoryMappings.${key}`, "NAME_CONFLICT", `已存在同名启用分类「${preset.category.name}」，请选择 existingCategoryMappings 映射或取消该项`));
          }
        }
        if (fieldErrors.length > 0) {
          throw validationError(req, "预设导入存在冲突，请逐项处理", fieldErrors);
        }

        const imported = [];
        const skipped = [];
        const restored = [];
        const mapped = [];
        const categoryIdByPresetKey = new Map();

        // 第一遍：分类
        for (const key of categoryKeys) {
          const preset = getCategoryPreset(key);
          const binding = bindingByKey.get(`category:${key}`);
          if (binding && binding.status === "active") {
            skipped.push({ presetKey: key, entityType: "category", entityId: binding.entity_id, reason: "already_imported" });
            categoryIdByPresetKey.set(key, binding.entity_id);
            continue;
          }
          if (binding && binding.status === "tombstone") {
            if (!restoreSet.has(key)) {
              skipped.push({ presetKey: key, entityType: "category", entityId: binding.entity_id, reason: "deleted_tombstone" });
              continue;
            }
            const entity = await loadCategoryRow(conn, id, binding.entity_id, { lock: true });
            if (!entity) {
              throw reject(req, 409, "STATE_CONFLICT", `墓碑实体 ${binding.entity_id} 不存在，无法恢复`);
            }
            const renamed = restoreRenames[key];
            const newName = renamed !== undefined ? validateCategoryName(req, renamed) : entity.name;
            const newNameKey = normalizeNameKey(newName);
            const [updateResult] = await conn.execute(
              `UPDATE item_categories
               SET status = 'active', name = ?, name_key = ?, version = version + 1
               WHERE id = ? AND collection_id = ? AND status = 'archived'`,
              [newName, newNameKey, entity.id, id]
            );
            if (updateResult.affectedRows === 0) {
              throw reject(req, 409, "STATE_CONFLICT", "待恢复分类状态已变化，请刷新后重试");
            }
            await conn.execute(
              "UPDATE preset_bindings SET status = 'active' WHERE id = ?",
              [binding.id]
            );
            const updatedRow = await loadCategoryRow(conn, id, entity.id);
            await writeAudit(conn, {
              collectionId: id, actorUserId: userId, action: "restore",
              entityType: "category", entityId: entity.id,
              beforeJson: { category: categoryToApi(entity) },
              afterJson: { category: categoryToApi(updatedRow) }
            });
            restored.push({ presetKey: key, entityType: "category", entityId: entity.id });
            categoryIdByPresetKey.set(key, entity.id);
            continue;
          }
          if (mappings[key]) {
            const targetId = mappings[key];
            await conn.execute(
              `INSERT INTO preset_bindings (collection_id, entity_type, preset_key, entity_id, pack_key, pack_version, status)
               VALUES (?, 'category', ?, ?, ?, ?, 'active')`,
              [id, key, targetId, preset.pack.packKey, preset.pack.packVersion]
            );
            await conn.execute(
              `UPDATE item_categories
               SET source_pack_key = ?, source_preset_key = ?, source_pack_version = ?
               WHERE id = ? AND collection_id = ? AND source_preset_key IS NULL`,
              [preset.pack.packKey, key, preset.pack.packVersion, targetId, id]
            );
            await writeAudit(conn, {
              collectionId: id, actorUserId: userId, action: "map",
              entityType: "category", entityId: targetId,
              afterJson: { presetKey: key, packKey: preset.pack.packKey, packVersion: preset.pack.packVersion }
            });
            mapped.push({ presetKey: key, entityType: "category", entityId: targetId });
            categoryIdByPresetKey.set(key, targetId);
            continue;
          }
          // 新建分类
          const [countRows] = await conn.execute(
            "SELECT COUNT(*) AS total FROM item_categories WHERE collection_id = ? AND status = 'active'",
            [id]
          );
          if (Number(countRows[0].total) >= MAX_ACTIVE_CATEGORIES) {
            throw validationError(req, "分类数量已达上限", [
              fieldError("selectedPresetKeys", "LIMIT_EXCEEDED", `最多 ${MAX_ACTIVE_CATEGORIES} 个启用分类`)
            ]);
          }
          const [maxRows] = await conn.execute(
            "SELECT COALESCE(MAX(sort_order), -1) AS max_order FROM item_categories WHERE collection_id = ?",
            [id]
          );
          const categoryId = uuid();
          try {
            await conn.execute(
              `INSERT INTO item_categories
                 (id, collection_id, name, name_key, icon_type, icon_value, color, sort_order, status,
                  source_pack_key, source_preset_key, source_pack_version, created_by)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'active', ?, ?, ?, ?)`,
              [
                categoryId, id, preset.category.name, normalizeNameKey(preset.category.name),
                preset.category.icon ? preset.category.icon.type : null,
                preset.category.icon ? preset.category.icon.value : null,
                preset.category.color, Number(maxRows[0].max_order) + 1,
                preset.pack.packKey, key, preset.pack.packVersion, userId
              ]
            );
          } catch (error) {
            if (error && error.code === "ER_DUP_ENTRY") {
              throw validationError(req, "分类名称重复", [fieldError(`selectedPresetKeys`, "DUPLICATE_NAME", `分类「${preset.category.name}」与启用分类重名`)]);
            }
            throw error;
          }
          await conn.execute(
            `INSERT INTO preset_bindings (collection_id, entity_type, preset_key, entity_id, pack_key, pack_version, status)
             VALUES (?, 'category', ?, ?, ?, ?, 'active')`,
            [id, key, categoryId, preset.pack.packKey, preset.pack.packVersion]
          );
          const created = await loadCategoryRow(conn, id, categoryId);
          await writeAudit(conn, {
            collectionId: id, actorUserId: userId, action: "create",
            entityType: "category", entityId: categoryId,
            afterJson: { category: categoryToApi(created), presetKey: key }
          });
          imported.push({ presetKey: key, entityType: "category", entityId: categoryId });
          categoryIdByPresetKey.set(key, categoryId);
        }

        // 第二遍：模板（其分类必须在本集合启用）
        for (const key of templateKeys) {
          const presetEntry = getTemplatePreset(key);
          const preset = presetEntry.template;
          const parentPresetKey = presetEntry.category.presetKey;
          const binding = bindingByKey.get(`template:${key}`);
          if (binding && binding.status === "active") {
            skipped.push({ presetKey: key, entityType: "template", entityId: binding.entity_id, reason: "already_imported" });
            continue;
          }
          const parentCategoryId = categoryIdByPresetKey.get(parentPresetKey);
          if (!parentCategoryId) {
            throw validationError(req, "模板所属分类不可用", [
              fieldError(`selectedPresetKeys`, "INVALID_OPTION", `模板 ${key} 所属分类未导入或被跳过，请先导入分类`)
            ]);
          }
          if (binding && binding.status === "tombstone") {
            if (!restoreSet.has(key)) {
              skipped.push({ presetKey: key, entityType: "template", entityId: binding.entity_id, reason: "deleted_tombstone" });
              continue;
            }
            const entity = await loadTemplateRow(conn, id, binding.entity_id, { lock: true });
            if (!entity) {
              throw reject(req, 409, "STATE_CONFLICT", `墓碑实体 ${binding.entity_id} 不存在，无法恢复`);
            }
            const [updateResult] = await conn.execute(
              `UPDATE item_templates
               SET status = 'active', category_id = ?, version = version + 1
               WHERE id = ? AND collection_id = ? AND status = 'archived'`,
              [parentCategoryId, entity.id, id]
            );
            if (updateResult.affectedRows === 0) {
              throw reject(req, 409, "STATE_CONFLICT", "待恢复模板状态已变化，请刷新后重试");
            }
            await conn.execute(
              "UPDATE preset_bindings SET status = 'active' WHERE id = ?",
              [binding.id]
            );
            const updatedRow = await loadTemplateRow(conn, id, entity.id);
            await writeAudit(conn, {
              collectionId: id, actorUserId: userId, action: "restore",
              entityType: "template", entityId: entity.id,
              beforeJson: { template: templateToApi(entity) },
              afterJson: { template: templateToApi(updatedRow) }
            });
            restored.push({ presetKey: key, entityType: "template", entityId: entity.id });
            continue;
          }
          const templateId = uuid();
          const isInventory = collection.domain === "inventory";
          await conn.execute(
            `INSERT INTO item_templates
               (id, collection_id, category_id, name, icon_type, icon_value,
                default_unit, suggested_alert_line, remark, note, suggested_amount,
                sort_order, status, source_pack_key, source_preset_key, source_pack_version, created_by)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', ?, ?, ?, ?)`,
            [
              templateId, id, parentCategoryId, preset.name,
              preset.icon ? preset.icon.type : null,
              preset.icon ? preset.icon.value : null,
              isInventory ? preset.defaultUnit || null : null,
              isInventory ? preset.suggestedAlertLine || null : null,
              isInventory ? preset.remark || null : null,
              isInventory ? null : preset.note || null,
              isInventory ? null : preset.suggestedAmount || null,
              0,
              presetEntry.pack.packKey, key, presetEntry.pack.packVersion, userId
            ]
          );
          await conn.execute(
            `INSERT INTO preset_bindings (collection_id, entity_type, preset_key, entity_id, pack_key, pack_version, status)
             VALUES (?, 'template', ?, ?, ?, ?, 'active')`,
            [id, key, templateId, presetEntry.pack.packKey, presetEntry.pack.packVersion]
          );
          const created = await loadTemplateRow(conn, id, templateId);
          await writeAudit(conn, {
            collectionId: id, actorUserId: userId, action: "create",
            entityType: "template", entityId: templateId,
            afterJson: { template: templateToApi(created), presetKey: key }
          });
          imported.push({ presetKey: key, entityType: "template", entityId: templateId });
        }

        const collectionVersion = await bumpCollectionVersion(conn, id);
        return {
          result: {
            operation: OPERATIONS.PRESETS_APPLY,
            imported,
            skipped,
            restored,
            mapped,
            restoreRequired: [],
            collectionVersion
          }
        };
      });
      res.json({ ok: true, data: { ...outcome.result, replayed: outcome.replayed } });
    } catch (error) {
      next(error);
    }
  });

  router.get("/category-collections/:id/mutations/:clientMutationId", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { id, clientMutationId } = req.params;
      const data = await withTransaction(pool, async (conn) => {
        await authorizeCollection(req, conn, userId, id, { forWrite: false });
        const [rows] = await conn.execute(
          `SELECT operation, result_json, created_at FROM collection_mutations
           WHERE collection_id = ? AND actor_user_id = ? AND client_mutation_id = ?`,
          [id, userId, clientMutationId]
        );
        if (rows.length === 0) {
          return { status: "not_found" };
        }
        return {
          status: "applied",
          operation: rows[0].operation,
          result: parseReceiptJson(rows[0].result_json)
        };
      });
      res.json({ ok: true, data });
    } catch (error) {
      next(error);
    }
  });

  /* ================================================================ *
   * 分类 CRUD
   * ================================================================ */

  router.post("/category-collections/:id/categories", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { id } = req.params;
      const body = req.body || {};
      const mutationId = requireMutationId(req, body);
      const name = validateCategoryName(req, body.name);
      const icon = validateIcon(req, body.icon, "icon");
      const color = validateColor(req, body.color, "color");
      const requestHash = buildRequestHash({
        method: "POST",
        resourcePath: "/category-collections/:id/categories",
        targetId: null,
        body
      });

      const outcome = await performWrite(req, id, OPERATIONS.CATEGORIES_CREATE, mutationId, requestHash, async (conn) => {
        const [countRows] = await conn.execute(
          "SELECT COUNT(*) AS total FROM item_categories WHERE collection_id = ? AND status = 'active'",
          [id]
        );
        if (Number(countRows[0].total) >= MAX_ACTIVE_CATEGORIES) {
          throw validationError(req, "分类数量已达上限", [
            fieldError("name", "LIMIT_EXCEEDED", `最多 ${MAX_ACTIVE_CATEGORIES} 个启用分类`)
          ]);
        }
        const nameKey = normalizeNameKey(name);
        const [dupes] = await conn.execute(
          "SELECT id FROM item_categories WHERE collection_id = ? AND name_key = ? AND status = 'active' LIMIT 1",
          [id, nameKey]
        );
        if (dupes.length > 0) {
          throw reject(req, 409, "DUPLICATE_NAME", "同集合启用分类名称不能重复", {
            fieldErrors: [fieldError("name", "DUPLICATE_NAME", "同集合启用分类名称不能重复")]
          });
        }
        const [maxRows] = await conn.execute(
          "SELECT COALESCE(MAX(sort_order), -1) AS max_order FROM item_categories WHERE collection_id = ?",
          [id]
        );
        const categoryId = uuid();
        try {
          await conn.execute(
            `INSERT INTO item_categories
               (id, collection_id, name, name_key, icon_type, icon_value, color, sort_order, status, created_by)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'active', ?)`,
            [
              categoryId, id, name, nameKey,
              icon ? icon.type : null, icon ? icon.value : null,
              color, Number(maxRows[0].max_order) + 1, userId
            ]
          );
        } catch (error) {
          if (error && error.code === "ER_DUP_ENTRY") {
            throw reject(req, 409, "DUPLICATE_NAME", "同集合启用分类名称不能重复", {
              fieldErrors: [fieldError("name", "DUPLICATE_NAME", "同集合启用分类名称不能重复")]
            });
          }
          throw error;
        }
        const created = await loadCategoryRow(conn, id, categoryId);
        await writeAudit(conn, {
          collectionId: id, actorUserId: userId, action: "create",
          entityType: "category", entityId: categoryId,
          afterJson: { category: categoryToApi(created) }
        });
        const collectionVersion = await bumpCollectionVersion(conn, id);
        return {
          result: { operation: OPERATIONS.CATEGORIES_CREATE, entity: categoryToApi(created), collectionVersion }
        };
      });
      res.json({ ok: true, data: { ...outcome.result, replayed: outcome.replayed } });
    } catch (error) {
      next(error);
    }
  });

  router.get("/category-collections/:id/categories", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { id } = req.params;
      const limit = parseLimit(req, req.query.limit);
      const status = parseStatusFilter(req, req.query.status);
      const data = await withTransaction(pool, async (conn) => {
        const auth = await authorizeCollection(req, conn, userId, id, { forWrite: false });
        const where = ["collection_id = ?"];
        const params = [id];
        if (status !== "all") {
          where.push("status = ?");
          params.push(status);
        }
        let continuation = "";
        if (req.query.cursor) {
          let boundary;
          try {
            boundary = JSON.parse(Buffer.from(String(req.query.cursor), "base64url").toString("utf8"));
          } catch (_error) {
            throw validationError(req, "cursor 无效", [fieldError("cursor", "INVALID_TYPE", "cursor 格式错误")]);
          }
          if (!boundary || !Number.isSafeInteger(boundary.o) || typeof boundary.id !== "string") {
            throw validationError(req, "cursor 无效", [fieldError("cursor", "INVALID_TYPE", "cursor 格式错误")]);
          }
          continuation = " AND (sort_order > ? OR (sort_order = ? AND id > ?))";
          params.push(boundary.o, boundary.o, boundary.id);
        }
        const [rows] = await conn.execute(
          `SELECT * FROM item_categories WHERE ${where.join(" AND ")}${continuation}
           ORDER BY sort_order ASC, id ASC LIMIT ${limit + 1}`,
          params
        );
        const hasMore = rows.length > limit;
        const items = rows.slice(0, limit).map(categoryToApi);
        const nextCursor = hasMore
          ? Buffer.from(JSON.stringify({ o: Number(rows[limit - 1].sort_order), id: rows[limit - 1].id })).toString("base64url")
          : null;
        return {
          items,
          nextCursor,
          collectionVersion: Number(auth.collection.version),
          canWrite: auth.canWrite
        };
      });
      res.json({ ok: true, data });
    } catch (error) {
      next(error);
    }
  });

  router.patch("/category-collections/:id/categories/:categoryId", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { id, categoryId } = req.params;
      const body = req.body || {};
      const mutationId = requireMutationId(req, body);
      const expectedVersion = parseExpectedVersion(req, body.expectedVersion);
      const hasName = body.name !== undefined && body.name !== null;
      const name = hasName ? validateCategoryName(req, body.name) : null;
      const icon = body.icon !== undefined ? validateIcon(req, body.icon, "icon") : undefined;
      const color = body.color !== undefined ? validateColor(req, body.color, "color") : undefined;
      if (!hasName && icon === undefined && color === undefined) {
        throw validationError(req, "至少提供一项修改", [fieldError("name", "REQUIRED", "没有可更新的字段")]);
      }
      const requestHash = buildRequestHash({
        method: "PATCH",
        resourcePath: "/category-collections/:id/categories/:categoryId",
        targetId: categoryId,
        body
      });

      const outcome = await performWrite(req, id, OPERATIONS.CATEGORIES_UPDATE, mutationId, requestHash, async (conn) => {
        const category = await loadCategoryRow(conn, id, categoryId, { lock: true });
        if (!category) {
          throw reject(req, 404, "RESOURCE_NOT_FOUND", "分类不存在");
        }
        if (category.status !== "active") {
          throw reject(req, 409, "STATE_CONFLICT", "已归档分类不能修改，请先恢复");
        }
        const newName = hasName ? name : category.name;
        const newNameKey = normalizeNameKey(newName);
        if (hasName) {
          const [dupes] = await conn.execute(
            "SELECT id FROM item_categories WHERE collection_id = ? AND name_key = ? AND status = 'active' AND id != ? LIMIT 1",
            [id, newNameKey, categoryId]
          );
          if (dupes.length > 0) {
            throw reject(req, 409, "DUPLICATE_NAME", "同集合启用分类名称不能重复", {
              fieldErrors: [fieldError("name", "DUPLICATE_NAME", "同集合启用分类名称不能重复")]
            });
          }
        }
        const newIconType = icon === undefined ? category.icon_type : icon ? icon.type : null;
        const newIconValue = icon === undefined ? category.icon_value : icon ? icon.value : null;
        const newColor = color === undefined ? category.color : color;
        const [updateResult] = await conn.execute(
          `UPDATE item_categories
           SET name = ?, name_key = ?, icon_type = ?, icon_value = ?, color = ?, version = version + 1
           WHERE id = ? AND collection_id = ? AND version = ? AND status = 'active'`,
          [newName, newNameKey, newIconType, newIconValue, newColor, categoryId, id, expectedVersion]
        );
        if (updateResult.affectedRows === 0) {
          throw reject(req, 409, "VERSION_CONFLICT", "分类已被他人修改，请刷新后重试", {
            currentVersion: Number(category.version),
            entityType: "category",
            entityId: categoryId
          });
        }
        const updated = await loadCategoryRow(conn, id, categoryId);
        await writeAudit(conn, {
          collectionId: id, actorUserId: userId, action: "update",
          entityType: "category", entityId: categoryId,
          beforeJson: { category: categoryToApi(category) },
          afterJson: { category: categoryToApi(updated) }
        });
        return {
          result: {
            operation: OPERATIONS.CATEGORIES_UPDATE,
            entity: categoryToApi(updated),
            collectionVersion: Number((await conn.execute("SELECT version FROM category_collections WHERE id = ?", [id]))[0][0].version)
          }
        };
      });
      res.json({ ok: true, data: { ...outcome.result, replayed: outcome.replayed } });
    } catch (error) {
      next(error);
    }
  });

  router.get("/category-collections/:id/categories/:categoryId/delete-preview", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { id, categoryId } = req.params;
      const data = await withTransaction(pool, async (conn) => {
        const auth = await authorizeCollection(req, conn, userId, id, { forWrite: false });
        const category = await loadCategoryRow(conn, id, categoryId);
        if (!category) {
          throw reject(req, 404, "RESOURCE_NOT_FOUND", "分类不存在");
        }
        const references = await countCategoryReferences(conn, auth.collection, categoryId);
        const [targets] = await conn.execute(
          "SELECT * FROM item_categories WHERE collection_id = ? AND status = 'active' AND id != ? ORDER BY sort_order ASC, id ASC",
          [id, categoryId]
        );
        return {
          categoryId,
          status: category.status,
          references: {
            templates: references.templates,
            records: references.records
          },
          // spec 6.2：物资条目必须迁移（recordsMoveRequired=true 时前端强制选择目标分类）；
          // 账单历史不迁移，归档后由「已删除分类」标记兜底；
          // legacy 行（category_id 为 NULL）不归属任何分类，不计入引用数
          recordsMoveRequired: auth.collection.domain === "inventory" && references.records > 0,
          moveTargets: targets.map(categoryToApi),
          collectionVersion: Number(auth.collection.version),
          categoryVersion: Number(category.version)
        };
      });
      res.json({ ok: true, data });
    } catch (error) {
      next(error);
    }
  });

  router.post("/category-collections/:id/categories/:categoryId/archive", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { id, categoryId } = req.params;
      const body = req.body || {};
      const mutationId = requireMutationId(req, body);
      const expectedVersion = parseExpectedVersion(req, body.expectedVersion);
      const expectedCollectionVersion = parseExpectedVersion(req, body.expectedCollectionVersion, "expectedCollectionVersion");
      if (body.moveToCategoryId !== undefined && body.moveToCategoryId !== null && !isUuid(body.moveToCategoryId)) {
        throw validationError(req, "moveToCategoryId 无效", [fieldError("moveToCategoryId", "INVALID_TYPE", "moveToCategoryId 必须是 UUID")]);
      }
      const requestHash = buildRequestHash({
        method: "POST",
        resourcePath: "/category-collections/:id/categories/:categoryId/archive",
        targetId: categoryId,
        body
      });

      const outcome = await performWrite(req, id, OPERATIONS.CATEGORIES_ARCHIVE, mutationId, requestHash, async (conn, auth) => {
        if (Number(auth.collection.version) !== expectedCollectionVersion) {
          throw reject(req, 409, "VERSION_CONFLICT", "集合已被修改，请刷新后重试", {
            currentVersion: Number(auth.collection.version),
            entityType: "collection",
            entityId: id
          });
        }
        const category = await loadCategoryRow(conn, id, categoryId, { lock: true });
        if (!category) {
          throw reject(req, 404, "RESOURCE_NOT_FOUND", "分类不存在");
        }
        if (category.status !== "active") {
          throw reject(req, 409, "STATE_CONFLICT", "分类已归档");
        }
        // 事务内复核引用数（spec 6.2）：集合版本已挡住对方新增引用，这里再读一次作为最终口径
        const references = await countCategoryReferences(conn, auth.collection, categoryId);

        let moveTarget = null;
        if (body.moveToCategoryId !== undefined && body.moveToCategoryId !== null) {
          moveTarget = await loadCategoryRow(conn, id, body.moveToCategoryId, { lock: true });
          if (!moveTarget || moveTarget.status !== "active" || moveTarget.id === categoryId) {
            throw validationError(req, "迁移目标分类无效", [
              fieldError("moveToCategoryId", "INVALID_OPTION", "目标必须是同集合其他启用分类")
            ]);
          }
        }
        if (references.templates > 0 && !moveTarget) {
          throw validationError(req, "归档前必须指定模板迁移目标分类", [
            fieldError("moveToCategoryId", "REQUIRED", "该分类下还有启用模板，必须选择启用目标分类")
          ]);
        }
        // spec 6.2「有物资条目」：物资不是历史快照，归档前必须整体迁移到同集合启用分类
        if (auth.collection.domain === "inventory" && references.records > 0 && !moveTarget) {
          throw validationError(req, "归档前必须指定物资迁移目标分类", [
            fieldError("moveToCategoryId", "REQUIRED", "该分类下还有物资条目，必须选择启用目标分类")
          ]);
        }

        const movedTemplateIds = [];
        let movedRecordCount = 0;
        if (moveTarget) {
          const [movedTemplates] = await conn.execute(
            "SELECT id FROM item_templates WHERE collection_id = ? AND category_id = ? AND status = 'active'",
            [id, categoryId]
          );
          for (const template of movedTemplates) {
            await conn.execute(
              "UPDATE item_templates SET category_id = ?, version = version + 1 WHERE id = ?",
              [moveTarget.id, template.id]
            );
            movedTemplateIds.push(template.id);
          }
          // 物资条目随归档迁移（保留数量/单位/保质期/图片等其余字段不动）；
          // 账单历史不迁移（spec 6.2）：保留原 category_id 并显示「已删除分类」
          if (auth.collection.domain === "inventory") {
            const [movedRecords] = await conn.execute(
              "UPDATE inventory SET category_id = ? WHERE category_id = ?",
              [moveTarget.id, categoryId]
            );
            movedRecordCount = movedRecords.affectedRows || 0;
          }
        }

        const [updateResult] = await conn.execute(
          `UPDATE item_categories SET status = 'archived', version = version + 1
           WHERE id = ? AND collection_id = ? AND version = ? AND status = 'active'`,
          [categoryId, id, expectedVersion]
        );
        if (updateResult.affectedRows === 0) {
          throw reject(req, 409, "VERSION_CONFLICT", "分类已被他人修改，请刷新后重试", {
            currentVersion: Number(category.version),
            entityType: "category",
            entityId: categoryId
          });
        }
        // 预设墓碑：归档即墓碑，防止再次导入自动复活（spec 6.2 / 5.2）
        await conn.execute(
          `UPDATE preset_bindings SET status = 'tombstone'
           WHERE collection_id = ? AND entity_type = 'category' AND entity_id = ?`,
          [id, categoryId]
        );

        const updated = await loadCategoryRow(conn, id, categoryId);
        await writeAudit(conn, {
          collectionId: id, actorUserId: userId, action: "archive",
          entityType: "category", entityId: categoryId,
          beforeJson: { category: categoryToApi(category) },
          afterJson: {
            category: categoryToApi(updated),
            references,
            movedTemplateIds,
            movedRecordCount,
            moveToCategoryId: moveTarget ? moveTarget.id : null
          }
        });
        const collectionVersion = await bumpCollectionVersion(conn, id);
        return {
          result: {
            operation: OPERATIONS.CATEGORIES_ARCHIVE,
            entity: categoryToApi(updated),
            references,
            movedTemplateIds,
            movedRecordCount,
            collectionVersion
          }
        };
      });
      res.json({ ok: true, data: { ...outcome.result, replayed: outcome.replayed } });
    } catch (error) {
      next(error);
    }
  });

  /* ================================================================ *
   * 恢复归档分类（spec 6.2：归档保留 ID，可显式恢复）
   * 预设来源分类也可走 presets/apply 的 restoreKeys；本端点对自建与
   * 预设来源通用，恢复后预设绑定从 tombstone 回到 active，避免再次
   * 导入时重复建类。
   * ================================================================ */

  router.post("/category-collections/:id/categories/:categoryId/unarchive", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { id, categoryId } = req.params;
      const body = req.body || {};
      const mutationId = requireMutationId(req, body);
      const expectedVersion = parseExpectedVersion(req, body.expectedVersion);
      const expectedCollectionVersion = parseExpectedVersion(req, body.expectedCollectionVersion, "expectedCollectionVersion");
      if (body.rename !== undefined && body.rename !== null && typeof body.rename !== "string") {
        throw validationError(req, "rename 无效", [fieldError("rename", "INVALID_TYPE", "rename 必须是字符串")]);
      }
      const requestHash = buildRequestHash({
        method: "POST",
        resourcePath: "/category-collections/:id/categories/:categoryId/unarchive",
        targetId: categoryId,
        body
      });

      const outcome = await performWrite(req, id, OPERATIONS.CATEGORIES_UNARCHIVE, mutationId, requestHash, async (conn, auth) => {
        if (Number(auth.collection.version) !== expectedCollectionVersion) {
          throw reject(req, 409, "VERSION_CONFLICT", "集合已被修改，请刷新后重试", {
            currentVersion: Number(auth.collection.version),
            entityType: "collection",
            entityId: id
          });
        }
        const category = await loadCategoryRow(conn, id, categoryId, { lock: true });
        if (!category) {
          throw reject(req, 404, "RESOURCE_NOT_FOUND", "分类不存在");
        }
        if (category.status === "active") {
          throw reject(req, 409, "STATE_CONFLICT", "分类未归档");
        }
        const targetName = body.rename !== undefined && body.rename !== null
          ? validateCategoryName(req, body.rename)
          : category.name;
        const nameKey = normalizeNameKey(targetName);
        const [dupes] = await conn.execute(
          "SELECT id FROM item_categories WHERE collection_id = ? AND name_key = ? AND status = 'active' AND id != ? LIMIT 1",
          [id, nameKey, categoryId]
        );
        if (dupes.length > 0) {
          throw validationError(req, "恢复名称已被占用", [
            fieldError("rename", "NAME_CONFLICT", `已存在同名启用分类「${targetName}」，请用 rename 改名恢复`)
          ]);
        }
        const [countRows] = await conn.execute(
          "SELECT COUNT(*) AS total FROM item_categories WHERE collection_id = ? AND status = 'active'",
          [id]
        );
        if (Number(countRows[0].total) >= MAX_ACTIVE_CATEGORIES) {
          throw validationError(req, "分类数量已达上限", [
            fieldError("rename", "LIMIT_EXCEEDED", `最多 ${MAX_ACTIVE_CATEGORIES} 个启用分类`)
          ]);
        }
        const [maxRows] = await conn.execute(
          "SELECT COALESCE(MAX(sort_order), -1) AS max_order FROM item_categories WHERE collection_id = ?",
          [id]
        );
        const [updateResult] = await conn.execute(
          `UPDATE item_categories
           SET status = 'active', name = ?, name_key = ?, sort_order = ?, version = version + 1
           WHERE id = ? AND collection_id = ? AND version = ? AND status = 'archived'`,
          [targetName, nameKey, Number(maxRows[0].max_order) + 1, categoryId, id, expectedVersion]
        );
        if (updateResult.affectedRows === 0) {
          throw reject(req, 409, "VERSION_CONFLICT", "分类已被他人修改，请刷新后重试", {
            currentVersion: Number(category.version),
            entityType: "category",
            entityId: categoryId
          });
        }
        // 预设绑定复活：归档时的墓碑回到 active，防止再次导入重复建类（spec 6.2 / 5.2）
        await conn.execute(
          `UPDATE preset_bindings SET status = 'active'
           WHERE collection_id = ? AND entity_type = 'category' AND entity_id = ? AND status = 'tombstone'`,
          [id, categoryId]
        );

        const updated = await loadCategoryRow(conn, id, categoryId);
        await writeAudit(conn, {
          collectionId: id, actorUserId: userId, action: "unarchive",
          entityType: "category", entityId: categoryId,
          beforeJson: { category: categoryToApi(category) },
          afterJson: { category: categoryToApi(updated), renamed: targetName !== category.name }
        });
        const collectionVersion = await bumpCollectionVersion(conn, id);
        return {
          result: {
            operation: OPERATIONS.CATEGORIES_UNARCHIVE,
            entity: categoryToApi(updated),
            collectionVersion
          }
        };
      });
      res.json({ ok: true, data: { ...outcome.result, replayed: outcome.replayed } });
    } catch (error) {
      next(error);
    }
  });

  /* ================================================================ *
   * 排序（spec 7）
   * ================================================================ */

  router.put("/category-collections/:id/order", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { id } = req.params;
      const body = req.body || {};
      const mutationId = requireMutationId(req, body);
      const expectedCollectionVersion = parseExpectedVersion(req, body.expectedCollectionVersion, "expectedCollectionVersion");
      const requestHash = buildRequestHash({
        method: "PUT",
        resourcePath: "/category-collections/:id/order",
        targetId: null,
        body
      });

      const outcome = await performWrite(req, id, OPERATIONS.ORDER_UPDATE, mutationId, requestHash, async (conn, auth) => {
        if (Number(auth.collection.version) !== expectedCollectionVersion) {
          throw reject(req, 409, "VERSION_CONFLICT", "分类已被另一位成员修改，请加载最新后重新调整", {
            currentVersion: Number(auth.collection.version),
            entityType: "collection",
            entityId: id
          });
        }
        const [activeRows] = await conn.execute(
          "SELECT id, sort_order FROM item_categories WHERE collection_id = ? AND status = 'active' ORDER BY sort_order ASC, id ASC",
          [id]
        );
        const orderErrors = validateOrderIds(body.categoryIds, activeRows.map((row) => row.id));
        if (orderErrors) {
          throw validationError(req, "排序数组无效", orderErrors);
        }
        const categoryIds = body.categoryIds;
        const currentOrder = activeRows.map((row) => row.id);
        const unchanged = currentOrder.length === categoryIds.length &&
          currentOrder.every((categoryId, index) => categoryId === categoryIds[index]);
        if (unchanged) {
          // 相同顺序重复提交不产生不同结果（spec 7）：不重排、不递增版本
          return {
            result: {
              operation: OPERATIONS.ORDER_UPDATE,
              unchanged: true,
              collectionVersion: Number(auth.collection.version)
            }
          };
        }
        for (let index = 0; index < categoryIds.length; index += 1) {
          await conn.execute(
            "UPDATE item_categories SET sort_order = ? WHERE id = ? AND collection_id = ?",
            [index, categoryIds[index], id]
          );
        }
        await writeAudit(conn, {
          collectionId: id, actorUserId: userId, action: "reorder",
          entityType: "collection", entityId: id,
          beforeJson: { categoryIds: currentOrder },
          afterJson: { categoryIds }
        });
        const collectionVersion = await bumpCollectionVersion(conn, id);
        return {
          result: { operation: OPERATIONS.ORDER_UPDATE, unchanged: false, collectionVersion }
        };
      });
      res.json({ ok: true, data: { ...outcome.result, replayed: outcome.replayed } });
    } catch (error) {
      next(error);
    }
  });

  /* ================================================================ *
   * 模板
   * ================================================================ */

  const TEMPLATE_ALLOWED_FIELDS = Object.freeze({
    inventory: new Set(["name", "categoryId", "icon", "defaultUnit", "suggestedAlertLine", "remark"]),
    bills: new Set(["name", "categoryId", "icon", "note", "suggestedAmount"])
  });
  const TEMPLATE_BASE_KEYS = new Set(["clientMutationId", "expectedVersion", "status"]);

  function validateTemplateFields(req, domain, body, { partial }) {
    const allowed = TEMPLATE_ALLOWED_FIELDS[domain];
    for (const key of Object.keys(body)) {
      if (TEMPLATE_BASE_KEYS.has(key)) continue;
      if (!allowed.has(key)) {
        throw validationError(req, "模板字段不在白名单内", [
          fieldError(key, "INVALID_OPTION", `${domain === "inventory" ? "物资" : "账单"}模板不允许字段 ${key}`)
        ]);
      }
    }
    const out = {};
    const has = (key) => body[key] !== undefined;
    if (!partial || has("name")) {
      if (typeof body.name !== "string") {
        throw validationError(req, "模板名称必须是字符串", [fieldError("name", partial ? "INVALID_TYPE" : "REQUIRED", "name 必填，1–40 字符")]);
      }
      const name = body.name.normalize("NFC").trim();
      if (codePointLength(name) < 1 || codePointLength(name) > 40) {
        throw validationError(req, "模板名称长度错误", [fieldError("name", "OUT_OF_RANGE", "名称 1–40 字符")]);
      }
      out.name = name;
    }
    if (!partial || has("categoryId")) {
      if (!isUuid(body.categoryId)) {
        throw validationError(req, "categoryId 无效", [fieldError("categoryId", has("categoryId") ? "INVALID_TYPE" : "REQUIRED", "categoryId 必须是本集合启用分类 UUID")]);
      }
      out.categoryId = body.categoryId;
    }
    if (has("icon")) {
      const icon = validateIcon(req, body.icon, "icon");
      out.iconType = icon ? icon.type : null;
      out.iconValue = icon ? icon.value : null;
    }
    if (domain === "inventory") {
      if (has("defaultUnit")) {
        if (body.defaultUnit === null) {
          out.defaultUnit = null;
        } else if (typeof body.defaultUnit !== "string" || codePointLength(body.defaultUnit.trim()) < 1 || codePointLength(body.defaultUnit.trim()) > 8) {
          throw validationError(req, "默认单位长度错误", [fieldError("defaultUnit", "OUT_OF_RANGE", "单位 1–8 字符，null 表示无建议")]);
        } else {
          out.defaultUnit = body.defaultUnit.trim();
        }
      }
      if (has("suggestedAlertLine")) {
        out.suggestedAlertLine = validateSuggestedDecimal(req, body.suggestedAlertLine, "suggestedAlertLine", { min: 0, max: 99999999.99, label: "建议告警线" });
      }
      if (has("remark")) {
        if (body.remark === null) {
          out.remark = null;
        } else if (typeof body.remark !== "string" || codePointLength(body.remark) > 500) {
          throw validationError(req, "备注过长", [fieldError("remark", "OUT_OF_RANGE", "备注最多 500 字符")]);
        } else {
          out.remark = body.remark;
        }
      }
    } else {
      if (has("note")) {
        if (body.note === null) {
          out.note = null;
        } else if (typeof body.note !== "string" || codePointLength(body.note) > 200) {
          throw validationError(req, "说明过长", [fieldError("note", "OUT_OF_RANGE", "说明最多 200 字符")]);
        } else {
          out.note = body.note;
        }
      }
      if (has("suggestedAmount")) {
        out.suggestedAmount = validateSuggestedDecimal(req, body.suggestedAmount, "suggestedAmount", { min: 0.01, max: 99999999.99, label: "建议金额" });
      }
    }
    return out;
  }

  router.get("/category-collections/:id/templates", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { id } = req.params;
      const status = parseStatusFilter(req, req.query.status);
      const data = await withTransaction(pool, async (conn) => {
        const auth = await authorizeCollection(req, conn, userId, id, { forWrite: false });
        const where = ["collection_id = ?"];
        const params = [id];
        if (status !== "all") {
          where.push("status = ?");
          params.push(status);
        }
        const [rows] = await conn.execute(
          `SELECT * FROM item_templates WHERE ${where.join(" AND ")}
           ORDER BY sort_order ASC, id ASC LIMIT ${MAX_ACTIVE_TEMPLATES + 1}`,
          params
        );
        return {
          items: rows.slice(0, MAX_ACTIVE_TEMPLATES).map(templateToApi),
          collectionVersion: Number(auth.collection.version),
          canWrite: auth.canWrite
        };
      });
      res.json({ ok: true, data });
    } catch (error) {
      next(error);
    }
  });

  router.post("/category-collections/:id/templates", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { id } = req.params;
      const body = req.body || {};
      const mutationId = requireMutationId(req, body);
      const requestHash = buildRequestHash({
        method: "POST",
        resourcePath: "/category-collections/:id/templates",
        targetId: null,
        body
      });

      const outcome = await performWrite(req, id, OPERATIONS.TEMPLATES_CREATE, mutationId, requestHash, async (conn, auth) => {
        const fields = validateTemplateFields(req, auth.collection.domain, body, { partial: false });
        const category = await loadCategoryRow(conn, id, fields.categoryId);
        if (!category) {
          throw reject(req, 404, "RESOURCE_NOT_FOUND", "分类不存在");
        }
        if (category.status !== "active") {
          throw validationError(req, "不能归入归档分类", [fieldError("categoryId", "INVALID_OPTION", "归档分类不能新增模板")]);
        }
        const [countRows] = await conn.execute(
          "SELECT COUNT(*) AS total FROM item_templates WHERE collection_id = ? AND status = 'active'",
          [id]
        );
        if (Number(countRows[0].total) >= MAX_ACTIVE_TEMPLATES) {
          throw validationError(req, "模板数量已达上限", [
            fieldError("name", "LIMIT_EXCEEDED", `最多 ${MAX_ACTIVE_TEMPLATES} 个启用模板`)
          ]);
        }
        const [maxRows] = await conn.execute(
          "SELECT COALESCE(MAX(sort_order), -1) AS max_order FROM item_templates WHERE collection_id = ?",
          [id]
        );
        const templateId = uuid();
        await conn.execute(
          `INSERT INTO item_templates
             (id, collection_id, category_id, name, icon_type, icon_value,
              default_unit, suggested_alert_line, remark, note, suggested_amount,
              sort_order, status, created_by)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', ?)`,
          [
            templateId, id, fields.categoryId, fields.name,
            fields.iconType || null, fields.iconValue || null,
            fields.defaultUnit !== undefined ? fields.defaultUnit : null,
            fields.suggestedAlertLine !== undefined ? fields.suggestedAlertLine : null,
            fields.remark !== undefined ? fields.remark : null,
            fields.note !== undefined ? fields.note : null,
            fields.suggestedAmount !== undefined ? fields.suggestedAmount : null,
            Number(maxRows[0].max_order) + 1, userId
          ]
        );
        const created = await loadTemplateRow(conn, id, templateId);
        await writeAudit(conn, {
          collectionId: id, actorUserId: userId, action: "create",
          entityType: "template", entityId: templateId,
          afterJson: { template: templateToApi(created) }
        });
        const collectionVersion = await bumpCollectionVersion(conn, id);
        return {
          result: { operation: OPERATIONS.TEMPLATES_CREATE, entity: templateToApi(created), collectionVersion }
        };
      });
      res.json({ ok: true, data: { ...outcome.result, replayed: outcome.replayed } });
    } catch (error) {
      next(error);
    }
  });

  router.patch("/category-collections/:id/templates/:templateId", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { id, templateId } = req.params;
      const body = req.body || {};
      const mutationId = requireMutationId(req, body);
      const expectedVersion = parseExpectedVersion(req, body.expectedVersion);
      if (body.status !== undefined && body.status !== "archived") {
        throw validationError(req, "状态无效", [fieldError("status", "INVALID_OPTION", "模板修改只支持 status=archived 归档")]);
      }
      const requestHash = buildRequestHash({
        method: "PATCH",
        resourcePath: "/category-collections/:id/templates/:templateId",
        targetId: templateId,
        body
      });

      const outcome = await performWrite(req, id, OPERATIONS.TEMPLATES_UPDATE, mutationId, requestHash, async (conn, auth) => {
        const template = await loadTemplateRow(conn, id, templateId, { lock: true });
        if (!template) {
          throw reject(req, 404, "RESOURCE_NOT_FOUND", "模板不存在");
        }
        if (template.status !== "active") {
          throw reject(req, 409, "STATE_CONFLICT", "模板已归档");
        }
        const isArchive = body.status === "archived";
        const fields = isArchive
          ? {}
          : validateTemplateFields(req, auth.collection.domain, body, { partial: true });
        if (!isArchive && Object.keys(fields).length === 0) {
          throw validationError(req, "至少提供一项修改", [fieldError("name", "REQUIRED", "没有可更新的字段")]);
        }
        if (fields.categoryId !== undefined) {
          const category = await loadCategoryRow(conn, id, fields.categoryId);
          if (!category || category.status !== "active") {
            throw validationError(req, "目标分类无效", [fieldError("categoryId", "INVALID_OPTION", "目标必须是本集合启用分类")]);
          }
        }
        const [updateResult] = await conn.execute(
          `UPDATE item_templates
           SET name = ?, icon_type = ?, icon_value = ?,
               default_unit = ?, suggested_alert_line = ?, remark = ?, note = ?, suggested_amount = ?,
               category_id = ?, status = ?, version = version + 1
           WHERE id = ? AND collection_id = ? AND version = ? AND status = 'active'`,
          [
            fields.name !== undefined ? fields.name : template.name,
            fields.iconType !== undefined ? fields.iconType : template.icon_type,
            fields.iconValue !== undefined ? fields.iconValue : template.icon_value,
            fields.defaultUnit !== undefined ? fields.defaultUnit : template.default_unit,
            fields.suggestedAlertLine !== undefined ? fields.suggestedAlertLine : dbDecimal(template.suggested_alert_line),
            fields.remark !== undefined ? fields.remark : template.remark,
            fields.note !== undefined ? fields.note : template.note,
            fields.suggestedAmount !== undefined ? fields.suggestedAmount : dbDecimal(template.suggested_amount),
            fields.categoryId !== undefined ? fields.categoryId : template.category_id,
            isArchive ? "archived" : "active",
            templateId, id, expectedVersion
          ]
        );
        if (updateResult.affectedRows === 0) {
          throw reject(req, 409, "VERSION_CONFLICT", "模板已被他人修改，请刷新后重试", {
            currentVersion: Number(template.version),
            entityType: "template",
            entityId: templateId
          });
        }
        const updated = await loadTemplateRow(conn, id, templateId);
        await writeAudit(conn, {
          collectionId: id, actorUserId: userId, action: isArchive ? "archive" : "update",
          entityType: "template", entityId: templateId,
          beforeJson: { template: templateToApi(template) },
          afterJson: { template: templateToApi(updated) }
        });
        // 模板新增/归档属于引用变化，递增集合版本使归档复核可检测
        let collectionVersion = Number(auth.collection.version);
        if (isArchive) {
          collectionVersion = await bumpCollectionVersion(conn, id);
        }
        return {
          result: { operation: OPERATIONS.TEMPLATES_UPDATE, entity: templateToApi(updated), collectionVersion }
        };
      });
      res.json({ ok: true, data: { ...outcome.result, replayed: outcome.replayed } });
    } catch (error) {
      next(error);
    }
  });

  /* ================================================================ *
   * 个人偏好（常用面板顺序属个人，不改共享顺序）
   * ================================================================ */

  function preferencesToApi(collectionId, userId, row) {
    if (!row) {
      return {
        collectionId,
        userId: String(userId),
        orderedTemplateIds: [],
        pinnedTemplateIds: [],
        hiddenTemplateIds: [],
        version: 1
      };
    }
    return {
      collectionId,
      userId: String(userId),
      orderedTemplateIds: parseReceiptJson(row.ordered_template_ids) || [],
      pinnedTemplateIds: parseReceiptJson(row.pinned_template_ids) || [],
      hiddenTemplateIds: parseReceiptJson(row.hidden_template_ids) || [],
      version: Number(row.version)
    };
  }

  router.get("/category-collections/:id/preferences", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { id } = req.params;
      const data = await withTransaction(pool, async (conn) => {
        await authorizeCollection(req, conn, userId, id, { forWrite: false });
        const [rows] = await conn.execute(
          "SELECT * FROM item_template_preferences WHERE user_id = ? AND collection_id = ?",
          [userId, id]
        );
        return { preferences: preferencesToApi(id, userId, rows[0] || null) };
      });
      res.json({ ok: true, data });
    } catch (error) {
      next(error);
    }
  });

  function validateTemplateIdArray(req, raw, path, { max }) {
    if (raw === undefined) return undefined;
    if (!Array.isArray(raw)) {
      throw validationError(req, `${path} 必须是数组`, [fieldError(path, "INVALID_TYPE", `${path} 必须是模板 ID 数组`)]);
    }
    if (raw.length > max) {
      throw validationError(req, `${path} 数量超限`, [fieldError(path, "OUT_OF_RANGE", `${path} 最多 ${max} 项`)]);
    }
    const seen = new Set();
    raw.forEach((value, index) => {
      if (!isUuid(value)) {
        throw validationError(req, `${path} 含非法 ID`, [fieldError(`${path}.${index}`, "INVALID_TYPE", "模板 ID 必须是 UUID")]);
      }
      if (seen.has(value)) {
        throw validationError(req, `${path} 重复`, [fieldError(`${path}.${index}`, "DUPLICATE", `${path} 不能重复`)]);
      }
      seen.add(value);
    });
    return raw;
  }

  router.patch("/category-collections/:id/preferences", async (req, res, next) => {
    try {
      const userId = req.userId;
      const { id } = req.params;
      const body = req.body || {};
      const mutationId = requireMutationId(req, body);
      const expectedVersion = parseExpectedVersion(req, body.expectedVersion);
      const ordered = validateTemplateIdArray(req, body.orderedTemplateIds, "orderedTemplateIds", { max: MAX_ACTIVE_TEMPLATES });
      const pinned = validateTemplateIdArray(req, body.pinnedTemplateIds, "pinnedTemplateIds", { max: MAX_PINNED });
      const hidden = validateTemplateIdArray(req, body.hiddenTemplateIds, "hiddenTemplateIds", { max: MAX_ACTIVE_TEMPLATES });
      if (ordered === undefined && pinned === undefined && hidden === undefined) {
        throw validationError(req, "至少提供一项偏好", [fieldError("orderedTemplateIds", "REQUIRED", "ordered/pinned/hidden 至少提供一项")]);
      }
      const requestHash = buildRequestHash({
        method: "PATCH",
        resourcePath: "/category-collections/:id/preferences",
        targetId: "preferences",
        body
      });

      const outcome = await performWrite(req, id, OPERATIONS.PREFERENCES_UPDATE, mutationId, requestHash, async (conn) => {
        // 引用的模板必须存在于本集合（spec 8.2：数组校验元素都是本集合存在的模板 ID）
        const referenced = [...new Set([...(ordered || []), ...(pinned || []), ...(hidden || [])])];
        if (referenced.length > 0) {
          const placeholders = referenced.map(() => "?").join(", ");
          const [rows] = await conn.execute(
            `SELECT id FROM item_templates WHERE collection_id = ? AND id IN (${placeholders})`,
            [id, ...referenced]
          );
          const found = new Set(rows.map((row) => row.id));
          const missing = referenced.filter((templateId) => !found.has(templateId));
          if (missing.length > 0) {
            throw validationError(req, "偏好引用了不存在的模板", [
              fieldError("orderedTemplateIds", "INVALID_OPTION", `模板 ID 不属于本集合：${missing[0]}`)
            ]);
          }
        }
        const [existingRows] = await conn.execute(
          "SELECT * FROM item_template_preferences WHERE user_id = ? AND collection_id = ? FOR UPDATE",
          [userId, id]
        );
        const existing = existingRows[0] || null;
        if (existing) {
          if (Number(existing.version) !== expectedVersion) {
            throw reject(req, 409, "VERSION_CONFLICT", "偏好已被修改，请刷新后重试", {
              currentVersion: Number(existing.version),
              entityType: "preference",
              entityId: "preferences"
            });
          }
          const nextOrdered = ordered !== undefined ? ordered : parseReceiptJson(existing.ordered_template_ids) || [];
          const nextPinned = pinned !== undefined ? pinned : parseReceiptJson(existing.pinned_template_ids) || [];
          const nextHidden = hidden !== undefined ? hidden : parseReceiptJson(existing.hidden_template_ids) || [];
          await conn.execute(
            `UPDATE item_template_preferences
             SET ordered_template_ids = ?, pinned_template_ids = ?, hidden_template_ids = ?, version = version + 1
             WHERE user_id = ? AND collection_id = ? AND version = ?`,
            [JSON.stringify(nextOrdered), JSON.stringify(nextPinned), JSON.stringify(nextHidden), userId, id, expectedVersion]
          );
          const [updatedRows] = await conn.execute(
            "SELECT * FROM item_template_preferences WHERE user_id = ? AND collection_id = ?",
            [userId, id]
          );
          const api = preferencesToApi(id, userId, updatedRows[0]);
          return { result: { operation: OPERATIONS.PREFERENCES_UPDATE, entity: api } };
        }
        if (expectedVersion !== 1) {
          throw reject(req, 409, "VERSION_CONFLICT", "偏好已被创建，请刷新后重试", {
            currentVersion: 1,
            entityType: "preference",
            entityId: "preferences"
          });
        }
        try {
          // 偏好行不存在时 GET 返回虚拟 version 1；首次写入即落库为 version 2（一次写=版本+1）
          await conn.execute(
            `INSERT INTO item_template_preferences
               (user_id, collection_id, ordered_template_ids, pinned_template_ids, hidden_template_ids, version)
             VALUES (?, ?, ?, ?, ?, 2)`,
            [userId, id, JSON.stringify(ordered || []), JSON.stringify(pinned || []), JSON.stringify(hidden || [])]
          );
        } catch (error) {
          if (error && error.code === "ER_DUP_ENTRY") {
            throw reject(req, 409, "VERSION_CONFLICT", "偏好已被并发创建，请刷新后重试", {
              currentVersion: 1,
              entityType: "preference",
              entityId: "preferences"
            });
          }
          throw error;
        }
        const [createdRows] = await conn.execute(
          "SELECT * FROM item_template_preferences WHERE user_id = ? AND collection_id = ?",
          [userId, id]
        );
        const api = preferencesToApi(id, userId, createdRows[0]);
        return { result: { operation: OPERATIONS.PREFERENCES_UPDATE, entity: api } };
      });
      res.json({ ok: true, data: { ...outcome.result, replayed: outcome.replayed } });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createCategoryCollectionsRouter };
// 仅供单元测试的纯函数导出
module.exports._internals = { validateOrderIds, normalizeNameKey, canonicalize, buildRequestHash };
