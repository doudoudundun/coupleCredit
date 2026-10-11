const crypto = require("crypto");
// Called only within the same transaction as the successful post/comment/reaction.
// No body, nickname, mood, media URL or push token enters the event payload.
//
// eventType 显式传 "reaction" 时：resource_id 用 diary_id（一条帖子对同一作者
// 只有一条 reaction 事件）；重复贴/换贴走 ON DUPLICATE 把已 seen 的事件重新置为
// unread——对方应当再次被提醒。commentId 存在且未显式指定类型时仍按 "reply"。
async function enqueueDiaryUpdate(conn, { spaceId, recipientId, diaryId, commentId = null, eventType = null }) {
  if (!recipientId) return;
  const type = eventType || (commentId ? "reply" : "published");
  const resourceId = commentId || diaryId;
  const resetUnread = type === "reaction" ? ", state='unread'" : "";
  await conn.execute(
    `INSERT INTO diary_update_events (event_id,space_id,recipient_id,diary_id,comment_id,event_type,resource_id)
     VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE event_id=event_id${resetUnread}`,
    [crypto.randomUUID(),spaceId,recipientId,diaryId,commentId,type,resourceId]);
}
// eventType 可选：撤销时限定事件类型（如取消贴纸只撤 reaction，不动 published/reply）。
async function revokeDiaryUpdates(conn, field, value, { eventType = null } = {}) {
  if (!["space_id","diary_id","comment_id"].includes(field)) throw new Error("INVALID_UPDATE_SCOPE");
  await conn.execute(
    `UPDATE diary_update_events SET state='revoked' WHERE ${field}=? AND state<>'revoked'${eventType ? " AND event_type=?" : ""}`,
    eventType ? [value, eventType] : [value]);
}
module.exports={enqueueDiaryUpdate,revokeDiaryUpdates};
