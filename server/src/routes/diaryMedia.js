/**
 * 共同小记媒体路由（v0.2）—— spec: docs/API_CONTRACT_GOALS_DIARY_20261006.md（小程序仓库）。
 *
 * 本路由在 index.js 的全局鉴权中间件之前挂载：内容端点要被小程序 <image> 标签
 * 直接请求（无法携带 Authorization 头），所以这里不依赖外层鉴权——需要登录的
 * 端点逐个挂 requireAuth，内容端点自做「短时效签名 URL / Bearer 会话」双通道鉴权。
 *
 * 存储目录是 server/media/diary（私有）。注意绝不能放进 uploads/——那里被
 * 静态公开服务，小记媒体必须走带鉴权的内容端点。
 */
const express = require("express");
const multer = require("multer");
const path = require("path");
const fs = require("fs");
const crypto = require("crypto");
const sharp = require("sharp");

const { ApiError } = require("../errors");
const { createRequireAuth, loadActiveUserSession } = require("../middleware/auth");
const { verifyAccessToken } = require("../utils/jwt");
const { buildMediaUrl, verifyMediaToken } = require("../utils/mediaToken");

const { withTransaction } = require("../utils/transactions");
const { loadCoupleSpaceContext } = require("../utils/spaceContext");

const MEDIA_DIR = path.resolve(process.env.DIARY_MEDIA_DIR || path.resolve(__dirname, "../../media/diary"));

if (!fs.existsSync(MEDIA_DIR)) {
  fs.mkdirSync(MEDIA_DIR, { recursive: true });
}

const PURPOSES = new Set(["diary", "background"]);
const ALLOWED_MIME = new Set(["image/jpeg", "image/png", "image/webp"]);
// 与前端约定一致：单张原图 ≤20MB，解码后 ≤40MP（防像素炸弹）。
const MAX_UPLOAD_BYTES = 20 * 1024 * 1024;
const MAX_UPLOAD_MB = Math.round(MAX_UPLOAD_BYTES / 1024 / 1024);
const MAX_PIXELS = 40000000;
// 转码参数：按 EXIF 方向纠正后统一转 WebP；剥离全部元数据（EXIF/GPS/设备等）。
const FULL_MAX_EDGE = 2048;
const THUMB_MAX_EDGE = 480;
const FULL_QUALITY = 82;
const THUMB_QUALITY = 80;
// 上传会话有效期与懒清理阈值：未 complete 的会话 24h 后可回收。
const SESSION_TTL_MS = 24 * 60 * 60 * 1000;
const STALE_AFTER_MS = 24 * 60 * 60 * 1000;

// memoryStorage：转码在内存完成后再落盘，避免半成品文件占用存储目录。
const rawUpload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: MAX_UPLOAD_BYTES }
});

// multer 的 MulterError 不带 status，会被全局兜底当 500 处理；
// 这里统一转成带 status 的 ApiError（与 routes/upload.js 同口径）。
const MULTER_ERROR_MAP = {
  LIMIT_FILE_SIZE: [413, "FILE_TOO_LARGE", `图片过大，单张不能超过 ${MAX_UPLOAD_MB}MB`],
  LIMIT_FILE_COUNT: [400, "UPLOAD_INVALID", "一次只能上传一张图片"],
  LIMIT_UNEXPECTED_FILE: [400, "UPLOAD_INVALID", "上传字段名不正确，应为 file"],
  LIMIT_PART_COUNT: [400, "UPLOAD_INVALID", "表单字段过多"],
  LIMIT_FIELD_KEY: [400, "UPLOAD_INVALID", "表单字段名过长"],
  LIMIT_FIELD_VALUE: [400, "UPLOAD_INVALID", "表单字段值过大"],
  LIMIT_FIELD_COUNT: [400, "UPLOAD_INVALID", "表单字段过多"]
};

function handleRawUpload(middleware) {
  return (req, res, next) => {
    middleware(req, res, (error) => {
      if (!error) return next();
      if (error.name !== "MulterError") return next(error);
      const mapped = MULTER_ERROR_MAP[error.code];
      if (!mapped) {
        return next(new ApiError(400, "UPLOAD_INVALID", "上传失败，请检查文件后重试"));
      }
      next(new ApiError(mapped[0], mapped[1], mapped[2]));
    });
  };
}

function validationFailed(message, fieldErrors) {
  return new ApiError(422, "VALIDATION_FAILED", message, { fieldErrors });
}

