const express = require("express");
const { ApiError } = require("../errors");
const { parseRequiredInteger } = require("../utils/queryHelpers");

function createPushRouter({ pool }) {
  const router = express.Router();

  router.post("/register", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.body.userId);
      const token = req.body.token;
      const channel = req.body.channel || "fcm";

      if (!token || typeof token !== "string" || token.trim().length < 10) {
        throw new ApiError(400, "INVALID_REQUEST", "无效的 push token");
      }
      if (!["fcm", "jpush"].includes(channel)) {
        throw new ApiError(400, "INVALID_REQUEST", "不支持的推送通道: " + channel);
      }

      const deviceId = req.body.deviceId || null;

      await pool.execute(
        `INSERT INTO push_tokens (user_id, token, channel, device_id) VALUES (?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE user_id = VALUES(user_id), device_id = VALUES(device_id), updated_at = CURRENT_TIMESTAMP`,
        [userId, token.trim(), channel, deviceId]
      );
      console.log(`Push: Registered ${channel} token for userId=${userId}`);
      res.json({ ok: true, channel });
    } catch (error) {
      next(error);
    }
  });

  router.delete("/token", async (req, res, next) => {
    try {
      const token = req.body.token || req.query.token;
      const channel = req.body.channel || req.query.channel;
      if (!token || typeof token !== "string") {
        throw new ApiError(400, "INVALID_REQUEST", "缺少 token");
      }

      if (channel) {
        await pool.execute("DELETE FROM push_tokens WHERE token = ? AND channel = ?", [token.trim(), channel]);
      } else {
        await pool.execute("DELETE FROM push_tokens WHERE token = ?", [token.trim()]);
      }
      res.json({ ok: true });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createPushRouter };
