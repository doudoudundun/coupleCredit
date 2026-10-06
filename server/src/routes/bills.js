const express = require("express");
const { ApiError } = require("../errors");
const { cache, Keys, TTL } = require("../cache");
const {
  invalidateOverviewForUser,
  buildCoupleOrPrivateScope,
  loadActiveRelationship,
  trimValue,
  parseOptionalInteger,
  parseRequiredInteger,
  parseRequiredAmount
} = require("../utils/queryHelpers");
const { withTransaction } = require("../utils/transactions");
const { parseIncomeType } = require("../utils/dataValidation");
const { resolveWritableCategory } = require("../utils/categoryScope");

// 账单图片（照片 / 小票凭证）。
// 契约：最多 MAX_BILL_IMAGES 张，只接受站内上传路径（/uploads/...）或 https 绝对地址。
// undefined = 未提供（不参与写入，编辑时保留原值）；[] = 明确清空。
const MAX_BILL_IMAGES = 4;

function normalizeBillImages(value, label) {
  if (value === undefined) return undefined;
  if (!Array.isArray(value)) {
    throw new ApiError(400, "INVALID_REQUEST", `${label}格式不正确`);
  }
  if (value.length > MAX_BILL_IMAGES) {
    throw new ApiError(400, "INVALID_REQUEST", `${label}最多 ${MAX_BILL_IMAGES} 张`);
  }

  const list = [];
  for (const item of value) {
    const text = trimValue(item);
    if (!text) continue;
    if (text === "__mock__") continue; // 前端 mock 占位符绝不入库
    if (text.length > 500) {
      throw new ApiError(400, "INVALID_REQUEST", `${label}包含过长的图片地址`);
    }
    if (!text.startsWith("/uploads/") && !/^https:\/\//i.test(text)) {
      throw new ApiError(400, "INVALID_REQUEST", `${label}包含非法图片地址`);
    }
    list.push(text);
  }
  return list;
}

/** 入库前序列化：undefined / null → NULL，数组 → JSON 字符串 */
function serializeBillImages(list) {
  if (list === undefined || list === null) return null;
  return JSON.stringify(list);
}

/** 读取时反序列化：容忍 NULL、JSON 字符串、以及历史异常数据 */
function parseBillImages(raw) {
  if (!raw) return [];
  if (Array.isArray(raw)) return raw;
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch (_e) {
    return [];
  }
}

/**
 * 月报聚合（RETENTION_MONTHLY_SPEC_20261003 §6.2）：DECIMAL 聚合结果 mysql2 以字符串
 * 返回（如 "328.00"），这里按十进制字符串精确转整数分，全程不经浮点乘 100。
 */
function decimalStringToCents(value) {
  if (value === null || value === undefined) return 0;
  const text = typeof value === "number" ? value.toFixed(2) : String(value).trim();
  const negative = text.startsWith("-");
  const [intPart = "0", fracPart = ""] = (negative ? text.slice(1) : text).split(".");
  const frac2 = `${fracPart}00`.slice(0, 2);
  const cents = Number(intPart || "0") * 100 + Number(frac2 || "0");
  return negative ? -cents : cents;
}

