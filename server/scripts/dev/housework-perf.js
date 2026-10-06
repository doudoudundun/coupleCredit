#!/usr/bin/env node
/**
 * 家务模块性能实测（AC-22，Spec 第 10 节规模与口径）。
 *
 * 规模：200 启用模板 / 50 启用分类 / 每空间 10000 条记录（近 400 天分布，
 *       其中最近 366 天范围 ≥3000 条）。
 * 样本：列表 / 统计 / 提交 各 ≥30 次采样，输出 p50/p95/max。
 * 环境：本地稳定网络（127.0.0.1，RTT≈0，响应时间≈服务端处理时间，不与外网混淆）。
 *
 * 用法：AUTH_API_BASE_URL=http://127.0.0.1:18099 node scripts/dev/housework-perf.js
 * 环境变量：RECORDS（默认 10000）、SAMPLES（默认 30）、SEED_CONCURRENCY（默认 8）。
 */
const fs = require("fs");
const path = require("path");

const baseUrl = process.env.AUTH_API_BASE_URL || "http://127.0.0.1:8080";
const inviteCode = process.env.AUTH_API_INVITE_CODE || "COUPLE-PRIVATE-2026";
const TOTAL_RECORDS = Number(process.env.RECORDS || 10000);
const SAMPLES = Number(process.env.SAMPLES || 30);
const CONCURRENCY = Number(process.env.SEED_CONCURRENCY || 8);

