// 共同目标路由（规格 v0.2 §3/§6/§7，契约 docs/API_CONTRACT_GOALS_DIARY_20261006.md §1）
//
// 不变量：
//  - current_amount_fen 只在流水事务（记一笔 / 冲正）内改写，其余端点忽略客户端余额；
//  - 每空间最多一个 active 目标，uk_goals_space_active 唯一键兜底并发；
//  - 空间复用 housework 生命周期：解绑冻结共享空间后目标转 frozen，
//    成员仍可读流水（历史归档口径），但不可再改写。
const express = require("express");
const crypto = require("crypto");
const { ApiError } = require("../errors");
const { withTransaction } = require("../utils/transactions");
const { requireIdempotencyKey, withIdempotency } = require("../utils/idempotency");
const {
  loadCoupleSpaceContext,
  resolveGoalSpace,
  todayInTimezone,
  parseDateOnly
} = require("../utils/spaceContext");

const GOAL_SELECT = `SELECT goal_id, space_id, relationship_id, created_by, title, description,
       target_amount_fen, current_amount_fen, currency,
       DATE_FORMAT(target_date, '%Y-%m-%d') AS target_date, timezone, status, version, legacy_plan_id,
       created_at, updated_at, completed_at
  FROM couple_goals`;

const ENTRY_SELECT = `SELECT entry_id, goal_id, actor_id, type, amount_fen, note, occurred_at,
       created_at, status, reversal_event_id
  FROM couple_goal_entries`;

const MAX_TARGET_AMOUNT_FEN = 99999999;
const MAX_NOTE_LENGTH = 200;

function validationError(fieldErrors) {
  return new ApiError(422, "VALIDATION_FAILED", "字段校验失败", { fieldErrors });
}

function versionConflict(currentVersion) {
  return new ApiError(409, "VERSION_CONFLICT", "内容已被修改，请刷新后重试", { currentVersion });
}

function goalLimitConflict() {
  return new ApiError(409, "GOAL_LIMIT_CONFLICT", "已有进行中的目标");
}

function goalNotFound() {
  // 目标不存在、非本人空间、空间已关闭统一不枚举原因
  return new ApiError(404, "NOT_FOUND", "目标不存在或不可访问");
}

/** 宽松解析整数：接受 number 或纯数字字符串，非法返回 null。 */
function parseInteger(value) {
  if (typeof value === "number" && Number.isInteger(value)) return value;
  if (typeof value === "string" && /^-?\d+$/.test(value.trim())) return Number(value.trim());
  return null;
}

function parseExpectedVersion(body) {
  const value = parseInteger(body && body.expectedVersion);
  if (value === null || value < 0) {
    throw validationError({ expectedVersion: "expectedVersion 必须为非负整数" });
  }
  return value;
}

function checkTitle(value) {
  if (typeof value !== "string") return "标题应为 1-30 个字符";
  const title = value.trim();
  if (title.length < 1 || title.length > 30) return "标题应为 1-30 个字符";
  return null;
}

function checkTargetAmountFen(value) {
  const amount = parseInteger(value);
  if (amount === null || amount < 1 || amount > MAX_TARGET_AMOUNT_FEN) {
    return "目标金额应为 1-99,999,999 的整数（分）";
  }
  return null;
}

/** 可选文本（≤200 去空格）：返回 [归一化值, 错误消息] 之一。 */
function normalizeText(value) {
  if (value === undefined || value === null || value === "") return [null, null];
  if (typeof value !== "string") return [null, "内容不能超过 200 个字符"];
  const text = value.trim();
  if (text.length > MAX_NOTE_LENGTH) return [null, "内容不能超过 200 个字符"];
  return [text === "" ? null : text, null];
}

function serializeGoal(row) {
  if (!row) return null;
  return {
    id: row.goal_id,
    goalId: row.goal_id,
    spaceId: row.space_id,
    createdBy: Number(row.created_by),
    title: row.title,
    description: row.description === undefined ? null : row.description,
    targetAmountFen: Number(row.target_amount_fen),
    currentAmountFen: Number(row.current_amount_fen),
    currency: row.currency,
    targetDate: row.target_date || null,
    timezone: row.timezone,
    status: row.status,
    version: Number(row.version),
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    completedAt: row.completed_at || null,
    legacyPlanId: row.legacy_plan_id === undefined || row.legacy_plan_id === null
      ? null
      : Number(row.legacy_plan_id)
  };
}

