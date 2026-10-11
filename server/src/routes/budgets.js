// 预算路由（F2），挂在 /api/budgets（全局鉴权之后）。
//
// 口径：
//  - 一行预算 = 某用户在某范围（私人 relationship_id NULL / active 关系）对某分类
//    （NULL=月度总预算）设定的月度金额（DECIMAL 元）；伴侣双方都可在关系范围设预算，
//    读路径按「关系行优先、同范围取最新 updated_at」合并出有效预算；
//  - used 与 monthlyStats 一致：整数分、仅支出（income_type=0）、
//    scope=共同+私人（buildCoupleOrPrivateScope，与 GET /api/bills 相同的可见范围）；
//    总预算 used=当月全部支出；分类预算 used 按 category_id 精确匹配
//    （legacy 字符串账单计入总预算，不归属任何分类预算）；
//  - amount 出入参均为元（与 bills.amount 一致），used 为整数分（与 monthlyStats 一致）。
const express = require("express");
const { ApiError } = require("../errors");
const { withTransaction } = require("../utils/transactions");
const {
  loadActiveRelationship,
  buildCoupleOrPrivateScope,
  parseRequiredInteger,
  parseRequiredAmount
} = require("../utils/queryHelpers");
const { resolveWritableCategory } = require("../utils/categoryScope");
const { decimalStringToCents } = require("./bills");

/** 预算行 → 有效预算：关系行优先于私人行；同范围（伴侣双方都设过）取最新 updated_at。 */
function mergeEffectiveBudgets(rows) {
  const toRank = (row) => [
    row.relationship_id == null ? 0 : 1,
    row.updated_at instanceof Date ? row.updated_at.getTime() : 0,
    Number(row.id)
  ];
  const effective = new Map();
  for (const row of rows) {
    const key = row.category_id == null ? null : row.category_id;
    const rank = toRank(row);
    const current = effective.get(key);
    if (!current) {
      effective.set(key, { row, rank });
      continue;
    }
    // 逐位比较，第一位分出胜负即定；完全相同（不可能）保留先者
    let replace = false;
    for (let i = 0; i < rank.length; i += 1) {
      if (rank[i] !== current.rank[i]) {
        replace = rank[i] > current.rank[i];
        break;
      }
    }
    if (replace) effective.set(key, { row, rank });
  }
  return effective;
}

