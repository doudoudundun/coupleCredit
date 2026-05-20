const fcmService = require("./fcmService");

async function getTokensForUsers(pool, userIds) {
  if (!userIds || userIds.length === 0) return [];
  const uniqueIds = [...new Set(userIds)];
  const placeholders = uniqueIds.map(() => "?").join(",");
  const [rows] = await pool.execute(
    `SELECT user_id, token, channel FROM push_tokens WHERE user_id IN (${placeholders})`,
    uniqueIds
  );
  return rows.filter(r => r.channel === "fcm").map(r => r.token);
}

async function cleanupInvalidTokens(pool, invalidTokens) {
  if (!invalidTokens || invalidTokens.length === 0) return;
  const placeholders = invalidTokens.map(() => "?").join(",");
  await pool.execute(`DELETE FROM push_tokens WHERE token IN (${placeholders})`, invalidTokens);
}

async function pushTokens(tokens, notification) {
  if (tokens.length === 0) return [];
  return await fcmService.sendMulticast(tokens, notification);
}

module.exports = { getTokensForUsers, cleanupInvalidTokens, pushTokens };
