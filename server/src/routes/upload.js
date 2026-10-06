const express = require("express");
const multer = require("multer");
const path = require("path");
const fs = require("fs");
const crypto = require("crypto");
const sharp = require("sharp");
const { ApiError } = require("../errors");

const UPLOADS_DIR = path.resolve(__dirname, "../../uploads");

if (!fs.existsSync(UPLOADS_DIR)) {
  fs.mkdirSync(UPLOADS_DIR, { recursive: true });
}

// 允许的图片 MIME（sharp 探测真实格式后用于校验）
const ALLOWED_MIME = new Set(["image/jpeg", "image/png", "image/gif", "image/webp"]);

// 单张图片大小上限。集中在这里定义，避免「上限改了但错误提示没跟着改」。
const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
const MAX_IMAGE_MB = Math.round(MAX_IMAGE_BYTES / 1024 / 1024);

const storage = multer.diskStorage({
  destination: (_req, _file, cb) => cb(null, UPLOADS_DIR),
  filename: (_req, file, cb) => {
    const ext = path.extname(file.originalname) || ".jpg";
    const uniqueName = crypto.randomBytes(16).toString("hex") + ext;
    cb(null, uniqueName);
  }
});

const upload = multer({
  storage,
  limits: { fileSize: MAX_IMAGE_BYTES },
  fileFilter: (_req, file, cb) => {
    // 第一道：扩展名快速预筛（含 SVG 禁止，防 stored XSS）
    const allowed = [".jpg", ".jpeg", ".png", ".gif", ".webp"];
    const ext = path.extname(file.originalname).toLowerCase();
    if (!allowed.includes(ext)) {
      return cb(new ApiError(400, "INVALID_FILE", "不支持的图片格式，仅支持 jpg/png/gif/webp"));
    }
    cb(null, true);
  }
});

// multer 抛出的 MulterError 不带 status 字段（超限时是 code=LIMIT_FILE_SIZE），
// 于是被 src/index.js 的全局兜底当成未知错误，返回 HTTP 500「服务器内部错误」——
// 客户端既拿不到 413，也看不到真实原因，只能反复重试。
// 这里统一把 MulterError 转成带 status 的 ApiError，让错误信封符合契约。
// 放在路由层而不是全局错误处理器：只有这里知道大小上限，也能避免把 multer 的
// 专属知识漏进通用兜底逻辑。
const MULTER_ERROR_MAP = {
  LIMIT_FILE_SIZE: [413, "FILE_TOO_LARGE", `图片过大，单张不能超过 ${MAX_IMAGE_MB}MB`],
  LIMIT_FILE_COUNT: [400, "UPLOAD_INVALID", "一次只能上传一张图片"],
  LIMIT_UNEXPECTED_FILE: [400, "UPLOAD_INVALID", "上传字段名不正确，应为 image"],
  LIMIT_PART_COUNT: [400, "UPLOAD_INVALID", "表单字段过多"],
  LIMIT_FIELD_KEY: [400, "UPLOAD_INVALID", "表单字段名过长"],
  LIMIT_FIELD_VALUE: [400, "UPLOAD_INVALID", "表单字段值过大"],
  LIMIT_FIELD_COUNT: [400, "UPLOAD_INVALID", "表单字段过多"]
};

function handleUpload(middleware) {
  return (req, res, next) => {
    middleware(req, res, (error) => {
      if (!error) {
        return next();
      }
      // fileFilter 里抛出的 ApiError 已经带 status，原样放行
      if (error.name !== "MulterError") {
        return next(error);
      }
      const mapped = MULTER_ERROR_MAP[error.code];
      if (!mapped) {
        return next(new ApiError(400, "UPLOAD_INVALID", "上传失败，请检查文件后重试"));
      }
      next(new ApiError(mapped[0], mapped[1], mapped[2]));
    });
  };
}

function createUploadRouter() {
  const router = express.Router();

  router.post("/image", handleUpload(upload.single("image")), async (req, res, next) => {
    try {
      if (!req.file) {
        return res.status(400).json({ ok: false, error: { message: "请选择图片" } });
      }

      // 第二道：用 sharp 读 magic number 校验真实 MIME（防改后缀伪装）
      let realFormat;
      try {
        const meta = await sharp(req.file.path).metadata();
        realFormat = meta.format;
      } catch (_e) {
        // sharp 无法识别 → 非图片，删除并拒绝
        fs.promises.unlink(req.file.path).catch(() => {});
        throw new ApiError(400, "INVALID_FILE", "文件不是有效图片");
      }

      const realMime = realFormat ? `image/${realFormat}` : null;
      if (!realMime || !ALLOWED_MIME.has(realMime)) {
        fs.promises.unlink(req.file.path).catch(() => {});
        throw new ApiError(400, "INVALID_FILE", `文件实际格式 ${realMime || "未知"} 不被支持`);
      }

      const imageUrl = `/uploads/${req.file.filename}`;
      res.json({
        ok: true,
        message: "上传成功",
        data: { imageUrl }
      });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createUploadRouter, MAX_IMAGE_BYTES };
