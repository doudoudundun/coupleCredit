// 解除关系路由（规格 v0.2 §5.3，契约 docs/API_CONTRACT_GOALS_DIARY_20261006.md §2）
//
// 单事务原子完成：锁双方 users 行 → 锁关系行 → 取当前共享空间 →
// 关系 dissolved + 关闭 cycle/冻结空间 → 目标整体 frozen → 邀请全部撤销 →
// 小记媒体授权版本递增（旧签名 URL 立即失效）→ 双方 couple_status='single'。
// 与 couple.js 旧 DELETE /api/couple/unbind 的差别：补齐目标冻结、
// 邀请失效、媒体授权失效与新版历史政策返回值。
const express = require("express");
const { revokeDiaryUpdates } = require("../utils/diaryUpdates");
const { ApiError } = require("../errors");
const { cache } = require("../cache");
const { invalidateRelationshipScopedCaches } = require("../utils/queryHelpers");
const { requireIdempotencyKey, withIdempotency } = require("../utils/idempotency");
const { closeCurrentCycleAndSpace } = require("../utils/houseworkLifecycle");

function parseExpectedVersion(value) {
  if (typeof value === "number" && Number.isInteger(value)) return value;
  if (typeof value === "string" && /^-?\d+$/.test(value.trim())) return Number(value.trim());
  return null;
}

function createRelationshipsRouter({ pool }) {
  const router = express.Router();

  router.post("/relationships/:id/unbind", async (req, res, next) => {
    try {
      const userId = req.userId;
      const body = req.body || {};
      const key = requireIdempotencyKey(body.idempotencyKey);
      const expectedVersion = String(body.expectedVersion || "");
      if (!expectedVersion) throw new ApiError(422, "VALIDATION_FAILED", "请刷新共同空间后重试");

      let affectedUserIds = [];
      const outcome = await withIdempotency(
        pool,
        { userId, scope: "relationship.unbind", key, payload: { ...body, relationshipId: req.params.id } },
        async (conn) => {
          // 不用缓存版关系查询：解绑必须在事务内复核当前状态
          const [foundRows] = await conn.execute(
            `SELECT relationship_id, user_id_1, user_id_2 FROM couple_relationships
              WHERE status = 'active' AND (user_id_1 = ? OR user_id_2 = ?) LIMIT 1`,
            [userId, userId]
          );
          if (foundRows.length === 0) {
            throw new ApiError(404, "NOT_FOUND", "没有找到活跃的情侣关系");
          }
          const rel = foundRows[0];
          const relationshipId = Number(rel.relationship_id);
          if (Number(req.params.id) !== relationshipId) {
            throw new ApiError(404, "NOT_FOUND", "没有找到活跃的情侣关系");
          }


          const u1 = Number(rel.user_id_1);
          const u2 = Number(rel.user_id_2);
          const [lockFirst, lockSecond] = u1 < u2 ? [u1, u2] : [u2, u1];
          await conn.execute("SELECT id FROM users WHERE id = ? FOR UPDATE", [lockFirst]);
          await conn.execute("SELECT id FROM users WHERE id = ? FOR UPDATE", [lockSecond]);

          const [lockedRows] = await conn.execute(
            "SELECT relationship_id FROM couple_relationships WHERE relationship_id = ? AND status = 'active' LIMIT 1 FOR UPDATE",
            [relationshipId]
          );
          if (lockedRows.length === 0) {
            throw new ApiError(404, "NOT_FOUND", "没有找到活跃的情侣关系");
          }

          // 先锁定当前 open cycle 对应的共享空间：后续目标冻结与媒体授权失效都以它为准
          const [spaceRows] = await conn.execute(
            `SELECT s.space_id, c.cycle_id
               FROM housework_relationship_cycles c
               JOIN housework_spaces s ON s.cycle_id = c.cycle_id
              WHERE c.relationship_id = ? AND c.ended_at IS NULL AND s.status = 'active'
              LIMIT 1 FOR UPDATE`,
            [relationshipId]
          );
          if (spaceRows.length === 0) {
            throw new ApiError(409, "SPACE_STATE_INVALID", "活跃关系缺少当前共享空间");
          }
          const spaceId = spaceRows[0].space_id;
          if (expectedVersion !== String(spaceRows[0].cycle_id)) throw new ApiError(409, "RELATIONSHIP_CHANGED", "关系已变化，请刷新后重试");

          await conn.execute(
            "UPDATE couple_relationships SET status = 'dissolved' WHERE relationship_id = ?",
            [relationshipId]
          );
          await closeCurrentCycleAndSpace(conn, relationshipId, rel);

          // 共享空间内进行中的目标整体冻结（行保留，成员仍可按归档口径读）
          const [activeGoals] = await conn.execute(
            "SELECT goal_id FROM couple_goals WHERE space_id = ? AND status = 'active'",
            [spaceId]
          );
          for (const { goal_id: goalId } of activeGoals) {
            await conn.execute(
              "UPDATE couple_goals SET status = 'frozen', version = version + 1 WHERE goal_id = ?",
              [goalId]
            );
            await conn.execute(
              "INSERT INTO couple_goal_events (goal_id, space_id, actor_id, type) VALUES (?, ?, ?, 'freeze')",
              [goalId, spaceId, userId]
            );
          }

          await conn.execute(
            "UPDATE goal_invitations SET status = 'revoked' WHERE inviter_id IN (?, ?) AND status = 'active'",
            [u1, u2]
          );
          // 授权版本递增：冻结空间里所有已签名媒体 URL 立即失效
          await revokeDiaryUpdates(conn, "space_id", spaceId);
          await conn.execute(
            "UPDATE diary_media SET auth_version = auth_version + 1 WHERE space_id = ?",
            [spaceId]
          );
          await conn.execute(
            "UPDATE users SET couple_status = 'single' WHERE id IN (?, ?)",
            [u1, u2]
          );

          affectedUserIds = [u1, u2];
          return {
            frozenSpaceId: spaceId,
            historyPolicy: "each-keeps-own-private-archive"
          };
        }
      );

      invalidateRelationshipScopedCaches(cache, affectedUserIds);
      res.json({ ok: true, data: outcome.result });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createRelationshipsRouter };
