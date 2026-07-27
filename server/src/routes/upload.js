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
  limits: { fileSize: 5 * 1024 * 1024 },
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

function createUploadRouter() {
  const router = express.Router();

  router.post("/image", upload.single("image"), async (req, res, next) => {
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

module.exports = { createUploadRouter };
