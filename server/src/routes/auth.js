const express = require("express");
const bcrypt = require("bcrypt");
const { ApiError } = require("../errors");
const { loadActiveRelationship } = require("../utils/queryHelpers");

function trimValue(value) {
  return typeof value === "string" ? value.trim() : "";
}

function createAuthRouter({ pool, config }) {
  const router = express.Router();

  router.get("/healthz", (_req, res) => {
    res.json({ ok: true, message: "service alive" });
  });

  router.post("/register", async (req, res, next) => {
    try {
      const username = trimValue(req.body.username);
      const email = trimValue(req.body.email);
      const password = typeof req.body.password === "string" ? req.body.password : "";
      const inviteCode = typeof req.body.inviteCode === "string" ? req.body.inviteCode : "";

      if (!username || username.length > 50 || !email || email.length > 100 || !password || !inviteCode) {
        throw new ApiError(400, "INVALID_REQUEST", "请求参数不完整或格式不正确");
      }

      if (inviteCode !== config.inviteCode) {
        throw new ApiError(403, "INVALID_INVITE_CODE", "邀请码错误");
      }

      const [existingUsers] = await pool.execute(
        "SELECT username, email FROM users WHERE username = ? OR email = ? LIMIT 2",
        [username, email]
      );

      for (const row of existingUsers) {
        if (row.username === username) {
          throw new ApiError(409, "USERNAME_TAKEN", "用户名已存在");
        }
        if (row.email === email) {
          throw new ApiError(409, "EMAIL_TAKEN", "邮箱已被注册");
        }
      }

      const hashedPassword = await bcrypt.hash(password, config.bcryptRounds);
      const [result] = await pool.execute(
        "INSERT INTO users (username, email, password, status, invite_code) VALUES (?, ?, ?, 'active', ?)",
        [username, email, hashedPassword, inviteCode]
      );

      res.status(201).json({
        ok: true,
        message: "注册成功",
        data: {
          userId: result.insertId,
          username,
          email
        }
      });
    } catch (error) {
      next(error);
    }
  });

  router.post("/login", async (req, res, next) => {
    try {
      const username = trimValue(req.body.username);
      const password = typeof req.body.password === "string" ? req.body.password : "";

      if (!username || !password) {
        throw new ApiError(400, "INVALID_REQUEST", "请求参数不完整或格式不正确");
      }

      const [rows] = await pool.execute(
        "SELECT id, username, email, password, status FROM users WHERE username = ? LIMIT 1",
        [username]
      );

      if (rows.length === 0) {
        throw new ApiError(401, "INVALID_CREDENTIALS", "用户名或密码错误");
      }

      const user = rows[0];
      if (user.status !== "active") {
        throw new ApiError(403, "USER_DISABLED", "用户不可登录");
      }

      const matched = await bcrypt.compare(password, user.password);
      if (!matched) {
        throw new ApiError(401, "INVALID_CREDENTIALS", "用户名或密码错误");
      }

      res.json({
        ok: true,
        message: "登录成功",
        data: {
          userId: user.id,
          username: user.username,
          email: user.email
        }
      });
    } catch (error) {
      next(error);
    }
  });

  router.get("/couple-info", async (req, res, next) => {
    try {
      const userId = parseInt(req.query.userId, 10);
      if (!userId || userId <= 0) {
        throw new ApiError(400, "INVALID_REQUEST", "userId 参数无效");
      }

      const relationship = await loadActiveRelationship(pool, userId);
      if (!relationship) {
        return res.json({ ok: true, data: { hasCouple: false } });
      }

      const partnerId = relationship.user_id_1 === userId
        ? relationship.user_id_2
        : relationship.user_id_1;

      const [partners] = await pool.execute(
        "SELECT id, username, nickname FROM users WHERE id = ? LIMIT 1",
        [partnerId]
      );

      if (partners.length === 0) {
        return res.json({ ok: true, data: { hasCouple: false } });
      }

      const partner = partners[0];
      res.json({
        ok: true,
        data: {
          hasCouple: true,
          partnerId: partner.id,
          partnerName: partner.username,
          partnerNickname: partner.nickname || null,
          relationshipId: relationship.relationship_id
        }
      });
    } catch (error) {
      next(error);
    }
  });

  // PUT /api/auth/avatar — update user avatar URL
  router.put("/avatar", async (req, res, next) => {
    try {
      const { userId, avatarUrl } = req.body;
      if (!userId || !avatarUrl) throw new ApiError(400, "INVALID_REQUEST", "userId 和 avatarUrl 必填");

      await pool.execute(`UPDATE users SET avatar = ? WHERE id = ?`, [avatarUrl, userId]);

      res.json({ ok: true, message: "头像更新成功" });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createAuthRouter };
