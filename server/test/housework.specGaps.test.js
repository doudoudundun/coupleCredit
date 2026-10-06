/**
 * Focused real-API checks for housework Spec gaps F05, F09 and F10.
 * Creates one independent local test account and only writes that account's personal housework data.
 */
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { test } = require("node:test");

const baseUrl = process.env.AUTH_API_BASE_URL || "http://127.0.0.1:18103";
assert.ok(["127.0.0.1", "localhost", "[::1]"].includes(new URL(baseUrl).hostname), "spec-gap API checks are restricted to localhost");

function readInviteCode() {
  if (process.env.AUTH_API_INVITE_CODE) return process.env.AUTH_API_INVITE_CODE;
  if (process.env.INVITE_CODE) return process.env.INVITE_CODE;
  const envPath = path.resolve(__dirname, "../.env");
  if (!fs.existsSync(envPath)) return "";
  const line = fs.readFileSync(envPath, "utf8").split(/\r?\n/).find((entry) => /^\s*INVITE_CODE\s*=/.test(entry));
  if (!line) return "";
  const value = line.slice(line.indexOf("=") + 1).trim();
  return value.length >= 2 && ((value[0] === '"' && value.at(-1) === '"') || (value[0] === "'" && value.at(-1) === "'"))
    ? value.slice(1, -1)
    : value;
}

const inviteCode = readInviteCode();
const password = "secret123";

async function readJsonOrText(response) {
  const text = await response.text();
  try { return JSON.parse(text); } catch (_error) { return null; }
}

