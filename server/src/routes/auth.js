const express = require("express");
const bcrypt = require("bcrypt");
const { ApiError } = require("../errors");
const { loadActiveRelationship, trimValue } = require("../utils/queryHelpers");
const { cache, Keys, TTL } = require("../cache");
const { signToken, signRefreshToken, verifyToken } = require("../utils/jwt");
const { requireAuthForBusiness } = require("../middleware/auth");

function createAuthRouter({ pool, config, authLimiter, strictLimiter }) {
  const router = express.Router();

  router.get("/healthz", (_req, res) => {
    res.json({ ok: true, message: "service alive" });
  });

  router.post("/register", authLimiter, async (req, res, next) => {
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

  router.post("/login", authLimiter, async (req, res, next) => {
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

      const accessToken = signToken({ userId: user.id });
      const refreshToken = signRefreshToken({ userId: user.id });

      res.json({
        ok: true,
        message: "登录成功",
        data: {
          userId: user.id,
          username: user.username,
          email: user.email,
          accessToken,
          refreshToken
        }
      });

      setImmediate(() => preloadUserCache(user.id));
    } catch (error) {
      next(error);
    }
  });

  // 刷新 access token：客户端用 refresh token 换取新的 access token
  router.post("/refresh", authLimiter, async (req, res, next) => {
    try {
      const refreshToken = typeof req.body.refreshToken === "string" ? req.body.refreshToken : "";
      if (!refreshToken) throw new ApiError(400, "INVALID_REQUEST", "缺少 refreshToken");

      let decoded;
      try {
        decoded = verifyToken(refreshToken);
      } catch (_e) {
        throw new ApiError(401, "UNAUTHORIZED", "refresh token 无效或已过期，请重新登录");
      }

      const accessToken = signToken({ userId: decoded.userId });
      res.json({ ok: true, message: "刷新成功", data: { accessToken } });
    } catch (error) {
      next(error);
    }
  });

  // 用 wx.login 的 code 换取微信 openid
  async function exchangeOpenid(code) {
    if (!config.wechatAppId || !config.wechatSecret) {
      throw new ApiError(503, "WECHAT_NOT_CONFIGURED", "微信登录未配置");
    }
    if (!code) throw new ApiError(400, "INVALID_REQUEST", "缺少 code");

    const url = `https://api.weixin.qq.com/sns/jscode2session?appid=${encodeURIComponent(config.wechatAppId)}&secret=${encodeURIComponent(config.wechatSecret)}&js_code=${encodeURIComponent(code)}&grant_type=authorization_code`;
    let payload;
    try {
      const resp = await fetch(url, { method: "GET" });
      payload = await resp.json();
    } catch (e) {
      throw new ApiError(502, "WECHAT_UPSTREAM_ERROR", "微信服务请求失败");
    }
    if (!payload || payload.errcode || !payload.openid) {
      throw new ApiError(401, "WECHAT_AUTH_FAILED", payload && payload.errmsg ? payload.errmsg : "微信登录校验失败");
    }
    return payload.openid;
  }

  function buildUserSession(user) {
    const accessToken = signToken({ userId: user.id });
    const refreshToken = signRefreshToken({ userId: user.id });
    return {
      userId: user.id,
      username: user.username,
      email: user.email,
      nickname: user.nickname || null,
      avatarUrl: user.avatar || null,
      accessToken,
      refreshToken
    };
  }

  // 微信登录：code -> openid -> 查绑定。已绑定返回登录态，未绑定返回 { bound:false, openid }
  router.post("/wechat-login", authLimiter, async (req, res, next) => {
    try {
      const code = typeof req.body.code === "string" ? req.body.code : "";
      const openid = await exchangeOpenid(code);

      const [rows] = await pool.execute(
        "SELECT id, username, email, nickname, avatar, status FROM users WHERE wechat_openid = ? LIMIT 1",
        [openid]
      );

      if (rows.length === 0) {
        return res.json({ ok: true, message: "需要绑定账号", data: { bound: false, openid } });
      }

      const user = rows[0];
      if (user.status !== "active") {
        throw new ApiError(403, "USER_DISABLED", "用户不可登录");
      }

      res.json({ ok: true, message: "登录成功", data: Object.assign({ bound: true }, buildUserSession(user)) });
      setImmediate(() => preloadUserCache(user.id));
    } catch (error) {
      next(error);
    }
  });

  // 微信绑定：openid + 已有账号密码，校验后写入 wechat_openid 并返回登录态
  router.post("/wechat-bind", authLimiter, async (req, res, next) => {
    try {
      const openid = trimValue(req.body.openid);
      const username = trimValue(req.body.username);
      const password = typeof req.body.password === "string" ? req.body.password : "";
      if (!openid || !username || !password) {
        throw new ApiError(400, "INVALID_REQUEST", "参数不完整");
      }

      const [bound] = await pool.execute(
        "SELECT id FROM users WHERE wechat_openid = ? LIMIT 1",
        [openid]
      );
      if (bound.length > 0) {
        throw new ApiError(409, "OPENID_BOUND", "该微信已绑定其他账号");
      }

      const [rows] = await pool.execute(
        "SELECT id, username, email, nickname, avatar, password, status FROM users WHERE username = ? LIMIT 1",
        [username]
      );
      if (rows.length === 0) throw new ApiError(401, "INVALID_CREDENTIALS", "用户名或密码错误");

      const user = rows[0];
      if (user.status !== "active") throw new ApiError(403, "USER_DISABLED", "用户不可登录");

      const matched = await bcrypt.compare(password, user.password);
      if (!matched) throw new ApiError(401, "INVALID_CREDENTIALS", "用户名或密码错误");

      await pool.execute("UPDATE users SET wechat_openid = ? WHERE id = ?", [openid, user.id]);

      res.json({ ok: true, message: "绑定成功", data: Object.assign({ bound: true }, buildUserSession(user)) });
      setImmediate(() => preloadUserCache(user.id));
    } catch (error) {
      next(error);
    }
  });

  router.get("/couple-info", requireAuthForBusiness, async (req, res, next) => {
    try {
      const userId = req.userId;

      const relationship = await loadActiveRelationship(pool, userId);
      if (!relationship) {
        return res.json({ ok: true, data: { hasCouple: false } });
      }

      const partnerId = relationship.user_id_1 === userId
        ? relationship.user_id_2
        : relationship.user_id_1;

      const [partners] = await pool.execute(
        "SELECT id, username, nickname, avatar FROM users WHERE id = ? LIMIT 1",
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
          partnerAvatarUrl: partner.avatar || null,
          relationshipId: relationship.relationship_id
        }
      });
    } catch (error) {
      next(error);
    }
  });

  router.put("/avatar", requireAuthForBusiness, async (req, res, next) => {
    try {
      const userId = req.userId;
      const { avatarUrl } = req.body;
      if (!avatarUrl) throw new ApiError(400, "INVALID_REQUEST", "avatarUrl 必填");

      const [rows] = await pool.execute("SELECT id FROM users WHERE id = ? LIMIT 1", [userId]);
      if (rows.length === 0) throw new ApiError(404, "NOT_FOUND", "用户不存在");

      await pool.execute(`UPDATE users SET avatar = ? WHERE id = ?`, [avatarUrl, userId]);
      cache.del(Keys.profile(userId));

      res.json({ ok: true, message: "头像更新成功" });
    } catch (error) {
      next(error);
    }
  });

  router.get("/profile", requireAuthForBusiness, async (req, res, next) => {
    try {
      const userId = req.userId;

      const [rows] = await pool.execute(
        "SELECT id, username, email, nickname, avatar FROM users WHERE id = ? LIMIT 1",
        [userId]
      );
      if (rows.length === 0) throw new ApiError(404, "NOT_FOUND", "用户不存在");

      const user = rows[0];
      res.json({
        ok: true,
        data: {
          userId: user.id,
          username: user.username,
          email: user.email,
          nickname: user.nickname || null,
          avatarUrl: user.avatar || null
        }
      });
    } catch (error) {
      next(error);
    }
  });

  router.get("/resolve", async (req, res, next) => {
    try {
      const username = trimValue(req.query.username);
      if (!username) throw new ApiError(400, "INVALID_REQUEST", "username 参数无效");

      const [rows] = await pool.execute(
        "SELECT id FROM users WHERE username = ? LIMIT 1",
        [username]
      );
      if (rows.length === 0) throw new ApiError(404, "NOT_FOUND", "用户不存在");

      res.json({ ok: true, data: { userId: rows[0].id } });
    } catch (error) {
      next(error);
    }
  });

  router.put("/nickname", requireAuthForBusiness, async (req, res, next) => {
    try {
      const userId = req.userId;
      const { nickname } = req.body;

      const [rows] = await pool.execute("SELECT id FROM users WHERE id = ? LIMIT 1", [userId]);
      if (rows.length === 0) throw new ApiError(404, "NOT_FOUND", "用户不存在");

      const [result] = await pool.execute("UPDATE users SET nickname = ? WHERE id = ?", [nickname || null, userId]);
      if (result.affectedRows === 0) throw new ApiError(404, "NOT_FOUND", "昵称更新失败");
      cache.del(Keys.profile(userId));
      res.json({ ok: true, message: "昵称更新成功" });
    } catch (error) {
      next(error);
    }
  });

  router.put("/password", strictLimiter, requireAuthForBusiness, async (req, res, next) => {
    try {
      const userId = req.userId;
      const { currentPassword, newPassword } = req.body;
      if (!currentPassword || !newPassword) {
        throw new ApiError(400, "INVALID_REQUEST", "参数不完整");
      }

      const [rows] = await pool.execute(
        "SELECT password FROM users WHERE id = ? LIMIT 1",
        [userId]
      );
      if (rows.length === 0) throw new ApiError(404, "NOT_FOUND", "用户不存在");

      const matched = await bcrypt.compare(currentPassword, rows[0].password);
      if (!matched) throw new ApiError(401, "WRONG_PASSWORD", "当前密码错误");

      const hashed = await bcrypt.hash(newPassword, config.bcryptRounds);
      await pool.execute("UPDATE users SET password = ? WHERE id = ?", [hashed, userId]);
      res.json({ ok: true, message: "密码修改成功" });
    } catch (error) {
      next(error);
    }
  });

  router.delete("/account", strictLimiter, requireAuthForBusiness, async (req, res, next) => {
    try {
      const userId = req.userId;
      const password = req.body?.password;
      if (!password) throw new ApiError(400, "INVALID_REQUEST", "需要密码确认");

      const [users] = await pool.execute("SELECT password FROM users WHERE id = ? LIMIT 1", [userId]);
      if (users.length === 0) throw new ApiError(404, "NOT_FOUND", "用户不存在");

      const matched = await bcrypt.compare(password, users[0].password);
      if (!matched) throw new ApiError(401, "WRONG_PASSWORD", "密码错误，无法删除账号");

      await pool.execute(
        "DELETE FROM couple_relationships WHERE user_id_1 = ? OR user_id_2 = ?",
        [userId, userId]
      );
      const [result] = await pool.execute("DELETE FROM users WHERE id = ?", [userId]);
      if (result.affectedRows === 0) throw new ApiError(404, "NOT_FOUND", "账号删除失败");
      cache.del(Keys.profile(userId));
      cache.del(Keys.relationship(userId));
      cache.del(Keys.coupleRole(userId));
      res.json({ ok: true, message: "账号已删除" });
    } catch (error) {
      next(error);
    }
  });

  async function preloadUserCache(userId) {
    try {
      const INVENTORY_SELECT_FIELDS = "inventory_id, user_id, name, category, quantity, unit, expiry_date, purchase_date, last_consumed_at, threshold, image_url, created_at, updated_at, relationship_id";
      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      let query, params;
      if (relationshipId) {
        query = `SELECT ${INVENTORY_SELECT_FIELDS} FROM inventory WHERE relationship_id = ? OR (user_id = ? AND relationship_id IS NULL) ORDER BY updated_at DESC`;
        params = [relationshipId, userId];
      } else {
        query = `SELECT ${INVENTORY_SELECT_FIELDS} FROM inventory WHERE user_id = ? AND relationship_id IS NULL ORDER BY updated_at DESC`;
        params = [userId];
      }
      const [rows] = await pool.execute(query, params);
      const items = rows.map(row => ({
        ...row,
        isLowStock: Number(row.quantity) <= Number(row.threshold)
      }));
      cache.set(Keys.inventory(userId), { ok: true, message: "查询成功", data: { items, relationshipId } }, TTL.INVENTORY);

      const [beadRows] = await pool.execute(
        `SELECT bi.color_code, bi.quantity, bi.threshold_override, bi.updated_at, bs.default_threshold
         FROM bead_inventory bi LEFT JOIN bead_settings bs ON bs.user_id = bi.user_id
         WHERE bi.user_id = ? ORDER BY bi.color_code`, [userId]
      );
      if (beadRows.length > 0) {
        const defaultThreshold = beadRows[0].default_threshold || 200;
        const beadItems = beadRows.map(row => ({
          colorCode: row.color_code,
          quantity: Number(row.quantity || 0),
          thresholdOverride: row.threshold_override,
          effectiveThreshold: row.threshold_override != null ? row.threshold_override : defaultThreshold
        }));
        cache.set(Keys.beads(userId), { ok: true, data: { items: beadItems } }, TTL.BEADS);
      }

      console.log(`Preloaded cache for user ${userId}`);
    } catch (e) {
      console.error(`Cache preload failed for user ${userId}:`, e.message);
    }
  }

  return router;
}

module.exports = { createAuthRouter };
