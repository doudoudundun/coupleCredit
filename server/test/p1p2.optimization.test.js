const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const {
  chunkTokens,
} = require("../src/services/fcmService");
const {
  buildBillsSummary,
  buildTodosSummary,
  buildInventorySummary,
} = require("../src/routes/me");

test("chunkTokens splits push tokens into batches of at most 500", () => {
  const tokens = Array.from({ length: 1001 }, (_, index) => `token-${index}`);

  const batches = chunkTokens(tokens, 500);

  assert.deepEqual(batches.map(batch => batch.length), [500, 500, 1]);
  assert.equal(batches.flat().length, tokens.length);
  assert.deepEqual(batches.flat(), tokens);
});

test("login inventory preload uses the current shelf-life columns", () => {
  const authSource = fs.readFileSync(path.join(__dirname, "../src/routes/auth.js"), "utf8");

  assert.doesNotMatch(authSource, /\bexpiry_date\b|\bpurchase_date\b/);
  assert.match(authSource, /INVENTORY_SELECT_FIELDS/);
});

test("buildBillsSummary aggregates income, expense and count without returning bill rows", () => {
  assert.deepEqual(buildBillsSummary([
    { incomeType: 0, amount: "12.50" },
    { incomeType: 1, amount: "100" },
    { incomeType: 0, amount: 7 },
  ]), {
    billCount: 3,
    income: 100,
    expense: 19.5,
  });
});

test("buildTodosSummary counts open, done and missed statuses", () => {
  assert.deepEqual(buildTodosSummary([
    { status: "open" },
    { status: "done" },
    { status: "missed" },
    { status: "open" },
  ]), {
    openCount: 2,
    doneCount: 1,
    missedCount: 1,
  });
});

test("buildInventorySummary counts low stock and expiration flags", () => {
  assert.deepEqual(buildInventorySummary([
    { quantity: 1, threshold: 2, isExpired: false, isExpiring: true },
    { quantity: 5, threshold: 2, isExpired: true, isExpiring: false },
    { quantity: 8, threshold: 2, isExpired: false, isExpiring: false },
  ]), {
    itemCount: 3,
    lowStockCount: 1,
    expiringCount: 2,
  });
});

test("Android API fallback and settings use the current remote domain", () => {
  const apiConfigSource = fs.readFileSync(
    path.join(__dirname, "../../app/src/main/java/com/example/couplecredit/config/ApiConfigManager.java"),
    "utf8",
  );
  const settingsSource = fs.readFileSync(
    path.join(__dirname, "../../app/src/main/java/com/example/couplecredit/activity/UserSettingsActivity.java"),
    "utf8",
  );

  assert.match(apiConfigSource, /STABLE_BASE_URL = "https:\/\/api\.couplecredit\.top"/);
  assert.doesNotMatch(apiConfigSource, /api\.datafun\.online/);
  assert.doesNotMatch(settingsSource, /api\.datafun\.online/);
});
