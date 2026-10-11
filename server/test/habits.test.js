/**
 * 情侣打卡 / 习惯共建（/api/habits）集成测试。
 *
 * 覆盖：建/改/归档/删、couple 空间双方可见、个人空间隔离、打卡幂等（UNIQUE 撞键
 * 不重复）、撤销当天打卡、myStreak 连续口径（今天+昨天=2、仅昨天=1、断签=0）、
 * jointDays 共同天数、解绑后不可见不可操作、未登录 401、habit.checkin 事件投递给伴侣。
 *
 * 需要服务运行在 AUTH_API_BASE_URL（默认 18099 dev），迁移 041 已执行：
 *   AUTH_API_BASE_URL=http://127.0.0.1:8082 node --env-file=.env --test test/habits.test.js
 */
const assert = require("assert");
const { test } = require("node:test");
const mysql = require("mysql2/promise");

const baseUrl = process.env.AUTH_API_BASE_URL || "http://127.0.0.1:18099";
const inviteCode = process.env.AUTH_API_INVITE_CODE || "COUPLE-PRIVATE-2026";

async function readJsonOrText(response) {
  const text = await response.text();
  try { return JSON.parse(text); } catch (_e) { return text; }
}

async function registerUser(tag) {
  // 与 test/activity.test.js 相同约定：v→w，避开 TEXT_PROMO_PATTERNS
  const { TEXT_PROMO_PATTERNS } = require("../src/services/contentSecurity");
  let username = "";
  for (let attempt = 0; attempt < 10 && !username; attempt += 1) {
    const stem = `${Date.now().toString(36).replace(/v/g, "w")}${tag}${Math.floor(Math.random() * 46656).toString(36).replace(/v/g, "w")}`;
    const candidate = `hab${stem}`;
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

async function bindCouple(userA, userB) {
  const invite = await api(userA, "POST", "/api/couple/generate-invite", {});
  assert.equal(invite.status, 200, JSON.stringify(invite.body));
  const bind = await api(userB, "POST", "/api/couple/bind", { inviteCode: invite.body.data.inviteCode });
  assert.equal(bind.status, 200, JSON.stringify(bind.body));
  return bind.body.data.relationshipId;
}

async function openDb() {
  return mysql.createConnection({
    host: process.env.DB_HOST || "127.0.0.1",
    port: Number(process.env.DB_PORT || 3306),
    user: process.env.DB_USER || "couple_app",
    password: process.env.DB_PASSWORD || "ChangeThisPrivateDbPassword_2026!",
    database: process.env.DB_NAME || "couple_credit_private"
  });
}

/** 空间时区（Asia/Shanghai）口径的日期工具。 */
function shanghaiDay(deltaDays = 0) {
  const today = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit"
  }).format(new Date());
  const date = new Date(`${today}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + deltaDays);
  return date.toISOString().slice(0, 10);
}

async function insertCheckin(db, habitId, userId, day) {
  await db.execute(
    "INSERT INTO habit_checkins (habit_id, user_id, checkin_on) VALUES (?, ?, ?)",
    [habitId, userId, day]
  );
}

async function listHabits(user) {
  const res = await api(user, "GET", "/api/habits");
  assert.equal(res.status, 200, JSON.stringify(res.body));
  return res.body.data;
}

test("couple 空间：建/改/归档/删、双方可见、打卡幂等、jointDays、habit.checkin 动态", async () => {
  const userA = await registerUser("h1a");
  const userB = await registerUser("h1b");
  await bindCouple(userA, userB);

  // 校验：空标题 422
  const bad = await api(userA, "POST", "/api/habits", { title: "  " });
  assert.equal(bad.status, 422, JSON.stringify(bad.body));

  // 创建（带幂等键，重放返回同一习惯）
  const key = `habit${Date.now()}h1`;
  const created = await api(userA, "POST", "/api/habits", { title: "早起喝水", icon: "💧", idempotencyKey: key });
  assert.equal(created.status, 200, JSON.stringify(created.body));
  const habit = created.body.data;
  assert.equal(habit.title, "早起喝水");
  assert.equal(habit.icon, "💧");
  assert.equal(habit.status, "active");
  assert.equal(habit.creatorId, userA.userId);
  const replayed = await api(userA, "POST", "/api/habits", { title: "早起喝水", icon: "💧", idempotencyKey: key });
  assert.equal(replayed.status, 200);
  assert.equal(replayed.body.data.habitId, habit.habitId, "同键重放返回同一习惯");

  // 双方可见（couple 空间）
  let listA = await listHabits(userA);
  assert.equal(listA.scope, "couple");
  assert.equal(listA.items.length, 1);
  assert.deepEqual(listA.items[0].todayCheckins, []);
  assert.equal(listA.items[0].myStreak, 0);
  assert.equal(listA.items[0].partnerStreak, 0, "couple 空间有 partnerStreak");
  assert.equal(listA.items[0].jointDays, 0);
  const listB = await listHabits(userB);
  assert.equal(listB.items.length, 1, "伴侣可见同一习惯");
  assert.equal(listB.items[0].habitId, habit.habitId);

  // 打卡 + 重复打卡幂等（UNIQUE 撞键，不新增记录）
  const checkin = await api(userA, "POST", `/api/habits/${habit.habitId}/checkin`, {});
  assert.equal(checkin.status, 200, JSON.stringify(checkin.body));
  assert.equal(checkin.body.data.created, true);
  assert.equal(checkin.body.data.checkinOn, shanghaiDay(0));
  const dup = await api(userA, "POST", `/api/habits/${habit.habitId}/checkin`, {});
  assert.equal(dup.status, 200, JSON.stringify(dup.body));
  assert.equal(dup.body.data.created, false, "重复打卡幂等返回已有");
  listA = await listHabits(userA);
  assert.deepEqual(listA.items[0].todayCheckins, [userA.userId], "重复打卡不重复计数");
  assert.equal(listA.items[0].myStreak, 1);

  // 造昨天数据：A 连续 2 天
  const db = await openDb();
  try {
    await insertCheckin(db, habit.habitId, userA.userId, shanghaiDay(-1));
  } finally {
    await db.end();
  }
  listA = await listHabits(userA);
  assert.equal(listA.items[0].myStreak, 2, "昨天+今天=连续 2 天");

  // habit.checkin 动态投递给伴侣（本人没有；重复打卡不重复投递）
  const inboxB = await api(userB, "GET", "/api/activity");
  const habitEventsForB = inboxB.body.data.items.filter((item) => item.eventType === "habit.checkin");
  assert.equal(habitEventsForB.length, 1, "伴侣收件箱收到 1 条 habit.checkin");
  assert.equal(habitEventsForB[0].resourceId, habit.habitId);
  assert.equal(habitEventsForB[0].titleSnapshot, "早起喝水");
  assert.equal(habitEventsForB[0].actorId, userA.userId);
  const inboxA0 = await api(userA, "GET", "/api/activity");
  assert.equal(inboxA0.body.data.items.filter((item) => item.eventType === "habit.checkin").length, 0, "本人收件箱没有");

  // 伴侣打卡：jointDays=1，partnerStreak 更新
  const checkinB = await api(userB, "POST", `/api/habits/${habit.habitId}/checkin`, {});
  assert.equal(checkinB.body.data.created, true);
  listA = await listHabits(userA);
  assert.equal(listA.items[0].todayCheckins.length, 2, "双方都已在今天打卡");
  assert.equal(listA.items[0].jointDays, 1, "双方同一天都打卡的共同天数");
  assert.equal(listA.items[0].partnerStreak, 1, "伴侣连续天数");

  // 改 title/icon（成员均可）
  const patched = await api(userB, "PATCH", `/api/habits/${habit.habitId}`, { title: "早起喝水 500ml", icon: "🥛" });
  assert.equal(patched.status, 200, JSON.stringify(patched.body));
  assert.equal(patched.body.data.title, "早起喝水 500ml");
  assert.equal(patched.body.data.icon, "🥛");

  // 归档后列表不再展示，且不可再打卡
  const archived = await api(userA, "PATCH", `/api/habits/${habit.habitId}`, { status: "archived" });
  assert.equal(archived.body.data.status, "archived");
  listA = await listHabits(userA);
  assert.equal(listA.items.length, 0, "归档习惯不在列表");
  const checkinArchived = await api(userA, "POST", `/api/habits/${habit.habitId}/checkin`, {});
  assert.equal(checkinArchived.status, 409, "已归档不可打卡");

  // 删除（连带 checkins）
  const deleted = await api(userA, "DELETE", `/api/habits/${habit.habitId}`);
  assert.equal(deleted.status, 200, JSON.stringify(deleted.body));
  const db2 = await openDb();
  try {
    const [rows] = await db2.execute("SELECT COUNT(*) AS n FROM habit_checkins WHERE habit_id = ?", [habit.habitId]);
    assert.equal(Number(rows[0].n), 0, "删除连带打卡记录");
  } finally {
    await db2.end();
  }
  const afterDelete = await api(userA, "POST", `/api/habits/${habit.habitId}/checkin`, {});
  assert.equal(afterDelete.status, 404);
});

test("myStreak 口径：断签=0、仅昨天=1、昨天+今天=2", async () => {
  const userC = await registerUser("h2a");
  const userD = await registerUser("h2b");
  await bindCouple(userC, userD);

  const mk = async (title) => {
    const res = await api(userC, "POST", "/api/habits", { title });
    assert.equal(res.status, 200, JSON.stringify(res.body));
    return res.body.data.habitId;
  };
  const brokenHabit = await mk("断签习惯");
  const yesterdayOnly = await mk("昨天习惯");
  const streakTwo = await mk("连续习惯");

  const db = await openDb();
  try {
    // 断签：只有前天，没有昨天/今天
    await insertCheckin(db, brokenHabit, userC.userId, shanghaiDay(-2));
    // 仅昨天
    await insertCheckin(db, yesterdayOnly, userC.userId, shanghaiDay(-1));
    // 昨天 + 今天（今天走接口打卡）
    await insertCheckin(db, streakTwo, userC.userId, shanghaiDay(-1));
  } finally {
    await db.end();
  }
  const todayCheckin = await api(userC, "POST", `/api/habits/${streakTwo}/checkin`, {});
  assert.equal(todayCheckin.body.data.created, true);

  const list = await listHabits(userC);
  const byId = new Map(list.items.map((item) => [item.habitId, item]));
  assert.equal(byId.get(brokenHabit).myStreak, 0, "断签归零");
  assert.equal(byId.get(yesterdayOnly).myStreak, 1, "今天未打则从昨天往前数");
  assert.equal(byId.get(streakTwo).myStreak, 2, "昨天+今天=2");
  assert.deepEqual(byId.get(streakTwo).todayCheckins, [userC.userId]);
});

test("个人空间：无关系时可建可打卡，他人不可见不可操作", async () => {
  const userE = await registerUser("h3a");
  const userF = await registerUser("h3b");

  const created = await api(userE, "POST", "/api/habits", { title: "独处阅读", icon: "📚" });
  assert.equal(created.status, 200, JSON.stringify(created.body));
  const habit = created.body.data;

  const listE = await listHabits(userE);
  assert.equal(listE.scope, "personal");
  assert.equal(listE.items.length, 1);
  assert.equal(listE.items[0].partnerStreak, null, "个人空间没有 partnerStreak");

  // 他人（无关系）列表为空，且不能操作该习惯
  const listF = await listHabits(userF);
  assert.equal(listF.items.length, 0, "个人空间互相隔离");
  const patchF = await api(userF, "PATCH", `/api/habits/${habit.habitId}`, { title: "改名" });
  assert.equal(patchF.status, 404);
  const checkinF = await api(userF, "POST", `/api/habits/${habit.habitId}/checkin`, {});
  assert.equal(checkinF.status, 404);
  const deleteF = await api(userF, "DELETE", `/api/habits/${habit.habitId}`);
  assert.equal(deleteF.status, 404);

  // 本人打卡 → 撤销 → 再打卡
  const checkin = await api(userE, "POST", `/api/habits/${habit.habitId}/checkin`, {});
  assert.equal(checkin.body.data.created, true);
  const undone = await api(userE, "DELETE", `/api/habits/${habit.habitId}/checkin`);
  assert.equal(undone.status, 200, JSON.stringify(undone.body));
  assert.equal(undone.body.data.removed, true);
  const undoneAgain = await api(userE, "DELETE", `/api/habits/${habit.habitId}/checkin`);
  assert.equal(undoneAgain.body.data.removed, false, "当天无打卡可撤销");
  const listAfterUndo = await listHabits(userE);
  assert.deepEqual(listAfterUndo.items[0].todayCheckins, [], "撤销后今日未打卡");
  assert.equal(listAfterUndo.items[0].myStreak, 0);
  const recheck = await api(userE, "POST", `/api/habits/${habit.habitId}/checkin`, {});
  assert.equal(recheck.body.data.created, true, "撤销后可重新打卡");
});

test("解绑后习惯不可见不可操作", async () => {
  const userG = await registerUser("h4a");
  const userH = await registerUser("h4b");
  await bindCouple(userG, userH);

  const created = await api(userG, "POST", "/api/habits", { title: "一起早睡" });
  assert.equal(created.status, 200, JSON.stringify(created.body));
  const habitId = created.body.data.habitId;
  await api(userG, "POST", `/api/habits/${habitId}/checkin`, {});

  const unbind = await api(userG, "DELETE", "/api/couple/unbind", {});
  assert.equal(unbind.status, 200, JSON.stringify(unbind.body));

  const listG = await listHabits(userG);
  assert.equal(listG.items.length, 0, "解绑后列表为空");
  const listH = await listHabits(userH);
  assert.equal(listH.items.length, 0, "伴侣同样不可见");
  const checkinAfter = await api(userH, "POST", `/api/habits/${habitId}/checkin`, {});
  assert.equal(checkinAfter.status, 404, "解绑后不可打卡");
  const patchAfter = await api(userH, "PATCH", `/api/habits/${habitId}`, { title: "改名" });
  assert.equal(patchAfter.status, 404, "解绑后不可修改");
});

test("未登录 401", async () => {
  for (const [method, path] of [
    ["GET", "/api/habits"],
    ["POST", "/api/habits"],
    ["PATCH", "/api/habits/whatever"],
    ["DELETE", "/api/habits/whatever"],
    ["POST", "/api/habits/whatever/checkin"],
    ["DELETE", "/api/habits/whatever/checkin"]
  ]) {
    const response = await fetch(`${baseUrl}${path}`, { method });
    assert.equal(response.status, 401, `${method} ${path} 未登录应 401`);
  }
});
