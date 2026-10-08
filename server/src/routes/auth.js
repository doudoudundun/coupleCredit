const express = require("express");
const crypto = require("crypto");
const bcrypt = require("bcrypt");
const { ApiError } = require("../errors");
const { loadActiveRelationship, loadPartnerProfile, trimValue, invalidateRelationshipScopedCaches } = require("../utils/queryHelpers");
const { cache, Keys, TTL } = require("../cache");
const { signToken, signRefreshToken, verifyRefreshToken } = require("../utils/jwt");
const { mapWechatSessionError } = require("../utils/wechatErrors");
const { createRequireAuth, loadActiveUserSession, isAuthSchemaError, authSchemaError } = require("../middleware/auth");
const { bcryptGate } = require("../middleware/bcryptGate");
const { addExpirationFlags, INVENTORY_SELECT_FIELDS } = require("./inventory");
const { withTransaction } = require("../utils/transactions");
const { cleanHouseworkForAccountDeletion } = require("../utils/houseworkLifecycle");
const {
  cleanCategoryCollectionsForAccountDeletion,
  archiveSharedCategoryCollectionsForAccountDeletion
} = require("../utils/categoryCollectionCleanup");

function createAuthRouter({
  pool,
  config,
  authLimiter,
  strictLimiter,
  contentSecurity,
  requireAuth: suppliedRequireAuth
}) {
  const router = express.Router();
  const requireAuth = suppliedRequireAuth || createRequireAuth(pool);

  // 内容安全：由 index.js 注入。单元测试直接构造 router 时不传，此时跳过远程审核
  // （checkText 内部的本地兜底校验仍会执行，因为它不依赖任何外部服务）。
  const security = contentSecurity || null;

  /**
   * UGC 文本送审，不通过则抛 400 让客户端提示用户修改。
   * @param {{userId:number|null, text:string, limits?:object, code?:string}} params
   *   code 是 wx.login 的临时凭证：注册时账号还不存在、或者账号是用密码注册的
   *   （没有 wechat_openid），都要靠它现场换一个 openid 来完成审核。
   */
  async function assertTextAllowed({ userId, text, limits, code }) {
    if (!security) return;
    const verdict = await security.checkText({ userId, content: text, limits, code });
    if (!verdict.pass) {
      throw new ApiError(
        400,
        verdict.reason || "CONTENT_NOT_ALLOWED",
        verdict.message || "内容未通过安全审核，请修改后重试"
      );
    }
  }

  async function executeAuthQuery(sql, params) {
    try {
      return await pool.execute(sql, params);
    } catch (error) {
      if (isAuthSchemaError(error)) throw authSchemaError();
      throw error;
    }
  }

  router.get("/healthz", (_req, res) => {
    res.json({ ok: true, message: "service alive" });
  });

  router.post("/register", authLimiter, bcryptGate, async (req, res, next) => {
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

      // 用户名是用户自由输入的文本，属 UGC，注册前先过内容安全。
      // 此时账号尚未创建（没有 userId），只能靠客户端随请求带来的 code 换 openid；
      // 换不到时 checkText 会降级放行，但本地兜底（字符集/控制字符/导流特征）始终生效。
      await assertTextAllowed({
        userId: null,
        text: username,
        code: typeof req.body.code === "string" ? req.body.code : ""
      });

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
        "INSERT INTO users (username, email, password, status, invite_code) VALUES (?, ?, ?, 'active', NULL)",
        [username, email, hashedPassword]
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

  router.post("/login", authLimiter, bcryptGate, async (req, res, next) => {
    try {
      const username = trimValue(req.body.username);
      const password = typeof req.body.password === "string" ? req.body.password : "";

      if (!username || !password) {
        throw new ApiError(400, "INVALID_REQUEST", "请求参数不完整或格式不正确");
      }

      const [rows] = await executeAuthQuery(
        "SELECT id, username, email, password, status, auth_token_version FROM users WHERE username = ? LIMIT 1",
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

      const sessionVersion = Number(user.auth_token_version);
      if (!Number.isSafeInteger(sessionVersion) || sessionVersion < 0) {
        throw authSchemaError();
      }
      const accessToken = signToken({ userId: user.id, sessionVersion });
      const refreshToken = signRefreshToken({ userId: user.id, sessionVersion });

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
        decoded = verifyRefreshToken(refreshToken);
      } catch (_e) {
        throw new ApiError(401, "UNAUTHORIZED", "refresh token 无效或已过期，请重新登录");
      }

      const session = await loadActiveUserSession(pool, decoded);
      if (!session) throw new ApiError(401, "UNAUTHORIZED", "用户不存在、已停用或会话已撤销");
      const accessToken = signToken({ userId: session.id, sessionVersion: session.sessionVersion });
      // 滑动续期：每次刷新同时签发新 refresh token，登录态随使用自动延续。
      // 旧 refresh token 在其自身有效期内仍可用（JWT 无状态，不做服务端吊销表）。
      const nextRefreshToken = signRefreshToken({ userId: session.id, sessionVersion: session.sessionVersion });
      res.json({ ok: true, message: "刷新成功", data: { accessToken, refreshToken: nextRefreshToken } });
    } catch (error) {
      next(error);
    }
  });

  // 用 wx.login 的 code 换取微信 openid
  async function exchangeOpenid(code) {
    if (!config.wechatAppId || !config.wechatSecret) {
      // 明确报出缺哪个变量：否则本地/线上都只看到一句「未配置」，
      // 得反复翻 .env 才知道是少 AppID 还是少 AppSecret。只报变量名，不涉及任何密钥值。
      const missing = [
        !config.wechatAppId ? "WECHAT_APPID" : null,
        !config.wechatSecret ? "WECHAT_SECRET" : null
      ].filter(Boolean).join(" 和 ");
      console.warn(`[wechat-login] 服务端缺少环境变量：${missing}`);
      throw new ApiError(503, "WECHAT_NOT_CONFIGURED", `微信登录未配置：服务端缺少 ${missing}`);
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
      throw mapWechatSessionError(payload);
    }
    return payload.openid;
  }

  /**
   * 用微信身份直接开一个新账号（「微信一键注册」）。
   *
   * users 的 username / email / password 都是 NOT NULL UNIQUE，而微信只给一个 openid，
   * 所以这三个字段只能造占位值：
   *   username  「微信用户」+ 6 位大写 base32 —— 用户可见；撞名时换一个重试
   *   email     wx_<openid>@wechat-user.invalid —— `.invalid` 是 RFC 2606 保留顶级域，
   *             永不解析，明确表示这不是可收信的邮箱；由 openid 派生，天然唯一
   *   password  32 字节随机值的 bcrypt 摘要 —— **明文没有任何人知道**，因此这个账号
   *             永远无法用「账号 + 密码」登录，只能用微信一键登录（产品上的明确决定）
   *
   * ⚠️ **绝不能接受客户端传来的 openid** —— 否则任何人都能拿别人的 openid 抢注。
   *    调用方必须先用服务端换来的 openid（由 wx.login 的 code 单向换取）。
   */
  async function createWechatAccount(openid) {
    const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    const email = `wx_${openid}@wechat-user.invalid`;
    const hashedPassword = await bcrypt.hash(
      crypto.randomBytes(32).toString("hex"),
      config.bcryptRounds
    );

    for (let attempt = 0; attempt < 5; attempt += 1) {
      const username = `微信用户${Array.from(crypto.randomBytes(6))
        .map((byte) => alphabet[byte % alphabet.length])
        .join("")}`;
      try {
        const [result] = await pool.execute(
          // 刻意不写 avatar_status：新账号 avatar 为 NULL，两个读取方
          // （queryHelpers.loadPartnerProfile / routes/security.js）都已把「无头像」归一为
          // approved，显式赋值没有意义，反而会让本接口额外依赖迁移 028。
          "INSERT INTO users (username, email, password, status, wechat_openid) VALUES (?, ?, ?, 'active', ?)",
          [username, email, hashedPassword, openid]
        );
        return { id: result.insertId, username, email };
      } catch (error) {
        if (error && error.code === "ER_DUP_ENTRY") {
          // 只有「用户名撞名」才值得换一个重试；
          // email / wechat_openid 撞了说明是并发重复建号，重试也没用，直接上报。
          if (/wechat_openid|email/i.test(error.message || "")) {
            throw new ApiError(409, "OPENID_BOUND", "该微信已注册，请直接使用微信一键登录");
          }
          continue;
        }
        throw error;
      }
    }
    throw new ApiError(500, "USERNAME_GENERATE_FAILED", "账号名生成失败，请重试");
  }

  function buildUserSession(user) {
    const sessionVersion = Number(user.auth_token_version);
    if (!Number.isSafeInteger(sessionVersion) || sessionVersion < 0) {
      throw authSchemaError();
    }
    const accessToken = signToken({ userId: user.id, sessionVersion });
    const refreshToken = signRefreshToken({ userId: user.id, sessionVersion });
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

      const [rows] = await executeAuthQuery(
        "SELECT id, username, email, nickname, avatar, status, auth_token_version FROM users WHERE wechat_openid = ? LIMIT 1",
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

      const [rows] = await executeAuthQuery(
        "SELECT id, username, email, nickname, avatar, password, status, auth_token_version FROM users WHERE username = ? LIMIT 1",
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

  // 微信一键注册：openid 还没绑定任何账号时，直接用它开一个新账号。
  //
  // 与 /wechat-bind 的区别：bind 是「把 openid 挂到已有账号上」（要验证该账号的密码），
  // 本接口是「用微信身份开新账号」，所以**不接受也不需要客户端传 openid** ——
  // 身份一律取自服务端用 code 换来的结果。
  //
  // 邀请码（config.inviteCode）沿用注册的内测门槛，校验口径与 /register 逐字一致。
  // 客户端每次必须重新调 wx.login 取 **新** code：code 是一次性的，
  // 被 /wechat-login 用掉的那个再拿去换 openid 会报 40029。
  //
  // 返回 201 + 完整登录态（而不是像 /register 那样只回 userId）：这个账号**没有可用的密码**，
  // 客户端拿不到任何可用于登录的凭据，必须由本接口直接把会话发下去，否则用户注册完进不去。
  router.post("/wechat-register", authLimiter, bcryptGate, async (req, res, next) => {
    try {
      const code = typeof req.body.code === "string" ? req.body.code : "";
      const inviteCode = trimValue(req.body.inviteCode);

      if (!inviteCode) throw new ApiError(400, "INVALID_REQUEST", "请填写邀请码");
      if (inviteCode !== config.inviteCode) {
        throw new ApiError(403, "INVALID_INVITE_CODE", "邀请码错误");
      }

      const openid = await exchangeOpenid(code);

      const [bound] = await pool.execute(
        "SELECT id FROM users WHERE wechat_openid = ? LIMIT 1",
        [openid]
      );
      if (bound.length > 0) {
        throw new ApiError(409, "OPENID_BOUND", "该微信已注册，请直接使用微信一键登录");
      }

      const created = await createWechatAccount(openid);
      // 回读整行而不是自己拼对象：auth_token_version 由表默认值给出，
      // 硬编码 0 会在将来改默认值时静默出错。
      const [rows] = await executeAuthQuery(
        "SELECT id, username, email, nickname, avatar, status, auth_token_version FROM users WHERE id = ? LIMIT 1",
        [created.id]
      );
      const user = rows[0];

      res.status(201).json({
        ok: true,
        message: "注册成功",
        data: Object.assign({ bound: true, created: true }, buildUserSession(user))
      });
      setImmediate(() => preloadUserCache(user.id));
    } catch (error) {
      next(error);
    }
  });

  router.get("/couple-info", requireAuth, async (req, res, next) => {
    try {
      const userId = req.userId;

      const relationship = await loadActiveRelationship(pool, userId);
      if (!relationship) {
        return res.json({ ok: true, data: { hasCouple: false } });
      }

      const partnerId = relationship.user_id_1 === userId
        ? relationship.user_id_2
        : relationship.user_id_1;

      // 只有过审的头像才展示给对方（审核中/被驳回返回 null），见 loadPartnerProfile
      const partner = await loadPartnerProfile(pool, partnerId);
      if (!partner) {
        return res.json({ ok: true, data: { hasCouple: false } });
      }

      res.json({
        ok: true,
        data: {
          hasCouple: true,
          partnerId: partner.id,
          partnerName: partner.username,
          partnerNickname: partner.nickname,
          partnerAvatarUrl: partner.avatarUrl,
          partnerAvatarPending: partner.avatarPending,
          relationshipId: relationship.relationship_id
        }
      });
    } catch (error) {
      next(error);
    }
  });

  router.put("/avatar", requireAuth, async (req, res, next) => {
    try {
      const userId = req.userId;
      const { avatarUrl } = req.body;
      if (!avatarUrl) throw new ApiError(400, "INVALID_REQUEST", "avatarUrl 必填");

      const [rows] = await pool.execute("SELECT id FROM users WHERE id = ? LIMIT 1", [userId]);
      if (rows.length === 0) throw new ApiError(404, "NOT_FOUND", "用户不存在");

      // 头像属 UGC，送微信异步审核（media_check_async），结果经消息推送回到
      // /api/wechat/callback。审核期间 avatar_status='pending'：
      // 自己看得到（有即时反馈），但不会展示给对方（见 couple-info / me/overview 的门控）。
      //
      // 若审核能力不可用（服务端未配微信 / 账号无 openid 且请求没带 code / 无公网基址），
      // 降级为直接可用 —— 不能因为第三方依赖故障让「换头像」整体不可用。
      let avatarStatus = "approved";
      let check = { traceId: null, degraded: true, reason: "NO_SECURITY_SERVICE" };
      if (security) {
        check = await security.startMediaCheck({
          userId,
          mediaUrl: avatarUrl,
          bizType: "avatar",
          bizRef: avatarUrl,
          code: typeof req.body.code === "string" ? req.body.code : ""
        });
        // 只有真正发起了审核（拿到 trace_id）才置 pending，否则会变成永远不可见
        avatarStatus = check.traceId ? "pending" : "approved";
      }

      try {
        await pool.execute(
          "UPDATE users SET avatar = ?, avatar_status = ? WHERE id = ?",
          [avatarUrl, avatarStatus, userId]
        );
      } catch (error) {
        // 迁移未执行时给出可操作的指引，而不是一个无从下手的 500
        if (/avatar_status/i.test(error.message || "")) {
          throw new ApiError(
            503,
            "SCHEMA_MIGRATION_REQUIRED",
            "服务端缺少迁移 028_add_content_safety.sql，请先在数据库执行该迁移"
          );
        }
        throw error;
      }
      cache.del(Keys.profile(userId));

      res.json({
        ok: true,
        message: avatarStatus === "pending" ? "头像已提交审核" : "头像更新成功",
        data: { avatarStatus, degraded: check.degraded, reason: check.reason }
      });
    } catch (error) {
      next(error);
    }
  });

  router.get("/profile", requireAuth, async (req, res, next) => {
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

  router.put("/nickname", requireAuth, async (req, res, next) => {
    try {
      const userId = req.userId;
      const { nickname } = req.body;

      const [rows] = await pool.execute("SELECT id FROM users WHERE id = ? LIMIT 1", [userId]);
      if (rows.length === 0) throw new ApiError(404, "NOT_FOUND", "用户不存在");

      // 昵称会被对方看到，属 UGC。清空昵称（传空值）是合法操作，不需要送审。
      if (nickname) {
        await assertTextAllowed({
          userId,
          text: nickname,
          code: typeof req.body.code === "string" ? req.body.code : ""
        });
      }

      const [result] = await pool.execute("UPDATE users SET nickname = ? WHERE id = ?", [nickname || null, userId]);
      if (result.affectedRows === 0) throw new ApiError(404, "NOT_FOUND", "昵称更新失败");
      cache.del(Keys.profile(userId));
      res.json({ ok: true, message: "昵称更新成功" });
    } catch (error) {
      next(error);
    }
  });

  router.put("/password", strictLimiter, bcryptGate, requireAuth, async (req, res, next) => {
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
      await pool.execute(
        "UPDATE users SET password = ?, auth_token_version = auth_token_version + 1 WHERE id = ?",
        [hashed, userId]
      );
      res.json({ ok: true, message: "密码修改成功" });
    } catch (error) {
      next(error);
    }
  });

  router.delete("/account", strictLimiter, bcryptGate, requireAuth, async (req, res, next) => {
    try {
      const userId = req.userId;
      const password = req.body?.password;
      if (!password) throw new ApiError(400, "INVALID_REQUEST", "需要密码确认");

      const [users] = await pool.execute("SELECT password FROM users WHERE id = ? LIMIT 1", [userId]);
      if (users.length === 0) throw new ApiError(404, "NOT_FOUND", "用户不存在");

      const matched = await bcrypt.compare(password, users[0].password);
      if (!matched) throw new ApiError(401, "WRONG_PASSWORD", "密码错误，无法删除账号");

      const affectedUserIds = await withTransaction(pool, async (conn) => {
        const [found] = await conn.execute(
          "SELECT relationship_id, user_id_1, user_id_2, status FROM couple_relationships WHERE user_id_1 = ? OR user_id_2 = ?",
          [userId, userId]
        );
        const ids = [...new Set([userId, ...found.flatMap((rel) => [Number(rel.user_id_1), Number(rel.user_id_2)])])].sort((a, b) => a - b);
        for (const id of ids) await conn.execute("SELECT id FROM users WHERE id = ? FOR UPDATE", [id]);
        const [lockedUser] = await conn.execute("SELECT password FROM users WHERE id = ? LIMIT 1", [userId]);
        if (!lockedUser.length) throw new ApiError(404, "NOT_FOUND", "用户不存在");
        if (lockedUser[0].password !== users[0].password) {
          throw new ApiError(409, "CREDENTIALS_CHANGED", "密码已更改，请重新确认删除");
        }
        const [relationships] = await conn.execute(
          "SELECT relationship_id, user_id_1, user_id_2, status FROM couple_relationships WHERE user_id_1 = ? OR user_id_2 = ? ORDER BY relationship_id FOR UPDATE",
          [userId, userId]
        );
        if (relationships.some((rel) => !ids.includes(Number(rel.user_id_1)) || !ids.includes(Number(rel.user_id_2)))) {
          throw new ApiError(409, "RELATIONSHIP_CHANGED", "情侣关系已变化，请重新确认删除");
        }
        await cleanHouseworkForAccountDeletion(conn, userId, relationships);
        for (const relationship of relationships) {
          const relationshipId = Number(relationship.relationship_id);
          const userId1 = Number(relationship.user_id_1);
          const userId2 = Number(relationship.user_id_2);
          const survivorUserId = userId1 === Number(userId) ? userId2 : userId2 === Number(userId) ? userId1 : null;
          if (!Number.isSafeInteger(survivorUserId) || survivorUserId <= 0) continue;

          // The surviving member's inventory becomes private and remains readable
          // after the relationship row is deleted (inventory's relationship FK cascades).
          await conn.execute(
            "UPDATE inventory SET relationship_id = NULL WHERE relationship_id = ? AND user_id = ?",
            [relationshipId, survivorUserId]
          );

          // Bills use relationship positions for owner=1/2. Normalize the surviving
          // member's own rows before relationship_id is set NULL by its FK.
          const survivorRole = userId1 === survivorUserId ? 1 : 2;
          const otherRole = survivorRole === 1 ? 2 : 1;
          await conn.execute(
            `UPDATE bills
             SET owner = CASE WHEN owner = ? THEN 1 WHEN owner = ? THEN 2 ELSE owner END
             WHERE relationship_id = ? AND user_id = ?`,
            [survivorRole, otherRole, relationshipId, survivorUserId]
          );
        }
        await archiveSharedCategoryCollectionsForAccountDeletion(conn, userId, relationships);
        await cleanCategoryCollectionsForAccountDeletion(conn, userId);
        const activePartners = relationships.filter((rel) => rel.status === "active")
          .map((rel) => Number(rel.user_id_1) === Number(userId) ? Number(rel.user_id_2) : Number(rel.user_id_1));
        for (const partnerId of activePartners) {
          await conn.execute("UPDATE users SET couple_status = 'single', invite_code = NULL WHERE id = ?", [partnerId]);
        }
        await conn.execute("DELETE FROM couple_relationships WHERE user_id_1 = ? OR user_id_2 = ?", [userId, userId]);
        const [result] = await conn.execute("DELETE FROM users WHERE id = ?", [userId]);
        if (result.affectedRows === 0) throw new ApiError(404, "NOT_FOUND", "账号删除失败");
        return ids;
      });
      invalidateRelationshipScopedCaches(cache, affectedUserIds);
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
      const items = rows.map(row => addExpirationFlags({
        ...row,
        isLowStock: Number(row.quantity) <= Number(row.threshold)
      }));
      cache.set(Keys.inventory(userId), { ok: true, message: "查询成功", data: { items, relationshipId } }, TTL.INVENTORY);

      const beadInventoryUserId = relationship ? relationship.user_id_2 : userId;
      const [beadRows] = await pool.execute(
        `SELECT bi.color_code, bi.quantity, bi.threshold_override, bi.updated_at, bs.default_threshold
         FROM bead_inventory bi LEFT JOIN bead_settings bs ON bs.user_id = bi.user_id
         WHERE bi.user_id = ? ORDER BY bi.color_code`, [beadInventoryUserId]
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
