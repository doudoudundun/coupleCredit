// 情境邀请路由（规格 v0.2 §4，契约 docs/API_CONTRACT_GOALS_DIARY_20261006.md §2）
//
// 挂载在全局鉴权之前（/api/invitations）：preview 必须可匿名访问，
// 其余端点各自挂 requireAuth。令牌只存 SHA-256 哈希；preview 与 accept
// 的失效路径统一 404 INVITATION_INVALID，不泄露具体原因与任何身份信息。
const express = require("express");
const crypto = require("crypto");
const { ApiError } = require("../errors");
const { cache } = require("../cache");
const { invalidateRelationshipScopedCaches } = require("../utils/queryHelpers");
const { withTransaction } = require("../utils/transactions");
const { requireIdempotencyKey, withIdempotency } = require("../utils/idempotency");
const { loadDisplayNames, ensureCycleForBind } = require("../utils/houseworkLifecycle");
const { GOAL_SELECT, serializeGoal, isGoalSpaceMember } = require("./goals");

const INVITATION_TTL_HOURS = 72;

function sha256Hex(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

function toBase64Url(buffer) {
  return buffer.toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function invitationInvalid() {
  return new ApiError(404, "INVITATION_INVALID", "邀请已失效或已被使用");
}

function isExpired(expiresAt) {
  return new Date(expiresAt).getTime() <= Date.now();
}

async function markExpired(pool, invitationId) {
  await pool.execute(
    "UPDATE goal_invitations SET status = 'expired' WHERE invitation_id = ? AND status = 'active'",
    [invitationId]
  );
}

function createInvitationsRouter({ pool, requireAuth }) {
  const router = express.Router();

  // 匿名可访问：只暴露发起人昵称与目标标题，绝不返回金额、成员 ID、历史
  router.get("/preview", async (req, res, next) => {
    try {
      const token = typeof req.query.token === "string" ? req.query.token.trim() : "";
      if (!token) throw invitationInvalid();
      const [rows] = await pool.execute(
        `SELECT i.invitation_id, i.status, i.expires_at, u.nickname, u.username, g.title
           FROM goal_invitations i
           JOIN couple_goals g ON g.goal_id = i.goal_id
           JOIN users u ON u.id = i.inviter_id
          WHERE i.token_hash = ? LIMIT 1`,
        [sha256Hex(token)]
      );
      const invitation = rows[0];
      if (!invitation || invitation.status !== "active" || isExpired(invitation.expires_at)) {
        if (invitation && invitation.status === "active") await markExpired(pool, invitation.invitation_id);
        throw invitationInvalid();
      }
      res.json({
        ok: true,
        data: {
          inviterNickname: invitation.nickname || invitation.username,
          goalTitle: invitation.title,
          status: "active"
        }
      });
    } catch (error) {
      next(error);
    }
  });

  router.post("/", requireAuth, async (req, res, next) => {
    try {
      const userId = req.userId;
      const goalId = typeof req.body?.goalId === "string" ? req.body.goalId.trim() : "";
      const data = await withTransaction(pool, async (conn) => {
        const [goalRows] = await conn.execute(
          `${GOAL_SELECT} WHERE goal_id = ? LIMIT 1 FOR UPDATE`,
          [goalId]
        );
        const goal = goalRows[0];
        if (!goal || !(await isGoalSpaceMember(conn, goal.space_id, userId, { requireActiveSpace: true }))) {
          throw new ApiError(404, "NOT_FOUND", "目标不存在或不可访问");
        }
        if (goal.status !== "active") {
          if (goal.status === "frozen") {
            throw new ApiError(409, "GOAL_FROZEN", "目标已冻结，无法发起邀请");
          }
          throw new ApiError(409, "GOAL_NOT_ACTIVE", "目标已结束，无法发起邀请");
        }
        // 重新生成即撤销旧令牌：同一发起人同一目标只保留一个 active 邀请
        const revokeActive = () => conn.execute(
          "UPDATE goal_invitations SET status = 'revoked' WHERE inviter_id = ? AND goal_id = ? AND status = 'active'",
          [userId, goalId]
        );
        await revokeActive();
        const token = toBase64Url(crypto.randomBytes(32));
        const invitationId = crypto.randomUUID();
        let lastError = null;
        for (let attempt = 0; attempt < 2; attempt++) {
          try {
            await conn.execute(
              `INSERT INTO goal_invitations (invitation_id, goal_id, inviter_id, token_hash, expires_at)
               VALUES (?, ?, ?, ?, DATE_ADD(NOW(3), INTERVAL ${INVITATION_TTL_HOURS} HOUR))`,
              [invitationId, goalId, userId, sha256Hex(token)]
            );
            lastError = null;
            break;
          } catch (error) {
            if (error.code !== "ER_DUP_ENTRY") throw error;
            // uk_invitations_active 兜底：再撤销一次后重试
            lastError = error;
            await revokeActive();
          }
        }
        if (lastError) throw lastError;
        const [freshRows] = await conn.execute(
          "SELECT expires_at FROM goal_invitations WHERE invitation_id = ? LIMIT 1",
          [invitationId]
        );
        return {
          token,
          expiresAt: freshRows[0].expires_at,
          sharePath: `/pages/invite/index?token=${token}`
        };
      });
      res.json({ ok: true, data });
    } catch (error) {
      next(error);
    }
  });

  router.post("/accept", requireAuth, async (req, res, next) => {
    try {
      const userId = req.userId;
      const body = req.body || {};
      const token = typeof body.token === "string" ? body.token.trim() : "";
      const key = requireIdempotencyKey(body.idempotencyKey);
      let inviterId = null;

      let data;
      try {
        const outcome = await withIdempotency(
          pool,
          { userId, scope: "invite.accept", key, payload: body },
          () => withTransaction(pool, async (conn) => {
            // 锁顺序与 couple.js bind 一致：邀请行 → 双方 users 行（升序）→
            // 关系行 → cycle/space → 目标行。
            const [inviteRows] = await conn.execute(
              `SELECT invitation_id, goal_id, inviter_id, status, expires_at
                 FROM goal_invitations WHERE token_hash = ? LIMIT 1 FOR UPDATE`,
              [sha256Hex(token)]
            );
            const invitation = inviteRows[0];
            if (!invitation || invitation.status !== "active") throw invitationInvalid();
            if (isExpired(invitation.expires_at)) {
              await conn.execute(
                "UPDATE goal_invitations SET status = 'expired' WHERE invitation_id = ? AND status = 'active'",
                [invitation.invitation_id]
              );
              throw invitationInvalid();
            }
            inviterId = Number(invitation.inviter_id);
            if (inviterId === userId) {
              throw new ApiError(409, "ALREADY_BOUND", "不能加入自己发起的邀请");
            }

            const [lockFirst, lockSecond] = inviterId < userId ? [inviterId, userId] : [userId, inviterId];
            await conn.execute("SELECT id FROM users WHERE id = ? FOR UPDATE", [lockFirst]);
            await conn.execute("SELECT id FROM users WHERE id = ? FOR UPDATE", [lockSecond]);

            const [existing1] = await conn.execute(
              "SELECT relationship_id FROM couple_relationships WHERE status = 'active' AND (user_id_1 = ? OR user_id_2 = ?) LIMIT 1 FOR UPDATE",
              [inviterId, inviterId]
            );
            if (existing1.length > 0) {
              throw new ApiError(409, "ALREADY_BOUND", "其中一方已有伴侣");
            }
            const [existing2] = await conn.execute(
              "SELECT relationship_id FROM couple_relationships WHERE status = 'active' AND (user_id_1 = ? OR user_id_2 = ?) LIMIT 1 FOR UPDATE",
              [userId, userId]
            );
            if (existing2.length > 0) {
              throw new ApiError(409, "ALREADY_BOUND", "其中一方已有伴侣");
            }

            await conn.execute(
              "UPDATE goal_invitations SET status = 'used', used_by = ?, used_at = NOW(3) WHERE invitation_id = ?",
              [userId, invitation.invitation_id]
            );
            await conn.execute(
              "UPDATE goal_invitations SET status = 'revoked' WHERE inviter_id = ? AND status = 'active'",
              [inviterId]
            );

            // 关系行：dissolved 旧行复用（uk_relationship_pair 不含 status），否则新插
            const [reusable] = await conn.execute(
              `SELECT relationship_id, user_id_1, user_id_2 FROM couple_relationships
                WHERE status = 'dissolved'
                  AND ((user_id_1 = ? AND user_id_2 = ?) OR (user_id_1 = ? AND user_id_2 = ?))
                LIMIT 1 FOR UPDATE`,
              [inviterId, userId, userId, inviterId]
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
                [inviterId, userId]
              );
              relationshipId = insertResult.insertId;
              relationshipRow = { relationship_id: relationshipId, user_id_1: inviterId, user_id_2: userId };
            }

            const namesById = await loadDisplayNames(
              conn,
              [Number(relationshipRow.user_id_1), Number(relationshipRow.user_id_2)]
            );
            const bound = await ensureCycleForBind(conn, relationshipRow, namesById);

            // 目标转移：必须是邀请里的 goal_id，且 inviter 仍是其空间成员
            const [goalRows] = await conn.execute(
              `${GOAL_SELECT} WHERE goal_id = ? LIMIT 1 FOR UPDATE`,
              [invitation.goal_id]
            );
            const goal = goalRows[0];
            if (!goal || !(await isGoalSpaceMember(conn, goal.space_id, inviterId))) {
              throw invitationInvalid();
            }
            const fromSpaceId = goal.space_id;
            // 解绑冻结的目标随转移恢复进行中；其余状态（completed/archived）原样保留
            const nextStatus = goal.status === "frozen" ? "active" : goal.status;
            await conn.execute(
              "UPDATE couple_goals SET space_id = ?, relationship_id = ?, status = ?, version = version + 1 WHERE goal_id = ?",
              [bound.spaceId, relationshipId, nextStatus, invitation.goal_id]
            );
            await conn.execute(
              `INSERT INTO couple_goal_events (goal_id, space_id, actor_id, type, meta_json)
               VALUES (?, ?, ?, 'transfer', ?)`,
              [invitation.goal_id, bound.spaceId, userId, JSON.stringify({ fromSpaceId })]
            );

            await conn.execute(
              "UPDATE users SET couple_status = 'coupled', invite_code = NULL WHERE id IN (?, ?)",
              [inviterId, userId]
            );

            const [transferredRows] = await conn.execute(
              `${GOAL_SELECT} WHERE goal_id = ? LIMIT 1`,
              [invitation.goal_id]
            );
            return {
              goal: serializeGoal(transferredRows[0]),
              relationship: { id: Number(relationshipId), version: Number(relationshipId) }
            };
          })
        );
        data = outcome.result;
      } catch (error) {
        if (error.code === "ER_DUP_ENTRY" || error.sqlState === "23000") {
          throw new ApiError(409, "ALREADY_BOUND", "其中一方已绑定情侣");
        }
        throw error;
      }

      invalidateRelationshipScopedCaches(cache, [inviterId, userId]);
      res.json({ ok: true, data });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createInvitationsRouter };
