const express = require("express");
const { ApiError } = require("../errors");
const { parseRequiredInteger } = require("../utils/queryHelpers");

function createFcmRouter({ pool }) {
  const router = express.Router();

  router.post("/register", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.userId);
      const token = req.body.token;
      if (!token || typeof token !== "string" || token.trim().length < 10) {
        throw new ApiError(400, "INVALID_REQUEST", "无效的 FCM token");
      }
      const deviceId = req.body.deviceId || null;

      await pool.execute(
        `INSERT INTO fcm_tokens (user_id, token, device_id) VALUES (?, ?, ?)
         ON DUPLICATE KEY UPDATE user_id = VALUES(user_id), device_id = VALUES(device_id), updated_at = CURRENT_TIMESTAMP`,
        [userId, token.trim(), deviceId]
      );
      res.json({ ok: true });
    } catch (error) {
      next(error);
    }
  });

  router.delete("/token", async (req, res, next) => {
    try {
      const token = req.body.token || req.query.token;
      if (!token || typeof token !== "string") {
        throw new ApiError(400, "INVALID_REQUEST", "缺少 token");
      }
      await pool.execute("DELETE FROM fcm_tokens WHERE token = ?", [token.trim()]);
      res.json({ ok: true });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createFcmRouter };