function serializeEntry(row) {
  if (!row) return null;
  return {
    id: row.entry_id,
    entryId: row.entry_id,
    goalId: row.goal_id,
    actorId: Number(row.actor_id),
    createdBy: Number(row.actor_id),
    type: row.type,
    amountFen: Number(row.amount_fen),
    note: row.note === undefined ? null : row.note,
    occurredAt: row.occurred_at,
    createdAt: row.created_at,
    status: row.status,
    reversed: row.status === "reversed",
    reversalEventId: row.reversal_event_id === undefined || row.reversal_event_id === null
      ? null
      : Number(row.reversal_event_id)
  };
}

/**
 * 读路径的「当前目标空间」：有共享空间用共享空间，无则回落本人个人空间（均不创建）。
 * 返回 null 表示两种空间都没有。
 */
async function loadReadGoalSpace(executor, userId) {
  const couple = await loadCoupleSpaceContext(executor, userId);
  if (couple) {
    return { scope: "couple", relationshipId: couple.relationshipId, spaceId: couple.spaceId };
  }
  const [rows] = await executor.execute(
    "SELECT space_id FROM housework_spaces WHERE scope = 'personal' AND owner_user_id = ? LIMIT 1",
    [userId]
  );
  if (rows.length === 0) return null;
  return { scope: "personal", relationshipId: null, spaceId: rows[0].space_id };
}

/** 成员身份校验；requireActiveSpace 时还要求空间 status='active'。 */
async function isGoalSpaceMember(executor, spaceId, userId, { requireActiveSpace = false } = {}) {
  if (requireActiveSpace) {
    const [spaces] = await executor.execute(
      "SELECT status FROM housework_spaces WHERE space_id = ? LIMIT 1",
      [spaceId]
    );
    if (spaces.length === 0 || spaces[0].status !== "active") return false;
  }
  const [members] = await executor.execute(
    "SELECT user_id FROM housework_space_members WHERE space_id = ? AND user_id = ? LIMIT 1",
    [spaceId, userId]
  );
  return members.length > 0;
}

async function selectGoalById(executor, goalId) {
  const [rows] = await executor.execute(`${GOAL_SELECT} WHERE goal_id = ? LIMIT 1`, [goalId]);
  return rows[0] || null;
}

async function lockGoalById(conn, goalId) {
  const [rows] = await conn.execute(`${GOAL_SELECT} WHERE goal_id = ? LIMIT 1 FOR UPDATE`, [goalId]);
  return rows[0] || null;
}

function assertVersion(goal, expectedVersion) {
  const current = Number(goal.version);
  if (current !== expectedVersion) throw versionConflict(current);
}

function assertGoalActive(goal) {
  if (goal.status === "frozen") throw new ApiError(409, "GOAL_FROZEN", "目标已冻结，不可操作");
  if (goal.status !== "active") throw new ApiError(409, "GOAL_NOT_ACTIVE", "目标已结束，不可操作");
}

