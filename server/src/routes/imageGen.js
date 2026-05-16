const express = require("express");
const crypto = require("crypto");
const fsPromises = require("fs").promises;
const path = require("path");
const { ApiError } = require("../errors");
const { callImageApi } = require("../utils/imageApi");

function createImageGenRouter() {
  const router = express.Router();

  router.post("/image-gen", async (req, res, next) => {
    try {
      const prompt = (req.body.prompt || "").trim();
      if (!prompt) {
        throw new ApiError(400, "INVALID_REQUEST", "请输入提示词");
      }

      const apiKey = process.env.AI_IMAGE_API_KEY || process.env.AI_API_KEY;
      const baseUrl = process.env.AI_IMAGE_BASE_URL || "";
      const model = process.env.AI_IMAGE_MODEL || "gpt-image-2";

      if (!apiKey || !baseUrl) {
        throw new ApiError(503, "SERVICE_UNAVAILABLE", "AI 生图服务未配置");
      }

      const payload = { model, prompt, n: 1, size: "auto" };

      let aiResult;
      try {
        aiResult = await callImageApi(baseUrl, apiKey, payload, 180000);
      } catch (apiErr) {
        console.error("Image gen API error:", apiErr.message);
        if (apiErr.message.includes("timeout")) {
          throw new ApiError(504, "AI_TIMEOUT", "生图超时，请稍后重试");
        }
        throw new ApiError(502, "AI_API_ERROR", "AI 生图服务异常，请稍后重试");
      }

      if (aiResult.error) {
        console.error("Image gen API returned error:", JSON.stringify(aiResult.error));
        throw new ApiError(502, "AI_API_ERROR", "AI 生图失败：" + (aiResult.error.message || "未知错误"));
      }

      const firstItem = aiResult.data && aiResult.data[0];
      let imageUrl = null;

      if (firstItem) {
        if (firstItem.url) {
          imageUrl = firstItem.url;
        } else if (firstItem.b64_json) {
          const buffer = Buffer.from(firstItem.b64_json, "base64");
          const filename = `ai_${Date.now()}_${crypto.randomBytes(4).toString("hex")}.png`;
          const savePath = path.join(__dirname, "../../uploads", filename);
          await fsPromises.writeFile(savePath, buffer);
          imageUrl = `/uploads/${filename}`;
        }
      }

      if (!imageUrl) {
        throw new ApiError(502, "AI_API_ERROR", "AI 生图未返回有效图片");
      }

      res.json({ ok: true, data: { image_url: imageUrl } });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = { createImageGenRouter };