async function resolveBillOwnership(pool, reqBody, userId) {
  const relationship = await loadActiveRelationship(pool, userId);
  const relationshipId = relationship ? relationship.relationship_id : null;

  if (reqBody.billOwner === undefined) {
    const owner = parseRequiredInteger(reqBody.owner);
    const isHelp = parseRequiredInteger(reqBody.isHelp ?? 0);
    if (![1, 2, 3].includes(owner) || ![0, 1].includes(isHelp)) {
      throw new ApiError(400, "INVALID_REQUEST", "账单归属参数无效");
    }
    return {
      relationship,
      relationshipId,
      owner,
      isHelp
    };
  }

  const billOwner = trimValue(reqBody.billOwner);
  if (!billOwner) {
    throw new ApiError(400, "INVALID_REQUEST", "请求参数不完整或格式不正确");
  }

  if (billOwner === "自己") {
    if (!relationship) {
      return {
        relationship,
        relationshipId: null,
        owner: 1,
        isHelp: 0
      };
    }

    return {
      relationship,
      relationshipId: relationship.relationship_id,
      owner: relationship.user_id_1 === userId ? 1 : 2,
      isHelp: 0
    };
  }

  if (billOwner === "对方") {
    if (!relationship) {
      return {
        relationship,
        relationshipId: null,
        owner: 2,
        isHelp: 1
      };
    }

    return {
      relationship,
      relationshipId: relationship.relationship_id,
      owner: relationship.user_id_1 === userId ? 2 : 1,
      isHelp: 1
    };
  }

  if (billOwner === "共同") {
    if (!relationship) {
      return {
        relationship,
        relationshipId: null,
        owner: 3,
        isHelp: 0
      };
    }

    return {
      relationship,
      relationshipId: relationship.relationship_id,
      owner: 3,
      isHelp: 0
    };
  }

  throw new ApiError(400, "INVALID_REQUEST", `不支持的billOwner类型或缺少情侣关系: ${billOwner}`);
}

