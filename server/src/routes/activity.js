// 活动中心（动态流）路由（F4），挂在 /api/activity（全局鉴权之后）。
//
// 事件由各业务写路径在事务内投递（bills/goals/sharedPlans/housework/recurring，
// 见 src/utils/activityEvents.js）；本路由只做读与已读管理。
//
// 读路径每次重校验「当前 active 关系」：只返回 relationship_id = 当前关系且
// 未 revoke 的事件——解绑（无论走哪条 unbind 路径）后旧事件自然不可见，
// unbind 事务内的整体 revoke 是第二道保险。
const express = require("express");
const { ApiError } = require("../errors");
const { withTransaction } = require("../utils/transactions");
const { loadActiveRelationship, parseRequiredInteger } = require("../utils/queryHelpers");

const DEFAULT_LIMIT = 20;
const MAX_LIMIT = 50;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function parseLimit(raw) {
  if (raw === undefined || raw === null || raw === "") return DEFAULT_LIMIT;
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value < 1 || value > MAX_LIMIT) {
    throw new ApiError(400, "INVALID_REQUEST", `limit 必须是 1-${MAX_LIMIT} 的整数`);
  }
  return value;
}

function toBase64Url(text) {
  return Buffer.from(text, "utf8").toString("base64")
    .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function fromBase64Url(text) {
  const padded = String(text).replace(/-/g, "+").replace(/_/g, "/");
  return Buffer.from(padded, "base64").toString("utf8");
}

/**
 * 游标内容：[created_at_ms, event_id]，与列表排序键（created_at DESC, event_id DESC）
 * 一一对应。TIMESTAMP 秒级精度下同秒事件靠 event_id 决胜，翻页不重不漏。
 */
function encodeCursor(row) {
  return toBase64Url(JSON.stringify([
    new Date(row.created_at).getTime(),
    row.event_id
  ]));
}

function decodeCursor(value) {
  let parsed;
  try {
    parsed = JSON.parse(fromBase64Url(value));
  } catch (_error) {
    parsed = null;
  }
  if (!Array.isArray(parsed) || parsed.length !== 2
    || !Number.isInteger(parsed[0]) || parsed[0] < 0
    || typeof parsed[1] !== "string" || !UUID_RE.test(parsed[1])) {
    throw new ApiError(400, "INVALID_REQUEST", "cursor 无效");
  }
  return { createdAtMs: parsed[0], eventId: parsed[1] };
}

/** ms → +08:00 墙上时间的 'YYYY-MM-DD HH:MM:SS'（与 MySQL TIMESTAMP 比较）。 */
function formatSqlTimestamp(ms) {
  return new Date(ms + 8 * 3600 * 1000).toISOString().slice(0, 19).replace("T", " ");
}

function serializeEvent(row) {
  return {
    eventId: row.event_id,
    eventType: row.event_type,
    actorId: Number(row.actor_id),
    resourceId: row.resource_id,
    amountCents: row.amount_cents == null ? null : Number(row.amount_cents),
    titleSnapshot: row.title_snapshot || null,
    state: row.state,
    createdAt: row.created_at
  };
}

function createActivityRouter({ pool }) {
  const router = express.Router();

  // 当前用户（=收件人）的动态流，游标分页 + 未读角标数
  router.get("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.userId);
      const limit = parseLimit(req.query.limit);
      const cursor = req.query.cursor ? decodeCursor(req.query.cursor) : null;

      const relationship = await loadActiveRelationship(pool, userId);
      if (!relationship) {
        return res.json({
          ok: true,
          message: "查询成功",
          data: { items: [], nextCursor: "", hasMore: false, unreadCount: 0 }
        });
      }
      const relationshipId = relationship.relationship_id;

      const where = [
        "recipient_id = ?",
        "relationship_id = ?",
        "state <> 'revoked'"
      ];
      const params = [userId, relationshipId];
      if (cursor) {
        where.push("(created_at < ? OR (created_at = ? AND event_id < ?))");
        params.push(formatSqlTimestamp(cursor.createdAtMs), formatSqlTimestamp(cursor.createdAtMs), cursor.eventId);
      }

      const [rows] = await pool.execute(
        `SELECT event_id, relationship_id, recipient_id, actor_id, event_type,
                resource_id, amount_cents, title_snapshot, state, created_at
         FROM activity_events
         WHERE ${where.join(" AND ")}
         ORDER BY created_at DESC, event_id DESC
         LIMIT ${limit + 1}`,
        params
      );

      const hasMore = rows.length > limit;
      const page = hasMore ? rows.slice(0, limit) : rows;
      const [unreadRows] = await pool.execute(
        "SELECT COUNT(*) AS unread FROM activity_events WHERE recipient_id = ? AND relationship_id = ? AND state = 'unread'",
        [userId, relationshipId]
      );

      res.json({
        ok: true,
        message: "查询成功",
        data: {
          items: page.map(serializeEvent),
          nextCursor: hasMore && page.length > 0 ? encodeCursor(page[page.length - 1]) : "",
          hasMore,
          unreadCount: Number(unreadRows[0].unread)
        }
      });
    } catch (error) {
      next(error);
    }
  });

  // 已读：不带 eventIds 则全部置 seen；带则只置这些（均限本人收件箱内的 unread）
  router.patch("/seen", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.userId);
      const rawIds = req.body && req.body.eventIds;

      let updated = 0;
      if (rawIds === undefined || rawIds === null) {
        updated = await withTransaction(pool, async (conn) => {
          const [result] = await conn.execute(
            "UPDATE activity_events SET state = 'seen' WHERE recipient_id = ? AND state = 'unread'",
            [userId]
          );
          return result.affectedRows;
        });
      } else {
        if (!Array.isArray(rawIds) || rawIds.length > 100
          || rawIds.some((id) => typeof id !== "string" || !UUID_RE.test(id))) {
          throw new ApiError(400, "INVALID_REQUEST", "eventIds 应为最多 100 个事件 ID");
        }
        if (rawIds.length > 0) {
          const placeholders = rawIds.map(() => "?").join(", ");
          updated = await withTransaction(pool, async (conn) => {
            const [result] = await conn.execute(
              `UPDATE activity_events SET state = 'seen'
               WHERE recipient_id = ? AND state = 'unread' AND event_id IN (${placeholders})`,
              [userId, ...rawIds]
            );
            return result.affectedRows;
          });
        }
      }

      res.json({ ok: true, message: "已标记已读", data: { updated } });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createActivityRouter };
