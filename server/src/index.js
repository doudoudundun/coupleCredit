const express = require("express");
const path = require("path");
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
const { sendError } = require("./errors");

const config = readConfig();
const pool = createPool(config);
const app = express();

app.use(cors());
app.use(compression({ level: 6, threshold: 512 }));
app.use(express.json({ limit: "10mb" }));
app.use("/uploads", express.static(path.resolve(__dirname, "../uploads")));

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

app.use("/api/auth", createAuthRouter({ pool, config }));
app.use("/api/bills", createBillsRouter({ pool }));
app.use("/api/inventory", createInventoryRouter({ pool }));
app.use("/api/upload", createUploadRouter());
app.use("/api/recipes", createRecipeRouter({ pool }));
app.use("/api/recipe-categories", createRecipeCategoryRouter({ pool }));
app.use("/api/couple", createCoupleRouter({ pool }));
app.use("/api/chat", createChatRouter({ pool }));
app.use((error, _req, res, _next) => {
  console.error("Unhandled error:", error);
  sendError(res, error);
});

app.listen(config.port, config.host, () => {
  console.log(`Server listening on http://${config.host}:${config.port}`);
  // Warm up DB pool
  pool.query("SELECT 1").then(() => console.log("DB pool warmed up")).catch(e => console.error("DB pool warm-up failed:", e.message));
});
