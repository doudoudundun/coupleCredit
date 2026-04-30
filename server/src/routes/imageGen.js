const express = require("express");
const https = require("https");
const http = require("http");
const crypto = require("crypto");
const fsPromises = require("fs").promises;
const path = require("path");

function createImageGenRouter() {
  const router = express.Router();

  router.post("/image-gen", async (req, res, next) => {
    try {
      const prompt = (req.body.prompt || "").trim();
      if (!prompt) {
        return res.status(400).json({ ok: false, error: "请输入提示词" });
      }

      const apiKey = process.env.AI_IMAGE_API_KEY || process.env.AI_API_KEY;
      const baseUrl = process.env.AI_IMAGE_BASE_URL || "";
      const model = process.env.AI_IMAGE_MODEL || "gpt-image-2";

      if (!apiKey || !baseUrl) {
        return res.status(503).json({ ok: false, error: "AI 生图服务未配置" });
      }

      const payload = {
        model,
        prompt,
        n: 1,
        size: "auto"
      };

      let aiResult;
      try {
        aiResult = await callImageApi(baseUrl, apiKey, payload);
      } catch (apiErr) {
        console.error("Image gen API error:", apiErr.message);
        if (apiErr.message.includes("timeout")) {
          return res.status(504).json({ ok: false, error: "生图超时，请稍后重试" });
        }
        return res.status(502).json({ ok: false, error: "AI 生图服务异常，请稍后重试" });
      }

      if (aiResult.error) {
        console.error("Image gen API returned error:", JSON.stringify(aiResult.error));
        return res.status(502).json({ ok: false, error: "AI 生图失败：" + (aiResult.error.message || "未知错误") });
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
        return res.status(502).json({ ok: false, error: "AI 生图未返回有效图片" });
      }

      res.json({ ok: true, image_url: imageUrl });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

function resolveImageApiUrl(baseUrl) {
  const trimmed = String(baseUrl || "").replace(/\/$/, "");
  if (trimmed.endsWith("/images/generations")) {
    return trimmed;
  }
  if (trimmed.endsWith("/v1")) {
    return `${trimmed}/images/generations`;
  }
  return `${trimmed}/v1/images/generations`;
}

function callImageApi(baseUrl, apiKey, payload) {
  return new Promise((resolve, reject) => {
    const url = new URL(resolveImageApiUrl(baseUrl));
    const isHttps = url.protocol === "https:";
    const requester = isHttps ? https : http;

    const body = JSON.stringify(payload);
    const options = {
      hostname: url.hostname,
      port: url.port || (isHttps ? 443 : 80),
      path: url.pathname + url.search,
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${apiKey}`,
        "Content-Length": Buffer.byteLength(body)
      },
      timeout: 180000
    };

    const req = requester.request(options, (apiRes) => {
      let data = "";
      apiRes.on("data", (chunk) => { data += chunk; });
      apiRes.on("end", () => {
        try {
          resolve(JSON.parse(data));
        } catch (e) {
          reject(new Error(`AI Image API response parse error: ${data.substring(0, 200)}`));
        }
      });
    });

    req.on("error", reject);
    req.on("timeout", () => { req.destroy(); reject(new Error("AI Image API timeout")); });
    req.write(body);
    req.end();
  });
}

module.exports = { createImageGenRouter };