function notFoundMedia() {
  // 不枚举「不存在」与「不属于你」，统一 404。
  return new ApiError(404, "NOT_FOUND", "媒体不存在");
}

// storage_path 存的是相对 server/media 的路径（diary/<id>_full.webp）。
// 取 basename 再拼 MEDIA_DIR，顺带杜绝路径穿越。
function mediaAbsolutePath(relativePath) {
  return path.join(MEDIA_DIR, path.basename(String(relativePath)));
}

// 懒清理：本人名下超过 24h 仍未 complete 的会话行，尽力删对应文件（失败忽略）。
// Retention policy is not approved: report candidates only, never delete existing bytes/rows.
async function cleanupStaleMedia(pool, userId) {
  const [rows] = await pool.execute(
    "SELECT m.media_id FROM diary_media m WHERE m.owner_id = ? AND m.created_at < ? " +
    "AND m.diary_id IS NULL AND m.status IN ('pending','transcoded','ready') " +
    "AND NOT EXISTS (SELECT 1 FROM space_diary_theme t WHERE t.media_id = m.media_id) LIMIT 100",
    [userId, new Date(Date.now() - STALE_AFTER_MS)]);
  return rows;
}

async function canReadMedia(pool, media, viewerId) {
  const owner = Number(media.owner_id) === Number(viewerId);
  if (media.diary_id) {
    const [posts] = await pool.execute("SELECT author_id, status FROM diary_posts WHERE diary_id = ? AND space_id = ? LIMIT 1", [media.diary_id, media.space_id]);
    if (!posts[0] || posts[0].status !== "visible") return false;
    if (owner && Number(posts[0].author_id) === Number(viewerId)) return true;
  }
  const [members] = await pool.execute(
    "SELECT 1 FROM housework_spaces s JOIN housework_space_members m ON m.space_id = s.space_id " +
    "WHERE s.space_id = ? AND s.status = 'active' AND m.user_id = ? LIMIT 1", [media.space_id, viewerId]);
  if (!members.length) return false;
  if (owner) return true;
  if (media.diary_id) return true;
  if (media.purpose === "background") {
    const [themes] = await pool.execute("SELECT 1 FROM space_diary_theme WHERE space_id = ? AND media_id = ? AND kind = 'media' LIMIT 1", [media.space_id, media.media_id]);
    return themes.length > 0;
  }
  return false;
}

