const fcmService = require("./fcmService");
const jpushService = require("./jpushService");
const { loadActiveRelationship } = require("../utils/queryHelpers");

async function getTokensForUsers(pool, userIds) {
  if (!userIds || userIds.length === 0) return { fcm: [], jpush: [] };
  const uniqueIds = [...new Set(userIds)];
  const placeholders = uniqueIds.map(() => "?").join(",");
  const [rows] = await pool.execute(
    `SELECT user_id, token, channel FROM push_tokens WHERE user_id IN (${placeholders})`,
    uniqueIds
  );
  const fcm = rows.filter(r => r.channel === "fcm").map(r => r.token);
  const jpush = rows.filter(r => r.channel === "jpush").map(r => r.token);
  return { fcm, jpush };
}

async function cleanupInvalidTokens(pool, invalidTokens) {
  if (!invalidTokens || invalidTokens.length === 0) return;
  const placeholders = invalidTokens.map(() => "?").join(",");
  await pool.execute(`DELETE FROM push_tokens WHERE token IN (${placeholders})`, invalidTokens);
}

async function pushTokens({ fcm, jpush }, notification) {
  const allInvalid = [];

  if (jpush.length > 0) {
    const invalid = await jpushService.pushByRegIds(jpush, notification);
    allInvalid.push(...invalid);
  }

  if (jpush.length === 0 && fcm.length > 0) {
    const invalid = await fcmService.sendMulticast(fcm, notification);
    allInvalid.push(...invalid);
  }

  return allInvalid;
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

  const allUserIds = new Set();
  for (const todo of todos) {
    allUserIds.add(todo.user_id);
    if (todo.relationship_id) {
      try {
        const rel = await loadActiveRelationship(pool, todo.user_id);
        if (rel) {
          allUserIds.add(rel.user_id_1);
          allUserIds.add(rel.user_id_2);
        }
      } catch (_) {}
    }
  }

  const tokens = await getTokensForUsers(pool, [...allUserIds]);
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

    const invalid = await pushTokens(tokens, { title: "高优先级待办提醒", body: todo.title });
    allInvalidTokens.push(...invalid);
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

  await pool.execute(
    "INSERT INTO notifications (user_id, type, title, body, related_id) VALUES (?, 'partner_nudge', ?, ?, ?)",
    [partnerId, "伴侣提醒你完成待办", todo.title, todo.todo_id]
  );

  await pool.execute(
    "INSERT INTO notifications (user_id, type, title, body, related_id) VALUES (?, 'system', ?, ?, ?)",
    [senderUserId, "提醒已发送", "已提醒伴侣完成「" + todo.title + "」", todo.todo_id]
  );

  const recipientIds = [partnerId, senderUserId];
  const tokens = await getTokensForUsers(pool, recipientIds);
  const invalidTokens = await pushTokens(tokens, { title: "伴侣提醒你完成待办", body: todo.title });
  await cleanupInvalidTokens(pool, invalidTokens);

  return { partnerId, todoTitle: todo.title };
}

module.exports = { sendTodoReminder, sendPartnerReminder };
