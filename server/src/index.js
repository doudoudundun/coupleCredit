const express = require("express");
const path = require("path");
const cors = require("cors");
const { readConfig } = require("./config");
const { createPool } = require("./db");
const { createAuthRouter } = require("./routes/auth");
const { createBillsRouter } = require("./routes/bills");
const { createInventoryRouter } = require("./routes/inventory");
const { createUploadRouter } = require("./routes/upload");
const { sendError } = require("./errors");

const config = readConfig();
const pool = createPool(config);
const app = express();

app.use(cors());
app.use(express.json());
app.use("/uploads", express.static(path.resolve(__dirname, "../uploads")));
app.use("/api/auth", createAuthRouter({ pool, config }));
app.use("/api/bills", createBillsRouter({ pool }));
app.use("/api/inventory", createInventoryRouter({ pool }));
app.use("/api/upload", createUploadRouter());
app.use((error, _req, res, _next) => {
  console.error("Unhandled error:", error);
  sendError(res, error);
});

app.listen(config.port, config.host, () => {
  console.log(`Local auth API listening on http://${config.host}:${config.port}`);
});
