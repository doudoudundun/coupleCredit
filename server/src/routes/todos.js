const express = require("express");
const { ApiError } = require("../errors");
const { cache, Keys, TTL } = require("../cache");
const { loadActiveRelationship, trimValue, parseRequiredInteger } = require("../utils/queryHelpers");

const TODO_SELECT_FIELDS = `todo_id, user_id, relationship_id, title, content, priority, fuzzy_date_text, image_url, status,
                 is_repeatable, series_id, completed_count, created_at, updated_at`;

function normalizeNullableText(value) {
  if (value === undefined || value === null) return null;
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed === "" ? null : trimmed;
}

function normalizePriority(value) {
  if (value === undefined) return undefined;
  if (value === "high" || value === "medium" || value === "low") {
    return value;
  }
  throw new ApiError(400, "INVALID_REQUEST", "优先级不合法");
}

function normalizeStatus(value) {
  if (value === undefined) return undefined;
  if (value === "open" || value === "done" || value === "missed") return value;
  throw new ApiError(400, "INVALID_REQUEST", "状态不合法");
}

function normalizeRepeatable(value) {
  if (value === undefined) return undefined;
  if (value === true || value === false) return value;
  if (value === 1 || value === 0) return value === 1;
  if (value === "1" || value === "0") return value === "1";
  throw new ApiError(400, "INVALID_REQUEST", "重复任务标记不合法");
}

function normalizeSeriesId(value) {
  if (value === undefined) return undefined;
  if (value === null || value === "") return null;
  if (typeof value === "string") {
    const trimmed = value.trim();
    if (trimmed === "") return null;
    return parseRequiredInteger(Number.parseInt(trimmed, 10));
  }
  return parseRequiredInteger(value);
}

function normalizeCompletedCount(value) {
  if (value === undefined) return undefined;
  if (value === null || value === "") return 0;
  if (typeof value === "string") {
    const trimmed = value.trim();
    if (trimmed === "") return 0;
    return parseRequiredInteger(Number.parseInt(trimmed, 10));
  }
  return parseRequiredInteger(value);
}

function normalizeRepeatFields(isRepeatable, seriesId, completedCount) {
  if (!isRepeatable) {
    return {
      isRepeatable: false,
      seriesId: null,
      completedCount: 0
    };
  }
  return {
    isRepeatable: true,
    seriesId: seriesId == null ? null : seriesId,
    completedCount: completedCount == null ? 0 : completedCount
  };
}

function resolveCompletedCountForUpdate(todo, nextStatus, repeatFields) {
  if (!repeatFields.isRepeatable) {
    return repeatFields.completedCount;
  }
  if (todo.status === "open" && nextStatus === "done") {
    return (todo.completed_count || 0) + 1;
  }
  return repeatFields.completedCount;
}

function mapTodo(row) {
  return {
    todoId: row.todo_id,
    userId: row.user_id,
    relationshipId: row.relationship_id,
    title: row.title,
    content: row.content,
    priority: row.priority,
    fuzzyDateText: row.fuzzy_date_text,
    imageUrl: row.image_url,
    status: row.status,
    isRepeatable: !!row.is_repeatable,
    seriesId: row.series_id,
    completedCount: row.completed_count || 0,
    createdAt: row.created_at,
    updatedAt: row.updated_at
  };
}

