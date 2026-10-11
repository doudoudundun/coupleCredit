const crypto = require("crypto");

// 活动中心事件写入（F4）。只在与主业务同一事务/成功路径内调用；
// 只存最小上下文（金额分 + 截断标题快照），不落正文、图片或任何 token。
const TITLE_SNAPSHOT_MAX = 64;
const EVENT_TYPES = new Set([
  "bill.created", "bill.deleted",
  "goal.entry", "goal.reversed",
  "plan.entry", "housework.recorded", "recurring.confirmed",
  "habit.checkin"
]);

/** 按 VARCHAR(64) 字符数截断（按 Unicode 码位切，不劈开代理对）。 */
function truncateSnapshot(value) {
  if (value === null || value === undefined) return null;
  const chars = Array.from(String(value));
  return chars.length <= TITLE_SNAPSHOT_MAX ? chars.join("") : chars.slice(0, TITLE_SNAPSHOT_MAX).join("");
}

/**
 * 写入一条动态。UNIQUE(recipient_id, event_type, resource_id) 是消费去重键：
 * 同键重放走 ON DUPLICATE 把已 seen 的事件重新置 unread（对方应当再次被提醒）。
 * recipient 与 actor 相同（理论不可能）时静默跳过。
 */
async function enqueueActivityEvent(conn, {
  relationshipId, actorId, recipientId, eventType, resourceId,
  amountCents = null, titleSnapshot = null
}) {
  if (!EVENT_TYPES.has(eventType)) throw new Error(`UNKNOWN_ACTIVITY_EVENT_TYPE: ${eventType}`);
  if (!relationshipId || !recipientId || !actorId) return;
  if (Number(recipientId) === Number(actorId)) return;
  await conn.execute(
    `INSERT INTO activity_events
       (event_id, relationship_id, recipient_id, actor_id, event_type, resource_id, amount_cents, title_snapshot)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
     ON DUPLICATE KEY UPDATE event_id = event_id, state = 'unread'`,
    [crypto.randomUUID(), relationshipId, recipientId, actorId, eventType,
      String(resourceId), amountCents, truncateSnapshot(titleSnapshot)]
  );
}

/** 撤销动态：field ∈ relationship_id | resource_id；已 revoked 的不再重复更新。 */
async function revokeActivityEvents(conn, field, value) {
  if (!["relationship_id", "resource_id"].includes(field)) throw new Error("INVALID_UPDATE_SCOPE");
  await conn.execute(
    "UPDATE activity_events SET state = 'revoked' WHERE " + field + " = ? AND state <> 'revoked'",
    [value]
  );
}

/**
 * 解析关系内另一半（收件人）。要求关系仍 active；关系不存在/已解绑返回 null，
 * 调用方据此静默跳过（活动是附属能力，绝不影响主流程）。
 */
async function resolveCouplePartner(conn, relationshipId, actorId) {
  const [rows] = await conn.execute(
    "SELECT user_id_1, user_id_2 FROM couple_relationships WHERE relationship_id = ? AND status = 'active' LIMIT 1",
    [relationshipId]
  );
  const relationship = rows[0];
  if (!relationship) return null;
  return Number(relationship.user_id_1) === Number(actorId)
    ? Number(relationship.user_id_2)
    : Number(relationship.user_id_1);
}

module.exports = { enqueueActivityEvent, revokeActivityEvents, resolveCouplePartner, truncateSnapshot };
