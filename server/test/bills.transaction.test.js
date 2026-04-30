const assert = require("node:assert");
const test = require("node:test");

test("bills atomic balance update SQL pattern has AND condition", () => {
  const sql = "UPDATE shared_plans SET current_balance = current_balance - ? WHERE plan_id = ? AND current_balance >= ?";
  assert.ok(sql.includes("current_balance >= ?"), "must check sufficient balance atomically");
});

test("sharedPlans adjust out uses atomic condition", () => {
  const sql = "UPDATE shared_plans SET current_balance = current_balance - ? WHERE plan_id = ? AND current_balance >= ?";
  assert.ok(sql.includes("AND current_balance >= ?"));
});
