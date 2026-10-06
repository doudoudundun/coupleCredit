/**
 * 家务（housework）HTTP 集成测试 —— 需要服务运行在 8080（npm start）。
 * 覆盖：注册→登录→绑定(新生命周期)→bootstrap→分类/模板(5种字段)→记录(共同60/40)→
 * 统计(spec 8.6 口径)→幂等重放→越权→解绑→归档只读→重绑新 cycleId。
 * 风格同 test/bills.create.test.js。
 */
const assert = require("assert");
const { test } = require("node:test");

const baseUrl = process.env.AUTH_API_BASE_URL || "http://127.0.0.1:8080";
assert.ok(["127.0.0.1", "localhost", "[::1]"].includes(new URL(baseUrl).hostname), "housework flows only create fixtures on localhost");
const inviteCode = process.env.AUTH_API_INVITE_CODE || "COUPLE-PRIVATE-2026";
const uniqueSuffix = Date.now();

async function readJsonOrText(response) {
  const text = await response.text();
  try {
    return JSON.parse(text);
  } catch (_error) {
    return text;
  }
}

async function registerUser(suffix) {
  // 账号名避免长串纯数字（毫秒时间戳会命中内容安全的手机号正则 CONTACT_NOT_ALLOWED）：
  // base36 化时间戳并混排字母，尾部保留调用方区分字母保证唯一。
  // 注意 base36 字母串可能拼出 "vx"+"5位字母数字"（如 …vxka9z9），命中导流正则
  // （vx|weixin|…）+{5,} —— 把 v 换成 w 生成，并用同一组正则复核，命中则换时间戳重来。
  const { TEXT_PROMO_PATTERNS } = require("../src/services/contentSecurity");
  const tag = String(suffix).replace(/[^a-zA-Z]/g, '') || 'x';
  let username = "";
  for (let attempt = 0; attempt < 10 && !username; attempt += 1) {
    const stem = `${Date.now().toString(36).replace(/v/g, "w")}${tag}${Math.floor(Math.random() * 46656).toString(36).replace(/v/g, "w")}`;
    const candidate = `hwflow${stem}`;
    if (!TEXT_PROMO_PATTERNS.some((p) => p.re.test(candidate))) username = candidate;
  }
  if (!username) throw new Error("无法生成合法测试用户名");
  const password = "secret123";
  const registerResponse = await fetch(`${baseUrl}/api/auth/register`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      username,
      email: `${username}@example.com`,
      password,
      inviteCode
    })
  });
  const registerBody = await readJsonOrText(registerResponse);
  assert.equal(registerResponse.status, 201, JSON.stringify(registerBody));

  const loginResponse = await fetch(`${baseUrl}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password })
  });
  const loginBody = await readJsonOrText(loginResponse);
  assert.equal(loginResponse.status, 200, JSON.stringify(loginBody));
  return { ...loginBody.data, username };
}

function authHeaders(user) {
  return {
    "Content-Type": "application/json",
    Authorization: `Bearer ${user.accessToken}`
  };
}

async function api(user, method, path, body) {
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: authHeaders(user),
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const json = await readJsonOrText(response);
  return { status: response.status, body: json };
}

function uuid() {
  return globalThis.crypto.randomUUID();
}

test("housework full lifecycle: bind → records → statistics → unbind → archive read-only → re-bind", async () => {
  const suffix = `${uniqueSuffix}_a`;
  const suffixB = `${uniqueSuffix}_b`;
  const suffixC = `${uniqueSuffix}_c`;
  const userA = await registerUser(`${suffix}a`);
  const userB = await registerUser(`${suffixB}b`);
  const userC = await registerUser(`${suffixC}c`);
  const aid = userA.userId;
  const bid = userB.userId;

  // 健康检查
  const health = await fetch(`${baseUrl}/api/health`);
  assert.equal(health.status, 200);

  // A 邀请、B 绑定 → 绑定事务内创建 housework cycle + 共享空间
  const invite = await api(userA, "POST", "/api/couple/generate-invite", {});
  assert.equal(invite.status, 200, JSON.stringify(invite.body));
  const bind = await api(userB, "POST", "/api/couple/bind", { inviteCode: invite.body.data.inviteCode });
  assert.equal(bind.status, 200, JSON.stringify(bind.body));

  // bootstrap：双方个人空间 + 当前共享空间
  const bootA = await api(userA, "POST", "/api/housework/bootstrap", {});
  assert.equal(bootA.status, 200, JSON.stringify(bootA.body));
  const spaces = bootA.body.data.spaces;
  const personal = spaces.find((s) => s.scope === "personal" && s.canWrite !== false);
  const shared = spaces.find((s) => s.scope === "couple" && s.status === "active");
  assert.ok(personal, "personal space exists");
  assert.ok(shared, "shared space exists");
  assert.equal(bootA.body.data.defaultSpaceId, shared.spaceId);
  assert.ok(bootA.body.data.currentCycleId, "cycleId present");
  assert.ok(bootA.body.data.today, "today present");
  const cycleIdFirst = bootA.body.data.currentCycleId;
  const spaceId = shared.spaceId;

  // 重复 bootstrap 幂等：不新建空间
  const bootAgain = await api(userA, "POST", "/api/housework/bootstrap", {});
  assert.equal(bootAgain.body.data.currentCycleId, cycleIdFirst);
  assert.equal(bootAgain.body.data.spaces.length, spaces.length);

  // config：含兜底分类、双方成员、本人偏好
  const config = await api(userA, "GET", `/api/housework/spaces/${spaceId}/config`);
  assert.equal(config.status, 200, JSON.stringify(config.body));
  assert.equal(config.body.data.space.canWrite, true);
  const fallbackCategory = config.body.data.categories.find((c) => c.isFallback);
  assert.ok(fallbackCategory, "fallback category exists");
  const memberIds = config.body.data.members.map((m) => m.userId).sort();
  assert.deepEqual(memberIds, [String(aid), String(bid)].sort());
  assert.equal(config.body.data.preferences.version, 1);

  // knownRevision 命中 → unchanged
  const unchanged = await api(userA, "GET", `/api/housework/spaces/${spaceId}/config?knownRevision=${config.body.data.revision}`);
  assert.equal(unchanged.body.data.unchanged, true);
  assert.equal(unchanged.body.data.space.spaceId, spaceId);

  // 建分类
  const category = await api(userA, "POST", `/api/housework/spaces/${spaceId}/categories`, {
    clientMutationId: uuid(),
    name: "测试清洁",
    color: "#FF6B81"
  });
  assert.equal(category.status, 200, JSON.stringify(category.body));
  const categoryId = category.body.data.entity.categoryId;
  assert.equal(category.body.data.entity.version, 1);

  // 建模板：扫地(event/权重2) + 洗衣(quantity/件/权重0.5/5种字段)
  const sweep = await api(userA, "POST", `/api/housework/spaces/${spaceId}/templates`, {
    clientMutationId: uuid(),
    name: "扫地",
    categoryId,
    measureMode: "event",
    weight: "2.00"
  });
  assert.equal(sweep.status, 200, JSON.stringify(sweep.body));
  const sweepTemplate = sweep.body.data.entity;
  assert.equal(sweepTemplate.unit, "次");
  assert.equal(sweepTemplate.defaultQuantity, "1.00");
  assert.equal(sweepTemplate.version, 1);

  const fieldIds = {
    text: uuid(),
    num: uuid(),
    single: uuid(),
    multi: uuid(),
    bool: uuid()
  };
  const optLiving = uuid();
  const optDeep = uuid();
  const laundry = await api(userA, "POST", `/api/housework/spaces/${spaceId}/templates`, {
    clientMutationId: uuid(),
    name: "洗衣",
    categoryId,
    measureMode: "quantity",
    unit: "件",
    weight: "0.50",
    durationEnabled: true,
    fields: [
      { fieldId: fieldIds.text, label: "备注", type: "text", required: false },
      { fieldId: fieldIds.num, label: "件数", type: "number", required: true, min: "0.00", max: "99999.99" },
      {
        fieldId: fieldIds.single, label: "方式", type: "single_select", required: false,
        options: [{ optionId: optLiving, label: "机洗", status: "active" }]
      },
      {
        fieldId: fieldIds.multi, label: "标签", type: "multi_select", required: false,
        options: [{ optionId: optDeep, label: "深度", status: "active" }]
      },
      { fieldId: fieldIds.bool, label: "烘干", type: "boolean", required: false }
    ]
  });
  assert.equal(laundry.status, 200, JSON.stringify(laundry.body));
  const laundryTemplate = laundry.body.data.entity;
  assert.equal(laundryTemplate.fields.length, 5);

  // 记录：A 单独扫地；A/B 共同洗衣 60/40（body 伪造 userId 不生效）
  const today = bootA.body.data.today;
  const sweepRecord = await api(userA, "POST", `/api/housework/spaces/${spaceId}/records`, {
    clientMutationId: uuid(),
    userId: bid,
    templateId: sweepTemplate.templateId,
    templateVersion: sweepTemplate.version,
    completedDate: today,
    completedTime: null,
    participants: [{ userId: aid, shareBps: 10000 }],
    fieldValues: {},
    note: null
  });
  assert.equal(sweepRecord.status, 200, JSON.stringify(sweepRecord.body));
  assert.equal(sweepRecord.body.data.entity.createdBy, String(aid), "createdBy from token, not body");
  assert.equal(sweepRecord.body.data.entity.quantity, "1.00");

  const laundryRecord = await api(userA, "POST", `/api/housework/spaces/${spaceId}/records`, {
    clientMutationId: uuid(),
    templateId: laundryTemplate.templateId,
    templateVersion: laundryTemplate.version,
    completedDate: today,
    completedTime: null,
    participants: [{ userId: aid, shareBps: 6000 }, { userId: bid, shareBps: 4000 }],
    quantity: "5.00",
    durationMinutes: 30,
    fieldValues: {
      [fieldIds.text]: "床单",
      [fieldIds.num]: "0.00",
      [fieldIds.single]: optLiving,
      [fieldIds.multi]: [optDeep],
      [fieldIds.bool]: false
    },
    note: "床单和被套一起洗了"
  });
  assert.equal(laundryRecord.status, 200, JSON.stringify(laundryRecord.body));
  const laundryRecordId = laundryRecord.body.data.entity.recordId;
  assert.equal(laundryRecord.body.data.entity.fieldValues[fieldIds.bool], false, "false is a valid fill");
  assert.equal(laundryRecord.body.data.entity.fieldValues[fieldIds.num], "0.00");

  // 幂等重放：同 clientMutationId + 同 body → replayed:true 同 recordId
  const replayPayload = {
    clientMutationId: laundryRecord.body.data.entity.clientMutationId,
    templateId: laundryTemplate.templateId,
    templateVersion: laundryTemplate.version,
    completedDate: today,
    completedTime: null,
    participants: [{ userId: aid, shareBps: 6000 }, { userId: bid, shareBps: 4000 }],
    quantity: "5.00",
    durationMinutes: 30,
    fieldValues: {
      [fieldIds.text]: "床单",
      [fieldIds.num]: "0.00",
      [fieldIds.single]: optLiving,
      [fieldIds.multi]: [optDeep],
      [fieldIds.bool]: false
    },
    note: "床单和被套一起洗了"
  };
  const replay = await api(userA, "POST", `/api/housework/spaces/${spaceId}/records`, replayPayload);
  assert.equal(replay.status, 200, JSON.stringify(replay.body));
  assert.equal(replay.body.data.replayed, true);
  assert.equal(replay.body.data.entity.recordId, laundryRecordId);

  // 开启耗时/工作量统计
  const settingsPatch = await api(userA, "PATCH", `/api/housework/spaces/${spaceId}/settings`, {
    clientMutationId: uuid(),
    expectedVersion: config.body.data.settingsVersion,
    showDurationStatistics: true,
    showWorkloadStatistics: true
  });
  assert.equal(settingsPatch.status, 200, JSON.stringify(settingsPatch.body));

  // 统计（spec 8.6 口径）：家庭2件；A参与2/B参与1；分摊1.60/0.40；工作量3.50/1.00；时长18/12
  const stats = await api(userB, "GET", `/api/housework/spaces/${spaceId}/statistics`);
  assert.equal(stats.status, 200, JSON.stringify(stats.body));
  const s = stats.body.data;
  assert.equal(s.householdCount, 2);
  assert.equal(s.equivalentTotal, "2.00");
  assert.equal(s.durationTotalMinutes, "30.00");
  assert.deepEqual(s.durationCoverage, { count: 1, total: 2 });
  assert.equal(s.workloadTotal, "4.50");
  const statsA = s.members.find((m) => m.userId === String(aid));
  const statsB = s.members.find((m) => m.userId === String(bid));
  assert.equal(statsA.participationCount, 2);
  assert.equal(statsA.equivalentCount, "1.60");
  assert.equal(statsA.allocatedDurationMinutes, "18.00");
  assert.equal(statsA.workload, "3.50");
  assert.equal(statsB.participationCount, 1);
  assert.equal(statsB.equivalentCount, "0.40");
  assert.equal(statsB.allocatedDurationMinutes, "12.00");
  assert.equal(statsB.workload, "1.00");
  const sweepGroup = s.quantityGroups.find((g) => g.displayName === "扫地");
  const laundryGroup = s.quantityGroups.find((g) => g.displayName === "洗衣");
  assert.equal(sweepGroup.quantity, "1.00");
  assert.equal(laundryGroup.quantity, "5.00");
  assert.equal(laundryGroup.allocations.find((a) => a.userId === String(aid)).quantity, "3.00");
  assert.equal(laundryGroup.allocations.find((a) => a.userId === String(bid)).quantity, "2.00");

  // 越权：第三人不能访问共享空间
  const forbidden = await api(userC, "GET", `/api/housework/spaces/${spaceId}/config`);
  assert.equal(forbidden.status, 403);
  assert.equal(forbidden.body.error.code, "SPACE_FORBIDDEN");
  const cRecord = await api(userC, "GET", `/api/housework/spaces/${spaceId}/records/${laundryRecordId}`);
  assert.equal(cRecord.status, 403);

  // 先做一次成功编辑（版本 1 → 2），再用旧版本号编辑 → 409 VERSION_CONFLICT
  const firstEdit = await api(userB, "PATCH", `/api/housework/spaces/${spaceId}/records/${laundryRecordId}`, {
    clientMutationId: uuid(),
    expectedVersion: 1,
    note: "B 补充：浅色衣物"
  });
  assert.equal(firstEdit.status, 200, JSON.stringify(firstEdit.body));
  assert.equal(firstEdit.body.data.entity.version, 2);

  // 版本冲突：B 用旧 expectedVersion 编辑
  const staleEdit = await api(userB, "PATCH", `/api/housework/spaces/${spaceId}/records/${laundryRecordId}`, {
    clientMutationId: uuid(),
    expectedVersion: 1,
    note: "旧版本编辑"
  });
  assert.equal(staleEdit.status, 409);
  assert.equal(staleEdit.body.error.code, "VERSION_CONFLICT");
  const currentVersion = staleEdit.body.error.currentVersion;
  assert.equal(currentVersion, 2);
  assert.equal(typeof currentVersion, "number");

  // 解绑 → 旧空间只读
  const unbind = await api(userA, "DELETE", "/api/couple/unbind");
  assert.equal(unbind.status, 200, JSON.stringify(unbind.body));

  const closedWrite = await api(userA, "POST", `/api/housework/spaces/${spaceId}/categories`, {
    clientMutationId: uuid(),
    name: "解绑后分类"
  });
  assert.equal(closedWrite.status, 409);
  assert.equal(closedWrite.body.error.code, "SPACE_CLOSED");

  const closedRead = await api(userB, "GET", `/api/housework/spaces/${spaceId}/config`);
  assert.equal(closedRead.status, 200, JSON.stringify(closedRead.body));
  assert.equal(closedRead.body.data.space.status, "closed");
  assert.equal(closedRead.body.data.space.canWrite, false);

  const closedStats = await api(userA, "GET", `/api/housework/spaces/${spaceId}/statistics`);
  assert.equal(closedStats.status, 200, "archived space stays readable");
  assert.equal(closedStats.body.data.householdCount, 2);

  // 个人空间仍可写
  const personalConfig = await api(userA, "GET", `/api/housework/spaces/${personal.spaceId}/config`);
  assert.equal(personalConfig.status, 200);
  assert.equal(personalConfig.body.data.space.canWrite, true);

  // 重绑 → 新 cycleId / 新 spaceId
  const invite2 = await api(userA, "POST", "/api/couple/generate-invite", {});
  assert.equal(invite2.status, 200, JSON.stringify(invite2.body));
  const rebind = await api(userB, "POST", "/api/couple/bind", { inviteCode: invite2.body.data.inviteCode });
  assert.equal(rebind.status, 200, JSON.stringify(rebind.body));

  const bootAfter = await api(userA, "POST", "/api/housework/bootstrap", {});
  const cycleIdSecond = bootAfter.body.data.currentCycleId;
  assert.ok(cycleIdSecond, "new cycle exists");
  assert.notEqual(cycleIdSecond, cycleIdFirst, "every re-bind creates a new cycleId");
  const spacesAfter = bootAfter.body.data.spaces;
  const newShared = spacesAfter.find((sp) => sp.scope === "couple" && sp.status === "active");
  const oldShared = spacesAfter.find((sp) => sp.scope === "couple" && sp.status === "closed");
  assert.ok(newShared, "new shared space exists");
  assert.ok(oldShared, "old shared space archived");
  assert.notEqual(newShared.spaceId, spaceId);
  assert.equal(oldShared.spaceId, spaceId);

  // 新空间看不到旧记录
  const newStats = await api(userA, "GET", `/api/housework/spaces/${newShared.spaceId}/statistics`);
  assert.equal(newStats.body.data.householdCount, 0);

  // 推荐 key 与幂等导入在真实数据库核对。
  const presetKeys = ["cleaning.sweep", "cleaning.mop", "cleaning.vacuum", "kitchen.cook",
    "kitchen.dishes", "laundry.wash", "laundry.bedding", "pets.feed", "pets.fish_tank",
    "shopping.grocery", "misc.trash"];
  const presetPayload = { clientMutationId: uuid(), selectedPresetKeys: presetKeys };
  const presets = await api(userA, "POST", `/api/housework/spaces/${newShared.spaceId}/presets/apply`, presetPayload);
  assert.equal(presets.status, 200, JSON.stringify(presets.body));
  // 基础包（6 key）已在重绑建空间时自动播撒 → 本次只剩扩展项 imported；基线项 skipped 已存在
  assert.deepEqual(presets.body.data.entity.imported.map((item) => item.presetKey).sort(),
    ["cleaning.vacuum", "laundry.bedding", "pets.feed", "pets.fish_tank", "shopping.grocery"].sort());
  assert.deepEqual(presets.body.data.entity.skipped.sort(),
    ["cleaning.sweep", "cleaning.mop", "kitchen.cook", "kitchen.dishes", "laundry.wash", "misc.trash"].sort());
  const presetsReplay = await api(userA, "POST", `/api/housework/spaces/${newShared.spaceId}/presets/apply`, presetPayload);
  assert.equal(presetsReplay.body.data.replayed, true);
  const presetsAgain = await api(userA, "POST", `/api/housework/spaces/${newShared.spaceId}/presets/apply`, { ...presetPayload, clientMutationId: uuid() });
  assert.equal(presetsAgain.status, 200, JSON.stringify(presetsAgain.body));
  assert.equal(presetsAgain.body.data.entity.imported.length, 0);
  assert.equal(presetsAgain.body.data.entity.skipped.length, 11);

  // 同 relationship 第 2 次关闭曾因 open_flag=1 唯一键撞库。
  const unbind2 = await api(userA, "DELETE", "/api/couple/unbind");
  assert.equal(unbind2.status, 200, JSON.stringify(unbind2.body));
  const invite3 = await api(userA, "POST", "/api/couple/generate-invite", {});
  const bind3 = await api(userB, "POST", "/api/couple/bind", { inviteCode: invite3.body.data.inviteCode });
  assert.equal(bind3.status, 200, JSON.stringify(bind3.body));
  const boot3 = await api(userA, "POST", "/api/housework/bootstrap", {});
  assert.equal(boot3.status, 200, JSON.stringify(boot3.body));
  assert.notEqual(boot3.body.data.currentCycleId, cycleIdSecond);
  assert.notEqual(boot3.body.data.currentCycleId, cycleIdFirst);
  assert.equal(boot3.body.data.spaces.filter((sp) => sp.scope === "couple" && sp.status === "closed").length, 2);
  const shared3 = boot3.body.data.spaces.find((sp) => sp.scope === "couple" && sp.status === "active");

  // 本人个人空间留下完整业务树，再删除帐号验证清理没有内部 FK 阻断。
  const personalCategoryId = personalConfig.body.data.categories.find((cat) => cat.isFallback).categoryId;
  const stableFieldId = uuid();
  const stableOptionId = uuid();
  const stableOptionId2 = uuid();
  const personalTemplate = await api(userA, "POST", `/api/housework/spaces/${personal.spaceId}/templates`, {
    clientMutationId: uuid(), name: "清理本人测试", categoryId: personalCategoryId,
    icon: { type: "emoji", value: "🧹" }, measureMode: "event",
    fields: [{ fieldId: stableFieldId, label: "区域", type: "single_select", required: true,
      defaultValue: null, status: "active",
      options: [{ optionId: stableOptionId, label: "客厅", status: "active" },
        { optionId: stableOptionId2, label: "卧室", status: "active" }] }]
  });
  assert.equal(personalTemplate.status, 200, JSON.stringify(personalTemplate.body));
  assert.deepEqual(personalTemplate.body.data.entity.icon, { type: "emoji", value: "🧹" });
  assert.equal(personalTemplate.body.data.entity.fields[0].defaultValue, null);
  const pt = personalTemplate.body.data.entity;
  const personalRecord = await api(userA, "POST", `/api/housework/spaces/${personal.spaceId}/records`, {
    clientMutationId: uuid(), templateId: pt.templateId, templateVersion: pt.version,
    completedDate: today, participants: [{ userId: aid, shareBps: 10000 }],
    fieldValues: { [stableFieldId]: stableOptionId }, note: null
  });
  assert.equal(personalRecord.status, 200, JSON.stringify(personalRecord.body));
  const removedField = await api(userA, "PATCH", `/api/housework/spaces/${personal.spaceId}/templates/${pt.templateId}`, {
    clientMutationId: uuid(), expectedVersion: pt.version, fields: []
  });
  assert.equal(removedField.status, 422, JSON.stringify(removedField.body));
  assert.equal(removedField.body.error.fieldErrors[0].code, "ID_REMOVAL_FORBIDDEN");
  const disabledFields = pt.fields.map((field) => ({ ...field, status: "disabled",
    options: field.options.map((option) => ({ ...option, status: "disabled" })) }));
  const disable = await api(userA, "PATCH", `/api/housework/spaces/${personal.spaceId}/templates/${pt.templateId}`, {
    clientMutationId: uuid(), expectedVersion: pt.version, fields: disabledFields
  });
  assert.equal(disable.status, 200, JSON.stringify(disable.body));
  assert.equal(disable.body.data.entity.fields[0].status, "disabled");
  assert.ok(disable.body.data.entity.fields[0].options.every((option) => option.status === "disabled"));
  // 历史记录编辑仍校验自己的启用快照，禁用当前字段不破坏旧值。
  const oldSnapshotEdit = await api(userA, "PATCH", `/api/housework/spaces/${personal.spaceId}/records/${personalRecord.body.data.entity.recordId}`, {
    clientMutationId: uuid(), expectedVersion: 1, fieldValues: { [stableFieldId]: stableOptionId2 }
  });
  assert.equal(oldSnapshotEdit.status, 200, JSON.stringify(oldSnapshotEdit.body));
  const pref = await api(userA, "PATCH", `/api/housework/spaces/${personal.spaceId}/preferences`, {
    clientMutationId: uuid(), expectedVersion: 1, pinnedTemplateIds: [pt.templateId]
  });
  assert.equal(pref.status, 200, JSON.stringify(pref.body));

  // 删除帐号必须关闭当前共享空间，保留 B 可读的旧记录、两人份额和操作历史。
  const deleted = await api(userA, "DELETE", "/api/auth/account", { password: "secret123" });
  assert.equal(deleted.status, 200, JSON.stringify(deleted.body));
  const deletedSession = await api(userA, "POST", "/api/housework/bootstrap", {});
  assert.equal(deletedSession.status, 401, JSON.stringify(deletedSession.body));
  const remainingBoot = await api(userB, "POST", "/api/housework/bootstrap", {});
  assert.equal(remainingBoot.status, 200, JSON.stringify(remainingBoot.body));
  assert.equal(remainingBoot.body.data.currentCycleId, null);
  assert.equal(remainingBoot.body.data.spaces.filter((sp) => sp.scope === "couple" && sp.status === "closed").length, 3);
  const retained = await api(userB, "GET", `/api/housework/spaces/${spaceId}/records/${laundryRecordId}`);
  assert.equal(retained.status, 200, JSON.stringify(retained.body));
  assert.equal(retained.body.data.createdBy, String(aid));
  assert.deepEqual(retained.body.data.participants.map((p) => [p.userId, p.shareBps]).sort(),
    [[String(aid), 6000], [String(bid), 4000]].sort());
  const retainedStats = await api(userB, "GET", `/api/housework/spaces/${spaceId}/statistics`);
  assert.equal(retainedStats.body.data.householdCount, 2);
  const currentClosed = await api(userB, "GET", `/api/housework/spaces/${shared3.spaceId}/config`);
  assert.equal(currentClosed.status, 200);
  assert.equal(currentClosed.body.data.space.status, "closed");
  assert.equal(currentClosed.body.data.members.length, 2);
  const retainedRevisions = await api(userB, "GET", `/api/housework/spaces/${spaceId}/records/${laundryRecordId}/revisions`);
  assert.equal(retainedRevisions.status, 200, JSON.stringify(retainedRevisions.body));
  assert.ok(retainedRevisions.body.data.items.some((item) => item.actorUserId === String(aid)));
  console.log("housework fixture verified", JSON.stringify({ deletedUserId: aid, remainingUserId: bid,
    deletedUsername: userA.username, remainingUsername: userB.username,
    personalSpaceId: personal.spaceId, archivedSpaceIds: [spaceId, newShared.spaceId, shared3.spaceId] }));

});

test("missing current cycle rejects unbind and rolls relationship and both users back in MySQL", async () => {
  const userA = await registerUser("missingcyclea");
  const userB = await registerUser("missingcycleb");
  const invite = await api(userA, "POST", "/api/couple/generate-invite", {});
  const bind = await api(userB, "POST", "/api/couple/bind", { inviteCode: invite.body.data.inviteCode });
  assert.equal(bind.status, 200, JSON.stringify(bind.body));
  const relationshipId = bind.body.data.relationshipId;
  const bootstrap = await api(userA, "POST", "/api/housework/bootstrap", {});
  assert.equal(bootstrap.status, 200, JSON.stringify(bootstrap.body));
  const cycleId = bootstrap.body.data.currentCycleId;
  const shared = bootstrap.body.data.spaces.find((space) => space.scope === "couple" && space.status === "active");
  const config = require("../src/config").readConfig();
  assert.ok(["127.0.0.1", "localhost", "::1"].includes(config.dbHost), "fault injection database must be local");
  const conn = await require("mysql2/promise").createConnection({
    host: config.dbHost, port: config.dbPort, user: config.dbUser,
    password: config.dbPassword, database: config.dbName
  });
  try {
    // 仅破坏本用例刚创建、尚无记录的共享周期；不会查询或写入其他帐号的数据。
    await conn.beginTransaction();
    const [records] = await conn.execute("SELECT COUNT(*) AS count FROM housework_records WHERE space_id = ?", [shared.spaceId]);
    assert.equal(Number(records[0].count), 0);
    const [cycles] = await conn.execute(
      "SELECT cycle_id FROM housework_relationship_cycles WHERE cycle_id = ? AND relationship_id = ? AND member_user_id_1 = ? AND member_user_id_2 = ?",
      [cycleId, relationshipId, userA.userId, userB.userId]
    );
    assert.equal(cycles.length, 1);
    await conn.execute("DELETE FROM housework_templates WHERE space_id = ?", [shared.spaceId]);
    await conn.execute("DELETE FROM housework_categories WHERE space_id = ?", [shared.spaceId]);
    await conn.execute("DELETE FROM housework_space_members WHERE space_id = ?", [shared.spaceId]);
    await conn.execute("DELETE FROM housework_spaces WHERE space_id = ?", [shared.spaceId]);
    await conn.execute("DELETE FROM housework_relationship_cycles WHERE cycle_id = ?", [cycleId]);
    await conn.commit();

    const unbind = await api(userA, "DELETE", "/api/couple/unbind");
    assert.equal(unbind.status, 409, JSON.stringify(unbind.body));
    assert.equal(unbind.body.error.code, "SPACE_STATE_INVALID");
    const [relations] = await conn.execute("SELECT status FROM couple_relationships WHERE relationship_id = ?", [relationshipId]);
    assert.equal(relations[0].status, "active", "relationship UPDATE was rolled back");
    const [users] = await conn.execute("SELECT id, couple_status FROM users WHERE id IN (?, ?)", [userA.userId, userB.userId]);
    assert.equal(users.length, 2);
    assert.ok(users.every((user) => user.couple_status === "coupled"), "neither member was unbound");
    // 本用例已移除全部家务历史，bootstrap 可按无历史兼容规则补建，再完成正常解绑。
    const repaired = await api(userA, "POST", "/api/housework/bootstrap", {});
    assert.equal(repaired.status, 200, JSON.stringify(repaired.body));
    assert.notEqual(repaired.body.data.currentCycleId, cycleId);
    const normalUnbind = await api(userA, "DELETE", "/api/couple/unbind");
    assert.equal(normalUnbind.status, 200, JSON.stringify(normalUnbind.body));
    console.log("missing-cycle rollback fixture verified", JSON.stringify({
      userIds: [userA.userId, userB.userId], relationshipId, restoredCycleId: repaired.body.data.currentCycleId
    }));
  } catch (error) {
    await conn.rollback();
    throw error;
  } finally {
    await conn.end();
  }
});

test("legacy relationship initialization gate is read-only, explicit, idempotent and rejects damaged history", async () => {
  const userA = await registerUser("legacya");
  const userB = await registerUser("legacyb");
  const invite = await api(userA, "POST", "/api/couple/generate-invite", {});
  const bind = await api(userB, "POST", "/api/couple/bind", { inviteCode: invite.body.data.inviteCode });
  assert.equal(bind.status, 200, JSON.stringify(bind.body));
  const relationshipId = bind.body.data.relationshipId;
  const bootstrap = await api(userA, "POST", "/api/housework/bootstrap", {});
  assert.equal(bootstrap.status, 200, JSON.stringify(bootstrap.body));
  const oldCycleId = bootstrap.body.data.currentCycleId;
  const shared = bootstrap.body.data.spaces.find((space) => space.scope === "couple" && space.status === "active");
  const config = require("../src/config").readConfig();
  assert.ok(["127.0.0.1", "localhost", "::1"].includes(config.dbHost), "legacy fixture database must be local");
  const conn = await require("mysql2/promise").createConnection({
    host: config.dbHost, port: config.dbPort, user: config.dbUser,
    password: config.dbPassword, database: config.dbName
  });
  const invokeGate = (apply) => {
    const args = ["--env-file=.env", "scripts/dev/initialize-housework-legacy.js", "--relationship-id", String(relationshipId)];
    if (apply) args.push("--apply");
    const child = require("node:child_process").spawnSync(process.execPath, args, {
      cwd: require("node:path").resolve(__dirname, ".."), encoding: "utf8"
    });
    assert.equal(child.signal, null, child.stderr);
    assert.equal(child.error, undefined);
    return { status: child.status, result: JSON.parse(child.stdout.trim()) };
  };
  try {
    // 仅移除刚创建且无记录的 fixture 周期，模拟上线前 active 关系尚无家务数据。
    await conn.beginTransaction();
    const [records] = await conn.execute("SELECT COUNT(*) AS count FROM housework_records WHERE space_id = ?", [shared.spaceId]);
    assert.equal(Number(records[0].count), 0);
    await conn.execute("DELETE FROM housework_templates WHERE space_id = ?", [shared.spaceId]);
    await conn.execute("DELETE FROM housework_categories WHERE space_id = ?", [shared.spaceId]);
    await conn.execute("DELETE FROM housework_space_members WHERE space_id = ?", [shared.spaceId]);
    await conn.execute("DELETE FROM housework_spaces WHERE space_id = ?", [shared.spaceId]);
    await conn.execute("DELETE FROM housework_relationship_cycles WHERE cycle_id = ? AND relationship_id = ?", [oldCycleId, relationshipId]);
    await conn.commit();

    const dry = invokeGate(false);
    assert.equal(dry.status, 1);
    assert.equal(dry.result.mode, "dry_run");
    assert.equal(dry.result.relationships[0].state, "needs_initialization");
    const [before] = await conn.execute("SELECT COUNT(*) AS count FROM housework_relationship_cycles WHERE relationship_id = ?", [relationshipId]);
    assert.equal(Number(before[0].count), 0, "dry-run must not write");
    const blockedUnbind = await api(userA, "DELETE", "/api/couple/unbind");
    assert.equal(blockedUnbind.body.error.code, "SPACE_STATE_INVALID", "runtime guard remains strict");

    const applied = invokeGate(true);
    assert.equal(applied.status, 0, JSON.stringify(applied.result));
    assert.equal(applied.result.relationships[0].created, true);
    const cycleId = applied.result.relationships[0].cycleId;
    assert.notEqual(cycleId, oldCycleId);
    const replay = invokeGate(true);
    assert.equal(replay.status, 0, JSON.stringify(replay.result));
    assert.equal(replay.result.relationships[0].created, false);
    assert.equal(replay.result.relationships[0].cycleId, cycleId);
    const ready = invokeGate(false);
    assert.equal(ready.status, 0);
    assert.equal(ready.result.ready, true);
    const unbind = await api(userA, "DELETE", "/api/couple/unbind");
    assert.equal(unbind.status, 200, JSON.stringify(unbind.body));

    // 模拟关系错误地恢复 active，但历史 cycle 已 ended：初始化脚本也不能重开。
    await conn.execute("UPDATE couple_relationships SET status = 'active' WHERE relationship_id = ?", [relationshipId]);
    await conn.execute("UPDATE users SET couple_status = 'coupled' WHERE id IN (?, ?)", [userA.userId, userB.userId]);
    const damaged = invokeGate(true);
    assert.equal(damaged.status, 1);
    assert.equal(damaged.result.mode, "apply_blocked");
    assert.equal(damaged.result.relationships[0].code, "SPACE_STATE_INVALID");
    const [after] = await conn.execute("SELECT cycle_id, ended_at FROM housework_relationship_cycles WHERE relationship_id = ?", [relationshipId]);
    assert.equal(after.length, 1, "damaged history cannot get a new cycle");
    assert.equal(after[0].cycle_id, cycleId);
    assert.ok(after[0].ended_at);
    console.log("legacy initialization fixture verified", JSON.stringify({ userIds: [userA.userId, userB.userId], relationshipId, cycleId }));
  } catch (error) {
    await conn.rollback();
    throw error;
  } finally {
    // 清理本 fixture 的模拟异常，避免后续全库只读发布门禁被测试损坏状态污染。
    await conn.execute("UPDATE couple_relationships SET status = 'dissolved' WHERE relationship_id = ?", [relationshipId]);
    await conn.execute("UPDATE users SET couple_status = 'single' WHERE id IN (?, ?)", [userA.userId, userB.userId]);
    await conn.end();
  }
});
