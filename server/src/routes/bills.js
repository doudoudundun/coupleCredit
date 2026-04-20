const express = require("express");
const { ApiError } = require("../errors");
const { cache, Keys, TTL } = require("../cache");
const { loadActiveRelationship, trimValue, parseOptionalInteger, parseRequiredInteger, parseRequiredAmount } = require("../utils/queryHelpers");

async function resolveBillOwnership(pool, reqBody) {
  if (reqBody.billOwner === undefined) {
    return {
      relationshipId: parseOptionalInteger(reqBody.relationshipId),
      owner: parseRequiredInteger(reqBody.owner),
      isHelp: parseRequiredInteger(reqBody.isHelp ?? 0)
    };
  }

  const billOwner = trimValue(reqBody.billOwner);
  if (!billOwner) {
    throw new ApiError(400, "INVALID_REQUEST", "请求参数不完整或格式不正确");
  }

  const userId = parseRequiredInteger(reqBody.userId);
  const relationship = await loadActiveRelationship(pool, userId);

  if (billOwner === "自己") {
    if (!relationship) {
      return {
        relationshipId: null,
        owner: 1,
        isHelp: 0
      };
    }

    return {
      relationshipId: relationship.relationship_id,
      owner: relationship.user_id_1 === userId ? 1 : 2,
      isHelp: 0
    };
  }

  if (billOwner === "对方") {
    if (!relationship) {
      return {
        relationshipId: null,
        owner: 2,
        isHelp: 1
      };
    }

    return {
      relationshipId: relationship.relationship_id,
      owner: relationship.user_id_1 === userId ? 2 : 1,
      isHelp: 1
    };
  }

  if (billOwner === "共同") {
    if (!relationship) {
      return {
        relationshipId: null,
        owner: 3,
        isHelp: 0
      };
    }

    return {
      relationshipId: relationship.relationship_id,
      owner: 3,
      isHelp: 0
    };
  }

  throw new ApiError(400, "INVALID_REQUEST", `不支持的billOwner类型或缺少情侣关系: ${billOwner}`);
}

