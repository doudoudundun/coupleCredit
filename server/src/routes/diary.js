// 共同小记（v0.2）：时间线、两层评论、空间背景、本人归档
//
// 权威口径：
//   * 「空间」复用 housework 生命周期：解绑后空间关闭（status='closed'），小记停止互看，
//     作者本人按私有归档读取（GET /me/diary-archives）；
//   * 媒体内容走 mediaToken 短时效签名 URL（auth_version 变更即失效，删除帖子时递增）；
//   * 帖子删除为软删（30 天回收期，仅作者可恢复，恢复前校验原关系与空间重新 active）；
//   * 评论仅两层：主评论 + 挂根平铺回复；删主评论有可见回复时保留无正文占位；
//   * 评论计数以 diary_posts.comment_count 为权威（仅统计可见未删除）。
const express = require("express");
const crypto = require("crypto");
const { ApiError } = require("../errors");
const { withTransaction } = require("../utils/transactions");
const {
  loadCoupleSpaceContext,
  todayInTimezone,
  parseDateOnly
} = require("../utils/spaceContext");
const { requireIdempotencyKey, withIdempotency } = require("../utils/idempotency");
const { buildMediaUrl } = require("../utils/mediaToken");

const MOOD_CODES = new Set(["happy", "calm", "moved", "tired", "sad", "complex"]);
const TEMPLATE_IDS = new Set(["sunset", "breeze", "mist"]);

const TIMELINE_DEFAULT_LIMIT = 20;
const TIMELINE_MAX_LIMIT = 20;
const MAIN_COMMENT_DEFAULT_LIMIT = 20;
const REPLY_DEFAULT_LIMIT = 50;
const COMMENT_MAX_LIMIT = 100;

const DIARY_BODY_MAX = 5000;
const MOOD_TEXT_MAX = 20;
const COMMENT_BODY_MAX = 500;
const MEDIA_MAX_COUNT = 9;
const REPLY_PREVIEW_COUNT = 3;
const RESTORE_WINDOW_MS = 30 * 24 * 60 * 60 * 1000;

const POST_FIELDS = `
  p.diary_id, p.space_id, p.relationship_id, p.author_id, p.body,
  DATE_FORMAT(p.occurred_on, '%Y-%m-%d') AS occurred_on,
  p.mood_code, p.mood_text, p.status, p.edited, p.comment_count, p.version,
  p.created_at, p.updated_at, p.deleted_at`;

const COMMENT_FIELDS = `
  c.comment_id, c.diary_id, c.space_id, c.author_id, c.body, c.root_comment_id,
  c.reply_to_comment_id, c.reply_to_user_id, c.status, c.version, c.created_at, c.updated_at`;

// ---------- 校验 / 游标工具 ----------

function invalidField(field, message) {
  return new ApiError(422, "VALIDATION_FAILED", "字段校验失败", {
    fieldErrors: { [field]: message }
  });
}

function versionConflict(currentVersion) {
  return new ApiError(409, "VERSION_CONFLICT", "数据已被修改，请刷新后重试", {
    currentVersion: Number(currentVersion)
  });
}

function diaryNotFound() {
  return new ApiError(404, "NOT_FOUND", "小记不存在或不可见");
}

function unicodeLength(value) {
  return [...value].length;
}

function parseLimitParam(raw, { def, max } = {}) {
  if (raw === undefined || raw === null || raw === "") return def;
  const value = Number(raw);
  if (!Number.isInteger(value) || value < 1) {
    throw invalidField("limit", "应为正整数");
  }
  return Math.min(value, max);
}

function encodeCursor(tuple) {
  return Buffer.from(JSON.stringify(tuple), "utf8").toString("base64url");
}

function decodeCursor(raw, validate) {
  let value;
  try {
    value = JSON.parse(Buffer.from(String(raw), "base64url").toString("utf8"));
  } catch (_error) {
    throw invalidField("cursor", "cursor 无效");
  }
  if (!validate(value)) throw invalidField("cursor", "cursor 无效");
  return value;
}

// 时间线游标：[occurred_on, created_at_ms, diary_id]
function decodeTimelineCursor(raw) {
  return decodeCursor(raw, (value) => Array.isArray(value) && value.length === 3
    && typeof value[0] === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value[0])
    && Number.isSafeInteger(value[1])
    && typeof value[2] === "string" && value[2].length > 0);
}

// 评论游标：[created_at_ms, comment_id]
function decodeCommentCursor(raw) {
  return decodeCursor(raw, (value) => Array.isArray(value) && value.length === 2
    && Number.isSafeInteger(value[0])
    && typeof value[1] === "string" && value[1].length > 0);
}

// DATETIME → 毫秒时间戳（mysql2 默认返回 Date；兜底 dateStrings 字面量按连接时区 +08:00 解释）
function dateTimeToMs(value) {
  if (value instanceof Date) return value.getTime();
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string") {
    const text = value.replace(" ", "T");
    const withZone = /Z$|[+-]\d{2}:?\d{2}$/.test(text) ? text : `${text}+08:00`;
    const ms = Date.parse(withZone);
    return Number.isNaN(ms) ? 0 : ms;
  }
  return 0;
}

function timelineCursorCondition(where, params, cursor) {
  const [occurredOn, createdAtMs, diaryId] = decodeTimelineCursor(cursor);
  const createdAt = new Date(createdAtMs);
  where.push(
    "(p.occurred_on < ? OR (p.occurred_on = ? AND (p.created_at < ? OR (p.created_at = ? AND p.diary_id < ?))))"
  );
  params.push(occurredOn, occurredOn, createdAt, createdAt, diaryId);
}

function ensureUserId(userId) {
  if (!userId || userId <= 0) throw new ApiError(400, "INVALID_REQUEST", "userId 参数无效");
  return userId;
}

// ---------- 序列化 ----------

// 作者信息批量加载：先带 avatar_status 查，列缺失（迁移 028 未跑）时回退不带并视为 approved。
async function loadAuthors(executor, ids) {
  const unique = [...new Set((ids || []).map(Number).filter((id) => Number.isInteger(id) && id > 0))];
  const authors = new Map();
  if (unique.length === 0) return authors;
  const placeholders = unique.map(() => "?").join(", ");
  let rows;
  try {
    [rows] = await executor.execute(
      `SELECT id, username, nickname, avatar, avatar_status FROM users WHERE id IN (${placeholders})`,
      unique
    );
  } catch (error) {
    if (error.code !== "ER_BAD_FIELD_ERROR" && !/avatar_status/i.test(error.message || "")) throw error;
    [rows] = await executor.execute(
      `SELECT id, username, nickname, avatar FROM users WHERE id IN (${placeholders})`,
      unique
    );
    rows = rows.map((row) => Object.assign({}, row, { avatar_status: "approved" }));
  }
  for (const row of rows) {
    authors.set(Number(row.id), {
      id: Number(row.id),
      nickname: row.nickname || row.username,
      avatarUrl: row.avatar_status === "approved" ? (row.avatar || null) : null
    });
  }
  return authors;
}