function createTodoRouter({ pool }) {
  const router = express.Router();

  function invalidateTodoCache(userId, relationship) {
    cache.del(Keys.todos(userId));
    if (relationship) {
      cache.del(Keys.todos(relationship.user_id_1));
      cache.del(Keys.todos(relationship.user_id_2));
    }
  }

  async function loadAccessibleTodo(todoId, userId) {
    const relationship = await loadActiveRelationship(pool, userId);
    const relationshipId = relationship ? relationship.relationship_id : null;

    let sql = `SELECT ${TODO_SELECT_FIELDS}
               FROM todo_items
               WHERE todo_id = ? AND (user_id = ?`;
    const params = [todoId, userId];
    if (relationshipId) {
      sql += " OR relationship_id = ?";
      params.push(relationshipId);
    }
    sql += ") LIMIT 1";

    const [rows] = await pool.execute(sql, params);
    return { todo: rows[0] || null, relationship };
  }

  router.get("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(parseInt(req.query.userId, 10));
      const cached = cache.get(Keys.todos(userId));
      if (cached) return res.json(cached);

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;

      let sql = `SELECT ${TODO_SELECT_FIELDS}
                 FROM todo_items
                 WHERE user_id = ? AND relationship_id IS NULL`;
      const params = [userId];
      if (relationshipId) {
        sql = `SELECT ${TODO_SELECT_FIELDS}
               FROM todo_items
               WHERE relationship_id = ? OR (user_id = ? AND relationship_id IS NULL)`;
        params.unshift(relationshipId);
      }
      sql += ` ORDER BY CASE status WHEN 'open' THEN 0 WHEN 'missed' THEN 1 ELSE 2 END ASC,
                      FIELD(priority, 'high', 'medium', 'low') ASC,
                      updated_at DESC,
                      todo_id DESC`;

      const [rows] = await pool.execute(sql, params);
      const response = { ok: true, data: { items: rows.map(mapTodo), relationshipId } };
      cache.set(Keys.todos(userId), response, TTL.TODOS);
      res.json(response);
    } catch (error) {
      next(error);
    }
  });

  router.post("/", async (req, res, next) => {
    try {
      const userId = parseRequiredInteger(req.body.userId);
      const title = trimValue(req.body.title);
      if (!title) throw new ApiError(400, "INVALID_REQUEST", "标题不能为空");

      const relationship = await loadActiveRelationship(pool, userId);
      const relationshipId = relationship ? relationship.relationship_id : null;
      const repeatFields = normalizeRepeatFields(
        normalizeRepeatable(req.body.isRepeatable) || false,
        normalizeSeriesId(req.body.seriesId),
        normalizeCompletedCount(req.body.completedCount)
      );

      const [result] = await pool.execute(
        `INSERT INTO todo_items (user_id, relationship_id, title, content, priority, fuzzy_date_text, image_url, status, is_repeatable, series_id, completed_count)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        [
          userId,
          relationshipId,
          title,
          normalizeNullableText(req.body.content),
          normalizePriority(req.body.priority) || "medium",
          normalizeNullableText(req.body.fuzzyDateText),
          normalizeNullableText(req.body.imageUrl),
          normalizeStatus(req.body.status) || "open",
          repeatFields.isRepeatable ? 1 : 0,
          repeatFields.seriesId,
          repeatFields.completedCount
        ]
      );

      if (repeatFields.isRepeatable && !repeatFields.seriesId) {
        await pool.execute("UPDATE todo_items SET series_id = ? WHERE todo_id = ?", [result.insertId, result.insertId]);
      }

      invalidateTodoCache(userId, relationship);
      res.status(201).json({ ok: true, data: { todoId: result.insertId } });
    } catch (error) {
      next(error);
    }
  });

  router.put("/:id", async (req, res, next) => {
    try {
      const todoId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(req.body.userId);
      const { todo, relationship } = await loadAccessibleTodo(todoId, userId);
      if (!todo) throw new ApiError(404, "NOT_FOUND", "代办不存在或无权修改");

      const updates = [];
      const params = [];

      if (req.body.title !== undefined) {
        const title = trimValue(req.body.title);
        if (!title) throw new ApiError(400, "INVALID_REQUEST", "标题不能为空");
        updates.push("title = ?");
        params.push(title);
      }
      if (req.body.content !== undefined) {
        updates.push("content = ?");
        params.push(normalizeNullableText(req.body.content));
      }
      if (req.body.priority !== undefined) {
        updates.push("priority = ?");
        params.push(normalizePriority(req.body.priority));
      }
      if (req.body.fuzzyDateText !== undefined) {
        updates.push("fuzzy_date_text = ?");
        params.push(normalizeNullableText(req.body.fuzzyDateText));
      }
      if (req.body.imageUrl !== undefined) {
        updates.push("image_url = ?");
        params.push(normalizeNullableText(req.body.imageUrl));
      }
      if (req.body.status !== undefined) {
        updates.push("status = ?");
        params.push(normalizeStatus(req.body.status));
      }

      const nextRepeatable = req.body.isRepeatable !== undefined
        ? normalizeRepeatable(req.body.isRepeatable)
        : !!todo.is_repeatable;
      const nextSeriesId = req.body.seriesId !== undefined
        ? normalizeSeriesId(req.body.seriesId)
        : todo.series_id;
      const nextCompletedCount = req.body.completedCount !== undefined
        ? normalizeCompletedCount(req.body.completedCount)
        : todo.completed_count;
      const repeatFieldsProvided = req.body.isRepeatable !== undefined || req.body.seriesId !== undefined || req.body.completedCount !== undefined;
      if (repeatFieldsProvided) {
        const repeatFields = normalizeRepeatFields(nextRepeatable, nextSeriesId, nextCompletedCount);
        const nextStatus = req.body.status !== undefined ? normalizeStatus(req.body.status) : todo.status;
        const resolvedCompletedCount = resolveCompletedCountForUpdate(todo, nextStatus, repeatFields);
        updates.push("is_repeatable = ?");
        params.push(repeatFields.isRepeatable ? 1 : 0);
        updates.push("series_id = ?");
        params.push(repeatFields.seriesId);
        updates.push("completed_count = ?");
        params.push(resolvedCompletedCount);
      }

      if (updates.length === 0) throw new ApiError(400, "INVALID_REQUEST", "无更新内容");

      params.push(todoId);
      await pool.execute(`UPDATE todo_items SET ${updates.join(", ")} WHERE todo_id = ?`, params);

      invalidateTodoCache(userId, relationship);
      res.json({ ok: true, message: "代办更新成功" });
    } catch (error) {
      next(error);
    }
  });

  router.post("/:id/duplicate", async (req, res, next) => {
    try {
      const todoId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(req.body.userId);
      const { todo, relationship } = await loadAccessibleTodo(todoId, userId);
      if (!todo) throw new ApiError(404, "NOT_FOUND", "代办不存在或无权访问");
      if (todo.status !== "done") throw new ApiError(400, "INVALID_REQUEST", "仅已完成的重复任务可再来一次");
      if (!todo.is_repeatable) throw new ApiError(400, "INVALID_REQUEST", "当前任务不是重复任务");

      const [result] = await pool.execute(
        `INSERT INTO todo_items (user_id, relationship_id, title, content, priority, fuzzy_date_text, image_url, status, is_repeatable, series_id, completed_count)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        [
          todo.user_id,
          todo.relationship_id,
          todo.title,
          todo.content,
          todo.priority,
          todo.fuzzy_date_text,
          todo.image_url,
          "open",
          todo.is_repeatable ? 1 : 0,
          todo.series_id,
          todo.completed_count || 0
        ]
      );

      const [rows] = await pool.execute(`SELECT ${TODO_SELECT_FIELDS} FROM todo_items WHERE todo_id = ? LIMIT 1`, [result.insertId]);
      invalidateTodoCache(userId, relationship);
      res.json({ ok: true, data: mapTodo(rows[0]) });
    } catch (error) {
      next(error);
    }
  });

  router.delete("/:id", async (req, res, next) => {
    try {
      const todoId = parseRequiredInteger(parseInt(req.params.id, 10));
      const userId = parseRequiredInteger(parseInt(req.query.userId, 10));
      const { todo, relationship } = await loadAccessibleTodo(todoId, userId);
      if (!todo) throw new ApiError(404, "NOT_FOUND", "代办不存在或无权删除");

      await pool.execute("DELETE FROM todo_items WHERE todo_id = ?", [todoId]);
      invalidateTodoCache(userId, relationship);
      res.json({ ok: true, message: "代办删除成功" });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createTodoRouter, mapTodo };