function uuid() { return globalThis.crypto.randomUUID(); }
function pad2(n) { return String(n).padStart(2, "0"); }
function dateStr(d) { return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`; }

async function readJsonOrText(response) {
  const text = await response.text();
  try { return JSON.parse(text); } catch (_e) { return text; }
}

async function registerUser(tag) {
  const stem = `${Date.now().toString(36)}${tag}${Math.floor(Math.random() * 46656).toString(36)}`;
  const username = `hwperf${stem}`;
  const password = "secret123";
  await fetch(`${baseUrl}/api/auth/register`, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, email: `${username}@example.com`, password, inviteCode })
  });
  const login = await fetch(`${baseUrl}/api/auth/login`, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password })
  }).then(readJsonOrText);
  if (login.status && login.status !== 200) throw new Error(JSON.stringify(login));
  return login.data;
}

function headers(user) { return { "Content-Type": "application/json", Authorization: `Bearer ${user.accessToken}` }; }

async function api(user, method, path, body) {
  const start = process.hrtime.bigint();
  const response = await fetch(`${baseUrl}${path}`, {
    method, headers: headers(user),
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const json = await readJsonOrText(response);
  const ms = Number(process.hrtime.bigint() - start) / 1e6;
  return { status: response.status, body: json, ms };
}

function percentile(sorted, p) {
  if (!sorted.length) return 0;
  const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return sorted[Math.max(0, idx)];
}

function stats(name, samples) {
  const sorted = samples.slice().sort((a, b) => a - b);
  const sum = sorted.reduce((a, b) => a + b, 0);
  const row = {
    endpoint: name,
    samples: sorted.length,
    p50: Number(percentile(sorted, 50).toFixed(1)),
    p95: Number(percentile(sorted, 95).toFixed(1)),
    max: Number(sorted[sorted.length - 1].toFixed(1)),
    avg: Number((sum / sorted.length).toFixed(1))
  };
  console.log(`  ${row.endpoint.padEnd(46)} n=${String(row.samples).padStart(3)}  p50=${row.p50}ms  p95=${row.p95}ms  avg=${row.avg}ms  max=${row.max}ms`);
  return row;
}

async function main() {
  console.log(`[1/5] 注册并绑定测试账号（${baseUrl}）…`);
  const userA = await registerUser("a");
  const userB = await registerUser("b");
  const invite = await api(userA, "POST", "/api/couple/generate-invite", {});
  await api(userB, "POST", "/api/couple/bind", { inviteCode: invite.body.data.inviteCode });
  const boot = await api(userA, "POST", "/api/housework/bootstrap", {});
  const spaceId = boot.body.data.spaces.find((s) => s.scope === "couple" && s.status === "active").spaceId;
  const today = boot.body.data.today;
  console.log(`      space=${spaceId} today=${today}`);

  console.log("[2/5] 建 49 分类 + 兜底 = 50 启用分类，200 模板…");
  const categoryIds = [];
  for (let i = 0; i < 49; i += 1) {
    const res = await api(userA, "POST", `/api/housework/spaces/${spaceId}/categories`, {
      clientMutationId: uuid(), name: `性能分类${pad2(i + 1)}`, color: "#ff6b81"
    });
    if (res.status !== 200) throw new Error(`category ${i}: ${JSON.stringify(res.body)}`);
    categoryIds.push(res.body.data.entity.categoryId);
  }
  const templateIds = [];
  for (let i = 0; i < 200; i += 1) {
    const res = await api(userA, "POST", `/api/housework/spaces/${spaceId}/templates`, {
      clientMutationId: uuid(), name: `性能模板${pad2(i + 1)}`,
      categoryId: categoryIds[i % categoryIds.length], measureMode: "quantity", unit: "件"
    });
    if (res.status !== 200) throw new Error(`template ${i}: ${JSON.stringify(res.body)}`);
    templateIds.push(res.body.data.entity.templateId);
  }
  console.log(`      categories=50(含兜底) templates=200`);

  console.log(`[3/5] 灌入 ${TOTAL_RECORDS} 条记录（直连 DB 批量插入，近 400 天分布）…`);
  const mysql = require("mysql2/promise");
  // 与 run-migration.js 相同：手动加载 .env（config.js 本身不读 .env 文件）
  const envPath = path.resolve(__dirname, "../../.env");
  if (fs.existsSync(envPath)) {
    for (const line of fs.readFileSync(envPath, "utf8").split(/\r?\n/)) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith("#")) continue;
      const idx = trimmed.indexOf("=");
      if (idx <= 0) continue;
      const key = trimmed.slice(0, idx).trim();
      if (process.env[key] === undefined) process.env[key] = trimmed.slice(idx + 1).trim();
    }
  }
  const { readConfig } = require("../../src/config");
  const cfg = readConfig();
  const db = await mysql.createConnection({
    host: cfg.dbHost, port: cfg.dbPort, database: cfg.dbName,
    user: cfg.dbUser, password: cfg.dbPassword, charset: "utf8mb4"
  });
  const configRes = await api(userA, "GET", `/api/housework/spaces/${spaceId}/config`);
  const templates = configRes.body.data.templates;
  const catById = new Map(configRes.body.data.categories.map((c) => [c.categoryId, c]));
  const seedStart = Date.now();
  const base = new Date(`${today}T00:00:00`);
  const BATCH = 500;
  let inserted = 0;
  for (let start = 0; start < TOTAL_RECORDS; start += BATCH) {
    const rows = [];
    const parts = [];
    for (let i = start; i < Math.min(start + BATCH, TOTAL_RECORDS); i += 1) {
      const d = new Date(base);
      d.setDate(d.getDate() - (i % 400)); // 近 400 天分布
      const tpl = templates[i % 200];
      const cat = catById.get(tpl.categoryId) || {};
      const rid = uuid();
      const joint = i % 4 === 0;
      const snapshot = {
        name: tpl.name,
        category: {
          categoryId: cat.categoryId || tpl.categoryId,
          name: cat.name || "未分类",
          icon: cat.icon || { type: "iconKey", value: "folder" },
          color: cat.color || "#ff6b81"
        },
        measureMode: tpl.measureMode,
        unit: tpl.unit,
        durationEnabled: false,
        weight: "1.00",
        fields: []
      };
      rows.push([
        rid, spaceId, tpl.templateId, 1, tpl.name, tpl.categoryId,
        JSON.stringify(snapshot), dateStr(d), null, "1.00", null, "1.00",
        "{}", i % 7 === 0 ? `perf-${i}` : null,
        Number(userA.userId), Number(userA.userId), uuid()
      ]);
    }
    for (let i = start; i < Math.min(start + BATCH, TOTAL_RECORDS); i += 1) {
      const rid = rows[i - start][0];
      const joint = i % 4 === 0;
      if (joint) {
        parts.push([rid, Number(userA.userId), 6000, "A"], [rid, Number(userB.userId), 4000, "B"]);
      } else {
        parts.push([rid, Number(userA.userId), 10000, "A"]);
      }
    }
    await db.query(
      `INSERT INTO housework_records
         (record_id, space_id, template_id, template_version, name, category_id_snapshot, snapshot_json,
          completed_date, completed_time, quantity, duration_minutes, weight_snapshot,
          field_values_json, note, created_by, updated_by, client_mutation_id)
       VALUES ?`,
      [rows]
    );
    await db.query(
      `INSERT INTO housework_record_participants (record_id, user_id, share_bps, display_name_snapshot) VALUES ?`,
      [parts]
    );
    inserted += rows.length;
    if (inserted % 2000 === 0) console.log(`      …${inserted}/${TOTAL_RECORDS}`);
  }
  await db.end();
  const seedSec = ((Date.now() - seedStart) / 1000).toFixed(1);
  console.log(`      完成 inserted=${inserted}，用时 ${seedSec}s`);

  // 验证规模：最近 366 天范围 ≥3000 条
  const from = new Date(base); from.setDate(from.getDate() - 365);
  const fromStr = dateStr(from);
  const check = await api(userA, "GET", `/api/housework/spaces/${spaceId}/statistics?dateFrom=${fromStr}&dateTo=${today}`);
  const inRange = check.body.data.householdCount;
  console.log(`      最近 366 天范围内记录数=${inRange}（要求 ≥3000）`);

  console.log(`[4/5] 采样：列表 / 统计 / 提交 各 ${SAMPLES} 次（先预热 5 次）…`);
  const listUrl = `/api/housework/spaces/${spaceId}/records?dateFrom=${fromStr}&dateTo=${today}&limit=20`;
  const statsUrl = `/api/housework/spaces/${spaceId}/statistics?dateFrom=${fromStr}&dateTo=${today}`;
  const sampleList = [];
  const sampleStats = [];
  const sampleSubmit = [];
  for (let i = 0; i < 5; i += 1) { await api(userA, "GET", listUrl); await api(userA, "GET", statsUrl); }
  for (let i = 0; i < SAMPLES; i += 1) {
    sampleList.push((await api(userA, "GET", listUrl)).ms);
    sampleStats.push((await api(userA, "GET", statsUrl)).ms);
    sampleSubmit.push((await api(userA, "POST", `/api/housework/spaces/${spaceId}/records`, {
      clientMutationId: uuid(), templateId: templateIds[i % 200], templateVersion: 1,
      completedDate: today, completedTime: null,
      participants: [{ userId: userA.userId, shareBps: 10000 }], quantity: "1.00"
    })).ms);
    if ((i + 1) % 10 === 0) console.log(`      …${i + 1}/${SAMPLES}`);
  }

  console.log("[5/5] 结果：");
  const rows = [
    stats("GET records（366 天范围, limit 20）", sampleList),
    stats("GET statistics（同一完整范围聚合）", sampleStats),
    stats("POST records（提交）", sampleSubmit)
  ];

  const result = {
    date: new Date().toISOString(),
    baseUrl, spaceId,
    scale: { categories: 50, userCategories: 49, templates: 200, records: TOTAL_RECORDS, recordsIn366d: inRange },
    samples: SAMPLES,
    environment: {
      node: process.version,
      device: "macOS 本地 (Apple Silicon)",
      network: "127.0.0.1 回环，RTT≈0，响应时间≈服务端处理耗时"
    },
    thresholds: { p95Ms: 1000, homepageOperableMs: 2000 },
    results: rows,
    pass: rows.every((r) => r.p95 <= 1000)
  };
  const out = path.resolve(__dirname, "../../docs/housework-perf-20261001.json");
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, JSON.stringify(result, null, 2));
  console.log(`\n结果已写入 ${out}`);
  console.log(`结论：${result.pass ? "✅ 全部接口 P95 ≤ 1s，达标" : "❌ 存在接口 P95 > 1s，未达标"}`);
  process.exit(result.pass ? 0 : 2);
}

main().catch((error) => { console.error(error); process.exit(1); });
