const express = require("express");
const { ApiError } = require("../errors");

function trimValue(value) {
  return typeof value === "string" ? value.trim() : "";
}

function parseOptionalInteger(value) {
  if (value === null || value === undefined || value === "") {
    return null;
  }
  if (!Number.isInteger(value) || value < 0) {
    throw new ApiError(400, "INVALID_REQUEST", "请求参数不完整或格式不正确");
  }
  return value;
}

function parseRequiredInteger(value) {
  if (!Number.isInteger(value) || value < 0) {
    throw new ApiError(400, "INVALID_REQUEST", "请求参数不完整或格式不正确");
  }
  return value;
}

function parseRequiredAmount(value) {
  if (typeof value !== "number" || !Number.isFinite(value) || value <= 0) {
    throw new ApiError(400, "INVALID_REQUEST", "请求参数不完整或格式不正确");
  }
  return value;
}

async function loadActiveRelationship(pool, userId) {
  const [rows] = await pool.execute(
    "SELECT relationship_id, user_id_1, user_id_2 FROM couple_relationships WHERE status = 'active' AND (user_id_1 = ? OR user_id_2 = ?) ORDER BY relationship_id DESC LIMIT 1",
    [userId, userId]
  );

  if (rows.length === 0) {
    return null;
  }

  return rows[0];
}

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
      throw new ApiError(400, "INVALID_REQUEST", "未找到情侣关系，无法为对方记账");
    }

    return {
      relationshipId: relationship.relationship_id,
      owner: relationship.user_id_1 === userId ? 2 : 1,
      isHelp: 1
    };
  }

  if (billOwner === "共同") {
    if (!relationship) {
      throw new ApiError(400, "INVALID_REQUEST", `不支持的billOwner类型或缺少情侣关系: ${billOwner}`);
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

      const [result] = await pool.execute(
        "INSERT INTO bills (relationship_id, owner, user_id, title, type, amount, date, time, income_type, is_help) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        [relationshipId, owner, userId, title, type, amount, date, time, incomeType, isHelp]
      );

      res.status(201).json({
        ok: true,
        message: "账单创建成功",
        data: {
          billId: result.insertId,
          relationshipId,
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
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createBillsRouter };