async function api(user, method, route, body) {
  const headers = { "Content-Type": "application/json" };
  if (user && user.accessToken) headers.Authorization = `Bearer ${user.accessToken}`;
  const response = await fetch(`${baseUrl}${route}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(15000)
  });
  return { status: response.status, body: await readJsonOrText(response) };
}

function uuid() { return globalThis.crypto.randomUUID(); }

async function registerUser() {
  assert.ok(inviteCode, "set AUTH_API_INVITE_CODE or provide INVITE_CODE in server/.env");
  const { TEXT_PROMO_PATTERNS } = require("../src/services/contentSecurity");
  let username = "";
  for (let attempt = 0; attempt < 10 && !username; attempt += 1) {
    const random = Math.floor(Math.random() * 1679616).toString(36).padStart(4, "0");
    const candidate = `hwgap${Date.now().toString(36).replace(/v/g, "w")}${random}`;
    if (!TEXT_PROMO_PATTERNS.some((pattern) => pattern.re.test(candidate))) username = candidate;
  }
  assert.ok(username, "could not generate a safe test username");

  const registered = await api(null, "POST", "/api/auth/register", {
    username,
    email: `${username}@example.com`,
    password,
    inviteCode
  });
  assert.equal(registered.status, 201, "register isolated spec-gap test account");

  const loggedIn = await api(null, "POST", "/api/auth/login", { username, password });
  assert.equal(loggedIn.status, 200, "log in isolated spec-gap test account");
  assert.ok(loggedIn.body && loggedIn.body.data && loggedIn.body.data.accessToken, "login must return a test session");
  return loggedIn.body.data;
}

test("housework Spec gaps F05/F09/F10 pass through the local API", async () => {
  const user = await registerUser();
  const bootstrap = await api(user, "POST", "/api/housework/bootstrap", {});
  assert.equal(bootstrap.status, 200, "bootstrap personal housework space");
  const spaceId = bootstrap.body.data.defaultSpaceId;
  assert.ok(spaceId, "bootstrap must return the default personal space");

  const unique = Date.now().toString(36).replace(/v/g, "w");
  const categoryResponse = await api(user, "POST", `/api/housework/spaces/${spaceId}/categories`, {
    clientMutationId: uuid(),
    name: `回归类${unique.slice(-8)}`
  });
  assert.equal(categoryResponse.status, 200, "create isolated category fixture");
  const category = categoryResponse.body.data.entity;

  const configResponse = await api(user, "GET", `/api/housework/spaces/${spaceId}/config`);
  assert.equal(configResponse.status, 200, "read initial personal config");
  const config = configResponse.body.data;
  const originalIds = config.categories.map((category) => category.categoryId);
  assert.ok(originalIds.length >= 2, "complete category order requires multiple active categories");
  const reorderedIds = originalIds.slice().reverse();
  const orderBody = {
    clientMutationId: uuid(),
    expectedRevision: config.revision,
    activeCategoryIds: reorderedIds
  };
  const ordered = await api(user, "PUT", `/api/housework/spaces/${spaceId}/categories/order`, orderBody);
  const orderError = ordered.body && ordered.body.error;
  const orderErrorSummary = orderError
    ? `${orderError.code || "unknown"}${Array.isArray(orderError.fieldErrors) ? `:${orderError.fieldErrors.map((error) => `${error.path || "?"}/${error.code || "?"}`).join(",")}` : ""}`
    : "unknown";
  assert.equal(ordered.status, 200, `save complete active category order (${orderErrorSummary})`);
  assert.deepEqual(ordered.body.data.entity.activeCategoryIds, reorderedIds);
  assert.ok(ordered.body.data.revision > config.revision, "a changed order must advance the space revision");
  const reorderedFixture = ordered.body.data.entity.categories.find((item) => item.categoryId === category.categoryId);
  assert.ok(reorderedFixture, "order result must include the isolated category fixture");
  category.version = reorderedFixture.version;

  const replay = await api(user, "PUT", `/api/housework/spaces/${spaceId}/categories/order`, orderBody);
  assert.equal(replay.status, 200, "replay category order with the same mutation ID");
  assert.equal(replay.body.data.replayed, true);
  assert.deepEqual(replay.body.data.entity.activeCategoryIds, reorderedIds);

  const stale = await api(user, "PUT", `/api/housework/spaces/${spaceId}/categories/order`, {
    clientMutationId: uuid(),
    expectedRevision: config.revision,
    activeCategoryIds: originalIds
  });
  assert.equal(stale.status, 409, "reject stale category collection revision");
  assert.equal(stale.body.error.code, "VERSION_CONFLICT");

  const templateResponse = await api(user, "POST", `/api/housework/spaces/${spaceId}/templates`, {
    clientMutationId: uuid(),
    name: `回归模板${unique.slice(-8)}`,
    categoryId: category.categoryId,
    measureMode: "quantity",
    unit: "件"
  });
  assert.equal(templateResponse.status, 200, "create isolated template fixture");
  const template = templateResponse.body.data.entity;

  const archivedTemplate = await api(user, "PATCH", `/api/housework/spaces/${spaceId}/templates/${template.templateId}`, {
    clientMutationId: uuid(), expectedVersion: template.version, status: "archived"
  });
  assert.equal(archivedTemplate.status, 200, "archive isolated template fixture");
  const archivedCategory = await api(user, "PATCH", `/api/housework/spaces/${spaceId}/categories/${category.categoryId}`, {
    clientMutationId: uuid(), expectedVersion: category.version, status: "archived"
  });
  assert.equal(archivedCategory.status, 200, "archive now-unused isolated category fixture");

  const restore = await api(user, "PATCH", `/api/housework/spaces/${spaceId}/templates/${template.templateId}`, {
    clientMutationId: uuid(),
    expectedVersion: archivedTemplate.body.data.entity.version,
    status: "active"
  });
  assert.equal(restore.status, 422, "reject template restore into archived category");
  assert.equal(restore.body.error.code, "VALIDATION_ERROR");
  assert.ok(restore.body.error.fieldErrors.some((error) => error.path === "categoryId" && error.code === "INVALID_OPTION"));

  const longRange = await api(user, "GET", `/api/housework/spaces/${spaceId}/statistics?dateFrom=2000-01-01`);
  assert.equal(longRange.status, 422, "bound missing dateTo to the server month and enforce the 366-day limit");
  assert.ok(longRange.body.error.fieldErrors.some((error) => error.path === "dateTo" && error.code === "OUT_OF_RANGE"));
});
