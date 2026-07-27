const express = require("express");
const { ApiError } = require("../errors");
const { cache, Keys, TTL } = require("../cache");
const { trimValue, parseRequiredInteger, normalizeNullableText } = require("../utils/queryHelpers");
const { decodeKey, encryptJson, decryptJson } = require("../utils/crypto");

// 查询字段：明文字段直接选出，encrypted_secret 单独取出后服务端解密
const ACCOUNT_SELECT_FIELDS = `account_id as accountId, user_id as userId,
    platform_name as platformName, account_identifier as accountIdentifier,
    phone, email, website_url as websiteUrl, encrypted_secret as encryptedSecret,
    category, note, sort_order as sortOrder,
    DATE_FORMAT(created_at, '%Y-%m-%d %H:%i') as createdAt,
    DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i') as updatedAt`;

/**
 * 把数据库行还原成客户端可用的对象（解密敏感字段）。
 * 解密失败时（密钥更换/数据损坏）以空串兜底，不抛错以免整列列表不可用。
 */
function toClientRow(row, key) {
  if (!row) return null;
  let password = "";
  let securityQuestion = "";
  let securityAnswer = "";
  if (row.encryptedSecret) {
    try {
      const secret = decryptJson(row.encryptedSecret, key);
      password = secret.password || "";
      securityQuestion = secret.securityQuestion || "";
      securityAnswer = secret.securityAnswer || "";
    } catch (e) {
      // 密钥不一致或数据损坏：返回空敏感字段，保留其余信息
      console.warn(`[password-accounts] decrypt failed for accountId=${row.accountId}: ${e.message}`);
    }
  }
  const { encryptedSecret, ...rest } = row;
  return {
    ...rest,
    password,
    securityQuestion,
    securityAnswer
  };
}

/**
 * 把明文敏感字段打包加密为 encrypted_secret 存储值。
 */
function buildEncryptedSecret(reqBody, key) {
  const secret = {
    password: reqBody.password != null ? String(reqBody.password) : "",
    securityQuestion: normalizeNullableText(reqBody.securityQuestion) || "",
    securityAnswer: normalizeNullableText(reqBody.securityAnswer) || ""
  };
  return encryptJson(secret, key);
}

