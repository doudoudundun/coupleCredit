const express = require("express");
const { ApiError } = require("../errors");
const {
  loadActiveRelationship,
  normalizeNullableText,
  parseRequiredInteger,
} = require("../utils/queryHelpers");

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

function formatDateObj(d) {
  if (typeof d === "string") return d.slice(0, 10);
  if (d instanceof Date) {
    const year = d.getFullYear();
    const month = String(d.getMonth() + 1).padStart(2, "0");
    const day = String(d.getDate()).padStart(2, "0");
    return `${year}-${month}-${day}`;
  }
  return String(d).slice(0, 10);
}

function resolveDateString(input) {
  if (typeof input === "string" && DATE_RE.test(input)) return input;
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

async function getUserDisplayMap(pool, userIds) {
  if (!userIds.length) return {};
  const placeholders = userIds.map(() => "?").join(",");
  const [rows] = await pool.execute(
    `SELECT id, username, nickname FROM users WHERE id IN (${placeholders})`,
    userIds
  );
  const map = {};
  for (const row of rows) {
    map[row.id] = row.nickname || row.username || `用户${row.id}`;
  }
  return map;
}

function computePrediction(records) {
  const completed = records.filter((r) => r.end_date !== null);
  if (completed.length < 2) {
    return { averageCycleDays: null, predictedNextStart: null };
  }

  const sorted = [...completed].sort(
    (a, b) => new Date(a.start_date) - new Date(b.start_date)
  );
  const cycles = [];
  for (let i = 1; i < sorted.length; i++) {
    const diff =
      (new Date(sorted[i].start_date) - new Date(sorted[i - 1].start_date)) /
      (1000 * 60 * 60 * 24);
    cycles.push(Math.round(diff));
  }

  const avg = Math.round(cycles.reduce((s, v) => s + v, 0) / cycles.length);

  // sorted is already in ascending order, last element is newest
  const lastStart = new Date(sorted[sorted.length - 1].start_date);
  lastStart.setDate(lastStart.getDate() + avg);
  const predictedStr = `${lastStart.getFullYear()}-${String(lastStart.getMonth() + 1).padStart(2, "0")}-${String(lastStart.getDate()).padStart(2, "0")}`;

  return { averageCycleDays: avg, predictedNextStart: predictedStr };
}

function createPeriodRouter({ pool }) {
  const router = express.Router();

  router.get("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(Number(req.query.userId));
      const relationship = await loadActiveRelationship(pool, userId);
      const userIds = [userId];
      if (relationship) {
        if (relationship.user_id_1 !== userId) userIds.push(relationship.user_id_1);
        if (relationship.user_id_2 !== userId) userIds.push(relationship.user_id_2);
      }

      const placeholders = userIds.map(() => "?").join(",");
      const [rows] = await pool.execute(
        `SELECT id, user_id, start_date, end_date, note, created_at
         FROM period_records
         WHERE user_id IN (${placeholders})
         ORDER BY start_date DESC
         LIMIT 24`,
        userIds
      );

      const userNameMap = await getUserDisplayMap(pool, userIds);

      const myRecords = rows.filter((r) => r.user_id === userId);
      const prediction = computePrediction(myRecords);

      let partnerPrediction = null;
      const partnerRecords = rows.filter((r) => r.user_id !== userId);
      if (partnerRecords.length > 0) {
        partnerPrediction = computePrediction(partnerRecords);
      }

      res.json({
        ok: true,
        data: {
          records: rows.map((r) => ({
            id: r.id,
            userId: r.user_id,
            userName: userNameMap[r.user_id] || `用户${r.user_id}`,
            startDate: formatDateObj(r.start_date),
            endDate: r.end_date ? formatDateObj(r.end_date) : null,
            note: r.note,
            createdAt: r.created_at,
          })),
          averageCycleDays: prediction.averageCycleDays,
          predictedNextStart: prediction.predictedNextStart,
          partnerAverageCycleDays: partnerPrediction ? partnerPrediction.averageCycleDays : null,
          partnerPredictedNextStart: partnerPrediction ? partnerPrediction.predictedNextStart : null,
          relationshipId: relationship ? relationship.relationship_id : null,
        },
      });
    } catch (error) {
      next(error);
    }
  });

  router.post("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(Number(req.body.userId));
      const startDate = resolveDateString(req.body.startDate);
      const note = normalizeNullableText(req.body.note);

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      await pool.execute(
        `UPDATE period_records SET end_date = ?, updated_at = NOW() WHERE user_id = ? AND end_date IS NULL`,
        [startDate, userId]
      );

      const [result] = await pool.execute(
        `INSERT INTO period_records (user_id, relationship_id, start_date, end_date, note, created_at)
         VALUES (?, ?, ?, NULL, ?, NOW())`,
        [userId, relationshipId, startDate, note]
      );

      res.status(201).json({ ok: true, data: { id: result.insertId } });
    } catch (error) {
      next(error);
    }
  });

  router.put("/:id", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(Number(req.body.userId));
      const recordId = parseRequiredInteger(Number(req.params.id));
      const startDate = req.body.startDate ? resolveDateString(req.body.startDate) : null;
      const endDate = req.body.endDate ? resolveDateString(req.body.endDate) : null;
      const note = normalizeNullableText(req.body.note);

      const [rows] = await pool.execute(
        `SELECT id, user_id FROM period_records WHERE id = ? LIMIT 1`,
        [recordId]
      );
      if (!rows.length) throw new ApiError(404, "NOT_FOUND", "记录不存在");
      if (rows[0].user_id !== userId) throw new ApiError(403, "FORBIDDEN", "只能修改自己的记录");

      const sets = [];
      const params = [];
      if (startDate !== null) { sets.push("start_date = ?"); params.push(startDate); }
      if (endDate !== null) { sets.push("end_date = ?"); params.push(endDate); }
      if (note !== null) { sets.push("note = ?"); params.push(note); }

      if (sets.length > 0) {
        sets.push("updated_at = NOW()");
        params.push(recordId);
        await pool.execute(
          `UPDATE period_records SET ${sets.join(", ")} WHERE id = ?`,
          params
        );
      }

      res.json({ ok: true, message: "更新成功" });
    } catch (error) {
      next(error);
    }
  });

  router.delete("/:id", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(Number(req.query.userId));
      const recordId = parseRequiredInteger(Number(req.params.id));

      const [rows] = await pool.execute(
        `SELECT id, user_id FROM period_records WHERE id = ? LIMIT 1`,
        [recordId]
      );
      if (!rows.length) throw new ApiError(404, "NOT_FOUND", "记录不存在");
      if (rows[0].user_id !== userId) throw new ApiError(403, "FORBIDDEN", "只能删除自己的记录");

      await pool.execute(`DELETE FROM period_records WHERE id = ?`, [recordId]);
      res.json({ ok: true, message: "删除成功" });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createPeriodRouter };
