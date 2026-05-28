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
const { initializeApp: initFcm } = require("./services/fcmService");
const { sendError } = require("./errors");
const { optionalAuth } = require("./middleware/auth");
const { standardLimiter, authLimiter, strictLimiter, aiLimiter } = require("./middleware/rateLimit");

const config = readConfig();
const pool = createPool(config);
const app = express();

app.set("trust proxy", 1);

app.use(cors());
app.use(compression({ level: 6, threshold: 512 }));
app.use(express.json({ limit: "10mb" }));
app.use(standardLimiter);
app.use(optionalAuth);
app.use("/uploads", express.static(path.resolve(__dirname, "../uploads")));
app.use(express.static(path.resolve(__dirname, "../public")));

// Request timing + health check
app.get("/api/health", (_req, res) => {
  res.json({ ok: true, uptime: process.uptime(), rss: Math.round(process.memoryUsage().rss / 1024 / 1024) + "MB" });
});

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

app.use("/api/auth", createAuthRouter({ pool, config, authLimiter, strictLimiter }));
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
