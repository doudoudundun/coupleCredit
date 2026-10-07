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
const { createPushRouter } = require("./routes/push");
const { createNotificationRouter } = require("./routes/notifications");
const { createAiChatRouter } = require("./routes/aiChat");
const { createPeriodRouter } = require("./routes/period");
const { createMeRouter } = require("./routes/me");
const { createPasswordAccountsRouter } = require("./routes/passwordAccounts");
const { createSecurityRouter } = require("./routes/security");
const { createWechatCallbackRouter } = require("./routes/wechatCallback");
const { createHouseworkRouter } = require("./routes/housework");
const { createCategoryCollectionsRouter } = require("./routes/categoryCollections");
const { createGoalsRouter } = require("./routes/goals");
const { createInvitationsRouter } = require("./routes/invitations");
const { createRelationshipsRouter } = require("./routes/relationships");
const { createDiaryRouter } = require("./routes/diary");
const { createDiaryMediaRouter } = require("./routes/diaryMedia");
const { createWechatAccessTokenProvider } = require("./utils/wechatAccessToken");
const { createContentSecurityService } = require("./services/contentSecurity");
const { initializeApp: initFcm } = require("./services/fcmService");
const { sendError } = require("./errors");
const { createRequireAuth } = require("./middleware/auth");
const { standardLimiter, authLimiter, strictLimiter, aiLimiter } = require("./middleware/rateLimit");

const config = readConfig();
const pool = createPool(config);
const app = express();
const authMiddleware = createRequireAuth(pool);
app.locals.authPool = pool;

// 内容安全（小程序 UGC 合规）：文本走 msg_sec_check（同步），图片走 media_check_async（异步回调）。
// access_token 用 stable_token 获取并缓存，避免与 Android 端互相顶掉。
const wechatAccessTokenProvider = createWechatAccessTokenProvider({
  appId: config.wechatAppId,
  appSecret: config.wechatSecret
});
const contentSecurity = createContentSecurityService({
  pool,
  config,
  accessTokenProvider: wechatAccessTokenProvider
});
app.locals.contentSecurity = contentSecurity;

app.set("trust proxy", "loopback");

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
// auth 内部的敏感端点（profile/avatar/nickname/password/account）自行挂 requireAuth
app.use("/api/auth", createAuthRouter({ pool, config, authLimiter, strictLimiter, contentSecurity, requireAuth: authMiddleware }));
// 微信「消息推送」回调（图片内容安全的异步结果只从这里回来）。
// 必须挂在全局鉴权之前：微信不会带 Authorization，验签由 msg_signature 自己完成。
app.use("/api/wechat", createWechatCallbackRouter({ config, contentSecurity, pool }));
// v0.2 共同目标/共同小记：邀请 preview 与小记媒体内容端点允许未登录访问
// （媒体内容用 st 签名或 Bearer 双通道自鉴权），两个路由对敏感端点自行挂 authMiddleware，
// 因此必须挂在全局鉴权之前。
app.use("/api/invitations", createInvitationsRouter({ pool, requireAuth: authMiddleware }));
app.use("/api/diary-media", createDiaryMediaRouter({ pool, config, requireAuth: authMiddleware }));
// /uploads 静态资源（图片）公开访问：App 用 Glide 加载图片不带 token，
// 文件名随机不可猜作为隐私防护。支持 ?w= 实时缩放转 webp（remove-bg 生成的 nobg PNG 单张 4MB+）。
const { imageResizeMiddleware } = require("./middleware/imageResize");
app.use("/uploads", imageResizeMiddleware(path.resolve(__dirname, "../uploads")));
app.use(express.static(path.resolve(__dirname, "../public")));
// 业务接口只接受 JWT 身份；query/body.userId 仅可作为普通业务字段，不能用于鉴权。
app.use(authMiddleware);

// Request timing
app.use((req, res, next) => {
  const start = Date.now();
  res.on("finish", () => {
    const ms = Date.now() - start;
    if (ms > 500) {
      console.warn(`SLOW ${req.method} ${req.path} ${ms}ms ${res.statusCode}`);
    } else {
      console.log(`${req.method} ${req.path} ${ms}ms ${res.statusCode}`);
    }
  });
  next();
});

app.use("/api/security", createSecurityRouter({ contentSecurity, pool }));
app.use("/api/bills", createBillsRouter({ pool }));
// Expensive image generation is throttled at its concrete route.  This keeps
// ordinary /api endpoints out of the AI quota while preserving the existing
// /api/image-gen URL handled by createImageGenRouter().
app.use("/api/inventory/generate-image", aiLimiter);
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
app.use("/api/image-gen", aiLimiter);
app.use("/api", createImageGenRouter());
app.use("/api/assets", createAssetsRouter({ pool }));
app.use("/api/push", createPushRouter({ pool }));
app.use("/api/notifications", createNotificationRouter({ pool }));
app.use("/api/ai-chat/analyze", aiLimiter);
app.use("/api/ai-chat", createAiChatRouter({ pool }));
app.use("/api/period", createPeriodRouter({ pool }));
app.use("/api/me", createMeRouter({ pool }));
app.use("/api/password-accounts", createPasswordAccountsRouter({ pool, config }));
app.use("/api/housework", createHouseworkRouter({ pool, config }));
app.use("/api", createCategoryCollectionsRouter({ pool }));
// v0.2 共同目标 / 解除关系 / 共同小记（全部要求登录，挂在全局鉴权之后）
app.use("/api", createGoalsRouter({ pool }));
app.use("/api", createRelationshipsRouter({ pool }));
app.use("/api", createDiaryRouter({ pool, config }));
app.use((error, _req, res, _next) => {
  console.error("Unhandled error:", { code: error.code || "INTERNAL_ERROR", status: error.status || 500 });
  sendError(res, error, _req.requestId);
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