function unknownAuthor(authorId) {
  return { id: Number(authorId), nickname: "已注销用户", avatarUrl: null };
}

// 帖子的 ready 媒体（按 sort_order 升序；ownerId 提供时只取本人图片 = 归档口径）
async function loadReadyMedia(executor, diaryIds, ownerId = null) {
  const mediaByPost = new Map();
  if (!diaryIds || diaryIds.length === 0) return mediaByPost;
  const placeholders = diaryIds.map(() => "?").join(", ");
  let sql = `SELECT media_id, diary_id, owner_id, status, width, height, sort_order, auth_version
               FROM diary_media WHERE diary_id IN (${placeholders}) AND status = 'ready'`;
  const params = [...diaryIds];
  if (ownerId != null) {
    sql += " AND owner_id = ?";
    params.push(ownerId);
  }
  sql += " ORDER BY sort_order ASC, media_id ASC";
  const [rows] = await executor.execute(sql, params);
  for (const row of rows) {
    const list = mediaByPost.get(row.diary_id) || [];
    list.push(row);
    mediaByPost.set(row.diary_id, list);
  }
  return mediaByPost;
}

function serializeMediaRef(secret, media) {
  return {
    mediaId: media.media_id,
    id: media.media_id,
    url: buildMediaUrl({ secret, mediaId: media.media_id, variant: "full", authVersion: Number(media.auth_version) }),
    thumbnailUrl: buildMediaUrl({ secret, mediaId: media.media_id, variant: "thumb", authVersion: Number(media.auth_version) }),
    width: media.width != null ? Number(media.width) : null,
    height: media.height != null ? Number(media.height) : null
  };
}

function serializeDiarySummary(secret, post, mediaRows, authors) {
  return {
    id: post.diary_id,
    author: authors.get(Number(post.author_id)) || unknownAuthor(post.author_id),
    body: post.body || "",
    occurredOn: post.occurred_on,
    moodCode: post.mood_code != null ? post.mood_code : null,
    moodText: post.mood_text != null ? post.mood_text : null,
    media: (mediaRows || []).map((media) => serializeMediaRef(secret, media)),
    commentCount: Number(post.comment_count || 0),
    createdAt: post.created_at,
    updatedAt: post.updated_at,
    edited: !!Number(post.edited),
    version: Number(post.version)
  };
}

function serializeComment(comment, authors) {
  const authorId = Number(comment.author_id);
  const replyToUserId = comment.reply_to_user_id != null ? Number(comment.reply_to_user_id) : null;
  return {
    id: comment.comment_id,
    commentId: comment.comment_id,
    diaryId: comment.diary_id,
    authorId,
    author: authors.get(authorId) || unknownAuthor(authorId),
    body: comment.status === "deleted" ? "" : (comment.body || ""),
    status: comment.status,
    deleted: comment.status === "deleted",
    rootCommentId: comment.root_comment_id != null ? comment.root_comment_id : null,
    replyToCommentId: comment.reply_to_comment_id != null ? comment.reply_to_comment_id : null,
    replyToUserId,
    replyTo: replyToUserId != null
      ? { nickname: (authors.get(replyToUserId) || unknownAuthor(replyToUserId)).nickname }
      : null,
    createdAt: comment.created_at,
    updatedAt: comment.updated_at,
    version: Number(comment.version)
  };
}

// ---------- 帖子读取 ----------

async function loadPostRow(executor, diaryId, { forUpdate = false } = {}) {
  const [rows] = await executor.execute(
    `SELECT ${POST_FIELDS} FROM diary_posts p WHERE p.diary_id = ? LIMIT 1${forUpdate ? " FOR UPDATE" : ""}`,
    [diaryId]
  );
  return rows[0] || null;
}

// 读路径可见性：visible 且（空间 active 且本人是成员 或 本人 = 作者）；归档口径只有作者可读。
async function loadPostForViewer(executor, userId, diaryId) {
  const [rows] = await executor.execute(
    `SELECT ${POST_FIELDS}, s.status AS space_status,
            (SELECT COUNT(*) FROM couple_relationships r
              WHERE r.relationship_id = p.relationship_id AND (r.user_id_1 = ? OR r.user_id_2 = ?)) AS member_flag
       FROM diary_posts p
       LEFT JOIN housework_spaces s ON s.space_id = p.space_id
      WHERE p.diary_id = ?
      LIMIT 1`,
    [userId, userId, diaryId]
  );
  const post = rows[0] || null;
  if (!post) return null;
  post.isAuthor = Number(post.author_id) === Number(userId);
  post.spaceActive = post.space_status === "active" && Number(post.member_flag) > 0;
  post.visible = post.status === "visible" && (post.spaceActive || post.isAuthor);
  return post;
}

// 写路径上下文：按 锁空间行 → 锁帖子行 的顺序，返回带可见性标记的帖子。
async function lockPostForWrite(conn, userId, diaryId) {
  const preview = await loadPostRow(conn, diaryId);
  if (!preview) return null;
  const [spaceRows] = await conn.execute(
    "SELECT space_id, status, timezone FROM housework_spaces WHERE space_id = ? LIMIT 1 FOR UPDATE",
    [preview.space_id]
  );
  const [relRows] = await conn.execute(
    "SELECT relationship_id FROM couple_relationships WHERE relationship_id = ? AND status = 'active' AND (user_id_1 = ? OR user_id_2 = ?) LIMIT 1",
    [preview.relationship_id, userId, userId]
  );
  const post = await loadPostRow(conn, diaryId, { forUpdate: true });
  if (!post) return null;
  const space = spaceRows[0] || null;
  post.isAuthor = Number(post.author_id) === Number(userId);
  post.spaceActive = Boolean(space) && space.status === "active" && relRows.length > 0;
  post.spaceTimezone = (space && space.timezone) || "Asia/Shanghai";
  post.visible = post.status === "visible" && (post.spaceActive || post.isAuthor);
  return post;
}

async function serializePost(executor, secret, diaryId, ownerId = null) {
  const post = await loadPostRow(executor, diaryId);
  if (!post) throw diaryNotFound();
  const mediaByPost = await loadReadyMedia(executor, [diaryId], ownerId);
  const authors = await loadAuthors(executor, [post.author_id]);
  return serializeDiarySummary(secret, post, mediaByPost.get(diaryId) || [], authors);
}

// ---------- 字段校验 ----------

