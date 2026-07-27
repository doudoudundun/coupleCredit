const express = require("express");
const { ApiError } = require("../errors");
const { parseRequiredInteger } = require("../utils/queryHelpers");

function createNotificationRouter({ pool }) {
  const router = express.Router();

  router.get("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.userId);
      const limit = Math.min(Math.max(parseInt(req.query.limit, 10) || 20, 1), 50);
      const [rows] = await pool.execute(
        "SELECT notification_id, type, title, body, related_id, is_read, created_at FROM notifications WHERE user_id = ? ORDER BY created_at DESC LIMIT " + limit,
        [userId]
      );
      res.json({ ok: true, data: { items: rows.map(r => ({
        notificationId: r.notification_id,
        type: r.type,
        title: r.title,
        body: r.body,
        relatedId: r.related_id,
        isRead: !!r.is_read,
        createdAt: r.created_at
      }))}});
    } catch (error) {
      next(error);
    }
  });

  router.put("/:id/read", async (req, res, next) => {
    try {
      const notificationId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(req.userId);
      const [result] = await pool.execute(
        "UPDATE notifications SET is_read = 1 WHERE notification_id = ? AND user_id = ?",
        [notificationId, userId]
      );
      if (result.affectedRows === 0) {
        throw new ApiError(404, "NOT_FOUND", "通知不存在");
      }
      res.json({ ok: true });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createNotificationRouter };
