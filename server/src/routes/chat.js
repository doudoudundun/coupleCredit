const express = require("express");
const { ApiError } = require("../errors");
const { loadActiveRelationship } = require("../utils/queryHelpers");

function createChatRouter({ pool }) {
  const router = express.Router();

  async function requireRelationshipAccess(userId, relationshipId) {
    const relationship = await loadActiveRelationship(pool, userId);
    if (!relationship || relationship.relationship_id !== relationshipId) {
      throw new ApiError(403, "FORBIDDEN", "无权访问该会话");
    }
    return relationship;
  }

  async function loadUserMessage(userId, messageId, requireOwnership = false) {
    const relationship = await loadActiveRelationship(pool, userId);
    if (!relationship) throw new ApiError(403, "FORBIDDEN", "无权操作");

    const [rows] = await pool.execute(
      "SELECT id, relationship_id, user_id FROM chat_messages WHERE id = ? LIMIT 1",
      [messageId]
    );
    if (rows.length === 0) throw new ApiError(404, "NOT_FOUND", "消息不存在");

    const msg = rows[0];
    if (msg.relationship_id !== relationship.relationship_id) {
      throw new ApiError(403, "FORBIDDEN", "无权操作该消息");
    }
    if (requireOwnership && msg.user_id !== userId) {
      throw new ApiError(403, "FORBIDDEN", "只能操作自己发送的消息");
    }

    return { message: msg, relationship };
  }

  router.post("/messages", async (req, res, next) => {
    try {
      const { relationshipId, userId, content, messageType, displayTime, isLiked } = req.body;
      if (!userId || !content) throw new ApiError(400, "INVALID_REQUEST", "参数不完整");

      const relationship = await loadActiveRelationship(pool, userId);
      if (!relationship) throw new ApiError(403, "FORBIDDEN", "无权操作");

      const [result] = await pool.execute(
        "INSERT INTO chat_messages (relationship_id, user_id, content, message_type, display_time, created_at, is_liked, is_deleted) VALUES (?, ?, ?, ?, ?, ?, ?, 0)",
        [relationship.relationship_id, userId, content, messageType || "text", displayTime || "", Date.now(), isLiked ? 1 : 0]
      );
      res.status(201).json({ ok: true, data: { messageId: result.insertId } });
    } catch (error) {
      next(error);
    }
  });

  router.get("/messages", async (req, res, next) => {
    try {
      const userId = parseInt(req.query.userId, 10);
      const relationshipId = parseInt(req.query.relationshipId, 10);
      const rawLimit = parseInt(req.query.limit, 10);
      const limit = Math.min(Number.isFinite(rawLimit) ? rawLimit : 50, 200);
      const before = req.query.before ? parseInt(req.query.before, 10) : null;

      if (!userId || !relationshipId) throw new ApiError(400, "INVALID_REQUEST", "参数不完整");
      await requireRelationshipAccess(userId, relationshipId);

      let sql = "SELECT id, relationship_id, user_id, content, message_type, display_time, created_at, is_liked, is_deleted FROM chat_messages WHERE relationship_id = ? AND is_deleted = 0";
      const params = [relationshipId];
      if (Number.isFinite(before)) {
        sql += " AND created_at < ?";
        params.push(before);
      }
      sql += " ORDER BY created_at DESC LIMIT " + Math.floor(limit);

      const [rows] = await pool.execute(sql, params);
      rows.reverse();
      res.json({ ok: true, data: { messages: rows } });
    } catch (error) {
      next(error);
    }
  });

  router.put("/messages/:id/like", async (req, res, next) => {
    try {
      const messageId = parseInt(req.params.id, 10);
      const userId = parseInt(req.body.userId, 10);
      if (!userId) throw new ApiError(400, "INVALID_REQUEST", "userId 必填");

      await loadUserMessage(userId, messageId);

      const { isLiked } = req.body;
      await pool.execute("UPDATE chat_messages SET is_liked = ? WHERE id = ?", [isLiked ? 1 : 0, messageId]);
      res.json({ ok: true });
    } catch (error) {
      next(error);
    }
  });

  router.delete("/messages/:id", async (req, res, next) => {
    try {
      const messageId = parseInt(req.params.id, 10);
      const userId = parseInt(req.query.userId, 10);
      if (!userId) throw new ApiError(400, "INVALID_REQUEST", "userId 必填");

      await loadUserMessage(userId, messageId, true);

      const [result] = await pool.execute("UPDATE chat_messages SET is_deleted = 1 WHERE id = ?", [messageId]);
      if (result.affectedRows === 0) throw new ApiError(404, "NOT_FOUND", "消息不存在");
      res.json({ ok: true });
    } catch (error) {
      next(error);
    }
  });

  router.get("/search", async (req, res, next) => {
    try {
      const userId = parseInt(req.query.userId, 10);
      const relationshipId = parseInt(req.query.relationshipId, 10);
      const keyword = (req.query.keyword || "").trim();
      if (!userId || !relationshipId || !keyword) throw new ApiError(400, "INVALID_REQUEST", "参数不完整");

      await requireRelationshipAccess(userId, relationshipId);

      const [rows] = await pool.execute(
        "SELECT id, relationship_id, user_id, content, message_type, display_time, created_at, is_liked FROM chat_messages WHERE relationship_id = ? AND is_deleted = 0 AND content LIKE ? ORDER BY created_at DESC LIMIT 50",
        [relationshipId, `%${keyword}%`]
      );
      res.json({ ok: true, data: { messages: rows } });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createChatRouter };
