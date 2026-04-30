const express = require("express");
const multer = require("multer");
const path = require("path");
const fs = require("fs");
const crypto = require("crypto");
const { ApiError } = require("../errors");

const UPLOADS_DIR = path.resolve(__dirname, "../../uploads");

if (!fs.existsSync(UPLOADS_DIR)) {
  fs.mkdirSync(UPLOADS_DIR, { recursive: true });
}

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
    const allowed = [".jpg", ".jpeg", ".png", ".gif", ".webp"];
    const ext = path.extname(file.originalname).toLowerCase();
    if (allowed.includes(ext)) {
      cb(null, true);
    } else {
      cb(new ApiError(400, "INVALID_FILE", "不支持的图片格式，仅支持 jpg/png/gif/webp"));
    }
  }
});

function createUploadRouter() {
  const router = express.Router();

  router.post("/image", upload.single("image"), (req, res) => {
    if (!req.file) {
      return res.status(400).json({ ok: false, error: { message: "请选择图片" } });
    }

    const imageUrl = `/uploads/${req.file.filename}`;
    res.json({
      ok: true,
      message: "上传成功",
      data: { imageUrl }
    });
  });

  return router;
}

module.exports = { createUploadRouter };
