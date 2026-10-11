// 周期自动记账路由（F1），挂在 /api/recurring-bills（全局鉴权之后）。
//
// 口径与 bills 对齐（spec）：
//  - relationship_id NULL = 私人模板；owner 1/2 = 关系内 user_id_1/2 位，3 = 共同；
//    无 active 关系时只允许私人模板（owner 恒存 1）；
//  - 金额 DECIMAL(10,2) 元；分类三列（category_id + 名称/图标快照）同 bills；
//  - 物化幂等键 = UNIQUE(template_id, due_on)；confirm 幂等（非 pending 返回现状）；
//  - 删除模板不动已生成期次；暂停模板不物化。
const express = require("express");
const { ApiError } = require("../errors");
const { withTransaction } = require("../utils/transactions");
const { requireIdempotencyKey, withIdempotency } = require("../utils/idempotency");
const {
  loadActiveRelationship,
  trimValue,
  parseRequiredInteger,
  parseRequiredAmount
} = require("../utils/queryHelpers");
const { parseIncomeType } = require("../utils/dataValidation");
const { resolveWritableCategory } = require("../utils/categoryScope");
const { resolveBillOwnership, invalidateBillCaches } = require("./bills");
const {
  isDateString,
  todayDateString,
  firstOccurrenceOnOrAfter,
  materializeRecurringTemplates
} = require("../utils/recurringBills");

const FREQUENCIES = ["weekly", "monthly"];
// 未选分类的模板确认成账单时，legacy type 列的兜底值（bills.type NOT NULL）
const DEFAULT_BILL_TYPE = "周期记账";

function notFound() {
  return new ApiError(404, "NOT_FOUND", "模板不存在或无权操作");
}

function serializeTemplate(row) {
  return {
    id: Number(row.id),
    relationshipId: row.relationship_id == null ? null : Number(row.relationship_id),
    userId: Number(row.user_id),
    owner: Number(row.owner),
    title: row.title,
    amount: Number(row.amount),
    incomeType: row.income_type == null ? 0 : Number(row.income_type),
    categoryId: row.category_id || null,
    categoryNameSnapshot: row.category_name_snapshot || null,
    categoryIconSnapshot: row.category_icon_snapshot || null,
    frequency: row.frequency,
    dayOfMonth: row.day_of_month == null ? null : Number(row.day_of_month),
    dayOfWeek: row.day_of_week == null ? null : Number(row.day_of_week),
    nextDueOn: row.next_due_on,
    status: row.status,
    createdAt: row.created_at,
    updatedAt: row.updated_at
  };
}

/** 可见范围：私人模板（仅本人）+ active 关系模板（双方）。 */
function templateVisibilityClause(userId, relationship) {
  if (!relationship) {
    return {
      clause: "(t.relationship_id IS NULL AND t.user_id = ?)",
      params: [userId]
    };
  }
  return {
    clause: "((t.relationship_id IS NULL AND t.user_id = ?) OR t.relationship_id = ?)",
    params: [userId, relationship.relationship_id]
  };
}

/**
 * 加载模板行并校验操作权限（创建者或关系成员）。
 * 私人模板仅创建者可见；关系模板要求模板所属关系 == 操作者的 active 关系
 * （解绑后双方都不可再操作，行保留做历史）。forUpdate 时须在事务内调用。
 */
async function loadTemplateForMutation(db, templateId, userId, { forUpdate = false } = {}) {
  const [rows] = await db.execute(
    `SELECT t.*, DATE_FORMAT(t.next_due_on, '%Y-%m-%d') AS next_due_on
     FROM recurring_bill_templates t WHERE t.id = ? LIMIT 1${forUpdate ? " FOR UPDATE" : ""}`,
    [templateId]
  );
  const template = rows[0];
  if (!template) throw notFound();

  const relationship = await loadActiveRelationship(db, userId);
  if (template.relationship_id == null) {
    if (Number(template.user_id) !== Number(userId)) throw notFound();
    return { template, relationship: null };
  }
  if (!relationship || Number(relationship.relationship_id) !== Number(template.relationship_id)) {
    throw notFound();
  }
  return { template, relationship };
}