function createBillsRouter({ pool }) {
  const router = express.Router();

  router.post("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.body.userId);
      const sharedPlanId = parseOptionalInteger(req.body.sharedPlanId);
      const { relationshipId, owner, isHelp } = await resolveBillOwnership(pool, req.body);
      const title = trimValue(req.body.title);
      const type = trimValue(req.body.type);
      const amount = parseRequiredAmount(req.body.amount);
      const date = trimValue(req.body.date);
      const time = trimValue(req.body.time);
      const incomeType = parseRequiredInteger(req.body.incomeType);

      if (!title || !type || !date || !time) {
        throw new ApiError(400, "INVALID_REQUEST", "请求参数不完整或格式不正确");
      }

      // Pre-check shared plan before inserting bill
      if (sharedPlanId) {
        const [plans] = await pool.execute(
          "SELECT plan_id, current_balance FROM shared_plans WHERE plan_id = ? AND (created_by = ? OR (relationship_id = ? AND visibility = 'both')) LIMIT 1",
          [sharedPlanId, userId, relationshipId]
        );
        if (plans.length === 0) throw new ApiError(404, "NOT_FOUND", "共同计划不存在或无权使用");
        if (incomeType === 0 && parseFloat(plans[0].current_balance) < amount) {
          throw new ApiError(400, "INSUFFICIENT_BALANCE", "小钱包余额不足");
        }
      }

      const [result] = await pool.execute(
        "INSERT INTO bills (relationship_id, shared_plan_id, owner, user_id, title, type, amount, date, time, income_type, is_help) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        [relationshipId, sharedPlanId, owner, userId, title, type, amount, date, time, incomeType, isHelp]
      );

      if (sharedPlanId) {
        if (incomeType === 0) {
          await pool.execute("UPDATE shared_plans SET current_balance = current_balance - ? WHERE plan_id = ?", [amount, sharedPlanId]);
        } else {
          await pool.execute("UPDATE shared_plans SET current_balance = current_balance + ? WHERE plan_id = ?", [amount, sharedPlanId]);
        }
        const relationship = await loadActiveRelationship(pool, userId);
        if (relationship) {
          cache.del(Keys.sharedPlans(relationship.user_id_1));
          cache.del(Keys.sharedPlans(relationship.user_id_2));
        } else {
          cache.del(Keys.sharedPlans(userId));
        }
      }

      res.status(201).json({
        ok: true,
        message: "账单创建成功",
        data: {
          billId: result.insertId,
          relationshipId,
          sharedPlanId,
          owner,
          userId,
          title,
          type,
          amount,
          date,
          time,
          incomeType,
          isHelp
        }
      });
      cache.delPrefix(`bills:${userId}:`);
    } catch (error) {
      next(error);
    }
  });

  // 查询账单列表
  router.get("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(parseInt(req.query.userId));
      const year = parseInt(req.query.year);
      const month = parseInt(req.query.month);

      if (!year || !month) {
        throw new ApiError(400, "INVALID_REQUEST", "缺少年月参数");
      }

      const cached = cache.get(Keys.bills(userId, year, month));
      if (cached) return res.json(cached);

      const datePattern = `${year}-${String(month).padStart(2, '0')}-%`;

      // 获取情侣关系
      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      // 查询账单 - 使用正确的字段名
      let query;
      let params;

      if (relationshipId) {
        // 有情侣关系：查询自己和对方的账单
        query = `SELECT bill_id as billId, user_id as userId, shared_plan_id as sharedPlanId, title, type, amount, date, time, income_type as incomeType, owner, is_help as isHelp, relationship_id as relationshipId
                 FROM bills
                 WHERE (user_id = ? OR relationship_id = ?)
                 AND date LIKE ?
                 ORDER BY date DESC, bill_id DESC`;
        params = [userId, relationshipId, datePattern];
      } else {
        // 无情侣关系：只查询自己的账单
        query = `SELECT bill_id as billId, user_id as userId, shared_plan_id as sharedPlanId, title, type, amount, date, time, income_type as incomeType, owner, is_help as isHelp, relationship_id as relationshipId
                 FROM bills
                 WHERE user_id = ? AND date LIKE ?
                 ORDER BY date DESC, bill_id DESC`;
        params = [userId, datePattern];
      }

      const [rows] = await pool.execute(query, params);

      const responseData = {
        ok: true,
        message: "查询成功",
        data: {
          bills: rows,
          relationshipId,
          year,
          month
        }
      };
      cache.set(Keys.bills(userId, year, month), responseData, TTL.BILLS);
      res.json(responseData);
    } catch (error) {
      next(error);
    }
  });

  router.delete("/:id", async (req, res, next) => {
    try {
      const billId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(parseInt(req.query.userId, 10));

      const [rows] = await pool.execute(
        "SELECT shared_plan_id, income_type, amount FROM bills WHERE bill_id = ? AND user_id = ?",
        [billId, userId]
      );
      if (rows.length === 0) throw new ApiError(404, "NOT_FOUND", "账单不存在或无权删除");

      const bill = rows[0];
      await pool.execute("DELETE FROM bills WHERE bill_id = ? AND user_id = ?", [billId, userId]);

      if (bill.shared_plan_id) {
        const delta = bill.income_type === 0 ? bill.amount : -bill.amount;
        await pool.execute(
          "UPDATE shared_plans SET current_balance = current_balance + ? WHERE plan_id = ?",
          [delta, bill.shared_plan_id]
        );
        const relationship = await loadActiveRelationship(pool, userId);
        if (relationship) {
          cache.del(Keys.sharedPlans(relationship.user_id_1));
          cache.del(Keys.sharedPlans(relationship.user_id_2));
        } else {
          cache.del(Keys.sharedPlans(userId));
        }
      }

      cache.delPrefix(`bills:${userId}:`);
      res.json({ ok: true, message: "删除成功", data: { billId, deleted: true } });
    } catch (error) {
      next(error);
    }
  });

  router.put("/:id", async (req, res, next) => {
    try {
      const billId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(req.body.userId);
      const title = trimValue(req.body.title);
      const type = trimValue(req.body.type);
      const amount = parseRequiredAmount(req.body.amount);
      const date = trimValue(req.body.date);
      const time = trimValue(req.body.time);
      const incomeType = parseOptionalInteger(req.body.incomeType);

      if (!date || !time) {
        throw new ApiError(400, "INVALID_REQUEST", "请求参数不完整或格式不正确");
      }

      const sets = [];
      const params = [];
      if (title !== null && title !== undefined) { sets.push("title = ?"); params.push(title); }
      if (type !== null && type !== undefined) { sets.push("type = ?"); params.push(type); }
      if (amount !== null && amount !== undefined) { sets.push("amount = ?"); params.push(amount); }
      sets.push("date = ?"); params.push(date);
      sets.push("time = ?"); params.push(time);
      if (incomeType !== null && incomeType !== undefined) { sets.push("income_type = ?"); params.push(incomeType); }

      if (sets.length === 0) {
        throw new ApiError(400, "INVALID_REQUEST", "没有需要更新的字段");
      }

      params.push(billId, userId);
      const [result] = await pool.execute(
        `UPDATE bills SET ${sets.join(", ")} WHERE bill_id = ? AND user_id = ?`,
        params
      );

      if (result.affectedRows === 0) {
        throw new ApiError(404, "NOT_FOUND", "账单不存在或无权修改");
      }

      cache.delPrefix(`bills:${userId}:`);
      res.json({ ok: true, message: "更新成功", data: { billId, updated: true } });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createBillsRouter };