function createBudgetsRouter({ pool }) {
  const router = express.Router();

  // 预算总览：有效预算 + 当月已用
  router.get("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.userId);
      const year = parseInt(req.query.year);
      const month = parseInt(req.query.month);
      if (!year || !month) {
        throw new ApiError(400, "INVALID_REQUEST", "缺少年月参数");
      }

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      // 私人预算（仅本人行）+ active 关系预算（双方行，merge 时合并）
      const [budgetRows] = await pool.execute(
        `SELECT id, relationship_id, user_id, category_id, amount, updated_at
         FROM budgets
         WHERE (user_id = ? AND relationship_id IS NULL) OR relationship_id = ?`,
        [userId, relationshipId]
      );
      const effective = mergeEffectiveBudgets(budgetRows);

      const monthStr = String(month).padStart(2, "0");
      const dateStart = `${year}-${monthStr}-01`;
      const dateEnd = month === 12
        ? `${Number(year) + 1}-01-01`
        : `${year}-${String(Number(month) + 1).padStart(2, "0")}-01`;
      const visibleScope = buildCoupleOrPrivateScope(userId, relationship, "b");

      const [totalRows] = await pool.execute(
        `SELECT SUM(b.amount) AS used FROM bills b
         WHERE ${visibleScope.clause} AND b.income_type = 0 AND b.date >= ? AND b.date < ?`,
        [...visibleScope.params, dateStart, dateEnd]
      );
      const totalUsedCents = decimalStringToCents(totalRows[0] ? totalRows[0].used : null);

      const categoryIds = Array.from(effective.keys()).filter((id) => id !== null);
      const usedByCategory = new Map();
      if (categoryIds.length > 0) {
        const placeholders = categoryIds.map(() => "?").join(", ");
        const [categoryUsedRows] = await pool.execute(
          `SELECT b.category_id, SUM(b.amount) AS used FROM bills b
           WHERE ${visibleScope.clause} AND b.income_type = 0
             AND b.date >= ? AND b.date < ? AND b.category_id IN (${placeholders})
           GROUP BY b.category_id`,
          [...visibleScope.params, dateStart, dateEnd, ...categoryIds]
        );
        for (const row of categoryUsedRows) {
          usedByCategory.set(row.category_id, decimalStringToCents(row.used));
        }
      }

      const categoryMetaById = new Map();
      if (categoryIds.length > 0) {
        const placeholders = categoryIds.map(() => "?").join(", ");
        const [metaRows] = await pool.execute(
          `SELECT id, name, icon_type, icon_value, color, status FROM item_categories
           WHERE id IN (${placeholders})`,
          categoryIds
        );
        for (const row of metaRows) {
          categoryMetaById.set(row.id, row);
        }
      }

      const totalBudget = effective.get(null);
      const categories = categoryIds.map((categoryId) => {
        const meta = categoryMetaById.get(categoryId);
        return {
          categoryId,
          name: meta ? meta.name : null,
          icon: meta && meta.icon_type ? { type: meta.icon_type, value: meta.icon_value } : null,
          status: meta ? meta.status : null,
          amount: Number(effective.get(categoryId).row.amount),
          used: usedByCategory.get(categoryId) || 0
        };
      });

      res.json({
        ok: true,
        message: "查询成功",
        data: {
          year,
          month,
          relationshipId,
          total: totalBudget
            ? { amount: Number(totalBudget.row.amount), used: totalUsedCents }
            : null,
          categories
        }
      });
    } catch (error) {
      next(error);
    }
  });

  // 保存预算：scope=当前 active 关系（无则私人）；amount null/0 = 删除该条
  router.put("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.userId);
      const body = req.body || {};
      const hasOwn = (field) => Object.prototype.hasOwnProperty.call(body, field);

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      const entries = [];
      if (hasOwn("total")) {
        if (body.total !== null && body.total !== undefined && body.total !== 0) {
          parseRequiredAmount(body.total);
        }
        entries.push({ categoryId: null, amount: body.total === 0 ? null : body.total });
      }
      if (body.categories !== undefined && body.categories !== null) {
        if (!Array.isArray(body.categories)) {
          throw new ApiError(400, "INVALID_REQUEST", "categories 格式不正确");
        }
        const seen = new Set();
        for (const entry of body.categories) {
          const categoryId = entry && entry.categoryId !== undefined && entry.categoryId !== null
            ? entry.categoryId
            : null;
          if (!categoryId) {
            throw new ApiError(400, "INVALID_REQUEST", "categories[].categoryId 不能为空");
          }
          if (seen.has(categoryId)) {
            throw new ApiError(400, "INVALID_REQUEST", "categories 存在重复分类");
          }
          seen.add(categoryId);
          // 分类须是当前范围的启用支出分类（与账单写入同一校验）
          await resolveWritableCategory(pool, {
            userId,
            relationshipId,
            domain: "bills",
            direction: "expense",
            categoryId
          });
          const normalized = entry.amount === 0 ? null : entry.amount;
          if (normalized !== null && normalized !== undefined) {
            parseRequiredAmount(normalized);
          }
          entries.push({ categoryId, amount: normalized });
        }
      }
      if (entries.length === 0) {
        throw new ApiError(400, "INVALID_REQUEST", "没有需要保存的预算");
      }

      await withTransaction(pool, async (conn) => {
        for (const entry of entries) {
          if (entry.amount === null || entry.amount === undefined) {
            await conn.execute(
              "DELETE FROM budgets WHERE user_id = ? AND relationship_id <=> ? AND category_id <=> ?",
              [userId, relationshipId, entry.categoryId]
            );
            continue;
          }
          // 先锁后写：UPDATE 未命中才 INSERT。不能只看 affectedRows——
          // 值未变化时 MySQL 报 0，若据此 INSERT 会在 NULL 唯一键绕过下产生重复行。
          const [existing] = await conn.execute(
            "SELECT id FROM budgets WHERE user_id = ? AND relationship_id <=> ? AND category_id <=> ? LIMIT 1 FOR UPDATE",
            [userId, relationshipId, entry.categoryId]
          );
          if (existing.length > 0) {
            await conn.execute("UPDATE budgets SET amount = ? WHERE id = ?", [entry.amount, existing[0].id]);
          } else {
            await conn.execute(
              "INSERT INTO budgets (relationship_id, user_id, category_id, amount) VALUES (?, ?, ?, ?)",
              [relationshipId, userId, entry.categoryId, entry.amount]
            );
          }
        }
      });

      res.json({ ok: true, message: "保存成功", data: { saved: true } });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createBudgetsRouter };
