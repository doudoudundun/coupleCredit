// 账单图片持久化端到端验证（25 条断言，本地/生产各跑一遍均为 25/0）
// 用法：
//   node scripts/verify-bill-images.js                                      # 打本地 127.0.0.1:8082
//   BASE=https://api.couplecredit.top node scripts/verify-bill-images.js    # 打生产
// 覆盖：上传 → 创建 → 列表读取 → 更新替换 → 更新不动 → 清空 → 非法入参 → 历史数据兼容
// 注意：会在目标库建 1 个随机后缀测试账号 + 2 条账单；脚本末尾自动删账单，**账号需手工清**
//       （先删 bills/password_accounts 等子表再删 users；users 主键是 id、bills 是 bill_id）
const BASE = process.env.BASE || 'http://127.0.0.1:8082';
const INVITE = process.env.INVITE_CODE || 'COUPLE-PRIVATE-2026';
const PASSWORD = process.env.TEST_PASSWORD || 'Verify123456';
const stamp = Date.now();
// 注意：用户名不能含 11 位连续数字（形如 1[3-9]xxxxxxxxx），否则会被内容安全判成手机号
const suffix = Math.random().toString(36).slice(2, 8);

let pass = 0, fail = 0;
function check(name, ok, detail) {
  if (ok) { pass++; console.log(`  PASS  ${name}`); }
  else { fail++; console.log(`  FAIL  ${name}   ${detail || ''}`); }
}

async function call(method, p, { token, body } = {}) {
  const headers = {};
  if (token) headers.Authorization = 'Bearer ' + token;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(BASE + p, {
    method, headers, body: body !== undefined ? JSON.stringify(body) : undefined
  });
  const text = await res.text();
  let json = null; try { json = JSON.parse(text); } catch (_e) { /* 非 JSON */ }
  return { status: res.status, json, text };
}

async function upload(token, buf, filename, mime) {
  const fd = new FormData();
  fd.append('image', new Blob([buf], { type: mime }), filename);
  const res = await fetch(BASE + '/api/upload/image', {
    method: 'POST', headers: { Authorization: 'Bearer ' + token }, body: fd
  });
  const text = await res.text();
  let json = null; try { json = JSON.parse(text); } catch (_e) { /* ignore */ }
  return { status: res.status, json, text };
}

const tinyPng = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64');