function toBase64Url(text) {
  return Buffer.from(text, "utf8").toString("base64")
    .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function fromBase64Url(text) {
  const padded = String(text).replace(/-/g, "+").replace(/_/g, "/");
  return Buffer.from(padded, "base64").toString("utf8");
}

/** cursor 内容：[occurred_at_ms, created_at_ms, entry_id]（与服务端排序键一一对应）。 */
function encodeEntryCursor(row) {
  return toBase64Url(JSON.stringify([
    new Date(row.occurred_at).getTime(),
    new Date(row.created_at).getTime(),
    row.entry_id
  ]));
}

function decodeEntryCursor(value) {
  try {
    const parsed = JSON.parse(fromBase64Url(value));
    if (!Array.isArray(parsed) || parsed.length !== 3
      || !Number.isInteger(parsed[0]) || !Number.isInteger(parsed[1])
      || typeof parsed[2] !== "string" || !parsed[2]) {
      throw new Error("cursor 结构不正确");
    }
    return parsed;
  } catch (_error) {
    throw validationError({ cursor: "cursor 无效" });
  }
}

/** ms → 本地 'YYYY-MM-DD HH:MM:SS.mmm'（与 mysql2 读出的 DATETIME(3) 同一坐标系）。 */
function formatSqlDateTime(ms) {
  const date = new Date(ms);
  const pad = (n, width = 2) => String(n).padStart(width, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
    + `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}.${pad(date.getMilliseconds(), 3)}`;
}

function parseLimitQuery(raw, { defaultValue, maxValue }) {
  if (raw === undefined || raw === null || raw === "") return defaultValue;
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value < 1 || value > maxValue) {
    throw validationError({ limit: `limit 必须是 1-${maxValue} 的整数` });
  }
  return value;
}

function createGoalsRouter({ pool }) {
  const router = express.Router();

  router.get("/spaces/current/goal", async (req, res, next) => {
    try {
      const userId = req.userId;
      const ctx = await loadReadGoalSpace(pool, userId);
      if (!ctx) {
        return res.json({
          ok: true,
          data: {
            goal: null,
            relationship: { status: "none", id: null, version: 0 },
            recentActivity: []
          }
        });
      }
      const [goalRows] = await pool.execute(
        `${GOAL_SELECT} WHERE space_id = ? AND status = 'active' LIMIT 1`,
        [ctx.spaceId]
      );
      const [events] = await pool.execute(
        `SELECT event_id, type, actor_id, goal_id, created_at FROM couple_goal_events
          WHERE space_id = ? ORDER BY event_id DESC LIMIT 10`,
        [ctx.spaceId]
      );
      res.json({
        ok: true,
        data: {
          goal: goalRows.length > 0 ? serializeGoal(goalRows[0]) : null,
          relationship: ctx.scope === "couple"
            ? { status: "active", id: ctx.relationshipId, version: ctx.relationshipId }
            : { status: "none", id: null, version: 0 },
          recentActivity: events.map((event) => ({
            id: Number(event.event_id),
            type: event.type,
            actorId: event.actor_id === null || event.actor_id === undefined ? null : Number(event.actor_id),
            goalId: event.goal_id,
            createdAt: event.created_at
          }))
        }
      });
    } catch (error) {
      next(error);
    }
  });

  router.get("/goals", async (req, res, next) => {
    try {
      const userId = req.userId;
      const limit = parseLimitQuery(req.query.limit, { defaultValue: 50, maxValue: 50 });
      const ctx = await loadReadGoalSpace(pool, userId);
      if (!ctx) return res.json({ ok: true, data: { items: [] } });
      const [rows] = await pool.execute(
        `${GOAL_SELECT} WHERE space_id = ? AND status IN ('completed', 'archived', 'frozen')
          ORDER BY updated_at DESC LIMIT ${limit}`,
        [ctx.spaceId]
      );
      res.json({ ok: true, data: { items: rows.map(serializeGoal) } });
    } catch (error) {
      next(error);
    }
  });

  router.post("/goals", async (req, res, next) => {
    try {
      const userId = req.userId;
      const body = req.body || {};
      const fieldErrors = {};
      const title = typeof body.title === "string" ? body.title.trim() : "";
      const titleError = checkTitle(body.title);
      if (titleError) fieldErrors.title = titleError;
      const amountError = checkTargetAmountFen(body.targetAmountFen);
      if (amountError) fieldErrors.targetAmountFen = amountError;
      let targetDate = null;
      if (body.targetDate !== undefined && body.targetDate !== null && body.targetDate !== "") {
        try {
          targetDate = parseDateOnly(body.targetDate, "targetDate");
        } catch (error) {
          fieldErrors.targetDate = error.details && error.details.fieldErrors
            ? error.details.fieldErrors.targetDate
            : "日期格式应为 YYYY-MM-DD";
        }
      }
      const [description, descriptionError] = normalizeText(body.description);
      if (descriptionError) fieldErrors.description = descriptionError;
      if (Object.keys(fieldErrors).length > 0) throw validationError(fieldErrors);
      const key = requireIdempotencyKey(body.idempotencyKey);

      let goal;
      try {
        const outcome = await withIdempotency(
          pool,
          { userId, scope: "goal.create", key, payload: body },
          () => withTransaction(pool, async (conn) => {
            const ctx = await resolveGoalSpace(conn, userId);
            if (targetDate && targetDate < todayInTimezone(ctx.timezone)) {
              throw validationError({ targetDate: "目标日期不能早于今天" });
            }
            const [activeRows] = await conn.execute(
              "SELECT goal_id FROM couple_goals WHERE space_id = ? AND status = 'active' LIMIT 1",
              [ctx.spaceId]
            );
            if (activeRows.length > 0) throw goalLimitConflict();
            const goalId = crypto.randomUUID();
            await conn.execute(
              `INSERT INTO couple_goals
                 (goal_id, space_id, relationship_id, created_by, title, description,
                  target_amount_fen, target_date, timezone)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
              [goalId, ctx.spaceId, ctx.relationshipId, userId, title, description,
                parseInteger(body.targetAmountFen), targetDate, ctx.timezone]
            );
            await conn.execute(
              "INSERT INTO couple_goal_events (goal_id, space_id, actor_id, type) VALUES (?, ?, ?, 'create')",
              [goalId, ctx.spaceId, userId]
            );
            return serializeGoal(await selectGoalById(conn, goalId));
          })
        );
        goal = outcome.result;
      } catch (error) {
        // uk_goals_space_active 撞键 = 并发下同空间第二个 active 目标，语义与预检一致
        if (error.code === "ER_DUP_ENTRY" || error.sqlState === "23000") throw goalLimitConflict();
        throw error;
      }
      res.json({ ok: true, data: goal });
    } catch (error) {
      next(error);
    }
  });

  router.patch("/goals/:id", async (req, res, next) => {
    try {
      const userId = req.userId;
      const goalId = req.params.id;
      const body = req.body || {};
      const expectedVersion = parseExpectedVersion(body);

      const fieldErrors = {};
      const updates = {};
      const fields = [];
      if (body.title !== undefined) {
        const titleError = checkTitle(body.title);
        if (titleError) fieldErrors.title = titleError;
        else {
          updates.title = body.title.trim();
          fields.push("title");
        }
      }
      if (body.targetAmountFen !== undefined) {
        const amountError = checkTargetAmountFen(body.targetAmountFen);
        if (amountError) fieldErrors.targetAmountFen = amountError;
        else {
          updates.target_amount_fen = parseInteger(body.targetAmountFen);
          fields.push("targetAmountFen");
        }
      }
      if (body.targetDate !== undefined) {
        if (body.targetDate === null || body.targetDate === "") {
          updates.target_date = null;
        } else {
          try {
            updates.target_date = parseDateOnly(body.targetDate, "targetDate");
          } catch (error) {
            fieldErrors.targetDate = error.details && error.details.fieldErrors
              ? error.details.fieldErrors.targetDate
              : "日期格式应为 YYYY-MM-DD";
          }
        }
        if (!fieldErrors.targetDate) fields.push("targetDate");
      }
      if (body.description !== undefined) {
        const [description, descriptionError] = normalizeText(body.description);
        if (descriptionError) fieldErrors.description = descriptionError;
        else {
          updates.description = description;
          fields.push("description");
        }
      }
      if (Object.keys(fieldErrors).length > 0) throw validationError(fieldErrors);

      const goal = await withTransaction(pool, async (conn) => {
        const row = await lockGoalById(conn, goalId);
        if (!row || !(await isGoalSpaceMember(conn, row.space_id, userId, { requireActiveSpace: true }))) {
          throw goalNotFound();
        }
        assertGoalActive(row);
        assertVersion(row, expectedVersion);
        const columns = Object.keys(updates).map((column) => `${column} = ?`);
        await conn.execute(
          `UPDATE couple_goals SET ${columns.length > 0 ? `${columns.join(", ")}, ` : ""}version = version + 1
            WHERE goal_id = ?`,
          [...Object.values(updates), goalId]
        );
        await conn.execute(
          `INSERT INTO couple_goal_events (goal_id, space_id, actor_id, type, meta_json)
           VALUES (?, ?, ?, 'update', ?)`,
          [goalId, row.space_id, userId, JSON.stringify({ fields })]
        );
        return serializeGoal(await selectGoalById(conn, goalId));
      });
      res.json({ ok: true, data: goal });
    } catch (error) {
      next(error);
    }
  });

  router.get("/goals/:id/entries", async (req, res, next) => {
    try {
      const userId = req.userId;
      const goalId = req.params.id;
      const limit = parseLimitQuery(req.query.limit, { defaultValue: 20, maxValue: 50 });
      let cursor = null;
      if (req.query.cursor !== undefined && req.query.cursor !== null && req.query.cursor !== "") {
        cursor = decodeEntryCursor(req.query.cursor);
      }
      const [goalRows] = await pool.execute(
        "SELECT goal_id, space_id FROM couple_goals WHERE goal_id = ? LIMIT 1",
        [goalId]
      );
      const goal = goalRows[0];
      // 空间已关闭（解绑冻结）的历史目标：成员仍可读流水，只校验成员身份
      if (!goal || !(await isGoalSpaceMember(pool, goal.space_id, userId))) throw goalNotFound();

      const params = [goalId];
      let whereClause = "goal_id = ?";
      if (cursor) {
        const [occurredAt, createdAt, entryId] = cursor;
        const occurredSql = formatSqlDateTime(occurredAt);
        const createdSql = formatSqlDateTime(createdAt);
        whereClause += " AND (occurred_at < ? OR (occurred_at = ? AND (created_at < ? OR (created_at = ? AND entry_id < ?))))";
        params.push(occurredSql, occurredSql, createdSql, createdSql, entryId);
      }
      const [rows] = await pool.execute(
        `${ENTRY_SELECT} WHERE ${whereClause}
          ORDER BY occurred_at DESC, created_at DESC, entry_id DESC
          LIMIT ${limit + 1}`,
        params
      );
      const hasMore = rows.length > limit;
      const items = (hasMore ? rows.slice(0, limit) : rows).map(serializeEntry);
      res.json({
        ok: true,
        data: {
          items,
          nextCursor: hasMore ? encodeEntryCursor(rows[limit - 1]) : null,
          hasMore
        }
      });
    } catch (error) {
      next(error);
    }
  });

  router.post("/goals/:id/entries", async (req, res, next) => {
    try {
      const userId = req.userId;
      const goalId = req.params.id;
      const body = req.body || {};

      const fieldErrors = {};
      const type = body.type;
      if (type !== "increase" && type !== "decrease") {
        fieldErrors.type = "type 必须为 increase 或 decrease";
      }
      const amountFen = parseInteger(body.amountFen);
      if (amountFen === null || amountFen < 1) fieldErrors.amountFen = "金额应为正整数（分）";
      const [note, noteError] = normalizeText(body.note);
      if (noteError) fieldErrors.note = noteError;
      let occurredSql = null;
      let occurredDatePart = null;
      if (body.occurredAt !== undefined && body.occurredAt !== null && body.occurredAt !== "") {
        const raw = String(body.occurredAt).trim();
        if (/^\d{4}-\d{2}-\d{2}$/.test(raw)) {
          occurredDatePart = parseDateOnly(raw, "occurredAt");
          occurredSql = `${occurredDatePart} 00:00:00`;
        } else if (/^\d{4}-\d{2}-\d{2}/.test(raw) && !Number.isNaN(new Date(raw).getTime())) {
          occurredDatePart = raw.slice(0, 10);
          occurredSql = formatSqlDateTime(new Date(raw).getTime());
        } else {
          fieldErrors.occurredAt = "occurredAt 应为 YYYY-MM-DD 或 ISO 时间";
        }
      }
      if (Object.keys(fieldErrors).length > 0) throw validationError(fieldErrors);
      const key = requireIdempotencyKey(body.idempotencyKey);

      const data = await withIdempotency(
        pool,
        // payload 附上 URL 中的 goalId：同键不同目标的请求不能互相重放
        { userId, scope: "entry.create", key, payload: { ...body, goalId } },
        () => withTransaction(pool, async (conn) => {
          const goal = await lockGoalById(conn, goalId);
          if (!goal || !(await isGoalSpaceMember(conn, goal.space_id, userId))) throw goalNotFound();
          if (goal.status !== "active") {
            // 冻结 / 已完成 / 已归档一律不可再记账
            throw new ApiError(409, "GOAL_NOT_ACTIVE", "目标已结束或已冻结，不可记账");
          }
          if (occurredDatePart && occurredDatePart > todayInTimezone(goal.timezone)) {
            throw validationError({ occurredAt: "记录日期不能晚于今天" });
          }
          const current = Number(goal.current_amount_fen);
          if (type === "decrease" && amountFen > current) {
            throw validationError({ amountFen: "超出当前净额" });
          }
          const entryId = crypto.randomUUID();
          await conn.execute(
            `INSERT INTO couple_goal_entries (entry_id, goal_id, actor_id, type, amount_fen, note, occurred_at)
             VALUES (?, ?, ?, ?, ?, ?, COALESCE(?, NOW(3)))`,
            [entryId, goalId, userId, type, amountFen, note, occurredSql]
          );
          const delta = type === "increase" ? amountFen : -amountFen;
          await conn.execute(
            "UPDATE couple_goals SET current_amount_fen = current_amount_fen + ?, version = version + 1 WHERE goal_id = ?",
            [delta, goalId]
          );
          await conn.execute(
            `INSERT INTO couple_goal_events (goal_id, space_id, actor_id, type, ref_id, meta_json)
             VALUES (?, ?, ?, 'entry', ?, ?)`,
            [goalId, goal.space_id, userId, entryId, JSON.stringify({ type, amountFen })]
          );
          const [entryRows] = await conn.execute(
            `${ENTRY_SELECT} WHERE entry_id = ? LIMIT 1`,
            [entryId]
          );
          return {
            entry: serializeEntry(entryRows[0]),
            currentAmountFen: current + delta,
            version: Number(goal.version) + 1
          };
        })
      );
      res.json({ ok: true, data: data.result });
    } catch (error) {
      next(error);
    }
  });

  router.post("/entries/:id/reverse", async (req, res, next) => {
    try {
      const userId = req.userId;
      const entryId = req.params.id;
      const body = req.body || {};
      const expectedVersion = parseExpectedVersion(body);
      const key = requireIdempotencyKey(body.idempotencyKey);

      const data = await withIdempotency(
        pool,
        // payload 附上 URL 中的 entryId：同键不同流水的请求不能互相重放
        { userId, scope: "entry.reverse", key, payload: { ...body, entryId } },
        () => withTransaction(pool, async (conn) => {
          const [entryRows] = await conn.execute(
            `${ENTRY_SELECT} WHERE entry_id = ? LIMIT 1 FOR UPDATE`,
            [entryId]
          );
          const entry = entryRows[0];
          if (!entry) throw new ApiError(404, "NOT_FOUND", "流水不存在");
          const goal = await lockGoalById(conn, entry.goal_id);
          if (!goal) throw goalNotFound();
          if (Number(entry.actor_id) !== userId) {
            throw new ApiError(403, "FORBIDDEN", "只能冲正本人记录的流水");
          }
          if (entry.status === "reversed") {
            throw new ApiError(409, "ALREADY_REVERSED", "该流水已冲正");
          }
          assertGoalActive(goal);
          assertVersion(goal, expectedVersion);

          const amount = Number(entry.amount_fen);
          const current = Number(goal.current_amount_fen);
          // 反向调整余额；increase 冲正在脏数据下防御性 clamp 到 0
          const nextAmount = entry.type === "increase"
            ? Math.max(0, current - amount)
            : current + amount;
          await conn.execute(
            "UPDATE couple_goal_entries SET status = 'reversed' WHERE entry_id = ?",
            [entryId]
          );
          await conn.execute(
            "UPDATE couple_goals SET current_amount_fen = ?, version = version + 1 WHERE goal_id = ?",
            [nextAmount, goal.goal_id]
          );
          const [eventResult] = await conn.execute(
            `INSERT INTO couple_goal_events (goal_id, space_id, actor_id, type, ref_id, meta_json)
             VALUES (?, ?, ?, 'reverse', ?, ?)`,
            [goal.goal_id, goal.space_id, userId, entryId, JSON.stringify({ entryId })]
          );
          await conn.execute(
            "UPDATE couple_goal_entries SET reversal_event_id = ? WHERE entry_id = ?",
            [eventResult.insertId, entryId]
          );
          const [freshRows] = await conn.execute(
            `${ENTRY_SELECT} WHERE entry_id = ? LIMIT 1`,
            [entryId]
          );
          return {
            entry: serializeEntry(freshRows[0]),
            currentAmountFen: nextAmount,
            version: Number(goal.version) + 1
          };
        })
      );
      res.json({ ok: true, data: data.result });
    } catch (error) {
      next(error);
    }
  });

  router.post("/goals/:id/transitions", async (req, res, next) => {
    try {
      const userId = req.userId;
      const goalId = req.params.id;
      const body = req.body || {};
      const action = body.action;
      if (action !== "complete" && action !== "archive" && action !== "reopen") {
        throw validationError({ action: "action 必须为 complete、archive 或 reopen" });
      }
      const expectedVersion = parseExpectedVersion(body);

      let goal;
      try {
        goal = await withTransaction(pool, async (conn) => {
          const row = await lockGoalById(conn, goalId);
          if (!row || !(await isGoalSpaceMember(conn, row.space_id, userId, { requireActiveSpace: true }))) {
            throw goalNotFound();
          }
          if (row.status === "frozen") {
            // frozen 由系统（解绑）设置，任何用户态操作一律拒绝
            throw new ApiError(409, "GOAL_FROZEN", "目标已冻结，不可操作");
          }
          assertVersion(row, expectedVersion);

          if (action === "complete") {
            if (row.status !== "active") {
              throw new ApiError(409, "GOAL_NOT_ACTIVE", "目标已结束，不可完成");
            }
            if (Number(row.current_amount_fen) < Number(row.target_amount_fen)) {
              throw validationError({ amount: "尚未达成目标金额" });
            }
            await conn.execute(
              "UPDATE couple_goals SET status = 'completed', completed_at = NOW(3), version = version + 1 WHERE goal_id = ?",
              [goalId]
            );
          } else if (action === "archive") {
            if (row.status !== "active" && row.status !== "completed") {
              throw new ApiError(409, "GOAL_NOT_ACTIVE", "目标状态不允许归档");
            }
            await conn.execute(
              "UPDATE couple_goals SET status = 'archived', version = version + 1 WHERE goal_id = ?",
              [goalId]
            );
          } else {
            if (row.status === "active") {
              throw new ApiError(409, "GOAL_NOT_ACTIVE", "目标已在进行中");
            }
            const [conflictRows] = await conn.execute(
              "SELECT goal_id FROM couple_goals WHERE space_id = ? AND status = 'active' AND goal_id != ? LIMIT 1",
              [row.space_id, goalId]
            );
            if (conflictRows.length > 0) throw goalLimitConflict();
            await conn.execute(
              "UPDATE couple_goals SET status = 'active', completed_at = NULL, version = version + 1 WHERE goal_id = ?",
              [goalId]
            );
          }
          await conn.execute(
            "INSERT INTO couple_goal_events (goal_id, space_id, actor_id, type) VALUES (?, ?, ?, ?)",
            [goalId, row.space_id, userId, action]
          );
          return serializeGoal(await selectGoalById(conn, goalId));
        });
      } catch (error) {
        // reopen 的 uk_goals_space_active 撞键兜底
        if (error.code === "ER_DUP_ENTRY" || error.sqlState === "23000") throw goalLimitConflict();
        throw error;
      }
      res.json({ ok: true, data: { goal } });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = {
  createGoalsRouter,
  // 供 invitations.js 复用：目标序列化与成员校验口径必须与本文件完全一致
  GOAL_SELECT,
  serializeGoal,
  isGoalSpaceMember
};
