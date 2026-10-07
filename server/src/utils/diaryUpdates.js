const crypto = require("crypto");
// Called only within the same transaction as the successful post/comment.
// No body, nickname, mood, media URL or push token enters the event payload.
async function enqueueDiaryUpdate(conn, { spaceId, recipientId, diaryId, commentId = null }) {
  if (!recipientId) return;
  await conn.execute(
    `INSERT INTO diary_update_events (event_id,space_id,recipient_id,diary_id,comment_id,event_type,resource_id)
     VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE event_id=event_id`,
    [crypto.randomUUID(),spaceId,recipientId,diaryId,commentId,commentId ? "reply" : "published",commentId || diaryId]);
}
async function revokeDiaryUpdates(conn, field, value) {
  if (!["space_id","diary_id","comment_id"].includes(field)) throw new Error("INVALID_UPDATE_SCOPE");
  await conn.execute(`UPDATE diary_update_events SET state='revoked' WHERE ${field}=? AND state<>'revoked'`,[value]);
}
module.exports={enqueueDiaryUpdate,revokeDiaryUpdates};
