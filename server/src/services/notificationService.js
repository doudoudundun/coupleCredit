const fcmService = require("./fcmService");
const jpushService = require("./jpushService");

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

  if (fcm.length > 0) {
    const invalid = await fcmService.sendMulticast(fcm, notification);
    allInvalid.push(...invalid);
  }

  return allInvalid;
}

module.exports = { getTokensForUsers, cleanupInvalidTokens, pushTokens };
