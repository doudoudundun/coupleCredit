const express = require("express");
const crypto = require("crypto");
const { ApiError } = require("../errors");
const { cache, Keys } = require("../cache");
const { loadActiveRelationship } = require("../utils/queryHelpers");
const { withTransaction } = require("../utils/transactions");

function createCoupleRouter({ pool }) {
  const router = express.Router();

  router.get("/role", async (req, res, next) => {
    try {
      const userId = parseInt(req.query.userId, 10);
      if (!userId || userId <= 0) throw new ApiError(400, "INVALID_REQUEST", "userId 参数无效");

      const rel = await loadActiveRelationship(pool, userId);
      if (!rel) {
        return res.json({ ok: true, data: { hasRelationship: false } });
      }
      res.json({
        ok: true,
        data: {
          hasRelationship: true,
          role: rel.user_id_1 === userId ? 1 : 2,
          relationshipId: rel.relationship_id
        }
      });
    } catch (error) {
      next(error);
    }
  });

  router.get("/relationship-id", async (req, res, next) => {
    try {
      const userId = parseInt(req.query.userId, 10);
      if (!userId || userId <= 0) throw new ApiError(400, "INVALID_REQUEST", "userId 参数无效");

      const rel = await loadActiveRelationship(pool, userId);
      res.json({ ok: true, data: { relationshipId: rel ? rel.relationship_id : null } });
    } catch (error) {
      next(error);
    }
  });

  router.post("/generate-invite", async (req, res, next) => {
    try {
      const { userId } = req.body;
      if (!userId) throw new ApiError(400, "INVALID_REQUEST", "userId 必填");

      const existing = await loadActiveRelationship(pool, userId);
      if (existing) {
        throw new ApiError(409, "ALREADY_BOUND", "您已有情侣，无法发起新的邀请");
      }

      const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
      let code;
      let attempts = 0;
      do {
        code = "";
        const bytes = crypto.randomBytes(6);
        for (let i = 0; i < 6; i++) {
          code += chars[bytes[i] % chars.length];
        }
        const [dupes] = await pool.execute(
          "SELECT id FROM users WHERE invite_code = ? AND id != ? LIMIT 1",
          [code, userId]
        );
        if (dupes.length === 0) break;
        attempts++;
      } while (attempts < 10);

      await pool.execute(
        "UPDATE users SET invite_code = ?, couple_status = 'pending' WHERE id = ?",
        [code, userId]
      );

      res.json({ ok: true, data: { inviteCode: code } });
    } catch (error) {
      next(error);
    }
  });

  router.get("/search", async (req, res, next) => {
    try {
      const inviteCode = (req.query.inviteCode || "").trim();
      if (!inviteCode) throw new ApiError(400, "INVALID_REQUEST", "inviteCode 参数无效");

      const [rows] = await pool.execute(
        "SELECT id, username, nickname FROM users WHERE invite_code = ? AND couple_status = 'pending' LIMIT 1",
        [inviteCode]
      );
      if (rows.length === 0) {
        throw new ApiError(404, "NOT_FOUND", "邀请码无效或已过期");
      }

      res.json({
        ok: true,
        data: {
          userId: rows[0].id,
          username: rows[0].username,
          nickname: rows[0].nickname || rows[0].username
        }
      });
    } catch (error) {
      next(error);
    }
  });

  router.post("/bind", async (req, res, next) => {
    try {
      const { inviterId, inviteeId } = req.body;
      if (!inviterId || !inviteeId) throw new ApiError(400, "INVALID_REQUEST", "参数不完整");

      const result = await withTransaction(pool, async (conn) => {
        const [existing1] = await conn.execute(
          "SELECT relationship_id FROM couple_relationships WHERE status = 'active' AND (user_id_1 = ? OR user_id_2 = ?) LIMIT 1 FOR UPDATE",
          [inviterId, inviterId]
        );
        if (existing1.length > 0) throw new ApiError(409, "ALREADY_BOUND", "其中一方已绑定情侣");

        const [existing2] = await conn.execute(
          "SELECT relationship_id FROM couple_relationships WHERE status = 'active' AND (user_id_1 = ? OR user_id_2 = ?) LIMIT 1 FOR UPDATE",
          [inviteeId, inviteeId]
        );
        if (existing2.length > 0) throw new ApiError(409, "ALREADY_BOUND", "其中一方已绑定情侣");

        const [insertResult] = await conn.execute(
          "INSERT INTO couple_relationships (user_id_1, user_id_2, status) VALUES (?, ?, 'active')",
          [inviterId, inviteeId]
        );

        await conn.execute(
          "UPDATE users SET couple_status = 'coupled', invite_code = NULL WHERE id IN (?, ?)",
          [inviterId, inviteeId]
        );

        return insertResult;
      });

      cache.del(Keys.relationship(inviterId));
      cache.del(Keys.relationship(inviteeId));
      cache.del(Keys.coupleRole(inviterId));
      cache.del(Keys.coupleRole(inviteeId));

      res.json({
        ok: true,
        message: "绑定成功",
        data: { relationshipId: result.insertId }
      });
    } catch (error) {
      if (error.code === "ER_DUP_ENTRY" || error.sqlState === "23000") {
        return next(new ApiError(409, "ALREADY_BOUND", "其中一方已绑定情侣"));
      }
      next(error);
    }
  });

  router.delete("/unbind", async (req, res, next) => {
    try {
      const userId = parseInt(req.query.userId, 10);
      if (!userId || userId <= 0) throw new ApiError(400, "INVALID_REQUEST", "userId 参数无效");

      const rel = await loadActiveRelationship(pool, userId);
      if (!rel) {
        throw new ApiError(404, "NOT_FOUND", "没有找到活跃的情侣关系");
      }

      await pool.execute(
        "UPDATE couple_relationships SET status = 'dissolved' WHERE relationship_id = ?",
        [rel.relationship_id]
      );

      await pool.execute(
        "UPDATE users SET couple_status = 'single' WHERE id IN (?, ?)",
        [rel.user_id_1, rel.user_id_2]
      );

      cache.del(Keys.relationship(rel.user_id_1));
      cache.del(Keys.relationship(rel.user_id_2));
      cache.del(Keys.coupleRole(rel.user_id_1));
      cache.del(Keys.coupleRole(rel.user_id_2));
      cache.del(Keys.profile(rel.user_id_1));
      cache.del(Keys.profile(rel.user_id_2));

      res.json({ ok: true, message: "解绑成功" });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createCoupleRouter };