function validateDiaryBody(value) {
  const text = typeof value === "string" ? value : "";
  if (typeof value !== "string" && value !== undefined && value !== null) {
    throw invalidField("body", "正文应为字符串");
  }
  if (unicodeLength(text) > DIARY_BODY_MAX) {
    throw invalidField("body", `正文不能超过 ${DIARY_BODY_MAX} 字`);
  }
  return text;
}

function validateMoodCode(value) {
  if (value === null) return null;
  if (typeof value !== "string" || !MOOD_CODES.has(value)) {
    throw invalidField("moodCode", "心情编码无效");
  }
  return value;
}

function validateMoodText(value) {
  if (value === null) return null;
  if (typeof value !== "string" || unicodeLength(value) > MOOD_TEXT_MAX) {
    throw invalidField("moodText", `心情文字不能超过 ${MOOD_TEXT_MAX} 字`);
  }
  return value;
}

function validateMediaIds(value) {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value)) throw invalidField("mediaIds", "mediaIds 应为数组");
  if (value.length > MEDIA_MAX_COUNT) {
    throw invalidField("mediaIds", `图片数量不能超过 ${MEDIA_MAX_COUNT} 张`);
  }
  for (const mediaId of value) {
    if (typeof mediaId !== "string" || !mediaId.trim()) {
      throw invalidField("mediaIds", "mediaId 无效");
    }
  }
  return [...new Set(value)];
}

function validateCommentBody(value) {
  const text = typeof value === "string" ? value.trim() : "";
  if (!text) throw invalidField("body", "评论内容不能为空");
  if (unicodeLength(text) > COMMENT_BODY_MAX) {
    throw invalidField("body", `评论内容不能超过 ${COMMENT_BODY_MAX} 字`);
  }
  return text;
}

function parseExpectedVersion(value) {
  if (value === undefined || value === null || value === "") {
    throw invalidField("expectedVersion", "缺少版本号");
  }
  const version = Number(value);
  if (!Number.isInteger(version) || version < 0) {
    throw invalidField("expectedVersion", "版本号无效");
  }
  return version;
}

function optionalExpectedVersion(value) {
  if (value === undefined || value === null || value === "") return null;
  const version = Number(value);
  if (!Number.isInteger(version) || version < 0) {
    throw invalidField("expectedVersion", "版本号无效");
  }
  return version;
}

function optionalIdempotencyKey(value) {
  if (value === undefined || value === null || value === "") return null;
  return requireIdempotencyKey(value);
}

// 发布/编辑共用的媒体可用性校验：存在、本人上传、用途匹配、已就绪、（未绑定 或 已绑定本帖）
async function assertMediaBindable(conn, userId, mediaId, currentDiaryId) {
  const [rows] = await conn.execute(
    `SELECT media_id, owner_id, purpose, status, diary_id, width, height, auth_version
       FROM diary_media WHERE media_id = ? LIMIT 1 FOR UPDATE`,
    [mediaId]
  );
  const media = rows[0];
  if (!media
    || Number(media.owner_id) !== Number(userId)
    || media.purpose !== "diary"
    || media.status !== "ready"
    || (media.diary_id != null && media.diary_id !== currentDiaryId)) {
    throw new ApiError(409, "MEDIA_NOT_READY", "图片尚未就绪或不可用");
  }
  return media;
}

// ---------- 路由 ----------

