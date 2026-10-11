// 情侣打卡 / 习惯共建路由
//
// 空间语义与共同目标（goals.js）一致：
//  - 写路径用 resolveGoalSpace：有 active 关系 → 共享空间，否则懒建个人空间；
//  - 读路径不创建空间：有共享空间用共享空间，无则回落本人已有个人空间；
//  - 解绑冻结空间后，写路径要求空间 active（统一 404 不枚举原因），
//    读路径因「当前空间」解析不到冻结空间而自然不可见。
// 打卡幂等：uk_habit_checkin_day(habit_id, user_id, checkin_on) 数据库兜底，
// 重复打卡撞键后返回已有记录（created=false），不产生第二条。
const express = require("express");
const crypto = require("crypto");
const { ApiError } = require("../errors");
const { withTransaction } = require("../utils/transactions");
const { withIdempotency } = require("../utils/idempotency");
const {
  loadCoupleSpaceContext,
  resolveGoalSpace,
  todayInTimezone
} = require("../utils/spaceContext");
const { enqueueActivityEvent } = require("../utils/activityEvents");

const MAX_TITLE_LENGTH = 64;
const MAX_ICON_LENGTH = 16;

const IDEM_KEY_PATTERN = /^[A-Za-z0-9_-]{8,80}$/;

function validationError(fieldErrors) {
  return new ApiError(422, "VALIDATION_FAILED", "字段校验失败", { fieldErrors });
}

function habitNotFound() {
  // 习惯不存在、非本人空间、空间已冻结统一不枚举原因
  return new ApiError(404, "NOT_FOUND", "习惯不存在或不可访问");
}

function checkTitle(value) {
  if (typeof value !== "string") return "标题应为 1-64 个字符";
  const title = value.trim();
  if (title.length < 1 || title.length > MAX_TITLE_LENGTH) return "标题应为 1-64 个字符";
  return null;
}

function checkIcon(value) {
  if (value === undefined || value === null || value === "") return null;
  if (typeof value !== "string" || Array.from(value.trim()).length > MAX_ICON_LENGTH) {
    return "图标应为不超过 16 个字符的 emoji";
  }
  return null;
}

function normalizeIcon(value) {
  if (value === undefined || value === null) return null;
  const icon = String(value).trim();
  return icon === "" ? null : icon;
}

/** 可选幂等键：缺省为 null（走数据库唯一键天然幂等），提供了就必须合法。 */
function optionalIdempotencyKey(value) {
  if (value === undefined || value === null || value === "") return null;
  const key = typeof value === "string" ? value.trim() : "";
  if (!IDEM_KEY_PATTERN.test(key)) {
    throw validationError({ idempotencyKey: "幂等键格式不正确（8-80 位字母数字-_）" });
  }
  return key;
}

function serializeHabit(row) {
  if (!row) return null;
  return {
    habitId: row.habit_id,
    spaceId: row.space_id,
    creatorId: Number(row.creator_id),
    title: row.title,
    icon: row.icon === undefined ? null : row.icon,
    status: row.status,
    createdAt: row.created_at
  };
}

