const fcmService = require("./fcmService");
const { loadActiveRelationship } = require("../utils/queryHelpers");

async function getTokensForUsers(pool, userIds) {
  if (!userIds || userIds.length === 0) return [];
  const placeholders = userIds.map(() => "?").join(",");
  const [rows] = await pool.execute(`SELECT user_id, token FROM fcm_tokens WHERE user_id IN (${placeholders})`, userIds);
  return rows;
}

async function cleanupInvalidTokens(pool, invalidTokens) {
  if (!invalidTokens || invalidTokens.length === 0) return;
  const placeholders = invalidTokens.map(() => "?").join(",");
  await pool.execute(`DELETE FROM fcm_tokens WHERE token IN (${placeholders})`, invalidTokens);
}

async function sendTodoReminder(pool) {
  console.log("[TodoReminder] Starting daily high-priority todo check...");

  const [todos] = await pool.execute(
    `SELECT todo_id, user_id, relationship_id, title FROM todo_items WHERE priority = 'high' AND status = 'open'`
  );

  if (todos.length === 0) {
    console.log("[TodoReminder] No high-priority open todos found.");
    return;
  }

  console.log(`[TodoReminder] Found ${todos.length} high-priority open todo(s).`);

  const allInvalidTokens = [];

  for (const todo of todos) {
    const recipientIds = new Set([todo.user_id]);

    if (todo.relationship_id) {
      try {
        const rel = await loadActiveRelationship(pool, todo.user_id);
        if (rel) {
          recipientIds.add(rel.user_id_1);
          recipientIds.add(rel.user_id_2);
        }
      } catch (_) {}
    }

    const userIds = [...recipientIds];

    for (const uid of userIds) {
      await pool.execute(
        "INSERT INTO notifications (user_id, type, title, body, related_id) VALUES (?, 'todo_reminder', ?, ?, ?)",
        [uid, "高优先级待办提醒", todo.title, todo.todo_id]
      );
    }

    const tokenRows = await getTokensForUsers(pool, userIds);
    if (tokenRows.length > 0) {
      const tokens = tokenRows.map(r => r.token);
      const invalid = await fcmService.sendMulticast(tokens, { title: "高优先级待办提醒", body: todo.title });
      allInvalidTokens.push(...invalid);
    }
  }

  await cleanupInvalidTokens(pool, allInvalidTokens);
  console.log("[TodoReminder] Done.");
}

async function sendPartnerReminder(pool, todoId, senderUserId) {
  const [todoRows] = await pool.execute(
    "SELECT todo_id, user_id, relationship_id, title, status FROM todo_items WHERE todo_id = ?",
    [todoId]
  );
  if (todoRows.length === 0) throw Object.assign(new Error("待办不存在"), { status: 404, code: "NOT_FOUND" });

  const todo = todoRows[0];

  const rel = await loadActiveRelationship(pool, senderUserId);
  if (!rel) throw Object.assign(new Error("没有情侣关系"), { status: 400, code: "INVALID_REQUEST" });

  const partnerId = rel.user_id_1 === senderUserId ? rel.user_id_2 : rel.user_id_1;

  // 通知伴侣
  await pool.execute(
    "INSERT INTO notifications (user_id, type, title, body, related_id) VALUES (?, 'partner_nudge', ?, ?, ?)",
    [partnerId, "伴侣提醒你完成待办", todo.title, todo.todo_id]
  );

  // 同时给自己一条确认通知
  await pool.execute(
    "INSERT INTO notifications (user_id, type, title, body, related_id) VALUES (?, 'system', ?, ?, ?)",
    [senderUserId, "提醒已发送", "已提醒伴侣完成「" + todo.title + "」", todo.todo_id]
  );

  // 向双方推送 FCM
  const recipientIds = [partnerId, senderUserId];
  const tokenRows = await getTokensForUsers(pool, recipientIds);
  let invalidTokens = [];
  if (tokenRows.length > 0) {
    const tokens = tokenRows.map(r => r.token);
    invalidTokens = await fcmService.sendMulticast(tokens, { title: "伴侣提醒你完成待办", body: todo.title });
  }

  await cleanupInvalidTokens(pool, invalidTokens);
  return { partnerId, todoTitle: todo.title };
}

module.exports = { sendTodoReminder, sendPartnerReminder };
