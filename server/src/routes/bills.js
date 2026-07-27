const express = require("express");
const { ApiError } = require("../errors");
const { cache, Keys, TTL } = require("../cache");
const { loadActiveRelationship, trimValue, parseOptionalInteger, parseRequiredInteger, parseRequiredAmount } = require("../utils/queryHelpers");
const { withTransaction } = require("../utils/transactions");

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
      const userId = parseRequiredInteger(req.userId);
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

      let insertId;
      if (sharedPlanId) {
        await withTransaction(pool, async (conn) => {
          const [plans] = await conn.execute(
            "SELECT plan_id, current_balance FROM shared_plans WHERE plan_id = ? AND (created_by = ? OR (relationship_id = ? AND visibility = 'both')) LIMIT 1 FOR UPDATE",
            [sharedPlanId, userId, relationshipId]
          );
          if (plans.length === 0) throw new ApiError(404, "NOT_FOUND", "共同计划不存在或无权使用");
          if (incomeType === 0 && parseFloat(plans[0].current_balance) < amount) {
            throw new ApiError(400, "INSUFFICIENT_BALANCE", "小钱包余额不足");
          }

          const [result] = await conn.execute(
            "INSERT INTO bills (relationship_id, shared_plan_id, owner, user_id, title, type, amount, date, time, income_type, is_help) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            [relationshipId, sharedPlanId, owner, userId, title, type, amount, date, time, incomeType, isHelp]
          );
          insertId = result.insertId;

          if (incomeType === 0) {
            const [updateResult] = await conn.execute(
              "UPDATE shared_plans SET current_balance = current_balance - ? WHERE plan_id = ? AND current_balance >= ?",
              [amount, sharedPlanId, amount]
            );
            if (updateResult.affectedRows === 0) {
              throw new ApiError(400, "INSUFFICIENT_BALANCE", "小钱包余额不足");
            }
          } else {
            await conn.execute("UPDATE shared_plans SET current_balance = current_balance + ? WHERE plan_id = ?", [amount, sharedPlanId]);
          }
        });
        const relationship = await loadActiveRelationship(pool, userId);
        if (relationship) {
          cache.del(Keys.sharedPlans(relationship.user_id_1));
          cache.del(Keys.sharedPlans(relationship.user_id_2));
        } else {
          cache.del(Keys.sharedPlans(userId));
        }
      } else {
        const [result] = await pool.execute(
          "INSERT INTO bills (relationship_id, shared_plan_id, owner, user_id, title, type, amount, date, time, income_type, is_help) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          [relationshipId, sharedPlanId, owner, userId, title, type, amount, date, time, incomeType, isHelp]
        );
        insertId = result.insertId;
      }

      res.status(201).json({
        ok: true,
        message: "账单创建成功",
        data: {
          billId: insertId,
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
      const userId = parseRequiredInteger(req.userId);
      const year = parseInt(req.query.year);
      const month = parseInt(req.query.month);

      if (!year || !month) {
        throw new ApiError(400, "INVALID_REQUEST", "缺少年月参数");
      }

      const cached = cache.get(Keys.bills(userId, year, month));
      if (cached) return res.json(cached);

      const monthStr = String(month).padStart(2, '0');
      const dateStart = `${year}-${monthStr}-01`;
      const dateEnd = month === 12 ? `${Number(year) + 1}-01-01` : `${year}-${String(Number(month) + 1).padStart(2, '0')}-01`;

      // 获取情侣关系
      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      let query;
      let params;

      if (relationshipId) {
        query = `SELECT b.bill_id as billId, b.user_id as userId, b.shared_plan_id as sharedPlanId, sp.name as sharedPlanName, b.title, b.type, b.amount, DATE_FORMAT(b.date, '%Y-%m-%d') as date, b.time, b.income_type as incomeType, b.owner, b.is_help as isHelp, b.relationship_id as relationshipId
                 FROM bills b
                 LEFT JOIN shared_plans sp ON sp.plan_id = b.shared_plan_id
                 WHERE (b.user_id = ? OR b.relationship_id = ?)
                 AND b.date >= ? AND b.date < ?
                 ORDER BY b.date DESC, b.bill_id DESC`;
        params = [userId, relationshipId, dateStart, dateEnd];
      } else {
        query = `SELECT b.bill_id as billId, b.user_id as userId, b.shared_plan_id as sharedPlanId, sp.name as sharedPlanName, b.title, b.type, b.amount, DATE_FORMAT(b.date, '%Y-%m-%d') as date, b.time, b.income_type as incomeType, b.owner, b.is_help as isHelp, b.relationship_id as relationshipId
                 FROM bills b
                 LEFT JOIN shared_plans sp ON sp.plan_id = b.shared_plan_id
                 WHERE b.user_id = ? AND b.date >= ? AND b.date < ?
                 ORDER BY b.date DESC, b.bill_id DESC`;
        params = [userId, dateStart, dateEnd];
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
      const userId = parseRequiredInteger(req.userId);

      const [rows] = await pool.execute(
        "SELECT shared_plan_id, income_type, amount FROM bills WHERE bill_id = ? AND user_id = ?",
        [billId, userId]
      );
      if (rows.length === 0) throw new ApiError(404, "NOT_FOUND", "账单不存在或无权删除");

      const bill = rows[0];

      if (bill.shared_plan_id) {
        const delta = bill.income_type === 0 ? bill.amount : -bill.amount;
        await withTransaction(pool, async (conn) => {
          await conn.execute("DELETE FROM bills WHERE bill_id = ? AND user_id = ?", [billId, userId]);
          await conn.execute("UPDATE shared_plans SET current_balance = current_balance + ? WHERE plan_id = ?", [delta, bill.shared_plan_id]);
        });
        const relationship = await loadActiveRelationship(pool, userId);
        if (relationship) {
          cache.del(Keys.sharedPlans(relationship.user_id_1));
          cache.del(Keys.sharedPlans(relationship.user_id_2));
        } else {
          cache.del(Keys.sharedPlans(userId));
        }
      } else {
        await pool.execute("DELETE FROM bills WHERE bill_id = ? AND user_id = ?", [billId, userId]);
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
      const userId = parseRequiredInteger(req.userId);
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
