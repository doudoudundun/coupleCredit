"use strict";

/**
 * /uploads 图片动态缩放中间件。
 *
 * 用法：app.use("/uploads", imageResizeMiddleware(uploadsDir))
 *
 * 行为：
 *  - 不带 ?w= 参数：直接返回原图（走 express.static）。
 *  - 带 ?w=300：用 sharp 实时缩放到指定宽度（保持比例），转 webp（体积小很多），
 *    结果缓存到 <uploadsDir>/cache/w300/<filename>.webp，下次直接返回缓存文件。
 *
 * 解决问题：remove-bg 生成的 nobg_*.png 单张 4MB+，App 列表加载慢。
 * 列表用 ?w=400 缩略图（约 20-50KB），详情页用原图。
 */

const path = require("path");
const fs = require("fs");
const sharp = require("sharp");
const express = require("express");

function imageResizeMiddleware(uploadsDir) {
  const cacheDir = path.resolve(uploadsDir, "cache");
  if (!fs.existsSync(cacheDir)) {
    fs.mkdirSync(cacheDir, { recursive: true });
  }

  const staticMiddleware = express.static(uploadsDir, {
    setHeaders: (res) => {
      res.setHeader("X-Content-Type-Options", "nosniff");
      res.setHeader("Cache-Control", "public, max-age=86400");
    }
  });

  return (req, res, next) => {
    const widthParam = req.query.w;
    // 没带 w 参数，或 w 不是正整数，走原图
    const width = parseInt(widthParam, 10);
    if (!widthParam || isNaN(width) || width <= 0 || width > 2000) {
      return staticMiddleware(req, res, next);
    }

    const filename = path.basename(req.path);
    const sourcePath = path.resolve(uploadsDir, filename);
    if (!fs.existsSync(sourcePath)) {
      return staticMiddleware(req, res, next);
    }

    const widthKey = `w${width}`;
    const widthCacheDir = path.resolve(cacheDir, widthKey);
    if (!fs.existsSync(widthCacheDir)) {
      fs.mkdirSync(widthCacheDir, { recursive: true });
    }
    const cachedPath = path.resolve(widthCacheDir, filename + ".webp");

    // 命中缓存：直接返回
    if (fs.existsSync(cachedPath)) {
      res.setHeader("Content-Type", "image/webp");
      res.setHeader("Cache-Control", "public, max-age=604800"); // 7 天
      res.setHeader("X-Content-Type-Options", "nosniff");
      return fs.createReadStream(cachedPath).pipe(res);
    }

    // 未命中：实时缩放 + 转 webp，写缓存并返回
    sharp(sourcePath)
      .resize({ width, withoutEnlargement: true })
      .webp({ quality: 80 })
      .toFile(cachedPath)
      .then(() => {
        res.setHeader("Content-Type", "image/webp");
        res.setHeader("Cache-Control", "public, max-age=604800");
        res.setHeader("X-Content-Type-Options", "nosniff");
        fs.createReadStream(cachedPath).pipe(res);
      })
      .catch((err) => {
        // 缩放失败：回退到原图
        console.error("image resize failed:", filename, err.message);
        return staticMiddleware(req, res, next);
      });
  };
}

module.exports = { imageResizeMiddleware };
