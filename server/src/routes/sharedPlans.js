const express = require("express");
const { ApiError } = require("../errors");
const { cache, Keys, TTL } = require("../cache");
const { loadActiveRelationship, trimValue, parseRequiredInteger, parseRequiredFloat, invalidateForUser } = require("../utils/queryHelpers");

function createSharedPlansRouter({ pool }) {
  const router = express.Router();

  function invalidateSharedPlans(userId, relationship) {
    invalidateForUser(cache, Keys.sharedPlans, userId, relationship);
  }

  async function loadAccessiblePlan(pool, planId, userId) {
    const relationship = await loadActiveRelationship(pool, userId);
    const relationshipId = relationship ? relationship.relationship_id : null;
    let sql = "SELECT plan_id, relationship_id, created_by, name, initial_amount, current_balance, visibility, created_at, updated_at FROM shared_plans WHERE plan_id = ? AND (created_by = ?";
    const params = [planId, userId];
    if (relationshipId) {
      sql += " OR (relationship_id = ? AND visibility = 'both')";
      params.push(relationshipId);
    }
    sql += ") LIMIT 1";
    const [rows] = await pool.execute(sql, params);
    return { plan: rows[0] || null, relationship };
  }

  router.get("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(parseInt(req.query.userId, 10));
      const cached = cache.get(Keys.sharedPlans(userId));
      if (cached) return res.json(cached);

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      let sql = "SELECT plan_id, relationship_id, created_by, name, initial_amount, current_balance, visibility, created_at, updated_at FROM shared_plans WHERE created_by = ?";
      const params = [userId];
      if (relationshipId) {
        sql += " OR (relationship_id = ? AND visibility = 'both')";
        params.push(relationshipId);
      }
      sql += " ORDER BY updated_at DESC, plan_id DESC";

      const [rows] = await pool.execute(sql, params);
      const items = rows.map(row => ({
        planId: row.plan_id,
        relationshipId: row.relationship_id,
        createdBy: row.created_by,
        name: row.name,
        initialAmount: row.initial_amount,
        currentBalance: row.current_balance,
        visibility: row.visibility,
        createdAt: row.created_at,
        updatedAt: row.updated_at
      }));
      const response = { ok: true, data: { items, relationshipId } };
      cache.set(Keys.sharedPlans(userId), response, TTL.SHARED_PLANS);
      res.json(response);
    } catch (error) {
      next(error);
    }
  });

  router.post("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.body.userId);
      const name = trimValue(req.body.name);
      const initialAmount = parseRequiredFloat(req.body.initialAmount ?? 0);
      const visibility = req.body.visibility === "self" ? "self" : "both";
      if (!name) throw new ApiError(400, "INVALID_REQUEST", "计划名称不能为空");

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;
      if (visibility === "both" && !relationshipId) {
        throw new ApiError(400, "NO_RELATIONSHIP", "共同可见计划需要先绑定情侣");
      }

      const [result] = await pool.execute(
        "INSERT INTO shared_plans (relationship_id, created_by, name, initial_amount, current_balance, visibility) VALUES (?, ?, ?, ?, ?, ?)",
        [relationshipId, userId, name, initialAmount, initialAmount, visibility]
      );
      invalidateSharedPlans(userId, relationship);
      res.status(201).json({ ok: true, data: { planId: result.insertId } });
    } catch (error) {
      next(error);
    }
  });

  router.post("/:id/adjust", async (req, res, next) => {
    try {
      const planId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(req.body.userId);
      const amount = parseRequiredFloat(req.body.amount);
      if (amount <= 0) throw new ApiError(400, "INVALID_REQUEST", "调整金额必须大于0");
      const direction = req.body.direction === "out" ? "out" : "in";
      const { plan, relationship } = await loadAccessiblePlan(pool, planId, userId);
      if (!plan) throw new ApiError(404, "NOT_FOUND", "计划不存在或无权操作");

      if (direction === "out") {
        const [updateResult] = await pool.execute(
          "UPDATE shared_plans SET current_balance = current_balance - ? WHERE plan_id = ? AND current_balance >= ?",
          [amount, planId, amount]
        );
        if (updateResult.affectedRows === 0) {
          throw new ApiError(400, "INSUFFICIENT_BALANCE", "计划余额不足");
        }
      } else {
        await pool.execute(
          "UPDATE shared_plans SET current_balance = current_balance + ? WHERE plan_id = ?",
          [amount, planId]
        );
      }
      invalidateSharedPlans(userId, relationship);
      res.json({ ok: true, message: "计划余额已更新" });
    } catch (error) {
      next(error);
    }
  });

  router.delete("/:id", async (req, res, next) => {
    try {
      const planId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(parseInt(req.query.userId, 10));
      const { plan, relationship } = await loadAccessiblePlan(pool, planId, userId);
      if (!plan || plan.created_by !== userId) throw new ApiError(404, "NOT_FOUND", "计划不存在或无权删除");
      await pool.execute("DELETE FROM shared_plans WHERE plan_id = ?", [planId]);
      invalidateSharedPlans(userId, relationship);
      res.json({ ok: true, message: "计划已删除" });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createSharedPlansRouter };
