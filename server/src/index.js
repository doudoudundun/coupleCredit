const fs = require("fs");
const path = require("path");

function loadDotEnv() {
  const envPath = path.resolve(__dirname, "../.env");
  if (!fs.existsSync(envPath)) {
    return;
  }
  const lines = fs.readFileSync(envPath, "utf8").split(/\r?\n/);
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) {
      continue;
    }
    const index = trimmed.indexOf("=");
    if (index <= 0) {
      continue;
    }
    const key = trimmed.slice(0, index).trim();
    const value = trimmed.slice(index + 1).trim();
    if (process.env[key] === undefined) {
      process.env[key] = value;
    }
  }
}

loadDotEnv();

const express = require("express");
const cors = require("cors");
const compression = require("compression");
const helmet = require("helmet");
const { readConfig } = require("./config");
const { createPool } = require("./db");
const { createAuthRouter } = require("./routes/auth");
const { createBillsRouter } = require("./routes/bills");
const { createInventoryRouter } = require("./routes/inventory");
const { createUploadRouter } = require("./routes/upload");
const { createRecipeRouter } = require("./routes/recipes");
const { createRecipeCategoryRouter } = require("./routes/recipeCategories");
const { createCoupleRouter } = require("./routes/couple");
const { createChatRouter } = require("./routes/chat");
const { createSharedPlansRouter } = require("./routes/sharedPlans");
const { createTodoRouter } = require("./routes/todos");
const { createBeadRouter } = require("./routes/beads");
const { createRestaurantRouter } = require("./routes/restaurants");
const { createCalorieRouter, createNutritionRouter } = require("./routes/calorie");
const { createImageGenRouter } = require("./routes/imageGen");
const { createAssetsRouter } = require("./routes/assets");
const { createFcmRouter } = require("./routes/fcm");
const { createPushRouter } = require("./routes/push");
const { createNotificationRouter } = require("./routes/notifications");
const { createAiChatRouter } = require("./routes/aiChat");
const { createPeriodRouter } = require("./routes/period");
const { createMeRouter } = require("./routes/me");
const { createPasswordAccountsRouter } = require("./routes/passwordAccounts");
const { initializeApp: initFcm } = require("./services/fcmService");
const { sendError } = require("./errors");
const { requireAuthForBusiness } = require("./middleware/auth");
const { standardLimiter, authLimiter, strictLimiter, aiLimiter } = require("./middleware/rateLimit");

const config = readConfig();
const pool = createPool(config);
const app = express();

app.set("trust proxy", 1);

// 安全响应头（HSTS/X-Frame-Options/X-Content-Type-Options 等）
// crossOriginResourcePolicy 设为 cross-origin，允许 App 端跨域加载 /uploads 图片
app.use(helmet({ crossOriginResourcePolicy: { policy: "cross-origin" } }));
app.use(cors({ origin: config.allowedOrigins, credentials: true }));
app.use(compression({ level: 6, threshold: 512 }));
app.use(express.json({ limit: "10mb" }));
app.use(standardLimiter);
// 健康检查不需要鉴权，放在全局鉴权之前
app.get("/api/health", (_req, res) => {
  res.json({ ok: true, uptime: process.uptime(), rss: Math.round(process.memoryUsage().rss / 1024 / 1024) + "MB" });
});
// auth 路由挂在全局鉴权之前：login/register/refresh/wechat 等公开端点不需要 token；
// auth 内部的敏感端点（profile/avatar/nickname/password/account）自行挂 requireAuthForBusiness
app.use("/api/auth", createAuthRouter({ pool, config, authLimiter, strictLimiter }));
// /uploads 静态资源（图片）公开访问：App 用 Glide 加载图片不带 token，
// 文件名随机不可猜作为隐私防护。支持 ?w= 实时缩放转 webp（remove-bg 生成的 nobg PNG 单张 4MB+）。
const { imageResizeMiddleware } = require("./middleware/imageResize");
app.use("/uploads", imageResizeMiddleware(path.resolve(__dirname, "../uploads")));
app.use(express.static(path.resolve(__dirname, "../public")));
// 业务全局鉴权（兼容期：无 token 降级 userId 并打 warn；App 联调通过后切纯 requireAuth）
app.use(requireAuthForBusiness);

// Request timing
app.use((req, res, next) => {
  const start = Date.now();
  res.on("finish", () => {
    const ms = Date.now() - start;
    if (ms > 500) {
      console.warn(`SLOW ${req.method} ${req.originalUrl} ${ms}ms ${res.statusCode}`);
    } else {
      console.log(`${req.method} ${req.originalUrl} ${ms}ms ${res.statusCode}`);
    }
  });
  next();
});

app.use("/api/bills", createBillsRouter({ pool }));
app.use("/api/inventory", createInventoryRouter({ pool }));
app.use("/api/upload", createUploadRouter());
app.use("/api/recipes", createRecipeRouter({ pool }));
app.use("/api/recipe-categories", createRecipeCategoryRouter({ pool }));
app.use("/api/couple", createCoupleRouter({ pool }));
app.use("/api/chat", createChatRouter({ pool }));
app.use("/api/shared-plans", createSharedPlansRouter({ pool }));
app.use("/api/todos", createTodoRouter({ pool }));
app.use("/api/beads", createBeadRouter({ pool, aiLimiter }));
app.use("/api/restaurants", createRestaurantRouter({ pool }));
app.use("/api/calorie", createCalorieRouter({ pool }));
app.use("/api/nutrition", createNutritionRouter({ pool }));
app.use("/api", aiLimiter, createImageGenRouter());
app.use("/api/assets", createAssetsRouter({ pool }));
app.use("/api/fcm", createFcmRouter({ pool }));
app.use("/api/push", createPushRouter({ pool }));
app.use("/api/notifications", createNotificationRouter({ pool }));
app.use("/api/ai-chat", aiLimiter, createAiChatRouter({ pool }));
app.use("/api/period", createPeriodRouter({ pool }));
app.use("/api/me", createMeRouter({ pool }));
app.use("/api/password-accounts", createPasswordAccountsRouter({ pool, config }));
app.use((error, _req, res, _next) => {
  console.error("Unhandled error:", error);
  sendError(res, error);
});

const server = app.listen(config.port, config.host, () => {
  console.log(`Server listening on http://${config.host}:${config.port}`);

  initFcm();
});

function gracefulShutdown() {
  console.log("Shutting down gracefully...");
  server.close(() => {
    console.log("HTTP server closed.");
    pool.end().then(() => {
      console.log("DB pool closed.");
      process.exit(0);
    });
  });
  setTimeout(() => {
    console.error("Force shutdown after timeout.");
    process.exit(1);
  }, 10000);
}

process.on("SIGTERM", gracefulShutdown);
process.on("SIGINT", gracefulShutdown);