/** 成员身份校验；requireActiveSpace 时还要求空间 status='active'。 */
async function isHabitSpaceMember(executor, spaceId, userId, { requireActiveSpace = false } = {}) {
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

/**
 * 读路径的「当前打卡空间」（同 goals 的 loadReadGoalSpace，附带时区/成员）：
 * 有共享空间用共享空间，无则回落本人个人空间（均不创建）。返回 null 表示都没有。
 */
async function loadReadHabitSpace(executor, userId) {
  const couple = await loadCoupleSpaceContext(executor, userId);
  if (couple) return { ...couple, scope: "couple" };
  const [rows] = await executor.execute(
    "SELECT space_id, timezone FROM housework_spaces WHERE scope = 'personal' AND owner_user_id = ? LIMIT 1",
    [userId]
  );
  if (rows.length === 0) return null;
  return {
    scope: "personal",
    relationshipId: null,
    spaceId: rows[0].space_id,
    memberIds: [Number(userId)],
    partnerId: null,
    timezone: rows[0].timezone || "Asia/Shanghai"
  };
}

/** 按 UTC 平移 YYYY-MM-DD 日期串（纯日期运算，避开本地时区）。 */
function shiftDate(dateStr, deltaDays) {
  const date = new Date(`${dateStr}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + deltaDays);
  return date.toISOString().slice(0, 10);
}

/**
 * 连续打卡天数：今天已打则从今天往前数；今天未打则从昨天往前数（昨天也没有 = 0）。
 * dates 为该用户全部打卡日期的 Set（'YYYY-MM-DD'）。
 */
function computeStreak(dates, today) {
  let cursor = today;
  if (!dates.has(cursor)) {
    cursor = shiftDate(cursor, -1);
    if (!dates.has(cursor)) return 0;
  }
  let streak = 0;
  while (dates.has(cursor)) {
    streak += 1;
    cursor = shiftDate(cursor, -1);
  }
  return streak;
}

function createHabitsRouter({ pool }) {
  const router = express.Router();

  router.post("/", async (req, res, next) => {
    try {
      const userId = req.userId;
      const body = req.body || {};
      const fieldErrors = {};
      const titleError = checkTitle(body.title);
      if (titleError) fieldErrors.title = titleError;
      const iconError = checkIcon(body.icon);
      if (iconError) fieldErrors.icon = iconError;
      if (Object.keys(fieldErrors).length > 0) throw validationError(fieldErrors);
      const key = optionalIdempotencyKey(body.idempotencyKey);
      const title = body.title.trim();
      const icon = normalizeIcon(body.icon);

      const executeCreate = async (conn) => {
        const ctx = await resolveGoalSpace(conn, userId);
        const habitId = crypto.randomUUID();
        await conn.execute(
          "INSERT INTO habits (habit_id, space_id, creator_id, title, icon) VALUES (?, ?, ?, ?, ?)",
          [habitId, ctx.spaceId, userId, title, icon]
        );
        const [rows] = await conn.execute("SELECT * FROM habits WHERE habit_id = ? LIMIT 1", [habitId]);
        return serializeHabit(rows[0]);
      };
      const habit = key
        ? (await withIdempotency(pool, { userId, scope: "habit.create", key, payload: body }, executeCreate)).result
        : await withTransaction(pool, executeCreate);
      res.json({ ok: true, data: habit });
    } catch (error) {
      next(error);
    }
  });

  router.get("/", async (req, res, next) => {
    try {
      const userId = req.userId;
      const ctx = await loadReadHabitSpace(pool, userId);
      if (!ctx) return res.json({ ok: true, data: { scope: "none", items: [] } });
      const today = todayInTimezone(ctx.timezone);

      const [habits] = await pool.execute(
        "SELECT * FROM habits WHERE space_id = ? AND status = 'active' ORDER BY created_at ASC",
        [ctx.spaceId]
      );
      if (habits.length === 0) {
        return res.json({ ok: true, data: { scope: ctx.scope, items: [] } });
      }
      const placeholders = habits.map(() => "?").join(", ");
      const [checkins] = await pool.execute(
        `SELECT habit_id, user_id, DATE_FORMAT(checkin_on, '%Y-%m-%d') AS checkin_day
           FROM habit_checkins WHERE habit_id IN (${placeholders})`,
        habits.map((habit) => habit.habit_id)
      );

      const memberIds = ctx.memberIds.map(Number);
      const items = habits.map((habit) => {
        const rows = checkins.filter((row) => row.habit_id === habit.habit_id);
        const todayCheckins = rows
          .filter((row) => row.checkin_day === today)
          .map((row) => Number(row.user_id));
        const datesByUser = new Map();
        for (const row of rows) {
          const uid = Number(row.user_id);
          if (!datesByUser.has(uid)) datesByUser.set(uid, new Set());
          datesByUser.get(uid).add(row.checkin_day);
        }
        const myStreak = computeStreak(datesByUser.get(Number(userId)) || new Set(), today);
        const partnerStreak = ctx.partnerId != null
          ? computeStreak(datesByUser.get(Number(ctx.partnerId)) || new Set(), today)
          : null;
        // 共同天数：同一日期空间内全部成员（couple=双方 / personal=本人）都已打卡
        const allDates = new Set(rows.map((row) => row.checkin_day));
        let jointDays = 0;
        for (const day of allDates) {
          const checked = new Set(
            rows.filter((row) => row.checkin_day === day).map((row) => Number(row.user_id))
          );
          if (memberIds.every((uid) => checked.has(uid))) jointDays += 1;
        }
        return {
          ...serializeHabit(habit),
          today,
          todayCheckins,
          myStreak,
          partnerStreak,
          jointDays
        };
      });
      res.json({ ok: true, data: { scope: ctx.scope, items } });
    } catch (error) {
      next(error);
    }
  });

  router.patch("/:id", async (req, res, next) => {
    try {
      const userId = req.userId;
      const habitId = req.params.id;
      const body = req.body || {};
      const fieldErrors = {};
      const updates = {};
      if (body.title !== undefined) {
        const titleError = checkTitle(body.title);
        if (titleError) fieldErrors.title = titleError;
        else updates.title = body.title.trim();
      }
      if (body.icon !== undefined) {
        const iconError = checkIcon(body.icon);
        if (iconError) fieldErrors.icon = iconError;
        else updates.icon = normalizeIcon(body.icon);
      }
      if (body.status !== undefined) {
        if (body.status !== "active" && body.status !== "archived") {
          fieldErrors.status = "status 必须为 active 或 archived";
        } else {
          updates.status = body.status;
        }
      }
      if (Object.keys(fieldErrors).length > 0) throw validationError(fieldErrors);
      if (Object.keys(updates).length === 0) {
        throw validationError({ _: "没有可更新的字段" });
      }

      const habit = await withTransaction(pool, async (conn) => {
        const [rows] = await conn.execute("SELECT * FROM habits WHERE habit_id = ? LIMIT 1 FOR UPDATE", [habitId]);
        const row = rows[0];
        if (!row || !(await isHabitSpaceMember(conn, row.space_id, userId, { requireActiveSpace: true }))) {
          throw habitNotFound();
        }
        const columns = Object.keys(updates).map((column) => `${column} = ?`).join(", ");
        await conn.execute(`UPDATE habits SET ${columns} WHERE habit_id = ?`, [...Object.values(updates), habitId]);
        const [fresh] = await conn.execute("SELECT * FROM habits WHERE habit_id = ? LIMIT 1", [habitId]);
        return serializeHabit(fresh[0]);
      });
      res.json({ ok: true, data: habit });
    } catch (error) {
      next(error);
    }
  });

  router.delete("/:id", async (req, res, next) => {
    try {
      const userId = req.userId;
      const habitId = req.params.id;
      await withTransaction(pool, async (conn) => {
        const [rows] = await conn.execute("SELECT * FROM habits WHERE habit_id = ? LIMIT 1 FOR UPDATE", [habitId]);
        const row = rows[0];
        if (!row || !(await isHabitSpaceMember(conn, row.space_id, userId, { requireActiveSpace: true }))) {
          throw habitNotFound();
        }
        await conn.execute("DELETE FROM habit_checkins WHERE habit_id = ?", [habitId]);
        await conn.execute("DELETE FROM habits WHERE habit_id = ?", [habitId]);
      });
      res.json({ ok: true, data: { deleted: true } });
    } catch (error) {
      next(error);
    }
  });

  router.post("/:id/checkin", async (req, res, next) => {
    try {
      const userId = req.userId;
      const habitId = req.params.id;
      const body = req.body || {};
      const key = optionalIdempotencyKey(body.idempotencyKey);

      const executeCheckin = async (conn) => {
        const [rows] = await conn.execute("SELECT * FROM habits WHERE habit_id = ? LIMIT 1 FOR UPDATE", [habitId]);
        const habit = rows[0];
        if (!habit || !(await isHabitSpaceMember(conn, habit.space_id, userId, { requireActiveSpace: true }))) {
          throw habitNotFound();
        }
        if (habit.status !== "active") {
          throw new ApiError(409, "HABIT_ARCHIVED", "习惯已归档，不可打卡");
        }
        const [spaces] = await conn.execute(
          "SELECT timezone FROM housework_spaces WHERE space_id = ? LIMIT 1",
          [habit.space_id]
        );
        const today = todayInTimezone((spaces[0] && spaces[0].timezone) || "Asia/Shanghai");

        let created = true;
        try {
          await conn.execute(
            "INSERT INTO habit_checkins (habit_id, user_id, checkin_on) VALUES (?, ?, ?)",
            [habitId, userId, today]
          );
        } catch (error) {
          // uk_habit_checkin_day 撞键 = 当天已打过卡，幂等返回已有
          if (error.code !== "ER_DUP_ENTRY" && error.sqlState !== "23000") throw error;
          created = false;
        }

        // 活动中心：首次打卡且共享空间（有 active 关系）时给伴侣投递动态；
        // 附属能力，写入失败不影响打卡主流程
        if (created) {
          const couple = await loadCoupleSpaceContext(conn, userId);
          if (couple && couple.spaceId === habit.space_id && couple.partnerId != null) {
            try {
              await enqueueActivityEvent(conn, {
                relationshipId: couple.relationshipId, actorId: userId, recipientId: couple.partnerId,
                eventType: "habit.checkin", resourceId: habit.habit_id,
                titleSnapshot: habit.title
              });
            } catch (activityError) {
              console.warn(`[habits] activity event failed (habit=${habitId}): ${activityError.message}`);
            }
          }
        }
        return { habitId, userId: Number(userId), checkinOn: today, created };
      };
      // payload 附上 URL 中的 habitId：同键不同习惯的请求不能互相重放
      const data = key
        ? (await withIdempotency(pool, { userId, scope: "habit.checkin", key, payload: { ...body, habitId } }, executeCheckin)).result
        : await withTransaction(pool, executeCheckin);
      res.json({ ok: true, data });
    } catch (error) {
      next(error);
    }
  });

  router.delete("/:id/checkin", async (req, res, next) => {
    try {
      const userId = req.userId;
      const habitId = req.params.id;
      const data = await withTransaction(pool, async (conn) => {
        const [rows] = await conn.execute("SELECT * FROM habits WHERE habit_id = ? LIMIT 1 FOR UPDATE", [habitId]);
        const habit = rows[0];
        if (!habit || !(await isHabitSpaceMember(conn, habit.space_id, userId, { requireActiveSpace: true }))) {
          throw habitNotFound();
        }
        const [spaces] = await conn.execute(
          "SELECT timezone FROM housework_spaces WHERE space_id = ? LIMIT 1",
          [habit.space_id]
        );
        const today = todayInTimezone((spaces[0] && spaces[0].timezone) || "Asia/Shanghai");
        const [result] = await conn.execute(
          "DELETE FROM habit_checkins WHERE habit_id = ? AND user_id = ? AND checkin_on = ?",
          [habitId, userId, today]
        );
        return { habitId, userId: Number(userId), checkinOn: today, removed: result.affectedRows > 0 };
      });
      res.json({ ok: true, data });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createHabitsRouter };