function validateFrequency(frequency) {
  if (!FREQUENCIES.includes(frequency)) {
    throw new ApiError(400, "INVALID_REQUEST", "frequency 必须是 weekly 或 monthly");
  }
  return frequency;
}

function validateDayFields(frequency, dayOfMonth, dayOfWeek) {
  if (frequency === "monthly") {
    if (!Number.isInteger(dayOfMonth) || dayOfMonth < 1 || dayOfMonth > 31) {
      throw new ApiError(400, "INVALID_REQUEST", "monthly 模板需要 1-31 的 dayOfMonth");
    }
  } else if (!Number.isInteger(dayOfWeek) || dayOfWeek < 0 || dayOfWeek > 6) {
    throw new ApiError(400, "INVALID_REQUEST", "weekly 模板需要 0-6 的 dayOfWeek（0=周日）");
  }
}

/** confirm 时按确认人视角算 is_help：代对方/共同位以外的人记录 = 1。 */
function resolveIsHelp(template, relationship, userId) {
  if (template.relationship_id == null) return 0;
  const myPosition = Number(relationship.user_id_1) === Number(userId) ? 1 : 2;
  const owner = Number(template.owner);
  return owner === 3 || owner === myPosition ? 0 : 1;
}

function createRecurringBillsRouter({ pool }) {
  const router = express.Router();

  // 建模板
  router.post("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.userId);
      const body = req.body || {};
      const title = trimValue(body.title);
      const amount = parseRequiredAmount(body.amount);
      const incomeType = body.incomeType === undefined ? 0 : parseIncomeType(body.incomeType);
      if (!title || title.length > 128) {
        throw new ApiError(400, "INVALID_REQUEST", "标题不能为空且不超过 128 字");
      }

      // owner 口径同 bills：有 active 关系按关系位解析；无关系只允许私人模板（owner 存 1）
      const resolved = await resolveBillOwnership(pool, body, userId);
      const relationshipId = resolved.relationshipId;
      const owner = relationshipId ? resolved.owner : 1;

      const frequency = validateFrequency(trimValue(body.frequency));
      const dayOfMonth = body.dayOfMonth === undefined || body.dayOfMonth === null
        ? null
        : Number(body.dayOfMonth);
      const dayOfWeek = body.dayOfWeek === undefined || body.dayOfWeek === null
        ? null
        : Number(body.dayOfWeek);
      validateDayFields(frequency, dayOfMonth, dayOfWeek);

      // 分类快照同 bills：给 categoryId 时校验范围/方向并写名称、图标快照
      let categoryId = null;
      let categoryNameSnapshot = null;
      let categoryIconSnapshot = null;
      if (body.categoryId !== undefined && body.categoryId !== null) {
        const category = await resolveWritableCategory(pool, {
          userId,
          relationshipId,
          domain: "bills",
          direction: incomeType === 1 ? "income" : "expense",
          categoryId: body.categoryId
        });
        categoryId = category.id;
        categoryNameSnapshot = category.name;
        categoryIconSnapshot = category.icon_value;
      }

      let nextDueOn = trimValue(body.nextDueOn);
      if (nextDueOn) {
        if (!isDateString(nextDueOn)) {
          throw new ApiError(400, "INVALID_REQUEST", "nextDueOn 格式应为 YYYY-MM-DD");
        }
      } else {
        nextDueOn = firstOccurrenceOnOrAfter(frequency, dayOfMonth, dayOfWeek, todayDateString());
      }

      const insert = async (conn) => {
        const [result] = await conn.execute(
          `INSERT INTO recurring_bill_templates
             (relationship_id, user_id, owner, title, amount, income_type,
              category_id, category_name_snapshot, category_icon_snapshot,
              frequency, day_of_month, day_of_week, next_due_on, status)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'active')`,
          [relationshipId, userId, owner, title, amount, String(incomeType),
            categoryId, categoryNameSnapshot, categoryIconSnapshot,
            frequency, dayOfMonth, dayOfWeek, nextDueOn]
        );
        const [rows] = await conn.execute(
          `SELECT t.*, DATE_FORMAT(t.next_due_on, '%Y-%m-%d') AS next_due_on
           FROM recurring_bill_templates t WHERE t.id = ?`,
          [result.insertId]
        );
        return serializeTemplate(rows[0]);
      };

      const template = body.idempotencyKey === undefined || body.idempotencyKey === null
        ? await withTransaction(pool, insert)
        : await withIdempotency(pool, {
          userId,
          scope: "recurring.create",
          key: requireIdempotencyKey(body.idempotencyKey),
          payload: { ...body, idempotencyKey: undefined }
        }, insert).then((outcome) => outcome.result);

      res.status(201).json({ ok: true, message: "模板创建成功", data: template });
    } catch (error) {
      next(error);
    }
  });

  // 模板列表（当前用户可见：私人 + active 关系）
  router.get("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.userId);
      const relationship = await loadActiveRelationship(pool, userId);
      const scope = templateVisibilityClause(userId, relationship);
      const [rows] = await pool.execute(
        `SELECT t.*, DATE_FORMAT(t.next_due_on, '%Y-%m-%d') AS next_due_on
         FROM recurring_bill_templates t
         WHERE ${scope.clause}
         ORDER BY t.status = 'active' DESC, t.next_due_on ASC, t.id ASC`,
        scope.params
      );
      res.json({ ok: true, message: "查询成功", data: { templates: rows.map(serializeTemplate) } });
    } catch (error) {
      next(error);
    }
  });

  // 改模板：title/amount/categoryId/status/day 字段；权限=创建者或关系成员
  router.patch("/:id", async (req, res, next) => {
    try {
      const templateId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(req.userId);
      const body = req.body || {};
      const hasOwn = (field) => Object.prototype.hasOwnProperty.call(body, field);

      const sets = [];
      const params = [];

      let frequency = null;
      if (hasOwn("frequency")) frequency = validateFrequency(trimValue(body.frequency));

      await withTransaction(pool, async (conn) => {
        const { template } = await loadTemplateForMutation(conn, templateId, userId, { forUpdate: true });

        const nextFrequency = frequency || template.frequency;
        const nextDayOfMonth = hasOwn("dayOfMonth")
          ? (body.dayOfMonth === null ? null : Number(body.dayOfMonth))
          : (template.day_of_month == null ? null : Number(template.day_of_month));
        const nextDayOfWeek = hasOwn("dayOfWeek")
          ? (body.dayOfWeek === null ? null : Number(body.dayOfWeek))
          : (template.day_of_week == null ? null : Number(template.day_of_week));
        validateDayFields(nextFrequency, nextDayOfMonth, nextDayOfWeek);

        if (hasOwn("title")) {
          const title = trimValue(body.title);
          if (!title || title.length > 128) {
            throw new ApiError(400, "INVALID_REQUEST", "标题不能为空且不超过 128 字");
          }
          sets.push("title = ?");
          params.push(title);
        }
        if (hasOwn("amount")) {
          sets.push("amount = ?");
          params.push(parseRequiredAmount(body.amount));
        }

        let nextIncomeType = template.income_type == null ? 0 : Number(template.income_type);
        if (hasOwn("incomeType")) {
          nextIncomeType = parseIncomeType(body.incomeType);
          sets.push("income_type = ?");
          params.push(String(nextIncomeType));
        }

        if (hasOwn("categoryId")) {
          if (body.categoryId === null) {
            sets.push("category_id = ?", "category_name_snapshot = ?", "category_icon_snapshot = ?");
            params.push(null, null, null);
          } else {
            const category = await resolveWritableCategory(conn, {
              userId,
              relationshipId: template.relationship_id == null ? null : Number(template.relationship_id),
              domain: "bills",
              direction: nextIncomeType === 1 ? "income" : "expense",
              categoryId: body.categoryId
            });
            sets.push("category_id = ?", "category_name_snapshot = ?", "category_icon_snapshot = ?");
            params.push(category.id, category.name, category.icon_value);
          }
        }

        const dayFieldsChanged = hasOwn("frequency") || hasOwn("dayOfMonth") || hasOwn("dayOfWeek");
        if (hasOwn("frequency")) { sets.push("frequency = ?"); params.push(nextFrequency); }
        if (hasOwn("dayOfMonth")) { sets.push("day_of_month = ?"); params.push(nextDayOfMonth); }
        if (hasOwn("dayOfWeek")) { sets.push("day_of_week = ?"); params.push(nextDayOfWeek); }

        if (hasOwn("status")) {
          const raw = trimValue(body.status);
          const status = raw === "pause" ? "paused"
            : raw === "resume" ? "active"
              : raw;
          if (status !== "active" && status !== "paused") {
            throw new ApiError(400, "INVALID_REQUEST", "status 只支持 active/paused（或 pause/resume）");
          }
          sets.push("status = ?");
          params.push(status);
        }

        if (dayFieldsChanged) {
          // 改频率/日期后从今天重推下一期，避免旧 next_due_on 永远不再命中
          const recomputed = firstOccurrenceOnOrAfter(
            nextFrequency, nextDayOfMonth, nextDayOfWeek, todayDateString()
          );
          sets.push("next_due_on = ?");
          params.push(recomputed);
        }

        if (sets.length === 0) {
          throw new ApiError(400, "INVALID_REQUEST", "没有需要更新的字段");
        }

        params.push(templateId);
        await conn.execute(`UPDATE recurring_bill_templates SET ${sets.join(", ")} WHERE id = ?`, params);
        const [rows] = await conn.execute(
          `SELECT t.*, DATE_FORMAT(t.next_due_on, '%Y-%m-%d') AS next_due_on
           FROM recurring_bill_templates t WHERE t.id = ?`,
          [templateId]
        );
        return serializeTemplate(rows[0]);
      }).then((updated) => {
        res.json({ ok: true, message: "更新成功", data: updated });
      });
    } catch (error) {
      next(error);
    }
  });

  // 删模板（不动已生成期次）
  router.delete("/:id", async (req, res, next) => {
    try {
      const templateId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(req.userId);

      const deleted = await withTransaction(pool, async (conn) => {
        await loadTemplateForMutation(conn, templateId, userId, { forUpdate: true });
        const [result] = await conn.execute(
          "DELETE FROM recurring_bill_templates WHERE id = ?",
          [templateId]
        );
        return result.affectedRows > 0;
      });
      if (!deleted) throw notFound();
      res.json({ ok: true, message: "删除成功", data: { templateId, deleted: true } });
    } catch (error) {
      next(error);
    }
  });

  // 懒生成：把到期期次物化成 pending 实例（GET /api/bills 也会顺带触发）
  router.post("/materialize", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.userId);
      const created = await materializeRecurringTemplates(pool, userId);
      res.json({ ok: true, message: "物化完成", data: { created } });
    } catch (error) {
      next(error);
    }
  });

  // 待确认期次列表（联模板取标题/金额/分类/owner）
  router.get("/pending", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.userId);
      const relationship = await loadActiveRelationship(pool, userId);
      const scope = templateVisibilityClause(userId, relationship);
      const [rows] = await pool.execute(
        `SELECT i.id AS instance_id, i.status AS instance_status, i.bill_id,
                DATE_FORMAT(i.due_on, '%Y-%m-%d') AS due_on,
                t.id AS template_id, t.relationship_id, t.user_id AS template_user_id, t.owner,
                t.title, t.amount, t.income_type,
                t.category_id, t.category_name_snapshot, t.category_icon_snapshot
         FROM recurring_bill_templates t
         JOIN recurring_bill_instances i ON i.template_id = t.id AND i.status = 'pending'
         WHERE ${scope.clause}
         ORDER BY i.due_on ASC, i.id ASC`,
        scope.params
      );
      const instances = rows.map((row) => ({
        instanceId: Number(row.instance_id),
        templateId: Number(row.template_id),
        dueOn: row.due_on,
        status: row.instance_status,
        billId: row.bill_id == null ? null : Number(row.bill_id),
        relationshipId: row.relationship_id == null ? null : Number(row.relationship_id),
        userId: Number(row.template_user_id),
        owner: Number(row.owner),
        title: row.title,
        amount: Number(row.amount),
        incomeType: row.income_type == null ? 0 : Number(row.income_type),
        categoryId: row.category_id || null,
        categoryNameSnapshot: row.category_name_snapshot || null,
        categoryIconSnapshot: row.category_icon_snapshot || null
      }));
      res.json({ ok: true, message: "查询成功", data: { instances } });
    } catch (error) {
      next(error);
    }
  });

  // 确认一期：按模板快照插入正式账单（date=due_on），实例置 confirmed + bill_id
  router.post("/instances/:id/confirm", async (req, res, next) => {
    try {
      const instanceId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(req.userId);
      let relationship = null;

      const confirmed = await withTransaction(pool, async (conn) => {
        const [rows] = await conn.execute(
          `SELECT i.id AS instance_id, i.status, i.bill_id,
                  DATE_FORMAT(i.due_on, '%Y-%m-%d') AS due_on, i.template_id,
                  t.relationship_id, t.user_id AS template_user_id, t.owner, t.title, t.amount,
                  t.income_type, t.category_id, t.category_name_snapshot, t.category_icon_snapshot
           FROM recurring_bill_instances i
           JOIN recurring_bill_templates t ON t.id = i.template_id
           WHERE i.id = ? LIMIT 1 FOR UPDATE`,
          [instanceId]
        );
        const row = rows[0];
        if (!row) throw notFound();

        relationship = await loadActiveRelationship(conn, userId);
        if (row.relationship_id == null) {
          if (Number(row.template_user_id) !== Number(userId)) throw notFound();
        } else if (!relationship
          || Number(relationship.relationship_id) !== Number(row.relationship_id)) {
          throw notFound();
        }

        // 非 pending 幂等返回现状（confirmed 带原 billId；skipped 不再生成账单）
        if (row.status !== "pending") {
          return {
            instanceId,
            status: row.status,
            billId: row.bill_id == null ? null : Number(row.bill_id),
            created: false
          };
        }

        const isHelp = resolveIsHelp(row, relationship, userId);
        const [inserted] = await conn.execute(
          `INSERT INTO bills
             (relationship_id, shared_plan_id, owner, user_id, title, type, amount,
              date, time, income_type, is_help, category_id, category_name_snapshot, category_icon_snapshot)
           VALUES (?, NULL, ?, ?, ?, ?, ?, ?, '00:00:00', ?, ?, ?, ?, ?)`,
          [row.relationship_id, row.owner, userId, row.title,
            row.category_name_snapshot || DEFAULT_BILL_TYPE, row.amount,
            row.due_on, row.income_type == null ? 0 : Number(row.income_type), isHelp,
            row.category_id, row.category_name_snapshot, row.category_icon_snapshot]
        );

        await conn.execute(
          "UPDATE recurring_bill_instances SET status = 'confirmed', bill_id = ?, confirmed_at = CURRENT_TIMESTAMP WHERE id = ?",
          [inserted.insertId, instanceId]
        );
        return {
          instanceId,
          status: "confirmed",
          billId: Number(inserted.insertId),
          created: true
        };
      });

      invalidateBillCaches(userId, relationship);
      res.json({ ok: true, message: "已确认", data: confirmed });
    } catch (error) {
      next(error);
    }
  });

  // 跳过一期：不再出现在待确认列表，也不生成账单
  router.post("/instances/:id/skip", async (req, res, next) => {
    try {
      const instanceId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(req.userId);

      const skipped = await withTransaction(pool, async (conn) => {
        const [rows] = await conn.execute(
          `SELECT i.id, i.status, t.relationship_id, t.user_id AS template_user_id
           FROM recurring_bill_instances i
           JOIN recurring_bill_templates t ON t.id = i.template_id
           WHERE i.id = ? LIMIT 1 FOR UPDATE`,
          [instanceId]
        );
        const row = rows[0];
        if (!row) throw notFound();

        const relationship = await loadActiveRelationship(conn, userId);
        if (row.relationship_id == null) {
          if (Number(row.template_user_id) !== Number(userId)) throw notFound();
        } else if (!relationship
          || Number(relationship.relationship_id) !== Number(row.relationship_id)) {
          throw notFound();
        }

        if (row.status === "confirmed") {
          throw new ApiError(409, "INSTANCE_ALREADY_CONFIRMED", "该期次已生成账单，不能跳过");
        }
        if (row.status === "pending") {
          await conn.execute(
            "UPDATE recurring_bill_instances SET status = 'skipped' WHERE id = ?",
            [instanceId]
          );
        }
        return { instanceId, status: "skipped" };
      });

      res.json({ ok: true, message: "已跳过", data: skipped });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createRecurringBillsRouter };
