const express = require("express");
const { ApiError } = require("../errors");

function createChatRouter({ pool }) {
  const router = express.Router();

  // POST /api/chat/messages — insert a chat message
  router.post("/messages", async (req, res, next) => {
    try {
      const { relationshipId, userId, content, messageType, displayTime, isLiked } = req.body;
      if (!userId || !content) throw new ApiError(400, "INVALID_REQUEST", "参数不完整");

      const [result] = await pool.execute(
        "INSERT INTO chat_messages (relationship_id, user_id, content, message_type, display_time, created_at, is_liked, is_deleted) VALUES (?, ?, ?, ?, ?, ?, ?, 0)",
        [
          relationshipId || null,
          userId,
          content,
          messageType || "text",
          displayTime || "",
          Date.now(),
          isLiked ? 1 : 0
        ]
      );
      res.status(201).json({ ok: true, data: { messageId: result.insertId } });
    } catch (error) {
      next(error);
    }
  });

  // GET /api/chat/messages?relationshipId=&limit=&before= — get messages
  router.get("/messages", async (req, res, next) => {
    try {
      const relationshipId = parseInt(req.query.relationshipId, 10);
      const rawLimit = parseInt(req.query.limit, 10);
      const limit = Math.min(Number.isFinite(rawLimit) ? rawLimit : 50, 200);
      const before = req.query.before ? parseInt(req.query.before, 10) : null;

      if (!relationshipId) throw new ApiError(400, "INVALID_REQUEST", "relationshipId 必填");

      let sql = "SELECT id, relationship_id, user_id, content, message_type, display_time, created_at, is_liked, is_deleted FROM chat_messages WHERE relationship_id = ? AND is_deleted = 0";
      const params = [relationshipId];

      if (Number.isFinite(before)) {
        sql += " AND created_at < ?";
        params.push(before);
      }
      sql += " ORDER BY created_at DESC LIMIT ?";
      params.push(limit);

      const [rows] = await pool.execute(sql, params);

      // Reverse for chronological order
      rows.reverse();
      res.json({ ok: true, data: { messages: rows } });
    } catch (error) {
      next(error);
    }
  });

  // PUT /api/chat/messages/:id/like — toggle like
  router.put("/messages/:id/like", async (req, res, next) => {
    try {
      const messageId = parseInt(req.params.id, 10);
      const { isLiked } = req.body;
      await pool.execute("UPDATE chat_messages SET is_liked = ? WHERE id = ?", [isLiked ? 1 : 0, messageId]);
      res.json({ ok: true });
    } catch (error) {
      next(error);
    }
  });

  // DELETE /api/chat/messages/:id — soft delete
  router.delete("/messages/:id", async (req, res, next) => {
    try {
      const messageId = parseInt(req.params.id, 10);
      await pool.execute("UPDATE chat_messages SET is_deleted = 1 WHERE id = ?", [messageId]);
      res.json({ ok: true });
    } catch (error) {
      next(error);
    }
  });

  // GET /api/chat/search?relationshipId=&keyword= — search messages
  router.get("/search", async (req, res, next) => {
    try {
      const relationshipId = parseInt(req.query.relationshipId, 10);
      const keyword = (req.query.keyword || "").trim();
      if (!relationshipId || !keyword) throw new ApiError(400, "INVALID_REQUEST", "参数不完整");

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
