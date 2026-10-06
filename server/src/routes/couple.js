const express = require("express");
const crypto = require("crypto");
const { ApiError } = require("../errors");
const { cache } = require("../cache");
const {
  invalidateRelationshipScopedCaches,
  loadActiveRelationship
} = require("../utils/queryHelpers");
const { withTransaction } = require("../utils/transactions");
const {
  loadDisplayNames,
  ensureCycleForBind,
  closeCurrentCycleAndSpace
} = require("../utils/houseworkLifecycle");

function createCoupleRouter({ pool }) {
  const router = express.Router();

  router.get("/role", async (req, res, next) => {
    try {
      const userId = req.userId;
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
      const userId = req.userId;
      if (!userId || userId <= 0) throw new ApiError(400, "INVALID_REQUEST", "userId 参数无效");

      const rel = await loadActiveRelationship(pool, userId);
      res.json({ ok: true, data: { relationshipId: rel ? rel.relationship_id : null } });
    } catch (error) {
      next(error);
    }
  });

  router.post("/generate-invite", async (req, res, next) => {
    try {
      const userId = req.userId;
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
      const inviteeId = req.userId;
      const inviteCode = String(req.body?.inviteCode || "").trim().toUpperCase();
      if (!inviteCode) throw new ApiError(400, "INVALID_REQUEST", "邀请码不能为空");

      const result = await withTransaction(pool, async (conn) => {
        // 先按邀请码找到邀请人（此查询不加锁，仅为确定双方、遵守锁顺序）。
        const [inviterRows] = await conn.execute(
          "SELECT id FROM users WHERE invite_code = ? AND couple_status = 'pending' LIMIT 1",
          [inviteCode]
        );
        if (inviterRows.length === 0) {
          throw new ApiError(404, "NOT_FOUND", "邀请码无效或已过期");
        }

        const inviterId = Number(inviterRows[0].id);
        if (inviterId === inviteeId) {
          throw new ApiError(400, "SELF_BIND", "不能绑定自己");
        }

        // 锁顺序：双方 users 行按 userId 升序 → 关系行 → cycle 行 → space 行 → 成员/子资源。
        const [lockFirst, lockSecond] = inviterId < inviteeId ? [inviterId, inviteeId] : [inviteeId, inviterId];
        await conn.execute("SELECT id FROM users WHERE id = ? FOR UPDATE", [lockFirst]);
        await conn.execute("SELECT id FROM users WHERE id = ? FOR UPDATE", [lockSecond]);

        // 双方行锁定后复核邀请码仍未被并发消费。
        const [revalidateRows] = await conn.execute(
          "SELECT id FROM users WHERE id = ? AND invite_code = ? AND couple_status = 'pending' LIMIT 1",
          [inviterId, inviteCode]
        );
        if (revalidateRows.length === 0) {
          throw new ApiError(404, "NOT_FOUND", "邀请码无效或已过期");
        }

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

        // 解绑只把关系标记为 dissolved，行本身仍在；而唯一索引 uk_relationship_pair
        // (user_id_1, user_id_2) 不含 status，直接 INSERT 会撞唯一键 →
        // 表现成「解绑后无法重新绑定同一人」（误报 409 ALREADY_BOUND）。
        // 另有 13 张表通过外键引用 relationship_id，不能删行，只能复用旧行。
        const [reusable] = await conn.execute(
          "SELECT relationship_id, user_id_1, user_id_2 FROM couple_relationships WHERE status = 'dissolved' AND ((user_id_1 = ? AND user_id_2 = ?) OR (user_id_1 = ? AND user_id_2 = ?)) LIMIT 1 FOR UPDATE",
          [inviterId, inviteeId, inviteeId, inviterId]
        );

        let relationshipId;
        let relationshipRow;
        if (reusable.length > 0) {
          relationshipId = reusable[0].relationship_id;
          relationshipRow = reusable[0];
          await conn.execute(
            "UPDATE couple_relationships SET status = 'active', created_at = CURRENT_TIMESTAMP WHERE relationship_id = ?",
            [relationshipId]
          );
        } else {
          const [insertResult] = await conn.execute(
            "INSERT INTO couple_relationships (user_id_1, user_id_2, status) VALUES (?, ?, 'active')",
            [inviterId, inviteeId]
          );
          relationshipId = insertResult.insertId;
          relationshipRow = { relationship_id: relationshipId, user_id_1: inviterId, user_id_2: inviteeId };
        }

        // 家务生命周期（spec 9）：dissolved → active 或首次插入，必须在绑定事务内
        // 生成新 cycle + 新 space + 两名成员 + 兜底分类；已 active 的重复确认只复用当前 cycle。
        const namesById = await loadDisplayNames(conn, [relationshipRow.user_id_1, relationshipRow.user_id_2]);
        await ensureCycleForBind(conn, relationshipRow, namesById);

        await conn.execute(
          "UPDATE users SET couple_status = 'coupled', invite_code = NULL WHERE id IN (?, ?)",
          [inviterId, inviteeId]
        );

        return { relationshipId, inviterId };
      });

      const inviterId = result.inviterId;
      invalidateRelationshipScopedCaches(cache, [inviterId, inviteeId]);

      res.json({
        ok: true,
        message: "绑定成功",
        data: { relationshipId: result.relationshipId }
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
      const userId = req.userId;
      if (!userId || userId <= 0) throw new ApiError(400, "INVALID_REQUEST", "userId 参数无效");

      // 单事务（spec 9）：锁双方 users 行 → 关系行 → 关闭当前家务 cycle、冻结共享 space →
      // 双方 couple_status。禁用缓存版 loadActiveRelationship（300s TTL 不能代替事务复核）。
      const outcome = await withTransaction(pool, async (conn) => {
        const [foundRows] = await conn.execute(
          "SELECT relationship_id, user_id_1, user_id_2 FROM couple_relationships WHERE status = 'active' AND (user_id_1 = ? OR user_id_2 = ?) LIMIT 1",
          [userId, userId]
        );
        if (foundRows.length === 0) {
          throw new ApiError(404, "NOT_FOUND", "没有找到活跃的情侣关系");
        }
        const rel = foundRows[0];
        const partnerId = Number(rel.user_id_1) === Number(userId) ? Number(rel.user_id_2) : Number(rel.user_id_1);

        const [lockFirst, lockSecond] = userId < partnerId ? [userId, partnerId] : [partnerId, userId];
        await conn.execute("SELECT id FROM users WHERE id = ? FOR UPDATE", [lockFirst]);
        await conn.execute("SELECT id FROM users WHERE id = ? FOR UPDATE", [lockSecond]);

        const [lockedRows] = await conn.execute(
          "SELECT relationship_id FROM couple_relationships WHERE relationship_id = ? AND status = 'active' LIMIT 1 FOR UPDATE",
          [rel.relationship_id]
        );
        if (lockedRows.length === 0) {
          throw new ApiError(404, "NOT_FOUND", "没有找到活跃的情侣关系");
        }

        await conn.execute(
          "UPDATE couple_relationships SET status = 'dissolved' WHERE relationship_id = ?",
          [rel.relationship_id]
        );

        // 与新版 POST /api/relationships/:id/unbind 同口径（v0.2 补漏）：
        // 冻结进行中目标、撤销活跃邀请、共享空间媒体签名 URL 立即失效。
        const [spaceRows] = await conn.execute(
          `SELECT s.space_id
             FROM housework_relationship_cycles c
             JOIN housework_spaces s ON s.cycle_id = c.cycle_id
            WHERE c.relationship_id = ? AND c.ended_at IS NULL AND s.status = 'active'
            LIMIT 1`,
          [rel.relationship_id]
        );
        const sharedSpaceId = spaceRows.length > 0 ? spaceRows[0].space_id : null;

        await closeCurrentCycleAndSpace(conn, rel.relationship_id, rel);

        if (sharedSpaceId) {
          const [activeGoals] = await conn.execute(
            "SELECT goal_id FROM couple_goals WHERE space_id = ? AND status = 'active'",
            [sharedSpaceId]
          );
          for (const { goal_id: goalId } of activeGoals) {
            await conn.execute(
              "UPDATE couple_goals SET status = 'frozen', version = version + 1 WHERE goal_id = ?",
              [goalId]
            );
            await conn.execute(
              "INSERT INTO couple_goal_events (goal_id, space_id, actor_id, type) VALUES (?, ?, ?, 'freeze')",
              [goalId, sharedSpaceId, userId]
            );
          }
          await conn.execute(
            "UPDATE goal_invitations SET status = 'revoked' WHERE inviter_id IN (?, ?) AND status = 'active'",
            [rel.user_id_1, rel.user_id_2]
          );
          await conn.execute(
            "UPDATE diary_media SET auth_version = auth_version + 1 WHERE space_id = ?",
            [sharedSpaceId]
          );
        }

        await conn.execute(
          "UPDATE users SET couple_status = 'single' WHERE id IN (?, ?)",
          [rel.user_id_1, rel.user_id_2]
        );

        return { userIds: [Number(rel.user_id_1), Number(rel.user_id_2)] };
      });

      invalidateRelationshipScopedCaches(cache, outcome.userIds);

      res.json({ ok: true, message: "解绑成功" });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createCoupleRouter };
