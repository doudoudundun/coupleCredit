const assert = require("node:assert");
const express = require("express");
const fs = require("node:fs");
const http = require("node:http");
const path = require("node:path");
const test = require("node:test");
const { standardLimiter, authLimiter, strictLimiter, aiLimiter } = require("../src/middleware/rateLimit");

test("standardLimiter is a function (Express middleware)", () => {
  assert.strictEqual(typeof standardLimiter, "function");
});

test("authLimiter is a function (Express middleware)", () => {
  assert.strictEqual(typeof authLimiter, "function");
});

test("strictLimiter is a function (Express middleware)", () => {
  assert.strictEqual(typeof strictLimiter, "function");
});

test("server mounts the AI limiter only at expensive concrete paths", () => {
  const source = fs.readFileSync(path.join(__dirname, "../src/index.js"), "utf8");
  assert.doesNotMatch(source, /app\.use\(["']\/api["'],\s*aiLimiter/);
  assert.match(source, /app\.use\(["']\/api\/image-gen["'],\s*aiLimiter\)/);
  assert.match(source, /app\.use\(["']\/api\/inventory\/generate-image["'],\s*aiLimiter\)/);
  assert.match(source, /app\.use\(["']\/api\/ai-chat\/analyze["'],\s*aiLimiter\)/);
  assert.match(source, /app\.use\(["']\/api\/ai-chat["'],\s*createAiChatRouter/);
});

function request(server, path, forwardedFor) {
  return new Promise((resolve, reject) => {
    const address = server.address();
    const req = http.request({
      host: "127.0.0.1",
      port: address.port,
      path,
      method: "POST",
      headers: { "X-Forwarded-For": forwardedFor },
    }, (res) => {
      res.resume();
      res.on("end", () => resolve(res));
    });
    req.on("error", reject);
    req.end();
  });
}

test("AI quota is scoped to expensive paths and counts one request once", async () => {
  const app = express();
  app.set("trust proxy", 1);
  app.post("/api/ordinary", (_req, res) => res.sendStatus(204));
  app.use("/api/image-gen", aiLimiter);
  app.post("/api/image-gen", (_req, res) => res.sendStatus(204));
  app.use("/api/ai-chat/analyze", aiLimiter);
  app.post("/api/ai-chat/analyze", (_req, res) => res.sendStatus(204));
  app.post("/api/ai-chat/extractions", (_req, res) => res.sendStatus(204));
  const server = await new Promise((resolve) => {
    const created = app.listen(0, "127.0.0.1", () => resolve(created));
  });
  try {
    const ordinary = [];
    for (let i = 0; i < 20; i++) ordinary.push(await request(server, "/api/ordinary", "192.0.2.40"));
    assert.equal(ordinary.every((response) => response.statusCode === 204), true);

    const image = [];
    for (let i = 0; i < 16; i++) image.push(await request(server, "/api/image-gen", "192.0.2.41"));
    assert.equal(image.slice(0, 15).every((response) => response.statusCode === 204), true);
    assert.equal(image[15].statusCode, 429);

    const extractions = [];
    for (let i = 0; i < 20; i++) extractions.push(await request(server, "/api/ai-chat/extractions", "192.0.2.42"));
    assert.equal(extractions.every((response) => response.statusCode === 204), true);

    const chatAnalyze = [];
    for (let i = 0; i < 16; i++) chatAnalyze.push(await request(server, "/api/ai-chat/analyze", "192.0.2.43"));
    assert.equal(chatAnalyze.slice(0, 15).every((response) => response.statusCode === 204), true);
    assert.equal(chatAnalyze[15].statusCode, 429);
    assert.equal(chatAnalyze[0].headers["ratelimit-remaining"], "14");
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});