function createDiaryMediaRouter({ pool, config, requireAuth: suppliedRequireAuth }) {
  const requireAuth = suppliedRequireAuth || createRequireAuth(pool);
  const router = express.Router();
  router.use((req, res, next) => {
    if (process.env.DIARY_WRITES_ENABLED === "false" && !["GET", "HEAD", "DELETE"].includes(req.method)) {
      return next(new ApiError(503, "DIARY_WRITES_DISABLED", "小记暂时停止新写入，已有内容仍可查看"));
    }
    next();
  });

  /* ---------------------------------------------------------------- *
   * POST /uploads —— 签发受限上传会话（pending 行）
   * ---------------------------------------------------------------- */
  router.post("/uploads", requireAuth, async (req, res, next) => {
    try {
      const body = req.body || {};
      const fieldErrors = {};

      if (!PURPOSES.has(body.purpose)) {
        fieldErrors.purpose = "purpose 必须是 diary 或 background";
      }
      if (!ALLOWED_MIME.has(body.mime)) {
        fieldErrors.mime = "仅支持 image/jpeg、image/png、image/webp";
      }
      if (!Number.isInteger(body.size) || body.size < 1 || body.size > MAX_UPLOAD_BYTES) {
        fieldErrors.size = `size 必须是 1 到 ${MAX_UPLOAD_BYTES} 之间的整数（字节）`;
      }
      if (Object.keys(fieldErrors).length > 0) {
        throw validationFailed("上传会话参数不合法", fieldErrors);
      }

      await cleanupStaleMedia(pool, req.userId);

      const mediaId = crypto.randomUUID();
      await withTransaction(pool, async conn => {
        const ctx = await loadCoupleSpaceContext(conn, req.userId, { forUpdate: true });
        if (!ctx || String(body.relationshipVersion || "") !== String(ctx.cycleId)) {
          throw new ApiError(409, "RELATIONSHIP_CHANGED", "关系已变化，请重新打开编辑器");
        }
        await conn.execute(
          "INSERT INTO diary_media (media_id, owner_id, space_id, purpose, status, mime, size_bytes) VALUES (?, ?, ?, ?, 'pending', ?, ?)",
          [mediaId, req.userId, ctx.spaceId, body.purpose, body.mime, body.size]
        );
      });

      res.status(201).json({
        ok: true,
        data: {
          mediaId,
          uploadUrl: `/api/diary-media/${mediaId}/raw`,
          form: { field: "file" },
          upload: {
            url: `/api/diary-media/${mediaId}/raw`,
            headers: {}
          },
          expiresAt: new Date(Date.now() + SESSION_TTL_MS).toISOString()
        }
      });
    } catch (error) {
      next(error);
    }
  });

  /* ---------------------------------------------------------------- *
   * POST /:id/raw —— 接收原图：EXIF 纠向 + 剥离元数据 + 转码 WebP
   * ---------------------------------------------------------------- */
  router.post(
    "/:id/raw",
    requireAuth,
    handleRawUpload(rawUpload.single("file")),
    async (req, res, next) => {
      try {
        const mediaId = req.params.id;
        const result = await withTransaction(pool, async conn => {
        const ctx = await loadCoupleSpaceContext(conn, req.userId, { forUpdate: true });
        const [rows] = await conn.execute(
          "SELECT media_id, owner_id, space_id, status, created_at FROM diary_media WHERE media_id = ? LIMIT 1 FOR UPDATE",
          [mediaId]
        );
        const media = rows[0];
        if (!media || Number(media.owner_id) !== Number(req.userId)) {
          throw notFoundMedia();
        }
        if (!ctx || ctx.spaceId !== media.space_id) throw new ApiError(409, "RELATIONSHIP_CHANGED", "上传所属空间已关闭");
        if (Date.now() - new Date(media.created_at).getTime() > SESSION_TTL_MS) {
          throw new ApiError(410, "UPLOAD_EXPIRED", "上传会话已过期，请重新选择图片");
        }
        if (media.status !== "pending") {
          throw new ApiError(409, "MEDIA_STATE_CONFLICT", "媒体当前状态不允许重复上传原图");
        }
        if (!req.file || !req.file.buffer || req.file.buffer.length === 0) {
          throw validationFailed("缺少上传文件", { file: "请选择要上传的图片文件" });
        }

        let rotated;
        let meta;
        try {
          // 无参 rotate() = 按 EXIF 方向纠正像素；toBuffer 输出不带元数据，
          // EXIF/GPS/设备信息/拍摄时间/嵌入缩略图随之剥离。
          const input = sharp(req.file.buffer, { limitInputPixels: MAX_PIXELS, animated: false });
          meta = await input.metadata();
          if (!["jpeg", "png", "webp"].includes(meta.format) || (meta.pages || 1) > 1 ||
              !meta.width || !meta.height || meta.width * meta.height > MAX_PIXELS) {
            throw new Error("UNSUPPORTED_IMAGE");
          }
          rotated = await input.rotate().toBuffer();
        } catch (_error) {
          throw validationFailed("无法解析上传的图片", { file: "无法解析图片" });
        }
        const pixels = meta.width * meta.height;
        if (!Number.isFinite(pixels)) {
          throw validationFailed("无法解析上传的图片", { file: "无法解析图片" });
        }
        if (pixels > MAX_PIXELS) {
          throw validationFailed("图片像素超限", { size: "图片像素超限" });
        }

        const full = await sharp(rotated)
          .resize({ width: FULL_MAX_EDGE, height: FULL_MAX_EDGE, fit: "inside", withoutEnlargement: true })
          .webp({ quality: FULL_QUALITY })
          .toBuffer({ resolveWithObject: true });
        const thumb = await sharp(rotated)
          .resize({ width: THUMB_MAX_EDGE, height: THUMB_MAX_EDGE, fit: "inside", withoutEnlargement: true })
          .webp({ quality: THUMB_QUALITY })
          .toBuffer({ resolveWithObject: true });

        await fs.promises.writeFile(path.join(MEDIA_DIR, `${mediaId}_full.webp`), full.data);
        await fs.promises.writeFile(path.join(MEDIA_DIR, `${mediaId}_thumb.webp`), thumb.data);

        await conn.execute(
          "UPDATE diary_media SET status = 'transcoded', width = ?, height = ?, size_bytes = ?, " +
            "storage_path = ?, thumb_path = ? WHERE media_id = ?",
          [
            full.info.width,
            full.info.height,
            full.data.length,
            `diary/${mediaId}_full.webp`,
            `diary/${mediaId}_thumb.webp`,
            mediaId
          ]
        );

        return {
            mediaId,
            status: "transcoded",
            width: full.info.width,
            height: full.info.height
        };
        });
        res.json({ ok: true, data: result });
      } catch (error) {
        next(error);
      }
    }
  );

  /* ---------------------------------------------------------------- *
   * POST /:id/complete —— 转码闸门：客户端「成功」不算数，服务端复核后
   * 才允许媒体被发布绑定（ready）
   * ---------------------------------------------------------------- */
  router.post("/:id/complete", requireAuth, async (req, res, next) => {
    try {
      const mediaId = req.params.id;
      const [rows] = await pool.execute(
        "SELECT media_id, owner_id, space_id, diary_id, purpose, status, width, height, auth_version FROM diary_media WHERE media_id = ? LIMIT 1",
        [mediaId]
      );
      const media = rows[0];
      if (!media || Number(media.owner_id) !== Number(req.userId)) {
        throw notFoundMedia();
      }
      if (media.status === "pending" || media.status === "failed") {
        throw new ApiError(409, "MEDIA_NOT_READY", "媒体尚未完成转码");
      }
      if (media.status === "transcoded") {
        await pool.execute(
          "UPDATE diary_media SET status = 'ready' WHERE media_id = ?",
          [mediaId]
        );
      }
      if (!(await canReadMedia(pool, { ...media, status: "ready" }, req.userId))) throw notFoundMedia();
      // status 已是 ready → 幂等直接返回

      const authVersion = Number(media.auth_version);
      const url = buildMediaUrl({
        secret: config.jwtSecret,
        mediaId,
        variant: "full",
        authVersion, viewerId: req.userId
      });
      res.json({
        ok: true,
        data: {
          mediaId,
          url,
          imageUrl: url,
          thumbnailUrl: buildMediaUrl({
            secret: config.jwtSecret,
            mediaId,
            variant: "thumb",
            authVersion, viewerId: req.userId
          }),
          width: media.width,
          height: media.height
        }
      });
    } catch (error) {
      next(error);
    }
  });

  /* ---------------------------------------------------------------- *
   * GET /:id/content?variant=full|thumb —— 内容端点（不挂 requireAuth）
   *
   * 双通道自鉴权：
   *   A. ?st= 短时效签名（绑定 mediaId+variant+authVersion，无头也能过）
   *   B. Authorization Bearer 会话（owner 本人，或 media 所属 active 共享空间的成员）
   * 响应禁公共缓存（private）。
   * ---------------------------------------------------------------- */
  router.get("/:id/content", async (req, res, next) => {
    try {
      const mediaId = req.params.id;
      const variant = req.query.variant === "thumb" ? "thumb" : "full";

      const [rows] = await pool.execute(
        "SELECT media_id, owner_id, space_id, diary_id, purpose, status, storage_path, thumb_path, auth_version " +
          "FROM diary_media WHERE media_id = ? LIMIT 1",
        [mediaId]
      );
      const media = rows[0];
      if (!media) throw notFoundMedia();

      if (media.status !== "ready") throw notFoundMedia();
      const signed = verifyMediaToken({ secret: config.jwtSecret, mediaId, variant,
        authVersion: Number(media.auth_version), token: req.query.st });
      let viewerId = signed && signed.viewerId;
      const header = req.headers.authorization || "";
      if (header.startsWith("Bearer ")) {
        let session = null;
        try { session = await loadActiveUserSession(pool, verifyAccessToken(header.slice(7))); } catch (_) {}
        // A supplied Bearer session must match the URL audience; never bypass it with a signature.
        if (!session || (viewerId && Number(session.id) !== viewerId)) throw notFoundMedia();
        viewerId = Number(session.id);
      }
      if (!header.startsWith("Bearer ")) throw new ApiError(401, "UNAUTHORIZED", "媒体读取需要当前会话");
      if (!viewerId || !(await canReadMedia(pool, media, viewerId))) throw notFoundMedia();

      const relativePath = variant === "thumb" ? media.thumb_path : media.storage_path;
      if (!relativePath) throw notFoundMedia();
      const absolutePath = mediaAbsolutePath(relativePath);
      try {
        await fs.promises.access(absolutePath, fs.constants.F_OK);
      } catch (_error) {
        throw notFoundMedia();
      }

      res.set("Cache-Control", "private, no-store");
      res.type("image/webp");
      res.sendFile(absolutePath);
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createDiaryMediaRouter, MAX_UPLOAD_BYTES, canReadMedia, cleanupStaleMedia };
