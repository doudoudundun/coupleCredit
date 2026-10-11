const { test } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
// v0.4 小记互动：贴纸回应 / 评论配图 / 已读回执。
// 与 v02 套件同一套一次性 socket 数据库（V02_TEST_SOCKET），不触碰应用 .env 与 TCP。
test('v0.4 reactions, comment media and read receipts', { skip: !process.env.V02_TEST_SOCKET }, async t => {
  assert.equal(process.env.V02_TEST_SOCKET, '/tmp/couplecredit-v02-mysql.sock');
  process.env.DB_PASSWORD = 'unused-local-socket';
  process.env.INVITE_CODE = 'synthetic-test';
  process.env.JWT_SECRET = crypto.randomBytes(32).toString('hex');
  process.env.ENCRYPTION_KEY = crypto.randomBytes(32).toString('base64');
  process.env.DIARY_MEDIA_DIR = '/tmp/couplecredit-v04-test-media';
  const mysql = require('mysql2/promise'), express = require('express');
  const pool = mysql.createPool({ socketPath: process.env.V02_TEST_SOCKET, user: 'root', database: 'v02_test', connectionLimit: 8 });
  t.after(() => pool.end());
  const tag = crypto.randomBytes(6).toString('hex'), ids = [];
  for (const letter of ['a','b','c']) {
    const [r] = await pool.execute("INSERT INTO users (username,email,password,status) VALUES (?,?,?,'active')", [`v04${tag}${letter}`,`${tag}${letter}@example.test`,'not-a-login-password']);
    ids.push(r.insertId);
  }
  const [a,b,c] = ids;
  const [rel] = await pool.execute("INSERT INTO couple_relationships (user_id_1,user_id_2,status) VALUES (?,?,'active')",[a,b]);
  const { ensureCycleForBind } = require('../src/utils/houseworkLifecycle');
  const { withTransaction } = require('../src/utils/transactions');
  const context = await withTransaction(pool, conn => ensureCycleForBind(conn,{relationship_id:rel.insertId,user_id_1:a,user_id_2:b},new Map([[a,'Synthetic A'],[b,'Synthetic B']])));
  const config = require('../src/config').readConfig();
  const { signToken } = require('../src/utils/jwt');
  const tokens = new Map(ids.map(id=>[id,signToken({userId:id})]));
  const app = express(); app.use(express.json());
  app.use('/api/diary-media', require('../src/routes/diaryMedia').createDiaryMediaRouter({pool,config}));
  app.use(require('../src/middleware/auth').createRequireAuth(pool));
  app.use('/api', require('../src/routes/diary').createDiaryRouter({pool,config}));
  app.use((error,req,res,next)=>{ if(!error.status) console.error('Unexpected route error:', error.code || error.message); require('../src/errors').sendError(res,error); });
  const server = app.listen(0,'127.0.0.1'); await new Promise(resolve=>server.once('listening',resolve)); t.after(()=>new Promise(resolve=>server.close(resolve)));
  const base = 'http://127.0.0.1:'+server.address().port;
  const key = ()=>crypto.randomUUID();
  async function api(method,url,user,body,status=200){const r=await fetch(base+url,{method,headers:{'Content-Type':'application/json',...(user?{Authorization:'Bearer '+tokens.get(user)}:{})},body:body?JSON.stringify(body):undefined});const j=await r.json();assert.equal(r.status,status,method+' '+url+' '+(j.error?.code || 'status mismatch'));return j.data;}

  let reactionPost;

  await t.test('sticker reaction: whitelist, self-block, event lifecycle, list projection', async () => {
    const post = await api('POST','/api/diary',a,{body:'v04 reaction target',occurredOn:'2026-10-01',relationshipVersion:context.cycleId,idempotencyKey:key()});
    await api('PUT',`/api/diary/${post.id}/reaction`,b,{emoji:'👽'},422);                       // 白名单外
    await api('PUT',`/api/diary/${post.id}/reaction`,a,{emoji:'❤️'},403);                       // 不能给自己贴
    await api('PUT',`/api/diary/${key()}/reaction`,b,{emoji:'❤️'},404);                         // 帖子不存在不枚举
    await api('PUT',`/api/diary/${post.id}/reaction`,c,{emoji:'❤️'},404);                       // 非空间成员不枚举

    await api('PUT',`/api/diary/${post.id}/reaction`,b,{emoji:'❤️'});
    let inbox = await api('GET','/api/spaces/current/diary-updates',a);
    assert.equal(inbox.hasUnread, true);
    assert.equal(inbox.items.filter(i=>i.diaryId===post.id)[0].eventType,'reaction');

    // 作者看过后（seen），对方换贴应把事件重新置为 unread
    await api('PATCH','/api/spaces/current/diary-updates/seen',a,{eventIds:inbox.items.map(i=>i.eventId)});
    await api('PUT',`/api/diary/${post.id}/reaction`,b,{emoji:'😂'});
    inbox = await api('GET','/api/spaces/current/diary-updates',a);
    assert.equal(inbox.items.filter(i=>i.diaryId===post.id)[0].eventType,'reaction');

    // 列表投影：作者看 partner=😂，贴的人看 mine=😂
    const listA = await api('GET','/api/diary',a);
    const itemA = listA.items.find(i=>i.id===post.id);
    assert.equal(itemA.reaction.partner,'😂'); assert.equal(itemA.reaction.mine,null);
    const listB = await api('GET','/api/diary',b);
    const itemB = listB.items.find(i=>i.id===post.id);
    assert.equal(itemB.reaction.mine,'😂'); assert.equal(itemB.reaction.partner,null);
    assert.equal(itemA.partnerSeenAt,null);                                    // 伴侣还没打开过

    // 取消贴纸：事件撤销 + 列表不再展示
    await api('DELETE',`/api/diary/${post.id}/reaction`,b);
    inbox = await api('GET','/api/spaces/current/diary-updates',a);
    assert.equal(inbox.items.filter(i=>i.diaryId===post.id).length,0);
    const after = (await api('GET','/api/diary',a)).items.find(i=>i.id===post.id);
    assert.equal(after.reaction.partner,null);
    reactionPost = post;
  });

  await t.test('read receipt: partner first open stamps once; repeated opens keep first time', async () => {
    const post = reactionPost;
    const asPartner = await api('GET',`/api/diary/${post.id}`,b);              // 伴侣打开 → 落 seen
    assert.equal(asPartner.partnerSeenAt,null);                                // 非作者不暴露该字段
    const first = await api('GET',`/api/diary/${post.id}`,a);
    assert.ok(first.partnerSeenAt);                                            // 作者侧可见
    const [[row1]] = await pool.execute('SELECT partner_seen_at FROM diary_posts WHERE diary_id=?',[post.id]);
    await api('GET',`/api/diary/${post.id}`,b);                                // 再次打开
    const [[row2]] = await pool.execute('SELECT partner_seen_at FROM diary_posts WHERE diary_id=?',[post.id]);
    assert.equal(row1.partner_seen_at.getTime(), row2.partner_seen_at.getTime()); // 只记第一次
    await api('GET',`/api/diary/${post.id}`,a);                                // 作者自己打开也不覆盖
    const [[row3]] = await pool.execute('SELECT partner_seen_at FROM diary_posts WHERE diary_id=?',[post.id]);
    assert.equal(row1.partner_seen_at.getTime(), row3.partner_seen_at.getTime());
  });

  await t.test('comment media: bind once, private, revoked on delete, reusable after unbind', async () => {
    const post = reactionPost;
    const sharp = require('sharp');
    const fixture = await sharp({create:{width:64,height:32,channels:3,background:'#8e9ddb'}}).jpeg().toBuffer();
    const upload = await api('POST','/api/diary-media/uploads',b,{purpose:'comment',mime:'image/jpeg',size:fixture.length,relationshipVersion:context.cycleId},201);
    const fd = new FormData(); fd.append('file',new Blob([fixture],{type:'image/jpeg'}),'synthetic.jpg');
    const raw = await fetch(base+upload.uploadUrl,{method:'POST',headers:{Authorization:'Bearer '+tokens.get(b)},body:fd});
    assert.equal(raw.status,200);
    const done = await api('POST','/api/diary-media/'+upload.mediaId+'/complete',b,{});

    // purpose=comment 不能被拿去发帖（assertMediaBindable 只认 diary）
    await api('POST','/api/diary',b,{body:'steal',mediaIds:[done.mediaId],occurredOn:'2026-10-01',relationshipVersion:context.cycleId,idempotencyKey:key()},409);
    // 两条图 → 超过单条上限
    const upload2 = await api('POST','/api/diary-media/uploads',b,{purpose:'comment',mime:'image/jpeg',size:fixture.length,relationshipVersion:context.cycleId},201);
    await api('POST',`/api/diary/${post.id}/comments`,b,{body:'two images',mediaIds:[done.mediaId,upload2.mediaId],idempotencyKey:key()},422);

    const comment = await api('POST',`/api/diary/${post.id}/comments`,b,{body:'look at this',mediaIds:[done.mediaId],idempotencyKey:key()});
    assert.equal(comment.media.length,1);
    assert.ok(comment.media[0].url.includes('/api/diary-media/'));

    // 列表接口带出配图与预览标记（URL 以查看者 a 为受众签名——POST 回包的 URL 只对上传者有效）
    const comments = await api('GET',`/api/diary/${post.id}/comments`,a);
    assert.equal(comments.items[0].media.length,1);
    const urlForA = comments.items[0].media[0].thumbnailUrl;
    const listed = (await api('GET','/api/diary',a)).items.find(i=>i.id===post.id);
    assert.equal(listed.lastComment.hasImage,true);
    assert.ok(listed.lastComment.preview.length>0);
    assert.equal(listed.lastComment.nickname,`v04${tag}b`);                    // 未设昵称回退用户名

    // 配图私密：非成员 c 拿不到内容；成员 a 可以
    assert.equal((await fetch(base+urlForA,{headers:{Authorization:'Bearer '+tokens.get(c)}})).status,404);
    assert.equal((await fetch(base+urlForA,{headers:{Authorization:'Bearer '+tokens.get(a)}})).status,200);

    // 删除评论：配图解绑 + 撤签（旧 URL 立即失效）
    await api('DELETE','/api/diary-comments/'+comment.id,b,{expectedVersion:1,idempotencyKey:key()});
    assert.equal((await fetch(base+urlForA,{headers:{Authorization:'Bearer '+tokens.get(a)}})).status,404);
    const [[bound]] = await pool.execute('SELECT comment_id FROM diary_media WHERE media_id=?',[done.mediaId]);
    assert.equal(bound.comment_id,null);

    // 解绑后的媒体可再次绑定到新评论（同 owner 复用）
    const again = await api('POST',`/api/diary/${post.id}/comments`,b,{body:'reuse image',mediaIds:[done.mediaId],idempotencyKey:key()});
    assert.equal(again.media.length,1);
    await api('DELETE','/api/diary-comments/'+again.id,b,{expectedVersion:1,idempotencyKey:key()});
  });
});
