/**
 * 家务（housework）AC 验收用例 —— 服务端集成自动化（Spec 第 12 节要求）：
 * AC-05 模板版本冲突 / AC-10 并发编辑与快捷撤销 / AC-11 删除-回放-恢复 /
 * AC-13 执行者筛选保留共同份额 / AC-14 45 条跨页与筛选 / AC-15 CURSOR_STALE /
 * AC-16 解绑拒写在途提交 / AC-17 越权伪造 / AC-18 幂等重试与改包冲突 / AC-20 日期时间数量边界。
 *
 * 需要服务运行在 AUTH_API_BASE_URL（默认 8080），账号密码走注册邀请码。
 * 每个用例独立注册账号，互不影响。
 */
const assert = require("assert");
const { test } = require("node:test");

const baseUrl = process.env.AUTH_API_BASE_URL || "http://127.0.0.1:8080";
const inviteCode = process.env.AUTH_API_INVITE_CODE || "COUPLE-PRIVATE-2026";

async function readJsonOrText(response) {
  const text = await response.text();
  try { return JSON.parse(text); } catch (_e) { return text; }
}

async function registerUser(tag) {
  // base36 时间戳字母串可能拼出 "vx"+5 位字母数字（如 …vxka9z9），命中本地导流正则
  // （vx|weixin|…）+{5,} 导致注册被 CONTACT_NOT_ALLOWED 拒绝 —— 把 v 换成 w 生成，
  // 并用服务端同一组正则复核，命中则换时间戳重来。
  const { TEXT_PROMO_PATTERNS } = require("../src/services/contentSecurity");
  let username = "";
  for (let attempt = 0; attempt < 10 && !username; attempt += 1) {
    const stem = `${Date.now().toString(36).replace(/v/g, "w")}${tag}${Math.floor(Math.random() * 46656).toString(36).replace(/v/g, "w")}`;
    const candidate = `hwac${stem}`;
    if (!TEXT_PROMO_PATTERNS.some((p) => p.re.test(candidate))) username = candidate;
  }
  if (!username) throw new Error("无法生成合法测试用户名");
  const password = "secret123";
  const reg = await fetch(`${baseUrl}/api/auth/register`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, email: `${username}@example.com`, password, inviteCode })
  });
  const regBody = await readJsonOrText(reg);
  assert.equal(reg.status, 201, JSON.stringify(regBody));
  const login = await fetch(`${baseUrl}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password })
  });
  const loginBody = await readJsonOrText(login);
  assert.equal(login.status, 200, JSON.stringify(loginBody));
  return loginBody.data;
}

function authHeaders(user) {
  return { "Content-Type": "application/json", Authorization: `Bearer ${user.accessToken}` };
}

async function api(user, method, path, body) {
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: authHeaders(user),
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  return { status: response.status, body: await readJsonOrText(response) };
}

function uuid() { return globalThis.crypto.randomUUID(); }

/** 注册 A/B（可选 C）并绑定，返回双方会话与共享空间。 */
async function setupCouple(withC) {
  const userA = await registerUser("a");
  const userB = await registerUser("b");
  const userC = withC ? await registerUser("c") : null;
  const invite = await api(userA, "POST", "/api/couple/generate-invite", {});
  assert.equal(invite.status, 200, JSON.stringify(invite.body));
  const bind = await api(userB, "POST", "/api/couple/bind", { inviteCode: invite.body.data.inviteCode });
  assert.equal(bind.status, 200, JSON.stringify(bind.body));
  const boot = await api(userA, "POST", "/api/housework/bootstrap", {});
  assert.equal(boot.status, 200, JSON.stringify(boot.body));
  const shared = boot.body.data.spaces.find((s) => s.scope === "couple" && s.status === "active");
  assert.ok(shared, "shared space exists");
  return { userA, userB, userC, aid: userA.userId, bid: userB.userId, spaceId: shared.spaceId, today: boot.body.data.today };
}

/** 在兜底分类下建一个 quantity 模板。 */
async function makeTemplate(user, spaceId, name, extra) {
  const cfg = await api(user, "GET", `/api/housework/spaces/${spaceId}/config`);
  assert.equal(cfg.status, 200, JSON.stringify(cfg.body));
  const catId = cfg.body.data.categories.find((c) => c.isFallback).categoryId;
  const res = await api(user, "POST", `/api/housework/spaces/${spaceId}/templates`, Object.assign({
    clientMutationId: uuid(), name, categoryId: catId, measureMode: "quantity", unit: "件"
  }, extra || {}));
  assert.equal(res.status, 200, JSON.stringify(res.body));
  return res.body.data.entity;
}

async function makeRecord(user, spaceId, payload) {
  const res = await api(user, "POST", `/api/housework/spaces/${spaceId}/records`, payload);
  return res;
}

// ============ AC-05：A 已开表单，B 改了模板，A 提交 → 模板版本冲突 ============
test("AC-05 template version conflict: stale templateVersion rejected, current accepted", async () => {
  const { userA, userB, aid, bid, spaceId, today } = await setupCouple(false);
  const tpl = await makeTemplate(userA, spaceId, "AC05洗衣", { durationEnabled: true });

  // A 已打开表单（拿的是 templateVersion 1）；B 新增一个必填字段 → 模板 v2
  const fieldId = uuid();
  const patchTpl = await api(userB, "PATCH", `/api/housework/spaces/${spaceId}/templates/${tpl.templateId}`, {
    clientMutationId: uuid(),
    expectedVersion: tpl.version,
    fields: [{ fieldId, label: "件数", type: "number", required: true, min: "0.00", max: "99999.99" }]
  });
  assert.equal(patchTpl.status, 200, JSON.stringify(patchTpl.body));
  assert.equal(patchTpl.body.data.entity.version, 2);

  // A 用 v1 提交 → 409 TEMPLATE_VERSION_CONFLICT + currentVersion
  const stale = await makeRecord(userA, spaceId, {
    clientMutationId: uuid(),
    templateId: tpl.templateId,
    templateVersion: 1,
    completedDate: today,
    completedTime: null,
    participants: [{ userId: aid, shareBps: 10000 }],
    quantity: "2.00"
  });
  assert.equal(stale.status, 409);
  assert.equal(stale.body.error.code, "TEMPLATE_VERSION_CONFLICT");
  assert.equal(stale.body.error.currentVersion, 2);

  // A 重新取最新定义并带必填字段提交 → 200
  const fresh = await makeRecord(userA, spaceId, {
    clientMutationId: uuid(),
    templateId: tpl.templateId,
    templateVersion: 2,
    completedDate: today,
    completedTime: null,
    participants: [{ userId: aid, shareBps: 10000 }],
    quantity: "2.00",
    fieldValues: { [fieldId]: "0.00" }
  });
  assert.equal(fresh.status, 200, JSON.stringify(fresh.body));
  assert.equal(fresh.body.data.entity.templateVersion, 2);
});

// ============ AC-10：并发编辑只有首个成功；快捷撤销不能删对方的新版本 ============
test("AC-10 concurrent edit conflict + undo fails after partner edited", async () => {
  const { userA, userB, aid, spaceId, today } = await setupCouple(false);
  const tpl = await makeTemplate(userA, spaceId, "AC10做饭");
  const rec = await makeRecord(userA, spaceId, {
    clientMutationId: uuid(), templateId: tpl.templateId, templateVersion: tpl.version,
    completedDate: today, completedTime: null,
    participants: [{ userId: aid, shareBps: 10000 }], quantity: "1.00", note: "原始"
  });
  assert.equal(rec.status, 200, JSON.stringify(rec.body));
  const rid = rec.body.data.entity.recordId;

  // A 编辑成功（v1→v2）
  const editA = await api(userA, "PATCH", `/api/housework/spaces/${spaceId}/records/${rid}`, {
    clientMutationId: uuid(), expectedVersion: 1, note: "A 改的"
  });
  assert.equal(editA.status, 200, JSON.stringify(editA.body));
  assert.equal(editA.body.data.entity.version, 2);

  // B 用同一旧版本 v1 编辑 → 409 VERSION_CONFLICT（保留草稿由客户端处理）
  const editB = await api(userB, "PATCH", `/api/housework/spaces/${spaceId}/records/${rid}`, {
    clientMutationId: uuid(), expectedVersion: 1, note: "B 改的"
  });
  assert.equal(editB.status, 409);
  assert.equal(editB.body.error.code, "VERSION_CONFLICT");
  assert.equal(editB.body.error.currentVersion, 2);

  // 快捷撤销带创建结果版本 v1，但记录已是 v2 → 409，不能误删对方的新版本
  const undo = await api(userA, "DELETE", `/api/housework/spaces/${spaceId}/records/${rid}?expectedVersion=1&clientMutationId=${uuid()}`);
  assert.equal(undo.status, 409);
  assert.equal(undo.body.error.code, "VERSION_CONFLICT");
  const still = await api(userA, "GET", `/api/housework/spaces/${spaceId}/records/${rid}`);
  assert.equal(still.body.data.version, 2);
  assert.equal(still.body.data.note, "A 改的");
});

// ============ AC-11：删除、同 ID 重放、回收站、恢复、迟到旧删除 ============
test("AC-11 delete replay + recycle bin + restore + late delete after restore", async () => {
  const { userA, userB, aid, spaceId, today } = await setupCouple(false);
  const tpl = await makeTemplate(userA, spaceId, "AC11洗碗");
  const rec = await makeRecord(userA, spaceId, {
    clientMutationId: uuid(), templateId: tpl.templateId, templateVersion: tpl.version,
    completedDate: today, completedTime: null,
    participants: [{ userId: aid, shareBps: 10000 }], quantity: "3.00"
  });
  const rid = rec.body.data.entity.recordId;

  // 统计包含
  const s0 = await api(userA, "GET", `/api/housework/spaces/${spaceId}/statistics`);
  assert.equal(s0.body.data.householdCount, 1);

  // 删除（M1，expectedVersion=1）→ version 2
  const m1 = uuid();
  const del1 = await api(userA, "DELETE", `/api/housework/spaces/${spaceId}/records/${rid}?expectedVersion=1&clientMutationId=${m1}`);
  assert.equal(del1.status, 200, JSON.stringify(del1.body));
  assert.equal(del1.body.data.entity.version, 2);

  // 同 ID 重放 → replayed:true，同结果，不产生第二次删除/审计
  const del2 = await api(userA, "DELETE", `/api/housework/spaces/${spaceId}/records/${rid}?expectedVersion=1&clientMutationId=${m1}`);
  assert.equal(del2.status, 200, JSON.stringify(del2.body));
  assert.equal(del2.body.data.replayed, true);
  assert.equal(del2.body.data.entity.version, 2);
  const revs = await api(userA, "GET", `/api/housework/spaces/${spaceId}/records/${rid}/revisions`);
  const deleteActions = revs.body.data.items.filter((r) => r.action === "delete");
  assert.equal(deleteActions.length, 1, "delete audited exactly once");

  // 新 ID 再删 → 409 STATE_CONFLICT
  const del3 = await api(userB, "DELETE", `/api/housework/spaces/${spaceId}/records/${rid}?expectedVersion=2&clientMutationId=${uuid()}`);
  assert.equal(del3.status, 409);
  assert.equal(del3.body.error.code, "STATE_CONFLICT");

  // 统计排除已删除；回收站可见
  const s1 = await api(userA, "GET", `/api/housework/spaces/${spaceId}/statistics`);
  assert.equal(s1.body.data.householdCount, 0);
  const bin = await api(userA, "GET", `/api/housework/spaces/${spaceId}/records?state=deleted`);
  assert.equal(bin.body.data.total, 1);
  assert.equal(bin.body.data.items[0].recordId, rid);

  // 恢复（expectedVersion=2）→ version 3
  const restore = await api(userB, "POST", `/api/housework/spaces/${spaceId}/records/${rid}/restore`, {
    clientMutationId: uuid(), expectedVersion: 2
  });
  assert.equal(restore.status, 200, JSON.stringify(restore.body));
  assert.equal(restore.body.data.entity.version, 3);

  // 迟到的原删除（同 M1）→ 只回放历史结果，不改变当前记录
  const late = await api(userA, "DELETE", `/api/housework/spaces/${spaceId}/records/${rid}?expectedVersion=1&clientMutationId=${m1}`);
  assert.equal(late.status, 200, JSON.stringify(late.body));
  assert.equal(late.body.data.replayed, true);
  const after = await api(userA, "GET", `/api/housework/spaces/${spaceId}/records/${rid}`);
  assert.equal(after.body.data.version, 3);
  assert.equal(after.body.data.deletedAt, null, "record stays active after late replay");

  // 统计恢复计入
  const s2 = await api(userA, "GET", `/api/housework/spaces/${spaceId}/statistics`);
  assert.equal(s2.body.data.householdCount, 1);
});

// ============ AC-13：只筛 A，共同记录中 B 的份额仍保留 ============
test("AC-13 performer filter keeps partner share on joint records", async () => {
  const { userA, aid, bid, spaceId, today } = await setupCouple(false);
  const sweep = await makeTemplate(userA, spaceId, "AC13扫地", { measureMode: "event", weight: "2.00" });
  const laundry = await makeTemplate(userA, spaceId, "AC13洗衣", { weight: "0.50", durationEnabled: true });
  await makeRecord(userA, spaceId, {
    clientMutationId: uuid(), templateId: sweep.templateId, templateVersion: sweep.version,
    completedDate: today, completedTime: null, participants: [{ userId: aid, shareBps: 10000 }]
  });
  await makeRecord(userA, spaceId, {
    clientMutationId: uuid(), templateId: laundry.templateId, templateVersion: laundry.version,
    completedDate: today, completedTime: null,
    participants: [{ userId: aid, shareBps: 6000 }, { userId: bid, shareBps: 4000 }],
    quantity: "5.00", durationMinutes: 30
  });
  const settings = await api(userA, "GET", `/api/housework/spaces/${spaceId}/config`);
  await api(userA, "PATCH", `/api/housework/spaces/${spaceId}/settings`, {
    clientMutationId: uuid(), expectedVersion: settings.body.data.settingsVersion,
    showDurationStatistics: true, showWorkloadStatistics: true
  });

  // 只筛 A：仍选出 2 条完整记录，B 的参与与份额保留
  const stats = await api(userA, "GET", `/api/housework/spaces/${spaceId}/statistics?performerIds=${aid}`);
  const s = stats.body.data;
  assert.equal(s.householdCount, 2);
  const mA = s.members.find((m) => m.userId === String(aid));
  const mB = s.members.find((m) => m.userId === String(bid));
  assert.equal(mA.participationCount, 2);
  assert.equal(mB.participationCount, 1, "B 的参与不被执行者筛选丢掉");
  assert.equal(mA.equivalentCount, "1.60");
  assert.equal(mB.equivalentCount, "0.40");
  assert.equal(mA.workload, "3.50");
  assert.equal(mB.workload, "1.00");
  assert.equal(mA.allocatedDurationMinutes, "18.00");
  assert.equal(mB.allocatedDurationMinutes, "12.00");
});

// ============ AC-14：45 条跨页 + 筛选 + 总数与统计一致 ============
test("AC-14 pagination 20/20/5 and filters cover all 45", async () => {
  const { userA, aid, bid, spaceId, today } = await setupCouple(false);
  const tpl = await makeTemplate(userA, spaceId, "AC14采购");
  const month = today.slice(0, 7);
  // 45 条：3 个月分散，部分带备注关键词，部分两人共同
  for (let i = 0; i < 45; i += 1) {
    const day = String((i % 28) + 1).padStart(2, "0");
    const date = (i < 30) ? `${month}-${day}` : (i < 38 ? `${month === "01" ? "2025-12" : month.slice(0, 5) + String(Number(month.slice(5)) - 1).padStart(2, "0")}-${day}` : `${month.slice(0, 5)}${String(Number(month.slice(5)) + 1).padStart(2, "0")}-${day}`);
    const joint = i % 5 === 0;
    const res = await makeRecord(userA, spaceId, {
      clientMutationId: uuid(), templateId: tpl.templateId, templateVersion: tpl.version,
      completedDate: date > today ? today : date, completedTime: null,
      participants: joint ? [{ userId: aid, shareBps: 5000 }, { userId: bid, shareBps: 5000 }] : [{ userId: aid, shareBps: 10000 }],
      quantity: "1.00",
      note: i % 3 === 0 ? `批量第${i}条 牛奶` : null
    });
    assert.equal(res.status, 200, `record ${i}: ${JSON.stringify(res.body)}`);
  }

  // 分页 20/20/5（显式日期范围覆盖近三个月；服务端限制单次范围 ≤366 天）
  const from = new Date(`${today}T00:00:00`);
  from.setDate(from.getDate() - 300);
  const fromStr = `${from.getFullYear()}-${String(from.getMonth() + 1).padStart(2, "0")}-${String(from.getDate()).padStart(2, "0")}`;
  const range = `dateFrom=${fromStr}&dateTo=${today}`;
  const p1 = await api(userA, "GET", `/api/housework/spaces/${spaceId}/records?limit=20&${range}`);
  assert.equal(p1.body.data.items.length, 20);
  assert.equal(p1.body.data.total, 45);
  const p2 = await api(userA, "GET", `/api/housework/spaces/${spaceId}/records?limit=20&${range}&cursor=${encodeURIComponent(p1.body.data.nextCursor)}`);
  assert.equal(p2.body.data.items.length, 20);
  const p3 = await api(userA, "GET", `/api/housework/spaces/${spaceId}/records?limit=20&${range}&cursor=${encodeURIComponent(p2.body.data.nextCursor)}`);
  assert.equal(p3.body.data.items.length, 5);
  assert.equal(p3.body.data.nextCursor, null);
  const ids = new Set([...p1.body.data.items, ...p2.body.data.items, ...p3.body.data.items].map((r) => r.recordId));
  assert.equal(ids.size, 45, "no duplicates across pages");

  // 筛选：备注搜「牛奶」；执行者筛 B；仅共同完成（全部在完整日期范围内统计）
  const qMilk = await api(userA, "GET", `/api/housework/spaces/${spaceId}/records?q=${encodeURIComponent("牛奶")}&limit=50&${range}`);
  assert.equal(qMilk.body.data.items.length, 15);
  const qB = await api(userA, "GET", `/api/housework/spaces/${spaceId}/records?performerIds=${bid}&limit=50&${range}`);
  assert.equal(qB.body.data.items.length, 9);
  const qJoint = await api(userA, "GET", `/api/housework/spaces/${spaceId}/records?jointOnly=1&limit=50&${range}`);
  assert.equal(qJoint.body.data.items.length, 9);

  // 同一筛选条件下统计覆盖完整范围（非仅第一页）
  const statsMilk = await api(userA, "GET", `/api/housework/spaces/${spaceId}/statistics?q=${encodeURIComponent("牛奶")}&${range}`);
  assert.equal(statsMilk.body.data.householdCount, 15);
  const statsAll = await api(userA, "GET", `/api/housework/spaces/${spaceId}/statistics?${range}`);
  assert.equal(statsAll.body.data.householdCount, 45);
  assert.equal(statsAll.body.data.members[0].participationCount, 45);
});

// ============ AC-15：翻页期间对方写入 → CURSOR_STALE ============
test("AC-15 stale cursor returns CURSOR_STALE after revision change", async () => {
  const { userA, userB, aid, spaceId, today } = await setupCouple(false);
  const tpl = await makeTemplate(userA, spaceId, "AC15拖地");
  for (let i = 0; i < 25; i += 1) {
    await makeRecord(userA, spaceId, {
      clientMutationId: uuid(), templateId: tpl.templateId, templateVersion: tpl.version,
      completedDate: today, completedTime: null, participants: [{ userId: aid, shareBps: 10000 }], quantity: "1.00"
    });
  }
  const p1 = await api(userA, "GET", `/api/housework/spaces/${spaceId}/records?limit=20`);
  assert.equal(p1.body.data.items.length, 20);

  // 对方新增 → 空间 revision 变化
  await makeRecord(userB, spaceId, {
    clientMutationId: uuid(), templateId: tpl.templateId, templateVersion: tpl.version,
    completedDate: today, completedTime: null, participants: [{ userId: aid, shareBps: 10000 }], quantity: "1.00"
  });

  // 旧 cursor 翻页 → 409 CURSOR_STALE
  const stale = await api(userA, "GET", `/api/housework/spaces/${spaceId}/records?limit=20&cursor=${encodeURIComponent(p1.body.data.nextCursor)}`);
  assert.equal(stale.status, 409);
  assert.equal(stale.body.error.code, "CURSOR_STALE");

  // 保留筛选从第一页重载 → 拿到 26 条
  const reload = await api(userA, "GET", `/api/housework/spaces/${spaceId}/records?limit=50`);
  assert.equal(reload.body.data.total, 26);
});

// ============ AC-16：表单已开，解绑后提交被拒 ============
test("AC-16 in-flight submit after unbind rejected with SPACE_CLOSED", async () => {
  const { userA, aid, spaceId, today } = await setupCouple(false);
  const tpl = await makeTemplate(userA, spaceId, "AC16浇花");

  // 解绑（冻结空间）
  const unbind = await api(userA, "DELETE", "/api/couple/unbind");
  assert.equal(unbind.status, 200, JSON.stringify(unbind.body));

  // 在途提交（表单默认执行者等仍有效）→ 409 SPACE_CLOSED，不自动转个人空间
  const submit = await makeRecord(userA, spaceId, {
    clientMutationId: uuid(), templateId: tpl.templateId, templateVersion: tpl.version,
    completedDate: today, completedTime: null, participants: [{ userId: aid, shareBps: 10000 }], quantity: "1.00"
  });
  assert.equal(submit.status, 409);
  assert.equal(submit.body.error.code, "SPACE_CLOSED");
});

// ============ AC-17：伪造 spaceId/recordId、跨空间模板、body 伪造 userId ============
test("AC-17 forgery rejected: spaces, records, cross-space templates, body userId", async () => {
  const { userA, userB, userC, aid, bid, spaceId, today } = await setupCouple(true);
  const tpl = await makeTemplate(userA, spaceId, "AC17取快递");
  const rec = await makeRecord(userA, spaceId, {
    clientMutationId: uuid(), templateId: tpl.templateId, templateVersion: tpl.version,
    completedDate: today, completedTime: null, participants: [{ userId: aid, shareBps: 10000 }], quantity: "1.00"
  });
  const rid = rec.body.data.entity.recordId;

  // C 伪造 spaceId / recordId
  const cCfg = await api(userC, "GET", `/api/housework/spaces/${spaceId}/config`);
  assert.equal(cCfg.status, 403);
  assert.equal(cCfg.body.error.code, "SPACE_FORBIDDEN");
  const cRec = await api(userC, "GET", `/api/housework/spaces/${spaceId}/records/${rid}`);
  assert.equal(cRec.status, 403);
  const forged = await api(userA, "GET", `/api/housework/spaces/${uuid()}/records/${rid}`);
  assert.equal(forged.status, 404);

  // B 的个人空间模板不能被共享空间引用
  const bootB = await api(userB, "POST", "/api/housework/bootstrap", {});
  const personalB = bootB.body.data.spaces.find((s) => s.scope === "personal" && s.canWrite !== false);
  const personalTpl = await makeTemplate(userB, personalB.spaceId, "AC17私人模板");
  const crossRef = await makeRecord(userA, spaceId, {
    clientMutationId: uuid(), templateId: personalTpl.templateId, templateVersion: 1,
    completedDate: today, completedTime: null, participants: [{ userId: aid, shareBps: 10000 }], quantity: "1.00"
  });
  assert.equal(crossRef.status, 404);

  // body 伪造 userId：createdBy 仍取 token；伪造参与者 C → 422
  const forgedBody = await makeRecord(userA, spaceId, {
    clientMutationId: uuid(), userId: bid, templateId: tpl.templateId, templateVersion: tpl.version,
    completedDate: today, completedTime: null, participants: [{ userId: aid, shareBps: 10000 }], quantity: "1.00"
  });
  assert.equal(forgedBody.status, 200);
  assert.equal(forgedBody.body.data.entity.createdBy, String(aid));
  const forgedParticipant = await makeRecord(userA, spaceId, {
    clientMutationId: uuid(), templateId: tpl.templateId, templateVersion: tpl.version,
    completedDate: today, completedTime: null, participants: [{ userId: userC.userId, shareBps: 10000 }], quantity: "1.00"
  });
  assert.equal(forgedParticipant.status, 422);
});

// ============ AC-18：响应丢失重试同 ID 同结果；改包 → 幂等冲突 ============
test("AC-18 response-loss retry replays original; changed payload conflicts", async () => {
  const { userA, aid, spaceId, today } = await setupCouple(false);
  const tpl = await makeTemplate(userA, spaceId, "AC18叠衣服");
  const m1 = uuid();
  const payload = {
    clientMutationId: m1, templateId: tpl.templateId, templateVersion: tpl.version,
    completedDate: today, completedTime: null,
    participants: [{ userId: aid, shareBps: 10000 }], quantity: "4.00", note: "第一次"
  };
  const first = await makeRecord(userA, spaceId, payload);
  assert.equal(first.status, 200, JSON.stringify(first.body));
  const rid = first.body.data.entity.recordId;
  const v = first.body.data.entity.version;

  // 响应丢失后按原 ID 原 payload 重试 → 同 recordId、原 version、replayed
  const retry = await makeRecord(userA, spaceId, payload);
  assert.equal(retry.status, 200);
  assert.equal(retry.body.data.replayed, true);
  assert.equal(retry.body.data.entity.recordId, rid);
  assert.equal(retry.body.data.entity.version, v);

  // 同 ID 改 payload → 409 IDEMPOTENCY_CONFLICT（不能换新 ID 绕过）
  const changed = await makeRecord(userA, spaceId, Object.assign({}, payload, { note: "偷偷改内容" }));
  assert.equal(changed.status, 409);
  assert.equal(changed.body.error.code, "IDEMPOTENCY_CONFLICT");
});

// ============ AC-20：未来日期、当天时间越界、数量超范围 ============
test("AC-20 date/time/quantity boundaries rejected", async () => {
  const { userA, aid, bid, spaceId, today } = await setupCouple(false);
  const tpl = await makeTemplate(userA, spaceId, "AC20清理");

  // 未来日期
  const future = await makeRecord(userA, spaceId, {
    clientMutationId: uuid(), templateId: tpl.templateId, templateVersion: tpl.version,
    completedDate: "2999-01-01", completedTime: null,
    participants: [{ userId: aid, shareBps: 10000 }], quantity: "1.00"
  });
  assert.equal(future.status, 422);
  assert.equal(future.body.error.code, "VALIDATION_ERROR");

  // 当天但时间晚于当前分钟（用 23:59 必然越界，除非真在 23:59 后跑）
  const now = new Date();
  const lateTime = `${String(now.getHours()).padStart(2, "0")}:${String(now.getMinutes()).padStart(2, "0")}`;
  const timeLate = await makeRecord(userA, spaceId, {
    clientMutationId: uuid(), templateId: tpl.templateId, templateVersion: tpl.version,
    completedDate: today, completedTime: lateTime === "23:59" ? "23:58" : "23:59",
    participants: [{ userId: aid, shareBps: 10000 }], quantity: "1.00"
  });
  assert.equal(timeLate.status, 422);

  // 数量 0 / 超上限 / 三位小数
  for (const bad of ["0", "100000", "1.234"]) {
    const res = await makeRecord(userA, spaceId, {
      clientMutationId: uuid(), templateId: tpl.templateId, templateVersion: tpl.version,
      completedDate: today, completedTime: null,
      participants: [{ userId: aid, shareBps: 10000 }], quantity: bad
    });
    assert.equal(res.status, 422, `quantity ${bad} should be rejected`);
  }

  // 份额不合规：两人之和必须 100%
  const badShare = await makeRecord(userA, spaceId, {
    clientMutationId: uuid(), templateId: tpl.templateId, templateVersion: tpl.version,
    completedDate: today, completedTime: null,
    participants: [{ userId: aid, shareBps: 6000 }, { userId: bid, shareBps: 3000 }], quantity: "1.00"
  });
  assert.ok([409, 422].includes(badShare.status), JSON.stringify(badShare.body));
});

// ============ 个人空间伴侣只读：双方可见、写仅本人、解绑即失效 ============
test("personal space: partner read-only, third party forbidden, revoked after unbind", async () => {
  const { userA, userB, userC, aid, today } = await setupCouple(true);

  // B 先 bootstrap 确保其个人空间已惰性创建，A 的列表才能看到它
  const bootB0 = await api(userB, "POST", "/api/housework/bootstrap", {});
  assert.equal(bootB0.status, 200, JSON.stringify(bootB0.body));

  // A 的本人个人空间（列表同时含 B 的个人空间，用 canWrite 区分）
  const bootA = await api(userA, "POST", "/api/housework/bootstrap", {});
  const personalA = bootA.body.data.spaces.find((s) => s.scope === "personal" && s.canWrite !== false);
  assert.ok(personalA, "own personal space exists");
  const partnerView = bootA.body.data.spaces.find((s) => s.scope === "personal" && s.canWrite === false);
  assert.ok(partnerView, "partner personal space visible to A");

  // A 在个人空间建模板并记一笔
  const tpl = await makeTemplate(userA, personalA.spaceId, "伴侣可见性扫地");
  const rec = await makeRecord(userA, personalA.spaceId, {
    clientMutationId: uuid(), templateId: tpl.templateId, templateVersion: tpl.version,
    completedDate: today, completedTime: null,
    participants: [{ userId: aid, shareBps: 10000 }], quantity: "1.00"
  });
  assert.equal(rec.status, 200, JSON.stringify(rec.body));
  const rid = rec.body.data.entity.recordId;

  // B 的空间列表能看到 A 的个人空间，且 canWrite=false
  const spacesB = await api(userB, "GET", "/api/housework/spaces");
  assert.equal(spacesB.status, 200, JSON.stringify(spacesB.body));
  const seenByB = spacesB.body.data.spaces.find((s) => s.spaceId === personalA.spaceId);
  assert.ok(seenByB, "B sees A's personal space");
  assert.equal(seenByB.canWrite, false);

  // B 只读访问 config / 单条记录
  const cfgB = await api(userB, "GET", `/api/housework/spaces/${personalA.spaceId}/config`);
  assert.equal(cfgB.status, 200, JSON.stringify(cfgB.body));
  assert.equal(cfgB.body.data.space.canWrite, false);
  const recB = await api(userB, "GET", `/api/housework/spaces/${personalA.spaceId}/records/${rid}`);
  assert.equal(recB.status, 200, JSON.stringify(recB.body));

  // B 写入一律 403：记记录 / 建模板 / 改偏好
  const writeRec = await makeRecord(userB, personalA.spaceId, {
    clientMutationId: uuid(), templateId: tpl.templateId, templateVersion: tpl.version,
    completedDate: today, completedTime: null,
    participants: [{ userId: aid, shareBps: 10000 }], quantity: "1.00"
  });
  assert.equal(writeRec.status, 403);
  assert.equal(writeRec.body.error.code, "SPACE_FORBIDDEN");
  const writeTpl = await api(userB, "POST", `/api/housework/spaces/${personalA.spaceId}/templates`, {
    clientMutationId: uuid(), name: "越权模板", categoryId: tpl.categoryId, measureMode: "quantity", unit: "件"
  });
  assert.equal(writeTpl.status, 403);
  const writePrefs = await api(userB, "PATCH", `/api/housework/spaces/${personalA.spaceId}/preferences`, {
    clientMutationId: uuid(), expectedVersion: 1, orderedTemplateIds: []
  });
  assert.equal(writePrefs.status, 403);

  // 第三人 C（无关系）仍然 403
  const cfgC = await api(userC, "GET", `/api/housework/spaces/${personalA.spaceId}/config`);
  assert.equal(cfgC.status, 403);

  // 解绑后 B 立即失去读取权，且 B 的列表里不再出现 A 的个人空间
  const unbind = await api(userA, "DELETE", "/api/couple/unbind");
  assert.equal(unbind.status, 200, JSON.stringify(unbind.body));
  const cfgBAfter = await api(userB, "GET", `/api/housework/spaces/${personalA.spaceId}/config`);
  assert.equal(cfgBAfter.status, 403);
  const spacesBAfter = await api(userB, "GET", "/api/housework/spaces");
  assert.ok(!spacesBAfter.body.data.spaces.some((s) => s.spaceId === personalA.spaceId),
    "A's personal space disappears from B's list after unbind");
});