function createDiaryRouter({ pool, config }) {
  const router = express.Router();
  const mediaSecret = (config && config.jwtSecret) || process.env.JWT_SECRET || "diary-media-dev-secret";

  // ===== 时间线 =====

  router.get("/diary", async (req, res, next) => {
    try {
      const userId = ensureUserId(req.userId);
      const ctx = await loadCoupleSpaceContext(pool, userId);
      if (!ctx) {
        return res.json({
          ok: true,
          data: { items: [], nextCursor: "", hasMore: false, serverTime: new Date().toISOString() }
        });
      }

      const limit = parseLimitParam(req.query.limit, { def: TIMELINE_DEFAULT_LIMIT, max: TIMELINE_MAX_LIMIT });

      let month = null;
      if (req.query.month !== undefined && req.query.month !== null && req.query.month !== "") {
        month = String(req.query.month);
        if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(month)) {
          throw invalidField("month", "月份格式应为 YYYY-MM");
        }
      }

      let authorScope = "all";
      if (req.query.authorScope !== undefined && req.query.authorScope !== null && req.query.authorScope !== "") {
        authorScope = String(req.query.authorScope);
        if (authorScope !== "all" && authorScope !== "mine") {
          throw invalidField("authorScope", "authorScope 只支持 all 或 mine");
        }
      }

      const where = ["p.space_id = ?", "p.status = 'visible'"];
      const params = [ctx.spaceId];
      if (month) {
        where.push("DATE_FORMAT(p.occurred_on, '%Y-%m') = ?");
        params.push(month);
      }
      if (authorScope === "mine") {
        where.push("p.author_id = ?");
        params.push(userId);
      }
      if (req.query.cursor) {
        timelineCursorCondition(where, params, req.query.cursor);
      }

      const [rows] = await pool.execute(
        `SELECT ${POST_FIELDS} FROM diary_posts p
          WHERE ${where.join(" AND ")}
          ORDER BY p.occurred_on DESC, p.created_at DESC, p.diary_id DESC
          LIMIT ${limit + 1}`,
        params
      );

      const hasMore = rows.length > limit;
      const page = hasMore ? rows.slice(0, limit) : rows;
      const postIds = page.map((row) => row.diary_id);
      const mediaByPost = await loadReadyMedia(pool, postIds);
      const authors = await loadAuthors(pool, page.map((row) => row.author_id));
      const items = page.map((post) => serializeDiarySummary(
        mediaSecret, post, mediaByPost.get(post.diary_id) || [], authors
      ));
      const last = page[page.length - 1];
      const nextCursor = hasMore && last
        ? encodeCursor([last.occurred_on, dateTimeToMs(last.created_at), last.diary_id])
        : "";

      res.json({
        ok: true,
        data: { items, nextCursor, hasMore, serverTime: new Date().toISOString() }
      });
    } catch (error) {
      next(error);
    }
  });

  // ===== 发布 =====

  router.post("/diary", async (req, res, next) => {
    try {
      const userId = ensureUserId(req.userId);
      const payload = req.body || {};

      const bodyText = validateDiaryBody(payload.body);
      const occurredOn = parseDateOnly(payload.occurredOn, "occurredOn");
      const moodCode = payload.moodCode !== undefined && payload.moodCode !== null
        ? validateMoodCode(payload.moodCode)
        : null;
      const moodText = payload.moodText !== undefined && payload.moodText !== null
        ? validateMoodText(payload.moodText)
        : null;
      const mediaIds = validateMediaIds(payload.mediaIds);

      if (!bodyText.trim() && mediaIds.length === 0) {
        throw invalidField("body", "正文与图片至少一项");
      }

      // 上下文相关校验全部放在幂等执行器内：重放同键成功请求时（哪怕关系此后变化）
      // 也必须返回首次存储的结果，而不是被 NO_RELATIONSHIP/RELATIONSHIP_CHANGED 短路（契约 §0）。
      const key = requireIdempotencyKey(payload.idempotencyKey);
      const { result } = await withIdempotency(pool, {
        userId,
        scope: "diary.create",
        key,
        payload: {
          body: bodyText,
          occurredOn,
          moodCode,
          moodText,
          mediaIds,
          relationshipVersion: payload.relationshipVersion !== undefined && payload.relationshipVersion !== null
            ? Number(payload.relationshipVersion)
            : null
        }
      }, async () => withTransaction(pool, async (conn) => {
        const txCtx = await loadCoupleSpaceContext(conn, userId, { forUpdate: true });
        if (!txCtx) {
          throw new ApiError(409, "NO_RELATIONSHIP", "绑定伴侣后才能发布小记");
        }
        if (occurredOn > todayInTimezone(txCtx.timezone)) {
          throw invalidField("occurredOn", "日期不能晚于今天");
        }
        if (payload.relationshipVersion !== undefined && payload.relationshipVersion !== null
          && Number(payload.relationshipVersion) !== txCtx.relationshipId) {
          throw new ApiError(409, "RELATIONSHIP_CHANGED", "关系状态已变化，请刷新后重试");
        }

        const diaryId = crypto.randomUUID();
        for (const mediaId of mediaIds) {
          await assertMediaBindable(conn, userId, mediaId, diaryId);
        }

        await conn.execute(
          `INSERT INTO diary_posts (diary_id, space_id, relationship_id, author_id, body, occurred_on, mood_code, mood_text)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
          [diaryId, txCtx.spaceId, txCtx.relationshipId, userId, bodyText, occurredOn, moodCode, moodText]
        );
        for (let index = 0; index < mediaIds.length; index++) {
          await conn.execute(
            "UPDATE diary_media SET diary_id = ?, space_id = ?, sort_order = ?, bound_at = NOW(3) WHERE media_id = ?",
            [diaryId, txCtx.spaceId, index, mediaIds[index]]
          );
        }

        return serializePost(conn, mediaSecret, diaryId);
      }));

      res.json({ ok: true, data: result });
    } catch (error) {
      next(error);
    }
  });

  // ===== 详情 =====

  router.get("/diary/:id", async (req, res, next) => {
    try {
      const userId = ensureUserId(req.userId);
      const post = await loadPostForViewer(pool, userId, req.params.id);
      if (!post || !post.visible) throw diaryNotFound();

      const mediaByPost = await loadReadyMedia(pool, [post.diary_id]);
      const authors = await loadAuthors(pool, [post.author_id]);
      const canEdit = post.isAuthor && post.spaceActive;
      res.json({
        ok: true,
        data: {
          ...serializeDiarySummary(mediaSecret, post, mediaByPost.get(post.diary_id) || [], authors),
          canEdit,
          canDelete: canEdit,
          canComment: post.spaceActive
        }
      });
    } catch (error) {
      next(error);
    }
  });

  // ===== 编辑 =====

  router.patch("/diary/:id", async (req, res, next) => {
    try {
      const userId = ensureUserId(req.userId);
      const payload = req.body || {};
      const expectedVersion = parseExpectedVersion(payload.expectedVersion);

      const bodyText = payload.body !== undefined ? validateDiaryBody(payload.body) : null;
      let occurredOn = null;
      if (payload.occurredOn !== undefined && payload.occurredOn !== null) {
        occurredOn = parseDateOnly(payload.occurredOn, "occurredOn");
      } else if (payload.occurredOn === null) {
        throw invalidField("occurredOn", "日期不能为空");
      }
      const moodCode = payload.moodCode !== undefined ? validateMoodCode(payload.moodCode) : undefined;
      const moodText = payload.moodText !== undefined ? validateMoodText(payload.moodText) : undefined;
      const mediaIds = payload.mediaIds !== undefined ? validateMediaIds(payload.mediaIds) : null;

      const result = await withTransaction(pool, async (conn) => {
        const post = await lockPostForWrite(conn, userId, req.params.id);
        if (!post || !post.visible) throw diaryNotFound();
        if (!post.isAuthor) {
          if (post.spaceActive) throw new ApiError(403, "FORBIDDEN", "只能编辑自己的小记");
          throw diaryNotFound();
        }
        if (!post.spaceActive) {
          throw new ApiError(409, "SPACE_FROZEN", "空间已关闭，无法编辑小记");
        }
        if (expectedVersion !== Number(post.version)) throw versionConflict(post.version);

        const nextBody = bodyText !== null ? bodyText : (post.body || "");
        const nextMediaIds = mediaIds !== null ? mediaIds : null;

        if (occurredOn !== null && occurredOn > todayInTimezone(post.spaceTimezone)) {
          throw invalidField("occurredOn", "日期不能晚于今天");
        }
        if (!nextBody.trim() && (nextMediaIds === null
          ? (await loadReadyMediaCount(conn, post.diary_id)) === 0
          : nextMediaIds.length === 0)) {
          throw invalidField("body", "正文与图片至少一项");
        }

        const sets = ["edited = 1", "version = version + 1"];
        const params = [];
        if (bodyText !== null) {
          sets.push("body = ?");
          params.push(bodyText);
        }
        if (occurredOn !== null) {
          sets.push("occurred_on = ?");
          params.push(occurredOn);
        }
        if (moodCode !== undefined) {
          sets.push("mood_code = ?");
          params.push(moodCode);
        }
        if (moodText !== undefined) {
          sets.push("mood_text = ?");
          params.push(moodText);
        }
        params.push(post.diary_id);
        await conn.execute(
          `UPDATE diary_posts SET ${sets.join(", ")} WHERE diary_id = ?`,
          params
        );

        if (nextMediaIds !== null) {
          await rebindPostMedia(conn, userId, post.diary_id, post.space_id, nextMediaIds);
        }

        return serializePost(conn, mediaSecret, post.diary_id);
      });

      res.json({ ok: true, data: result });
    } catch (error) {
      next(error);
    }
  });

  // ===== 删除（软删，回收期 30 天） =====

  router.delete("/diary/:id", async (req, res, next) => {
    try {
      const userId = ensureUserId(req.userId);
      const payload = req.body || {};
      const expectedVersion = optionalExpectedVersion(payload.expectedVersion);
      const key = optionalIdempotencyKey(payload.idempotencyKey);

      const run = () => withTransaction(pool, async (conn) => {
        const post = await lockPostForWrite(conn, userId, req.params.id);
        if (!post || !post.visible) throw diaryNotFound();
        if (!post.isAuthor) {
          if (post.spaceActive) throw new ApiError(403, "FORBIDDEN", "只能删除自己的小记");
          throw diaryNotFound();
        }
        if (expectedVersion !== null && expectedVersion !== Number(post.version)) {
          throw versionConflict(post.version);
        }
        await conn.execute(
          "UPDATE diary_posts SET status = 'deleted', deleted_at = NOW(3) WHERE diary_id = ?",
          [post.diary_id]
        );
        // 绑定媒体授权版本递增：已外发的签名 URL 立即失效（撤销对方访问）
        await conn.execute(
          "UPDATE diary_media SET auth_version = auth_version + 1 WHERE diary_id = ?",
          [post.diary_id]
        );
        return {};
      });

      const result = key
        ? (await withIdempotency(pool, {
          userId, scope: "diary.delete", key, payload: { diaryId: req.params.id, expectedVersion }
        }, run)).result
        : await run();

      res.json({ ok: true, data: result });
    } catch (error) {
      next(error);
    }
  });

  // ===== 恢复 =====

  router.post("/diary/:id/restore", async (req, res, next) => {
    try {
      const userId = ensureUserId(req.userId);
      const payload = req.body || {};
      const key = optionalIdempotencyKey(payload.idempotencyKey);

      const run = () => withTransaction(pool, async (conn) => {
        const post = await lockPostForWrite(conn, userId, req.params.id);
        // 已删除帖子对任何人不可见，非作者一律 404（不枚举）
        if (!post || !post.isAuthor) throw diaryNotFound();
        if (post.status !== "deleted" || !post.deleted_at) throw diaryNotFound();
        if (Date.now() - dateTimeToMs(post.deleted_at) > RESTORE_WINDOW_MS) throw diaryNotFound();

        if (!post.spaceActive) {
          throw new ApiError(409, "RELATIONSHIP_CHANGED", "原关系已不存在，无法恢复");
        }
        await conn.execute(
          "UPDATE diary_posts SET status = 'visible', deleted_at = NULL, version = version + 1 WHERE diary_id = ?",
          [post.diary_id]
        );
        return serializePost(conn, mediaSecret, post.diary_id);
      });

      const result = key
        ? (await withIdempotency(pool, {
          userId, scope: "diary.restore", key, payload: { diaryId: req.params.id }
        }, run)).result
        : await run();

      res.json({ ok: true, data: result });
    } catch (error) {
      next(error);
    }
  });

  // ===== 评论列表 =====

  router.get("/diary/:id/comments", async (req, res, next) => {
    try {
      const userId = ensureUserId(req.userId);
      const diaryId = req.params.id;
      // 归档口径不返回评论：必须是「空间 active 且本人成员」的可见帖子
      const ctx = await loadCoupleSpaceContext(pool, userId);
      const post = ctx ? await loadPostRow(pool, diaryId) : null;
      if (!ctx || !post || post.space_id !== ctx.spaceId || post.status !== "visible") {
        throw diaryNotFound();
      }

      const hasRootFilter = req.query.rootCommentId !== undefined
        && req.query.rootCommentId !== null && req.query.rootCommentId !== "";
      const total = Number(post.comment_count || 0);

      if (!hasRootFilter) {
        const limit = parseLimitParam(req.query.limit, { def: MAIN_COMMENT_DEFAULT_LIMIT, max: COMMENT_MAX_LIMIT });
        const where = [
          "c.diary_id = ?",
          "c.root_comment_id IS NULL",
          "(c.status = 'visible' OR (c.status = 'deleted' AND EXISTS (" +
            "SELECT 1 FROM diary_comments rr WHERE rr.root_comment_id = c.comment_id " +
            "AND rr.diary_id = c.diary_id AND rr.status = 'visible')))"
        ];
        const params = [diaryId];
        if (req.query.cursor) {
          const [createdAtMs, commentId] = decodeCommentCursor(req.query.cursor);
          const createdAt = new Date(createdAtMs);
          where.push("(c.created_at > ? OR (c.created_at = ? AND c.comment_id > ?))");
          params.push(createdAt, createdAt, commentId);
        }
        const [rows] = await pool.execute(
          `SELECT ${COMMENT_FIELDS} FROM diary_comments c
            WHERE ${where.join(" AND ")}
            ORDER BY c.created_at ASC, c.comment_id ASC
            LIMIT ${limit + 1}`,
          params
        );
        const hasMore = rows.length > limit;
        const roots = hasMore ? rows.slice(0, limit) : rows;
        const last = roots[roots.length - 1];

        const rootIds = roots.map((row) => row.comment_id);
        const countByRoot = new Map();
        const repliesByRoot = new Map();
        if (rootIds.length > 0) {
          const placeholders = rootIds.map(() => "?").join(", ");
          const [countRows] = await pool.execute(
            `SELECT root_comment_id, COUNT(*) AS reply_count FROM diary_comments
              WHERE root_comment_id IN (${placeholders}) AND status = 'visible'
              GROUP BY root_comment_id`,
            rootIds
          );
          for (const row of countRows) {
            countByRoot.set(row.root_comment_id, Number(row.reply_count));
          }
          const [replyRows] = await pool.execute(
            `SELECT ${COMMENT_FIELDS} FROM diary_comments c
              WHERE c.root_comment_id IN (${placeholders}) AND c.status = 'visible'
              ORDER BY c.created_at ASC, c.comment_id ASC`,
            rootIds
          );
          for (const row of replyRows) {
            const list = repliesByRoot.get(row.root_comment_id) || [];
            list.push(row);
            repliesByRoot.set(row.root_comment_id, list);
          }
        }

        const authorIds = [];
        for (const root of roots) authorIds.push(root.author_id);
        for (const replies of repliesByRoot.values()) {
          for (const reply of replies) {
            authorIds.push(reply.author_id);
            if (reply.reply_to_user_id != null) authorIds.push(reply.reply_to_user_id);
          }
        }
        const authors = await loadAuthors(pool, authorIds);

        const items = roots.map((root) => ({
          ...serializeComment(root, authors),
          replyCount: countByRoot.get(root.comment_id) || 0,
          replies: (repliesByRoot.get(root.comment_id) || [])
            .slice(0, REPLY_PREVIEW_COUNT)
            .map((reply) => serializeComment(reply, authors))
        }));
        const nextCursor = hasMore && last
          ? encodeCursor([dateTimeToMs(last.created_at), last.comment_id])
          : "";

        return res.json({ ok: true, data: { items, nextCursor, hasMore, total } });
      }

      const rootCommentId = String(req.query.rootCommentId);
      const [rootRows] = await pool.execute(
        "SELECT comment_id FROM diary_comments WHERE comment_id = ? AND diary_id = ? AND root_comment_id IS NULL LIMIT 1",
        [rootCommentId, diaryId]
      );
      if (!rootRows[0]) throw new ApiError(404, "NOT_FOUND", "评论不存在");

      const limit = parseLimitParam(req.query.limit, { def: REPLY_DEFAULT_LIMIT, max: COMMENT_MAX_LIMIT });
      const where = ["c.diary_id = ?", "c.root_comment_id = ?", "c.status = 'visible'"];
      const params = [diaryId, rootCommentId];
      if (req.query.cursor) {
        const [createdAtMs, commentId] = decodeCommentCursor(req.query.cursor);
        const createdAt = new Date(createdAtMs);
        where.push("(c.created_at > ? OR (c.created_at = ? AND c.comment_id > ?))");
        params.push(createdAt, createdAt, commentId);
      }
      const [rows] = await pool.execute(
        `SELECT ${COMMENT_FIELDS} FROM diary_comments c
          WHERE ${where.join(" AND ")}
          ORDER BY c.created_at ASC, c.comment_id ASC
          LIMIT ${limit + 1}`,
        params
      );
      const hasMore = rows.length > limit;
      const page = hasMore ? rows.slice(0, limit) : rows;
      const last = page[page.length - 1];

      const authorIds = [];
      for (const reply of page) {
        authorIds.push(reply.author_id);
        if (reply.reply_to_user_id != null) authorIds.push(reply.reply_to_user_id);
      }
      const authors = await loadAuthors(pool, authorIds);
      const items = page.map((reply) => serializeComment(reply, authors));
      const nextCursor = hasMore && last
        ? encodeCursor([dateTimeToMs(last.created_at), last.comment_id])
        : "";

      res.json({ ok: true, data: { items, nextCursor, hasMore, total } });
    } catch (error) {
      next(error);
    }
  });

  // ===== 发表评论 =====

  router.post("/diary/:id/comments", async (req, res, next) => {
    try {
      const userId = ensureUserId(req.userId);
      const diaryId = req.params.id;
      const payload = req.body || {};
      const bodyText = validateCommentBody(payload.body);

      const rootCommentIdRaw = payload.rootCommentId !== undefined && payload.rootCommentId !== null
        ? String(payload.rootCommentId)
        : null;
      const replyToCommentIdRaw = payload.replyToCommentId !== undefined && payload.replyToCommentId !== null
        ? String(payload.replyToCommentId)
        : null;

      const key = requireIdempotencyKey(payload.idempotencyKey);
      const { result } = await withIdempotency(pool, {
        userId,
        scope: "comment.create",
        key,
        payload: { diaryId, body: bodyText, rootCommentId: rootCommentIdRaw, replyToCommentId: replyToCommentIdRaw }
      }, async () => withTransaction(pool, async (conn) => {
        const ctx = await loadCoupleSpaceContext(conn, userId, { forUpdate: true });
        const post = ctx ? await loadPostRow(conn, diaryId, { forUpdate: true }) : null;
        if (!ctx || !post || post.space_id !== ctx.spaceId || post.status !== "visible") {
          throw diaryNotFound();
        }

        // 回复目标解析：回复一律挂根；reply_to_user_id 取目标评论作者（忽略客户端传入）
        let rootId = null;
        let replyToCommentId = null;
        let replyToUserId = null;
        if (replyToCommentIdRaw) {
          const [targetRows] = await conn.execute(
            `SELECT comment_id, diary_id, author_id, root_comment_id, status
               FROM diary_comments WHERE comment_id = ? AND diary_id = ? LIMIT 1 FOR UPDATE`,
            [replyToCommentIdRaw, diaryId]
          );
          const target = targetRows[0];
          if (!target || target.status !== "visible") {
            throw new ApiError(404, "TARGET_REMOVED", "回复目标已删除");
          }
          rootId = rootCommentIdRaw || target.root_comment_id || target.comment_id;
          if (target.comment_id !== rootId && target.root_comment_id !== rootId) {
            throw new ApiError(404, "TARGET_REMOVED", "回复目标已删除");
          }
          replyToCommentId = target.comment_id;
          replyToUserId = Number(target.author_id);
        } else if (rootCommentIdRaw) {
          rootId = rootCommentIdRaw;
        }

        if (rootId) {
          const [rootRows] = await conn.execute(
            `SELECT comment_id, diary_id, status FROM diary_comments
              WHERE comment_id = ? AND diary_id = ? AND root_comment_id IS NULL LIMIT 1 FOR UPDATE`,
            [rootId, diaryId]
          );
          const root = rootRows[0];
          if (!root || root.status !== "visible") {
            throw new ApiError(404, "TARGET_REMOVED", "回复目标已删除");
          }
        }

        const commentId = crypto.randomUUID();
        await conn.execute(
          `INSERT INTO diary_comments (comment_id, diary_id, space_id, author_id, body, root_comment_id, reply_to_comment_id, reply_to_user_id)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
          [commentId, diaryId, post.space_id, userId, bodyText, rootId, replyToCommentId, replyToUserId]
        );
        await conn.execute(
          "UPDATE diary_posts SET comment_count = comment_count + 1 WHERE diary_id = ?",
          [diaryId]
        );

        const [commentRows] = await conn.execute(
          `SELECT ${COMMENT_FIELDS} FROM diary_comments c WHERE c.comment_id = ? LIMIT 1`,
          [commentId]
        );
        const authors = await loadAuthors(conn, [userId, replyToUserId]);
        const summary = serializeComment(commentRows[0], authors);
        if (rootId == null) {
          summary.replyCount = 0;
          summary.replies = [];
        }
        return summary;
      }));

      res.json({ ok: true, data: result });
    } catch (error) {
      next(error);
    }
  });

  // ===== 编辑评论 =====

  router.patch("/diary-comments/:id", async (req, res, next) => {
    try {
      const userId = ensureUserId(req.userId);
      const payload = req.body || {};
      const expectedVersion = parseExpectedVersion(payload.expectedVersion);
      const bodyText = validateCommentBody(payload.body);

      const result = await withTransaction(pool, async (conn) => {
        const [rows] = await conn.execute(
          `SELECT ${COMMENT_FIELDS} FROM diary_comments c WHERE c.comment_id = ? LIMIT 1 FOR UPDATE`,
          [req.params.id]
        );
        const comment = rows[0];
        if (!comment) throw new ApiError(404, "NOT_FOUND", "评论不存在");
        if (Number(comment.author_id) !== Number(userId)) {
          throw new ApiError(403, "FORBIDDEN", "只能修改自己的评论");
        }
        if (comment.status !== "visible") {
          throw new ApiError(404, "NOT_FOUND", "评论不存在");
        }
        if (expectedVersion !== Number(comment.version)) throw versionConflict(comment.version);

        await conn.execute(
          "UPDATE diary_comments SET body = ?, version = version + 1 WHERE comment_id = ?",
          [bodyText, comment.comment_id]
        );
        const [updated] = await conn.execute(
          `SELECT ${COMMENT_FIELDS} FROM diary_comments c WHERE c.comment_id = ? LIMIT 1`,
          [comment.comment_id]
        );
        const authors = await loadAuthors(conn, [comment.author_id, comment.reply_to_user_id]);
        return serializeComment(updated[0], authors);
      });

      res.json({ ok: true, data: result });
    } catch (error) {
      next(error);
    }
  });

  // ===== 删除评论 =====

  router.delete("/diary-comments/:id", async (req, res, next) => {
    try {
      const userId = ensureUserId(req.userId);
      const payload = req.body || {};
      const expectedVersion = optionalExpectedVersion(payload.expectedVersion);
      const key = optionalIdempotencyKey(payload.idempotencyKey);

      const run = () => withTransaction(pool, async (conn) => {
        // 先读后按 帖子行 → 评论行 的顺序加锁（与发表评论的锁序一致，避免交叉死锁）
        const [previewRows] = await conn.execute(
          `SELECT ${COMMENT_FIELDS} FROM diary_comments c WHERE c.comment_id = ? LIMIT 1`,
          [req.params.id]
        );
        const preview = previewRows[0];
        if (!preview) throw new ApiError(404, "NOT_FOUND", "评论不存在");
        await conn.execute(
          "SELECT comment_count FROM diary_posts WHERE diary_id = ? LIMIT 1 FOR UPDATE",
          [preview.diary_id]
        );
        const [rows] = await conn.execute(
          `SELECT ${COMMENT_FIELDS} FROM diary_comments c WHERE c.comment_id = ? LIMIT 1 FOR UPDATE`,
          [req.params.id]
        );
        const comment = rows[0];
        if (!comment || comment.status !== "visible") {
          throw new ApiError(404, "NOT_FOUND", "评论不存在");
        }
        if (Number(comment.author_id) !== Number(userId)) {
          throw new ApiError(403, "FORBIDDEN", "只能删除自己的评论");
        }
        if (expectedVersion !== null && expectedVersion !== Number(comment.version)) {
          throw versionConflict(comment.version);
        }

        const isMain = comment.root_comment_id == null;
        if (isMain) {
          // 有可见回复 → 保留无正文占位；无回复 → 同样软删（列表口径自然不显示）
          await conn.execute(
            "UPDATE diary_comments SET status = 'deleted', body = '' WHERE comment_id = ?",
            [comment.comment_id]
          );
        } else {
          await conn.execute(
            "UPDATE diary_comments SET status = 'deleted' WHERE comment_id = ?",
            [comment.comment_id]
          );
        }
        await conn.execute(
          "UPDATE diary_posts SET comment_count = CASE WHEN comment_count > 0 THEN comment_count - 1 ELSE 0 END WHERE diary_id = ?",
          [comment.diary_id]
        );
        return {};
      });

      const result = key
        ? (await withIdempotency(pool, {
          userId, scope: "comment.delete", key, payload: { commentId: req.params.id, expectedVersion }
        }, run)).result
        : await run();

      res.json({ ok: true, data: result });
    } catch (error) {
      next(error);
    }
  });

  // ===== 本人归档（解绑后只读） =====

  router.get("/me/diary-archives", async (req, res, next) => {
    try {
      const userId = ensureUserId(req.userId);
      const limit = parseLimitParam(req.query.limit, { def: TIMELINE_DEFAULT_LIMIT, max: TIMELINE_MAX_LIMIT });

      const where = ["p.author_id = ?", "p.status = 'visible'"];
      const params = [userId];
      if (req.query.cursor) {
        timelineCursorCondition(where, params, req.query.cursor);
      }
      const [rows] = await pool.execute(
        `SELECT ${POST_FIELDS} FROM diary_posts p
          JOIN housework_spaces s ON s.space_id = p.space_id AND s.status = 'closed'
          WHERE ${where.join(" AND ")}
          ORDER BY p.occurred_on DESC, p.created_at DESC, p.diary_id DESC
          LIMIT ${limit + 1}`,
        params
      );

      const hasMore = rows.length > limit;
      const page = hasMore ? rows.slice(0, limit) : rows;
      const postIds = page.map((row) => row.diary_id);
      const mediaByPost = await loadReadyMedia(pool, postIds, userId);
      const authors = await loadAuthors(pool, [userId]);
      const items = page.map((post) => serializeDiarySummary(
        mediaSecret, post, mediaByPost.get(post.diary_id) || [], authors
      ));
      const last = page[page.length - 1];
      const nextCursor = hasMore && last
        ? encodeCursor([last.occurred_on, dateTimeToMs(last.created_at), last.diary_id])
        : "";

      res.json({
        ok: true,
        data: { items, nextCursor, hasMore, serverTime: new Date().toISOString() }
      });
    } catch (error) {
      next(error);
    }
  });

  // ===== 空间小记背景 =====

  function noneTheme(version = 0, overlay = 45) {
    return { kind: "none", templateId: null, mediaId: null, mediaUrl: "", thumbnailUrl: "", overlay, version };
  }

  function themePayload(row, mediaRow) {
    if (row && row.kind === "template") {
      return {
        kind: "template",
        templateId: row.template_id,
        mediaId: null,
        mediaUrl: "",
        thumbnailUrl: "",
        overlay: Number(row.overlay),
        version: Number(row.version)
      };
    }
    if (row && row.kind === "media" && mediaRow && mediaRow.status === "ready") {
      return {
        kind: "media",
        templateId: null,
        mediaId: mediaRow.media_id,
        mediaUrl: buildMediaUrl({ secret: mediaSecret, mediaId: mediaRow.media_id, variant: "full", authVersion: Number(mediaRow.auth_version) }),
        thumbnailUrl: buildMediaUrl({ secret: mediaSecret, mediaId: mediaRow.media_id, variant: "thumb", authVersion: Number(mediaRow.auth_version) }),
        overlay: Number(row.overlay),
        version: Number(row.version)
      };
    }
    return row
      ? noneTheme(Number(row.version), Number(row.overlay))
      : noneTheme();
  }

  router.get("/spaces/current/diary-theme", async (req, res, next) => {
    try {
      const userId = ensureUserId(req.userId);
      const ctx = await loadCoupleSpaceContext(pool, userId);
      if (!ctx) {
        return res.json({ ok: true, data: noneTheme() });
      }
      const [rows] = await pool.execute(
        "SELECT space_id, kind, template_id, media_id, overlay, version FROM space_diary_theme WHERE space_id = ? LIMIT 1",
        [ctx.spaceId]
      );
      const row = rows[0] || null;
      let mediaRow = null;
      if (row && row.kind === "media" && row.media_id) {
        const [mediaRows] = await pool.execute(
          "SELECT media_id, status, auth_version FROM diary_media WHERE media_id = ? LIMIT 1",
          [row.media_id]
        );
        mediaRow = mediaRows[0] || null;
      }
      res.json({ ok: true, data: themePayload(row, mediaRow) });
    } catch (error) {
      next(error);
    }
  });

  router.patch("/spaces/current/diary-theme", async (req, res, next) => {
    try {
      const userId = ensureUserId(req.userId);
      const payload = req.body || {};
      const expectedVersion = parseExpectedVersion(payload.expectedVersion);

      const hasTemplate = payload.templateId !== undefined && payload.templateId !== null;
      const hasMedia = payload.mediaId !== undefined && payload.mediaId !== null;
      if (hasTemplate && hasMedia) {
        throw invalidField("templateId", "模板与自定义背景图片只能二选一");
      }
      if (!hasTemplate && !hasMedia) {
        throw invalidField("templateId", "templateId 与 mediaId 至少提供一项");
      }
      if (hasTemplate && (typeof payload.templateId !== "string" || !TEMPLATE_IDS.has(payload.templateId))) {
        throw invalidField("templateId", "模板 ID 无效");
      }
      if (hasMedia && (typeof payload.mediaId !== "string" || !payload.mediaId.trim())) {
        throw invalidField("mediaId", "背景图片 mediaId 无效");
      }

      let overlay = null;
      if (payload.overlay !== undefined && payload.overlay !== null) {
        const raw = Number(payload.overlay);
        if (!Number.isFinite(raw) || raw < 0) {
          throw invalidField("overlay", "柔化度应为 20-70 的整数");
        }
        const normalized = raw > 0 && raw <= 1 ? raw * 100 : raw;
        overlay = Math.round(normalized);
        if (overlay < 20 || overlay > 70) {
          throw invalidField("overlay", "柔化度应为 20-70 的整数");
        }
      }

      const result = await withTransaction(pool, async (conn) => {
        const ctx = await loadCoupleSpaceContext(conn, userId, { forUpdate: true });
        if (!ctx) {
          throw new ApiError(409, "NO_RELATIONSHIP", "绑定伴侣后才能设置小记背景");
        }

        const [rows] = await conn.execute(
          "SELECT space_id, kind, template_id, media_id, overlay, version FROM space_diary_theme WHERE space_id = ? LIMIT 1 FOR UPDATE",
          [ctx.spaceId]
        );
        const existing = rows[0] || null;
        // 行不存在时无并发可冲突（UPSERT 原子），首次保存接受任意版本号；
        // 行存在时严格乐观锁。
        if (existing && expectedVersion !== Number(existing.version)) {
          throw versionConflict(Number(existing.version));
        }

        let mediaRow = null;
        if (hasMedia) {
          const [mediaRows] = await conn.execute(
            "SELECT media_id, owner_id, purpose, status, auth_version FROM diary_media WHERE media_id = ? LIMIT 1",
            [payload.mediaId]
          );
          mediaRow = mediaRows[0];
          if (!mediaRow
            || mediaRow.status !== "ready"
            || mediaRow.purpose !== "background"
            || !ctx.memberIds.includes(Number(mediaRow.owner_id))) {
            throw new ApiError(422, "VALIDATION_FAILED", "字段校验失败", {
              fieldErrors: { mediaId: "背景图片不可用或未就绪" }
            });
          }
        }

        const effectiveOverlay = overlay !== null
          ? overlay
          : (existing ? Number(existing.overlay) : 45);
        const kind = hasTemplate ? "template" : "media";
        const templateId = hasTemplate ? payload.templateId : null;
        const mediaId = hasMedia ? payload.mediaId : null;
        const cropJson = hasMedia && payload.crop !== undefined && payload.crop !== null
          ? JSON.stringify(payload.crop)
          : null;
        const focusJson = hasMedia && payload.focusPoint !== undefined && payload.focusPoint !== null
          ? JSON.stringify(payload.focusPoint)
          : null;

        await conn.execute(
          `INSERT INTO space_diary_theme
             (space_id, kind, template_id, media_id, crop_json, focus_point_json, overlay, version, updated_by)
           VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)
           ON DUPLICATE KEY UPDATE
             kind = VALUES(kind), template_id = VALUES(template_id), media_id = VALUES(media_id),
             crop_json = VALUES(crop_json), focus_point_json = VALUES(focus_point_json),
             overlay = VALUES(overlay), version = version + 1, updated_by = VALUES(updated_by)`,
          [ctx.spaceId, kind, templateId, mediaId, cropJson, focusJson, effectiveOverlay, userId]
        );

        return themePayload(
          { kind, template_id: templateId, media_id: mediaId, overlay: effectiveOverlay, version: (existing ? Number(existing.version) : 0) + 1 },
          mediaRow
        );
      });

      res.json({ ok: true, data: result });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

// 当前帖子绑定的 ready 媒体数（编辑后「正文与图片至少一项」校验用）
async function loadReadyMediaCount(executor, diaryId) {
  const [rows] = await executor.execute(
    "SELECT COUNT(*) AS total FROM diary_media WHERE diary_id = ? AND status = 'ready'",
    [diaryId]
  );
  return Number(rows[0].total);
}

// 编辑帖子的媒体重绑：移除的解绑（auth_version 不变），新绑定按数组序，已绑定的只调 sort_order
async function rebindPostMedia(conn, userId, diaryId, spaceId, nextMediaIds) {
  const [currentRows] = await conn.execute(
    "SELECT media_id, status FROM diary_media WHERE diary_id = ? FOR UPDATE",
    [diaryId]
  );
  const currentIds = currentRows.map((row) => row.media_id);

  for (const mediaId of nextMediaIds) {
    if (!currentIds.includes(mediaId)) {
      await assertMediaBindable(conn, userId, mediaId, diaryId);
    }
  }
  for (const mediaId of currentIds) {
    if (!nextMediaIds.includes(mediaId)) {
      await conn.execute(
        "UPDATE diary_media SET diary_id = NULL, space_id = NULL WHERE media_id = ?",
        [mediaId]
      );
    }
  }
  for (let index = 0; index < nextMediaIds.length; index++) {
    const mediaId = nextMediaIds[index];
    if (currentIds.includes(mediaId)) {
      await conn.execute(
        "UPDATE diary_media SET sort_order = ? WHERE media_id = ?",
        [index, mediaId]
      );
    } else {
      await conn.execute(
        "UPDATE diary_media SET diary_id = ?, space_id = ?, sort_order = ?, bound_at = NOW(3) WHERE media_id = ?",
        [diaryId, spaceId, index, mediaId]
      );
    }
  }
}

module.exports = { createDiaryRouter };