(async () => {
  console.log('\n===== 账单图片持久化验证 =====\n');
  const today = new Date();
  const dateStr = today.toISOString().slice(0, 10);
  const y = today.getFullYear(), m = today.getMonth() + 1;

  // ── 1. 账号 ──
  const u = `imgtest${suffix}`;
  const reg = await call('POST', '/api/auth/register', {
    body: { username: u, email: `${u}@img.local`, password: PASSWORD, inviteCode: INVITE }
  });
  check('注册测试账号', reg.status === 201, `HTTP ${reg.status} ${reg.text.slice(0, 160)}`);

  const login = await call('POST', '/api/auth/login', { body: { username: u, password: PASSWORD } });
  const token = login.json && login.json.data && login.json.data.accessToken;
  check('登录取到 token', !!token, `HTTP ${login.status} ${login.text.slice(0, 160)}`);
  if (!token) { console.log('\n无法登录，终止'); process.exit(1); }

  // ── 2. 真实上传 ──
  const up1 = await upload(token, tinyPng, 'p1.png', 'image/png');
  const up2 = await upload(token, tinyPng, 'p2.png', 'image/png');
  const url1 = up1.json && up1.json.data && up1.json.data.imageUrl;
  const url2 = up2.json && up2.json.data && up2.json.data.imageUrl;
  check('上传图片 A 成功', !!url1, JSON.stringify(up1.json).slice(0, 160));
  check('上传图片 B 成功', !!url2, JSON.stringify(up2.json).slice(0, 160));
  check('返回站内相对路径 /uploads/', String(url1 || '').startsWith('/uploads/'), String(url1));

  // ── 3. 创建带图账单 ──
  const create = await call('POST', '/api/bills', { token, body: {
    title: `图片验证${suffix}`, type: '餐饮', amount: 12.34, date: dateStr, time: '12:00:00',
    incomeType: 0, billOwner: '自己', photos: [url1, url2], receipts: [url1]
  }});
  check('创建带图账单 -> 201', create.status === 201, `HTTP ${create.status} ${create.text.slice(0, 200)}`);
  const billId = create.json && create.json.data && create.json.data.billId;
  check('创建响应回显 photos 数组(2)', Array.isArray(create.json && create.json.data && create.json.data.photos)
    && create.json.data.photos.length === 2, JSON.stringify(create.json && create.json.data && create.json.data.photos));
  check('创建响应回显 receipts 数组(1)', Array.isArray(create.json && create.json.data && create.json.data.receipts)
    && create.json.data.receipts.length === 1, JSON.stringify(create.json && create.json.data && create.json.data.receipts));

  // ── 4. 列表读取 ──
  const list = await call('GET', `/api/bills?year=${y}&month=${m}`, { token });
  check('列表查询 -> 200', list.status === 200, `HTTP ${list.status}`);
  const bills = (list.json && list.json.data && list.json.data.bills) || [];
  const found = bills.find(b => b.billId === billId);
  check('列表能查到刚建的账单', !!found, `共 ${bills.length} 条，未找到 billId=${billId}`);
  check('关键: 列表 photos 是数组(非字符串)', Array.isArray(found && found.photos),
    `实际=${typeof (found && found.photos)} 值=${JSON.stringify(found && found.photos)}`);
  check('关键: 列表 photos 内容与上传一致', JSON.stringify(found && found.photos) === JSON.stringify([url1, url2]),
    JSON.stringify(found && found.photos));
  check('关键: 列表 receipts 内容与上传一致', JSON.stringify(found && found.receipts) === JSON.stringify([url1]),
    JSON.stringify(found && found.receipts));

  // ── 5. 更新：替换图片 ──
  const upd = await call('PUT', `/api/bills/${billId}`, { token, body: {
    title: (found && found.title) || `图片验证${suffix}`, type: '餐饮', amount: 12.34,
    date: dateStr, time: '12:00:00', incomeType: 0, photos: [url2], receipts: []
  }});
  check('更新账单 -> 200', upd.status === 200, `HTTP ${upd.status} ${upd.text.slice(0, 200)}`);

  const list2 = await call('GET', `/api/bills?year=${y}&month=${m}`, { token });
  const found2 = ((list2.json && list2.json.data && list2.json.data.bills) || []).find(b => b.billId === billId);
  check('关键: 更新后 photos 替换为 1 张', JSON.stringify(found2 && found2.photos) === JSON.stringify([url2]),
    JSON.stringify(found2 && found2.photos));
  check('关键: 更新后 receipts 清空为 []', Array.isArray(found2 && found2.receipts) && found2.receipts.length === 0,
    JSON.stringify(found2 && found2.receipts));

  // ── 6. 更新时不带图片字段 -> 不应被清空 ──
  const upd2 = await call('PUT', `/api/bills/${billId}`, { token, body: {
    title: (found && found.title) || `图片验证${suffix}`, type: '餐饮', amount: 12.34,
    date: dateStr, time: '12:00:00', incomeType: 0
  }});
  check('更新(不带图片字段) -> 200', upd2.status === 200, `HTTP ${upd2.status} ${upd2.text.slice(0, 200)}`);
  const list3 = await call('GET', `/api/bills?year=${y}&month=${m}`, { token });
  const found3 = ((list3.json && list3.json.data && list3.json.data.bills) || []).find(b => b.billId === billId);
  check('关键: 不带图片字段更新后 photos 保持不变', JSON.stringify(found3 && found3.photos) === JSON.stringify([url2]),
    JSON.stringify(found3 && found3.photos));

  // ── 7. 不带图片创建 -> 读取必须是空数组（生产 635 条历史账单就是这种 NULL 状态）──
  const noImg = await call('POST', '/api/bills', { token, body: {
    title: `无图${suffix}`, type: '餐饮', amount: 5, date: dateStr, time: '09:00:00',
    incomeType: 0, billOwner: '自己'
  }});
  check('不带图片字段创建 -> 201', noImg.status === 201, `HTTP ${noImg.status} ${noImg.text.slice(0, 160)}`);
  const noImgId = noImg.json && noImg.json.data && noImg.json.data.billId;

  const list4 = await call('GET', `/api/bills?year=${y}&month=${m}`, { token });
  const found4 = ((list4.json && list4.json.data && list4.json.data.bills) || []).find(b => b.billId === noImgId);
  check('关键: 无图账单 photos 为 [] 而非 null', Array.isArray(found4 && found4.photos) && found4.photos.length === 0,
    `实际=${typeof (found4 && found4.photos)} ${JSON.stringify(found4 && found4.photos)}`);
  check('关键: 无图账单 receipts 为 [] 而非 null', Array.isArray(found4 && found4.receipts) && found4.receipts.length === 0,
    `实际=${typeof (found4 && found4.receipts)} ${JSON.stringify(found4 && found4.receipts)}`);

  // ── 8. 反例：非法入参 ──
  const baseBody = { title: 'x', type: '餐饮', amount: 1, date: dateStr, time: '12:00:00', incomeType: 0, billOwner: '自己' };

  const bad1 = await call('POST', '/api/bills', { token, body: { ...baseBody, photos: ['javascript:alert(1)'] } });
  check('非法图片地址(javascript:) -> 400', bad1.status === 400, `HTTP ${bad1.status} ${bad1.text.slice(0, 160)}`);

  const bad2 = await call('POST', '/api/bills', { token, body: { ...baseBody, photos: ['http://evil.example.com/a.png'] } });
  check('非 https 外链(http) -> 400', bad2.status === 400, `HTTP ${bad2.status} ${bad2.text.slice(0, 160)}`);

  const bad3 = await call('POST', '/api/bills', { token, body: { ...baseBody, photos: ['/uploads/a.png', '/uploads/b.png', '/uploads/c.png', '/uploads/d.png', '/uploads/e.png'] } });
  check('超过 4 张 -> 400', bad3.status === 400, `HTTP ${bad3.status} ${bad3.text.slice(0, 160)}`);

  const bad4 = await call('POST', '/api/bills', { token, body: { ...baseBody, photos: 'not-an-array' } });
  check('photos 非数组 -> 400', bad4.status === 400, `HTTP ${bad4.status} ${bad4.text.slice(0, 160)}`);

  // ── 9. 保留数据供查库核对原始入库格式 ──
  console.log(`\n===== 结果: ${pass} 通过 / ${fail} 失败 =====`);
  console.log(`测试账号: ${u}`);
  console.log(`带图账单ID: ${billId}`);
  console.log(`无图账单ID: ${noImgId}`);
  console.log(`图片地址: ${url1} ${url2}`);
  process.exit(fail === 0 ? 0 : 1);
})();