function createPasswordAccountsRouter({ pool, config }) {
  const router = express.Router();
  const key = decodeKey(config.encryptionKey);

  function invalidateListCache(userId) {
    cache.del(Keys.passwordAccounts(userId));
    cache.del(Keys.passwordAccountCategories(userId));
  }

  // GET /api/password-accounts - 列表（支持按分类筛选）
  router.get("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.userId);
      const category = req.query.category ? trimValue(req.query.category) : null;

      // 仅在无条件筛选时读缓存（带 category 时实时查）
      if (!category) {
        const cached = cache.get(Keys.passwordAccounts(userId));
        if (cached) return res.json(cached);
      }

      let query = `SELECT ${ACCOUNT_SELECT_FIELDS} FROM password_accounts WHERE user_id = ?`;
      const params = [userId];
      if (category) {
        query += " AND category = ?";
        params.push(category);
      }
      query += " ORDER BY sort_order ASC, created_at DESC";

      const [rows] = await pool.execute(query, params);
      const items = rows.map(row => toClientRow(row, key));

      const responseData = {
        ok: true,
        message: "查询成功",
        data: { items }
      };
      if (!category) {
        cache.set(Keys.passwordAccounts(userId), responseData, TTL.PASSWORD_ACCOUNTS);
      }
      res.json(responseData);
    } catch (error) {
      next(error);
    }
  });

  // GET /api/password-accounts/categories - 去重分类列表
  router.get("/categories", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.userId);

      const cached = cache.get(Keys.passwordAccountCategories(userId));
      if (cached) return res.json(cached);

      const [rows] = await pool.execute(
        "SELECT DISTINCT category FROM password_accounts WHERE user_id = ? ORDER BY category ASC",
        [userId]
      );
      const categories = rows.map(r => r.category);

      const responseData = {
        ok: true,
        message: "查询成功",
        data: { categories }
      };
      cache.set(Keys.passwordAccountCategories(userId), responseData, TTL.PASSWORD_ACCOUNT_CATS);
      res.json(responseData);
    } catch (error) {
      next(error);
    }
  });

  // GET /api/password-accounts/:id - 详情
  router.get("/:id", async (req, res, next) => {
    try {
      const accountId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(req.userId);

      const [rows] = await pool.execute(
        `SELECT ${ACCOUNT_SELECT_FIELDS} FROM password_accounts WHERE account_id = ? AND user_id = ?`,
        [accountId, userId]
      );
      if (rows.length === 0) {
        throw new ApiError(404, "NOT_FOUND", "账号不存在");
      }

      res.json({
        ok: true,
        message: "查询成功",
        data: toClientRow(rows[0], key)
      });
    } catch (error) {
      next(error);
    }
  });

  // POST /api/password-accounts - 新建
  router.post("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.userId);
      const platformName = trimValue(req.body.platformName);
      const accountIdentifier = trimValue(req.body.accountIdentifier);

      if (!platformName || !accountIdentifier) {
        throw new ApiError(400, "INVALID_REQUEST", "平台名和账号不能为空");
      }

      const phone = normalizeNullableText(req.body.phone);
      const email = normalizeNullableText(req.body.email);
      const websiteUrl = normalizeNullableText(req.body.websiteUrl);
      const category = trimValue(req.body.category) || "其他";
      const note = normalizeNullableText(req.body.note);
      const encryptedSecret = buildEncryptedSecret(req.body, key);

      const [result] = await pool.execute(
        `INSERT INTO password_accounts
         (user_id, platform_name, account_identifier, phone, email, website_url,
          encrypted_secret, category, note)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        [userId, platformName, accountIdentifier, phone, email, websiteUrl,
         encryptedSecret, category, note]
      );

      invalidateListCache(userId);
      res.status(201).json({
        ok: true,
        message: "账号添加成功",
        data: { accountId: result.insertId }
      });
    } catch (error) {
      next(error);
    }
  });

  // PUT /api/password-accounts/:id - 更新（先做所属校验，再更新字段）
  router.put("/:id", async (req, res, next) => {
    try {
      const accountId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(req.userId);

      // 先校验所属权
      const [existing] = await pool.execute(
        "SELECT user_id FROM password_accounts WHERE account_id = ?",
        [accountId]
      );
      if (existing.length === 0) {
        throw new ApiError(404, "NOT_FOUND", "账号不存在");
      }
      if (existing[0].user_id !== userId) {
        throw new ApiError(403, "FORBIDDEN", "无权修改该账号");
      }

      const updates = [];
      const params = [];

      if (req.body.platformName !== undefined) {
        const v = trimValue(req.body.platformName);
        if (!v) throw new ApiError(400, "INVALID_REQUEST", "平台名不能为空");
        updates.push("platform_name = ?");
        params.push(v);
      }
      if (req.body.accountIdentifier !== undefined) {
        const v = trimValue(req.body.accountIdentifier);
        if (!v) throw new ApiError(400, "INVALID_REQUEST", "账号不能为空");
        updates.push("account_identifier = ?");
        params.push(v);
      }
      if (req.body.phone !== undefined) {
        updates.push("phone = ?");
        params.push(normalizeNullableText(req.body.phone));
      }
      if (req.body.email !== undefined) {
        updates.push("email = ?");
        params.push(normalizeNullableText(req.body.email));
      }
      if (req.body.websiteUrl !== undefined) {
        updates.push("website_url = ?");
        params.push(normalizeNullableText(req.body.websiteUrl));
      }
      if (req.body.category !== undefined) {
        updates.push("category = ?");
        params.push(trimValue(req.body.category) || "其他");
      }
      if (req.body.note !== undefined) {
        updates.push("note = ?");
        params.push(normalizeNullableText(req.body.note));
      }
      // 任一敏感字段被提交则整体重新加密
      if (req.body.password !== undefined ||
          req.body.securityQuestion !== undefined ||
          req.body.securityAnswer !== undefined) {
        // 需要合并已有值：先取出旧密文还原，再覆盖提交的新值
        const [rows] = await pool.execute(
          "SELECT encrypted_secret as encryptedSecret FROM password_accounts WHERE account_id = ?",
          [accountId]
        );
        let oldSecret = { password: "", securityQuestion: "", securityAnswer: "" };
        if (rows[0] && rows[0].encryptedSecret) {
          try { oldSecret = decryptJson(rows[0].encryptedSecret, key); } catch (e) { /* 忽略 */ }
        }
        const merged = {
          password: req.body.password !== undefined ? String(req.body.password) : oldSecret.password,
          securityQuestion: req.body.securityQuestion !== undefined
            ? (normalizeNullableText(req.body.securityQuestion) || "")
            : oldSecret.securityQuestion,
          securityAnswer: req.body.securityAnswer !== undefined
            ? (normalizeNullableText(req.body.securityAnswer) || "")
            : oldSecret.securityAnswer
        };
        updates.push("encrypted_secret = ?");
        params.push(encryptJson(merged, key));
      }

      if (updates.length === 0) {
        throw new ApiError(400, "INVALID_REQUEST", "没有提供要更新的字段");
      }

      updates.push("updated_at = NOW()");
      params.push(accountId);

      await pool.execute(
        `UPDATE password_accounts SET ${updates.join(", ")} WHERE account_id = ?`,
        params
      );

      invalidateListCache(userId);
      res.json({ ok: true, message: "账号更新成功" });
    } catch (error) {
      next(error);
    }
  });

  // DELETE /api/password-accounts/:id - 删除（先做所属校验）
  router.delete("/:id", async (req, res, next) => {
    try {
      const accountId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(req.userId);

      const [result] = await pool.execute(
        "DELETE FROM password_accounts WHERE account_id = ? AND user_id = ?",
        [accountId, userId]
      );
      if (result.affectedRows === 0) {
        throw new ApiError(404, "NOT_FOUND", "账号不存在或无权删除");
      }

      invalidateListCache(userId);
      res.json({ ok: true, message: "账号删除成功" });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createPasswordAccountsRouter };
