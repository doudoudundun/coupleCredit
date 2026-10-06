#!/usr/bin/env node
/**
 * 情侣绑定的端到端契约验证（只碰 users / couple_relationships 两张表）。
 *
 * 为什么需要它：生产 /api/couple/bind 曾经读 `{inviterId, inviteeId}`，而小程序端
 * （api/couple.js）与安卓端（AuthApiClient.bindCouple）**都已改为传 `{inviteCode}`**
 * → 绑定在生产上必然 400「参数不完整」。而且旧写法完全是客户端说了算：
 * 不校验邀请码、也不校验发起人是不是当前登录用户，任何登录用户都能把自己绑到
 * 任意 userId 上（抢占/锁定别人的情侣关系）。2026-09-23 在生产实测并修复。
 *
 * 断言的重点不是「200 成功」，而是：
 *   - 旧写法必须 400 且**不能悄悄绑上**（越权关闭，这条最关键）
 *   - 新写法必须能成功（功能可用）
 *   - 邀请码一次性、不能绑自己、无 token 仍 401
 *
 * 用法：
 *   BASE_URL=https://api.couplecredit.top INVITE_CODE=xxx node scripts/verify-couple-bind.js
 *
 * ⚠️ 会在目标库里创建 2 个测试账号（用户名以 verifybind 开头）。
 *    脚本**不会自清理**，跑完请手动删：
 *      DELETE FROM couple_relationships WHERE user_id_1 IN (SELECT id FROM users WHERE username LIKE 'verifybind%')
 *        OR user_id_2 IN (SELECT id FROM users WHERE username LIKE 'verifybind%');
 *      DELETE FROM users WHERE username LIKE 'verifybind%';
 */
const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8082';
const INVITE_CODE = process.env.INVITE_CODE || '';
const PASSWORD = 'Verify123456';
const stamp = Date.now().toString().slice(-8);

let passed = 0;
const failures = [];

function ok(label, cond, detail) {
  if (cond) {
    passed++;
    console.log(`  ✅ ${label}${detail ? '  → ' + detail : ''}`);
  } else {
    failures.push(label);
    console.log(`  ❌ ${label}  → ${detail || ''}`);
  }
}

async function call(method, path, { token, body } = {}) {
  const headers = {};
  if (token) headers.Authorization = 'Bearer ' + token;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(BASE_URL + path, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch (_e) { /* 非 JSON */ }
  return { status: res.status, json, text };
}

async function registerAndLogin(tag) {
  const username = `verifybind${stamp}${tag}`;
  const reg = await call('POST', '/api/auth/register', {
    body: { username, email: `${username}@verify.local`, password: PASSWORD, inviteCode: INVITE_CODE }
  });
  if (reg.status !== 200 && reg.status !== 201) {
    throw new Error(`注册失败 HTTP ${reg.status} ${reg.text.slice(0, 200)}`);
  }
  const r = await call('POST', '/api/auth/login', { body: { username, password: PASSWORD } });
  const d = (r.json && r.json.data) || {};
  return { username, userId: d.userId, accessToken: d.accessToken };
}

(async () => {
  console.log(`目标后端：${BASE_URL}\n`);

  if (!INVITE_CODE) {
    console.error('缺少 INVITE_CODE');
    process.exit(1);
  }

  console.log('=== 0. 连通性 ===');
  const health = await call('GET', '/api/auth/healthz');
  ok('后端可达', health.status === 200, `HTTP ${health.status}`);
  if (health.status !== 200) process.exit(1);

  console.log('\n=== 1. 建两个测试账号（顺带验证注册可用）===');
  const A = await registerAndLogin('a');
  const B = await registerAndLogin('b');
  ok('A 注册并登录', Boolean(A.accessToken && A.userId), `userId=${A.userId}`);
  ok('B 注册并登录（连续两次都能注册，说明唯一键未被堵）', Boolean(B.accessToken && B.userId), `userId=${B.userId}`);
  console.log(`  ↳ 清理用 id：${A.userId},${B.userId}`);

  console.log('\n=== 2. 旧写法必须失效，且不能悄悄绑上（越权关闭）===');
  const legacy = await call('POST', '/api/couple/bind', {
    token: B.accessToken,
    body: { inviterId: A.userId, inviteeId: B.userId }
  });
  ok('POST /bind {inviterId,inviteeId} → 400', legacy.status === 400,
    `HTTP ${legacy.status} ${legacy.json && legacy.json.error ? legacy.json.error.code : ''}`);

  const roleAfterLegacy = await call('GET', '/api/couple/role', { token: B.accessToken });
  const rl = (roleAfterLegacy.json && roleAfterLegacy.json.data) || {};
  ok('旧写法之后 B 仍未绑定', rl.hasRelationship === false, `hasRelationship=${rl.hasRelationship}`);

  console.log('\n=== 3. 新写法必须可用 ===');
  const gen = await call('POST', '/api/couple/generate-invite', { token: A.accessToken });
  const code = gen.json && gen.json.data && gen.json.data.inviteCode;
  ok('A 生成邀请码', gen.status === 200 && Boolean(code), `inviteCode=${code}`);

  const selfBind = await call('POST', '/api/couple/bind', { token: A.accessToken, body: { inviteCode: code } });
  ok('A 用自己的邀请码 → 400 SELF_BIND', selfBind.status === 400,
    `HTTP ${selfBind.status} ${selfBind.json && selfBind.json.error ? selfBind.json.error.code : ''}`);

  const bind = await call('POST', '/api/couple/bind', { token: B.accessToken, body: { inviteCode: code } });
  const rid = bind.json && bind.json.data && bind.json.data.relationshipId;
  ok('B 用邀请码绑定成功', bind.status === 200 && Boolean(rid),
    `HTTP ${bind.status} relationshipId=${rid}`);

  const roleB = await call('GET', '/api/couple/role', { token: B.accessToken });
  const rb = (roleB.json && roleB.json.data) || {};
  ok('B 的角色变为已绑定', rb.hasRelationship === true, `relationshipId=${rb.relationshipId} role=${rb.role}`);

  // 字段名以服务端实现为准：auth.js 的 /couple-info 返回 partnerName（不是 partnerUsername）
  const info = await call('GET', '/api/auth/couple-info', { token: A.accessToken });
  const d = (info.json && info.json.data) || {};
  ok('A 能查到伴侣', d.partnerName === B.username,
    `hasCouple=${d.hasCouple} partnerName=${d.partnerName} partnerId=${d.partnerId}`);

  console.log('\n=== 4. 邀请码一次性 ===');
  const reuse = await call('POST', '/api/couple/bind', { token: A.accessToken, body: { inviteCode: code } });
  ok('已消费的邀请码不能再用', reuse.status === 404 || reuse.status === 409,
    `HTTP ${reuse.status} ${reuse.json && reuse.json.error ? reuse.json.error.code : ''}`);

  console.log('\n=== 5. 无 token 仍是 401 ===');
  const anon = await call('POST', '/api/couple/bind', { body: { inviteCode: code } });
  ok('无 token → 401', anon.status === 401, `HTTP ${anon.status}`);

  console.log(`\n结果：${passed} 通过 / ${failures.length} 失败`);
  if (failures.length) failures.forEach((f) => console.log('  ❌ ' + f));
  console.log(`CLEANUP_IDS=${A.userId},${B.userId}   ← 记得按文件头注释清理这两个账号`);
  process.exit(failures.length ? 1 : 0);
})().catch((e) => {
  console.error('异常：', e.message);
  process.exit(1);
});