function createBillsRouter({ pool }) {
  const router = express.Router();

  async function loadBillForMutation(billId, userId, { db = pool, forUpdate = false } = {}) {
    const relationship = await loadActiveRelationship(db, userId);
    const [rows] = await db.execute(
      `SELECT bill_id, relationship_id, shared_plan_id, owner, user_id, income_type, amount
       FROM bills WHERE bill_id = ? LIMIT 1${forUpdate ? " FOR UPDATE" : ""}`,
      [billId]
    );
    if (rows.length === 0) {
      throw new ApiError(404, "NOT_FOUND", "账单不存在或无权操作");
    }

    const bill = rows[0];
    if (bill.relationship_id == null) {
      if (bill.user_id !== userId) {
        throw new ApiError(404, "NOT_FOUND", "账单不存在或无权操作");
      }
      return {
        bill,
        relationship,
        mutationClause: "bill_id = ? AND relationship_id IS NULL AND user_id = ?",
        mutationParams: [billId, userId]
      };
    }

    if (!relationship || bill.relationship_id !== relationship.relationship_id) {
      throw new ApiError(404, "NOT_FOUND", "账单不存在或无权操作");
    }

    const userRole = relationship.user_id_1 === userId ? 1 : 2;
    const isCreator = bill.user_id === userId;
    const isSubject = Number(bill.owner) === 3 || Number(bill.owner) === userRole;
    if (!isCreator && !isSubject) {
      throw new ApiError(404, "NOT_FOUND", "账单不存在或无权操作");
    }

    return {
      bill,
      relationship,
      mutationClause: "bill_id = ? AND relationship_id = ?",
      mutationParams: [billId, relationship.relationship_id]
    };
  }

  async function lockPlanForBill(conn, bill, userId, relationship) {
    const planScope = bill.relationship_id == null
      ? {
          clause: "created_by = ? AND relationship_id IS NULL",
          params: [userId]
        }
      : {
          clause: "relationship_id = ? AND visibility = 'both'",
          params: [relationship.relationship_id]
        };
    const [plans] = await conn.execute(
      `SELECT plan_id, current_balance FROM shared_plans
       WHERE plan_id = ? AND ${planScope.clause} LIMIT 1 FOR UPDATE`,
      [bill.shared_plan_id, ...planScope.params]
    );
    if (plans.length === 0) {
      throw new ApiError(404, "NOT_FOUND", "共同计划不存在或无权使用");
    }
    return plans[0];
  }

  async function adjustPlanBalance(conn, bill, userId, relationship, delta) {
    if (!bill.shared_plan_id || delta === 0) return;

    await lockPlanForBill(conn, bill, userId, relationship);
    const planScope = bill.relationship_id == null
      ? {
          clause: "created_by = ? AND relationship_id IS NULL",
          params: [userId]
        }
      : {
          clause: "relationship_id = ? AND visibility = 'both'",
          params: [relationship.relationship_id]
        };

    if (delta > 0) {
      const [result] = await conn.execute(
        `UPDATE shared_plans SET current_balance = current_balance + ?
         WHERE plan_id = ? AND ${planScope.clause}`,
        [delta, bill.shared_plan_id, ...planScope.params]
      );
      if (result.affectedRows === 0) {
        throw new ApiError(404, "NOT_FOUND", "共同计划不存在或无权使用");
      }
      return;
    }

    const amount = -delta;
    const [result] = await conn.execute(
      `UPDATE shared_plans SET current_balance = current_balance - ?
       WHERE plan_id = ? AND ${planScope.clause} AND current_balance >= ?`,
      [amount, bill.shared_plan_id, ...planScope.params, amount]
    );
    if (result.affectedRows === 0) {
      throw new ApiError(400, "INSUFFICIENT_BALANCE", "小钱包余额不足");
    }
  }

  function invalidateBillCaches(userId, relationship) {
    const ids = new Set([userId]);
    if (relationship) {
      ids.add(relationship.user_id_1);
      ids.add(relationship.user_id_2);
    }
    for (const id of ids) {
      cache.delPrefix(`bills:${id}:`);
    }
    invalidateOverviewForUser(cache, userId, relationship);
  }

  // 列表读路径（spec 9）：返回 categoryId + 保存时快照 + 分类当前信息；
  // 历史 NULL 行 category 为 null，归档分类仍随快照可读（status: "archived"）。
  const BILL_CATEGORY_SELECT = `b.category_id as categoryId, b.category_name_snapshot as categoryNameSnapshot, b.category_icon_snapshot as categoryIconSnapshot,
                 ic.name as categoryName, ic.icon_type as categoryIconType, ic.icon_value as categoryIconValue, ic.color as categoryColor, ic.status as categoryStatus`;
  const BILL_CATEGORY_JOIN = "LEFT JOIN item_categories ic ON ic.id = b.category_id";

  function mapBillRow(row) {
    const {
      categoryName, categoryIconType, categoryIconValue, categoryColor, categoryStatus,
      ...rest
    } = row;
    return {
      ...rest,
      categoryId: row.categoryId || null,
      categoryNameSnapshot: row.categoryNameSnapshot || null,
      categoryIconSnapshot: row.categoryIconSnapshot || null,
      category: row.categoryId
        ? {
            categoryId: row.categoryId,
            name: categoryName || row.categoryNameSnapshot || null,
            icon: categoryIconType ? { type: categoryIconType, value: categoryIconValue } : null,
            color: categoryColor || null,
            status: categoryStatus || null
          }
        : null,
      photos: parseBillImages(row.photos),
      receipts: parseBillImages(row.receipts)
    };
  }

  router.post("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.userId);
      const sharedPlanId = parseOptionalInteger(req.body.sharedPlanId);
      const { relationship, relationshipId, owner, isHelp } = await resolveBillOwnership(pool, req.body, userId);
      const title = trimValue(req.body.title);
      const amount = parseRequiredAmount(req.body.amount);
      const date = trimValue(req.body.date);
      const time = trimValue(req.body.time);
      const incomeType = parseIncomeType(req.body.incomeType);
      const photos = normalizeBillImages(req.body.photos, "照片");
      const receipts = normalizeBillImages(req.body.receipts, "小票凭证");

      // categoryId（spec 第 9 节）：显式提供时优先于 legacy 字符串 type，
      // 服务端按分类解析显示名称并写快照；未提供时走旧字符串路径，行为不变。
      let categoryId = null;
      let categorySnapshot = null;
      if (req.body.categoryId !== undefined && req.body.categoryId !== null) {
        categorySnapshot = await resolveWritableCategory(pool, {
          userId,
          relationshipId,
          domain: "bills",
          direction: incomeType === 1 ? "income" : "expense",
          categoryId: req.body.categoryId
        });
        categoryId = categorySnapshot.id;
      }
      const type = categorySnapshot ? categorySnapshot.name : trimValue(req.body.type);

      if (!title || !type || !date || !time) {
        throw new ApiError(400, "INVALID_REQUEST", "请求参数不完整或格式不正确");
      }

      let insertId;
      if (sharedPlanId) {
        await withTransaction(pool, async (conn) => {
          const planScope = relationshipId
            ? {
                clause: "sp.relationship_id = ? AND sp.visibility = 'both'",
                params: [relationshipId]
              }
            : {
                clause: "sp.created_by = ? AND (sp.visibility = 'self' OR sp.relationship_id IS NULL)",
                params: [userId]
              };
          const [plans] = await conn.execute(
            `SELECT sp.plan_id, sp.current_balance FROM shared_plans sp
             WHERE sp.plan_id = ? AND ${planScope.clause} LIMIT 1 FOR UPDATE`,
            [sharedPlanId, ...planScope.params]
          );
          if (plans.length === 0) throw new ApiError(404, "NOT_FOUND", "共同计划不存在或无权使用");
          if (incomeType === 0 && parseFloat(plans[0].current_balance) < amount) {
            throw new ApiError(400, "INSUFFICIENT_BALANCE", "小钱包余额不足");
          }

          const [result] = await conn.execute(
            "INSERT INTO bills (relationship_id, shared_plan_id, owner, user_id, title, type, amount, date, time, income_type, is_help, photos, receipts, category_id, category_name_snapshot, category_icon_snapshot) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            [relationshipId, sharedPlanId, owner, userId, title, type, amount, date, time, incomeType, isHelp, serializeBillImages(photos), serializeBillImages(receipts), categoryId, categorySnapshot ? categorySnapshot.name : null, categorySnapshot ? categorySnapshot.icon_value : null]
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
        if (relationship) {
          cache.del(Keys.sharedPlans(relationship.user_id_1));
          cache.del(Keys.sharedPlans(relationship.user_id_2));
        } else {
          cache.del(Keys.sharedPlans(userId));
        }
      } else {
        const [result] = await pool.execute(
          "INSERT INTO bills (relationship_id, shared_plan_id, owner, user_id, title, type, amount, date, time, income_type, is_help, photos, receipts, category_id, category_name_snapshot, category_icon_snapshot) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          [relationshipId, sharedPlanId, owner, userId, title, type, amount, date, time, incomeType, isHelp, serializeBillImages(photos), serializeBillImages(receipts), categoryId, categorySnapshot ? categorySnapshot.name : null, categorySnapshot ? categorySnapshot.icon_value : null]
        );
        insertId = result.insertId;
      }

      invalidateBillCaches(userId, relationship);
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
          isHelp,
          photos: photos || [],
          receipts: receipts || [],
          categoryId,
          categoryNameSnapshot: categorySnapshot ? categorySnapshot.name : null,
          categoryIconSnapshot: categorySnapshot ? categorySnapshot.icon_value : null
        }
      });
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
        query = `SELECT b.bill_id as billId, b.user_id as userId, b.shared_plan_id as sharedPlanId, sp.name as sharedPlanName, b.title, b.type, b.amount, DATE_FORMAT(b.date, '%Y-%m-%d') as date, b.time, b.income_type as incomeType, b.owner, b.is_help as isHelp, b.relationship_id as relationshipId, b.photos, b.receipts, ${BILL_CATEGORY_SELECT}
                 FROM bills b
                 LEFT JOIN shared_plans sp ON sp.plan_id = b.shared_plan_id
                 ${BILL_CATEGORY_JOIN}
                 WHERE (b.relationship_id = ? OR (b.user_id = ? AND b.relationship_id IS NULL))
                 AND b.date >= ? AND b.date < ?
                 ORDER BY b.date DESC, b.bill_id DESC`;
        params = [relationshipId, userId, dateStart, dateEnd];
      } else {
        query = `SELECT b.bill_id as billId, b.user_id as userId, b.shared_plan_id as sharedPlanId, sp.name as sharedPlanName, b.title, b.type, b.amount, DATE_FORMAT(b.date, '%Y-%m-%d') as date, b.time, b.income_type as incomeType, b.owner, b.is_help as isHelp, b.relationship_id as relationshipId, b.photos, b.receipts, ${BILL_CATEGORY_SELECT}
                 FROM bills b
                 LEFT JOIN shared_plans sp ON sp.plan_id = b.shared_plan_id
                 ${BILL_CATEGORY_JOIN}
                 WHERE b.user_id = ? AND b.relationship_id IS NULL
                 AND b.date >= ? AND b.date < ?
                 ORDER BY b.date DESC, b.bill_id DESC`;
        params = [userId, dateStart, dateEnd];
      }

      const [rows] = await pool.execute(query, params);

      // photos / receipts 以 JSON 字符串存库，出参统一转成数组（前端按数组消费）
      const bills = rows.map(mapBillRow);

      const responseData = {
        ok: true,
        message: "查询成功",
        data: {
          bills,
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

  // 月报历史基线（RETENTION_MONTHLY_SPEC_20261003 §6.2）：一次拿回近 months 个月的只读聚合，
  // 纯 SELECT、无写入。months 数组按 ym 升序（旧 → 新，前端折线图按时间序消费）；
  // 窗口内无账单的月份也以零值占位，保证特点引擎拿到连续月基线（连续下降/上升类判定依赖）。
  // 金额一律整数分；byOwner.count 含收入，byOwner.amount 仅支出，与共用聚合器一致。
  router.get("/monthly-stats", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.userId);
      const parsedMonths = parseInt(req.query.months, 10);
      // 钳制到 1–24；缺省或非法值回落默认 6
      const months = Number.isNaN(parsedMonths) ? 6 : Math.min(24, Math.max(1, parsedMonths));
      // 默认共享视图保持旧调用口径；个人视图只纳入归属本人（不按创建者筛选）。
      const reportScope = req.query.scope === undefined ? "shared" : String(req.query.scope);
      if (reportScope !== "shared" && reportScope !== "self") {
        throw new ApiError(400, "INVALID_REQUEST", "月报范围无效");
      }
      const now = new Date();
      const ymOf = (date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}`;
      const currentYm = ymOf(now);
      const endYm = req.query.endYm === undefined ? currentYm : String(req.query.endYm);
      if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(endYm) || endYm < "1000-01" || endYm > currentYm) {
        throw new ApiError(400, "INVALID_REQUEST", "月报月份格式不正确或超出支持范围");
      }
      const [endYear, endMonth] = endYm.split("-").map(Number);
      const cached = cache.get(Keys.billsMonthly(userId, months, endYm, reportScope));
      if (cached) return res.json(cached);
      const monthKeys = [];
      for (let i = months - 1; i >= 0; i -= 1) {
        monthKeys.push(ymOf(new Date(endYear, endMonth - 1 - i, 1)));
      }
      // 可选 endYm 锚定目标月；旧调用仍以当前月为上界。窗口包含目标月与前 months-1 月。
      const dateStart = `${monthKeys[0]}-01`;
      const dateEnd = `${ymOf(new Date(endYear, endMonth, 1))}-01`;

      const relationship = await loadActiveRelationship(pool, userId);
      const visibleScope = buildCoupleOrPrivateScope(userId, relationship, "b");

      // owner 1/2 是关系位（写入路径：user_id_1 记「自己」→ 1，user_id_2 记「自己」→ 2，3=共同）。
      // 换成登录者视角的 self/partner 需按登录者是 user_id_1 还是 user_id_2 换算；无关系时 owner=1 即自己。
      const selfOwner = relationship
        ? (Number(relationship.user_id_1) === Number(userId) ? 1 : 2)
        : 1;

      let where;
      let whereParams;
      if (reportScope === "self" && relationship) {
        // 关系内按归属位选本人记录（可能由伴侣代记）；关系外只保留当前账号自己的 owner=1 私人账单。
        // 不含 owner=3 共同账单、伴侣 owner 位及任何已解绑关系的旧记录。
        where = `((b.relationship_id = ? AND b.owner = ?) OR (b.user_id = ? AND b.relationship_id IS NULL AND b.owner = 1)) AND b.date >= ? AND b.date < ?`;
        whereParams = [relationship.relationship_id, selfOwner, userId, dateStart, dateEnd];
      } else if (reportScope === "self") {
        // loadActiveRelationship 为空时不扩展到历史 dissolved 关系；身份边界与原列表查询一致。
        where = `(b.user_id = ? AND b.relationship_id IS NULL AND b.owner = 1) AND b.date >= ? AND b.date < ?`;
        whereParams = [userId, dateStart, dateEnd];
      } else {
        where = `${visibleScope.clause} AND b.date >= ? AND b.date < ?`;
        whereParams = [...visibleScope.params, dateStart, dateEnd];
      }

      const statsByYm = new Map(monthKeys.map((ym) => [ym, {
        ym,
        expense: 0,
        income: 0,
        count: 0,
        recordDays: 0,
        weekendExpenseCents: 0,
        byCategory: {},
        byOwner: {
          self: { count: 0, amount: 0 },
          partner: { count: 0, amount: 0 },
          common: { count: 0, amount: 0 }
        },
        topBill: null,
        sameDayDays: 0
      }]));

      // 月度总额 / 笔数 / 记账天数 / 周末支出：单条 SQL 按月分组
      const [totalRows] = await pool.execute(
        `SELECT DATE_FORMAT(b.date, '%Y-%m') AS ym,
                SUM(CASE WHEN b.income_type = 0 THEN b.amount ELSE 0 END) AS expense,
                SUM(CASE WHEN b.income_type = 1 THEN b.amount ELSE 0 END) AS income,
                COUNT(*) AS cnt,
                COUNT(DISTINCT b.date) AS recordDays,
                SUM(CASE WHEN b.income_type = 0 AND WEEKDAY(b.date) >= 5 THEN b.amount ELSE 0 END) AS weekendExpense
         FROM bills b
         WHERE ${where}
         GROUP BY ym`,
        whereParams
      );
      for (const row of totalRows) {
        const stats = statsByYm.get(row.ym);
        if (!stats) continue;
        stats.expense = decimalStringToCents(row.expense);
        stats.income = decimalStringToCents(row.income);
        stats.count = Number(row.cnt);
        stats.recordDays = Number(row.recordDays);
        stats.weekendExpenseCents = decimalStringToCents(row.weekendExpense);
      }

      // 分类维度（仅支出，与小程序报表口径一致）：普通分组多行返回、Node 侧组装为对象；
      // 绝不用 GROUP_CONCAT（默认 1024 字节会静默截断，项目踩过坑）。
      // 分类名口径与 GET /api/bills 相同：当前分类名 > 保存时快照 > legacy type。
      const [categoryRows] = await pool.execute(
        `SELECT DATE_FORMAT(b.date, '%Y-%m') AS ym,
                COALESCE(ic.name, b.category_name_snapshot, b.type) AS name,
                SUM(b.amount) AS amount
         FROM bills b
         LEFT JOIN item_categories ic ON ic.id = b.category_id
         WHERE ${where} AND b.income_type = 0
         GROUP BY DATE_FORMAT(b.date, '%Y-%m'), COALESCE(ic.name, b.category_name_snapshot, b.type)`,
        whereParams
      );
      for (const row of categoryRows) {
        const stats = statsByYm.get(row.ym);
        if (!stats || !row.name) continue;
        stats.byCategory[row.name] = decimalStringToCents(row.amount);
      }

      // 归属维度：按 owner 分组多行返回，Node 侧换算成登录者视角的 self/partner/common
      const [ownerRows] = await pool.execute(
        `SELECT DATE_FORMAT(b.date, '%Y-%m') AS ym, b.owner AS owner, b.relationship_id AS relationshipId,
                COUNT(*) AS cnt, SUM(CASE WHEN b.income_type = 0 THEN b.amount ELSE 0 END) AS amount
         FROM bills b
         WHERE ${where}
         GROUP BY ym, b.owner, b.relationship_id`,
        whereParams
      );
      for (const row of ownerRows) {
        const stats = statsByYm.get(row.ym);
        if (!stats) continue;
        const ownerValue = Number(row.owner);
        // 私有账单不使用关系位；绑定前记的 owner=1 仍然属于登录者本人。
        const rowSelfOwner = row.relationshipId == null ? 1 : selfOwner;
        const bucket = ownerValue === 3 ? "common" : (ownerValue === rowSelfOwner ? "self" : "partner");
        stats.byOwner[bucket].count += Number(row.cnt);
        stats.byOwner[bucket].amount += decimalStringToCents(row.amount);
      }

      // 当月「两个不同 user_id 在同一天都有账单」的天数
      const [sameDayRows] = await pool.execute(
        `SELECT DATE_FORMAT(d.date, '%Y-%m') AS ym, COUNT(*) AS sameDayDays
         FROM (
           SELECT b.date FROM bills b WHERE ${where} GROUP BY b.date HAVING COUNT(DISTINCT b.user_id) > 1
         ) d
         GROUP BY ym`,
        whereParams
      );
      for (const row of sameDayRows) {
        const stats = statsByYm.get(row.ym);
        if (stats) stats.sameDayDays = Number(row.sameDayDays);
      }

      // 当月最大单笔支出：整段按金额倒序取回，Node 侧每个 ym 取首行即该月最大
      // （避免窗口函数与关联子查询，保持与生产老版本 MySQL 的兼容）
      const [topRows] = await pool.execute(
        `SELECT DATE_FORMAT(b.date, '%Y-%m') AS ym, b.bill_id AS billId,
                DATE_FORMAT(b.date, '%Y-%m-%d') AS date, b.amount, b.title
         FROM bills b
         WHERE ${where} AND b.income_type = 0
         ORDER BY b.amount DESC, b.date ASC, b.bill_id ASC`,
        whereParams
      );
      for (const row of topRows) {
        const stats = statsByYm.get(row.ym);
        if (!stats || stats.topBill) continue;
        stats.topBill = {
          billId: Number(row.billId),
          date: row.date,
          amount: decimalStringToCents(row.amount),
          title: row.title
        };
      }

      const monthsOut = monthKeys.map((ym) => {
        const stats = statsByYm.get(ym);
        const weekendRatio = stats.expense > 0
          ? Math.floor((stats.weekendExpenseCents / stats.expense) * 10000) / 10000
          : 0;
        return {
          ym: stats.ym,
          expense: stats.expense,
          income: stats.income,
          count: stats.count,
          recordDays: stats.recordDays,
          byCategory: stats.byCategory,
          byOwner: stats.byOwner,
          topBill: stats.topBill,
          sameDayDays: stats.sameDayDays,
          weekendRatio
        };
      });

      const responseData = { ok: true, message: "查询成功", data: { scope: reportScope, months: monthsOut } };
      cache.set(Keys.billsMonthly(userId, months, endYm, reportScope), responseData, TTL.BILLS_MONTHLY);
      res.json(responseData);
    } catch (error) {
      next(error);
    }
  });

  router.delete("/:id", async (req, res, next) => {
    try {
      const billId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(req.userId);
      let relationship = null;
      let sharedPlanId = null;

      await withTransaction(pool, async (conn) => {
        const loaded = await loadBillForMutation(billId, userId, { db: conn, forUpdate: true });
        const { bill, mutationClause, mutationParams } = loaded;
        relationship = loaded.relationship;
        sharedPlanId = bill.shared_plan_id;

        if (bill.shared_plan_id) {
          const amount = Number(bill.amount);
          const delta = Number(bill.income_type) === 0 ? amount : -amount;
          await adjustPlanBalance(conn, bill, userId, relationship, delta);
        }

        const [result] = await conn.execute(`DELETE FROM bills WHERE ${mutationClause}`, mutationParams);
        if (result.affectedRows === 0) {
          throw new ApiError(404, "NOT_FOUND", "账单不存在或无权删除");
        }
      });

      if (sharedPlanId) {
        if (relationship) {
          cache.del(Keys.sharedPlans(relationship.user_id_1));
          cache.del(Keys.sharedPlans(relationship.user_id_2));
        } else {
          cache.del(Keys.sharedPlans(userId));
        }
      }
      invalidateBillCaches(userId, relationship);
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
      const amountProvided = Object.prototype.hasOwnProperty.call(req.body, "amount");
      const amount = amountProvided ? parseRequiredAmount(req.body.amount) : null;
      const date = trimValue(req.body.date);
      const time = trimValue(req.body.time);
      const incomeTypeProvided = Object.prototype.hasOwnProperty.call(req.body, "incomeType");
      const incomeType = incomeTypeProvided ? parseIncomeType(req.body.incomeType) : null;
      const photos = normalizeBillImages(req.body.photos, "照片");
      const receipts = normalizeBillImages(req.body.receipts, "小票凭证");
      const categoryIdProvided = Object.prototype.hasOwnProperty.call(req.body, "categoryId");
      const categoryIdValue = categoryIdProvided ? req.body.categoryId : undefined;

      if (!date || !time) {
        throw new ApiError(400, "INVALID_REQUEST", "请求参数不完整或格式不正确");
      }

      const sets = [];
      const params = [];
      if (Object.prototype.hasOwnProperty.call(req.body, "title")) { sets.push("title = ?"); params.push(title); }
      // categoryId 与 legacy type 同时提交时按 spec 9 以 categoryId 为准，
      // type 由服务端按分类名称解析，在事务内补写。
      if (Object.prototype.hasOwnProperty.call(req.body, "type") && !(categoryIdValue !== undefined && categoryIdValue !== null)) { sets.push("type = ?"); params.push(type); }
      if (amountProvided) { sets.push("amount = ?"); params.push(amount); }
      sets.push("date = ?"); params.push(date);
      sets.push("time = ?"); params.push(time);
      if (incomeTypeProvided) { sets.push("income_type = ?"); params.push(incomeType); }
      if (photos !== undefined) { sets.push("photos = ?"); params.push(serializeBillImages(photos)); }
      if (receipts !== undefined) { sets.push("receipts = ?"); params.push(serializeBillImages(receipts)); }

      if (sets.length === 0) {
        throw new ApiError(400, "INVALID_REQUEST", "没有需要更新的字段");
      }

      let relationship = null;
      let sharedPlanId = null;
      await withTransaction(pool, async (conn) => {
        const loaded = await loadBillForMutation(billId, userId, { db: conn, forUpdate: true });
        const { bill, mutationClause, mutationParams } = loaded;
        relationship = loaded.relationship;
        sharedPlanId = bill.shared_plan_id;

        const oldAmount = Number(bill.amount);
        const oldIncomeType = Number(bill.income_type);
        const nextAmount = amountProvided ? amount : oldAmount;
        const nextIncomeType = incomeTypeProvided ? incomeType : oldIncomeType;

        // categoryId：按账单所属范围与最终收支方向校验；null 表示显式清除分类关联。
        if (categoryIdProvided) {
          if (categoryIdValue === null) {
            sets.push("category_id = ?", "category_name_snapshot = ?", "category_icon_snapshot = ?");
            params.push(null, null, null);
          } else {
            const category = await resolveWritableCategory(conn, {
              userId,
              relationshipId: bill.relationship_id,
              domain: "bills",
              direction: nextIncomeType === 1 ? "income" : "expense",
              categoryId: categoryIdValue
            });
            sets.push("category_id = ?", "category_name_snapshot = ?", "category_icon_snapshot = ?", "type = ?");
            params.push(category.id, category.name, category.icon_value, category.name);
          }
        }

        if (bill.shared_plan_id) {
          const oldEffect = oldIncomeType === 0 ? -oldAmount : oldAmount;
          const nextEffect = nextIncomeType === 0 ? -nextAmount : nextAmount;
          await adjustPlanBalance(conn, bill, userId, relationship, nextEffect - oldEffect);
        }

        params.push(...mutationParams);
        await conn.execute(
          `UPDATE bills SET ${sets.join(", ")} WHERE ${mutationClause}`,
          params
        );
      });

      if (sharedPlanId) {
        if (relationship) {
          cache.del(Keys.sharedPlans(relationship.user_id_1));
          cache.del(Keys.sharedPlans(relationship.user_id_2));
        } else {
          cache.del(Keys.sharedPlans(userId));
        }
      }
      invalidateBillCaches(userId, relationship);
      res.json({ ok: true, message: "更新成功", data: { billId, updated: true } });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createBillsRouter };
