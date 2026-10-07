const { test } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const path = require('node:path');
// Explicit opt-in to a socket-only disposable database. Never uses application .env or TCP.
test('v0.2 pagination and lost-response regression', { skip: !process.env.V02_TEST_SOCKET }, async t => {
  assert.equal(process.env.V02_TEST_SOCKET, '/tmp/couplecredit-v02-mysql.sock');
  process.env.DB_PASSWORD = 'unused-local-socket';
  process.env.INVITE_CODE = 'synthetic-test';
  process.env.JWT_SECRET = crypto.randomBytes(32).toString('hex');
  process.env.ENCRYPTION_KEY = crypto.randomBytes(32).toString('base64');
  process.env.DIARY_MEDIA_DIR = '/tmp/couplecredit-v02-test-media';
  const mysql = require('mysql2/promise'), express = require('express');
  const pool = mysql.createPool({ socketPath: process.env.V02_TEST_SOCKET, user: 'root', database: 'v02_test', connectionLimit: 8 });
  t.after(() => pool.end());
  const tag = crypto.randomBytes(6).toString('hex'), ids = [];
  for (const letter of ['a','b','c']) {
    const [r] = await pool.execute("INSERT INTO users (username,email,password,status) VALUES (?,?,?,'active')", [`v02${tag}${letter}`,`${tag}${letter}@example.test`,'not-a-login-password']);
    ids.push(r.insertId);
  }
  const [a,b,c] = ids;
  const [rel] = await pool.execute("INSERT INTO couple_relationships (user_id_1,user_id_2,status) VALUES (?,?,'active')",[a,b]);
  const relationshipId=rel.insertId;
  const { ensureCycleForBind } = require('../src/utils/houseworkLifecycle');
  const { withTransaction } = require('../src/utils/transactions');
  const bind = () => withTransaction(pool, conn => ensureCycleForBind(conn,{relationship_id:relationshipId,user_id_1:a,user_id_2:b},new Map([[a,'Synthetic A'],[b,'Synthetic B']])));
  const context=await bind();
  const config=require('../src/config').readConfig();
  const { signToken }=require('../src/utils/jwt');
  const tokens=new Map(ids.map(id=>[id,signToken({userId:id})]));
  const app=express();app.use(express.json());
  app.use((req,res,next)=>{ if(req.headers['x-test-drop-response']==='once') res.json=()=>res.destroy(); next(); });
  app.use('/api/diary-media',require('../src/routes/diaryMedia').createDiaryMediaRouter({pool,config}));
  app.use(require('../src/middleware/auth').createRequireAuth(pool));
  app.use('/api',require('../src/routes/diary').createDiaryRouter({pool,config}));
  app.use('/api',require('../src/routes/goals').createGoalsRouter({pool}));
  app.use('/api',require('../src/routes/relationships').createRelationshipsRouter({pool}));
  app.use('/api/couple',require('../src/routes/couple').createCoupleRouter({pool,config}));
  app.use((error,req,res,next)=>{if(!error.status) console.error('Unexpected route error:', error.code || error.message);require('../src/errors').sendError(res,error);});
  const server=app.listen(0,'127.0.0.1');await new Promise(resolve=>server.once('listening',resolve));t.after(()=>new Promise(resolve=>server.close(resolve)));
  const base='http://127.0.0.1:'+server.address().port;
  const key=()=>crypto.randomUUID();
  async function api(method,url,user,body,status=200){const r=await fetch(base+url,{method,headers:{'Content-Type':'application/json',...(user?{Authorization:'Bearer '+tokens.get(user)}:{})},body:body?JSON.stringify(body):undefined});const j=await r.json();assert.equal(r.status,status,method+' '+url+' '+(j.error?.code || 'status mismatch'));return j.data;}

  const posts=[];
  await t.test('45 rows with tied timestamps page exactly once at default 20 and max 20',async()=>{
    for(let i=0;i<45;i++) posts.push(await api('POST','/api/diary',i%2?a:b,{body:'synthetic '+i,occurredOn:'2026-09-15',relationshipVersion:context.cycleId,idempotencyKey:key()}));
    await pool.execute("UPDATE diary_posts SET created_at='2026-09-15 12:00:00.123' WHERE space_id=?",[context.spaceId]);
    let cursor='',seen=[],sizes=[];
    do { const page=await api('GET','/api/diary?month=2026-09&limit=100'+(cursor?'&cursor='+encodeURIComponent(cursor):''),a);sizes.push(page.items.length);seen.push(...page.items.map(x=>x.id));cursor=page.nextCursor;assert.equal(page.hasMore,!!cursor); } while(cursor);
    assert.deepEqual(sizes,[20,20,5]);assert.equal(new Set(seen).size,45);assert.deepEqual(seen,[...seen].sort().reverse());
    const defaults=await api('GET','/api/diary?month=2026-09',a);assert.equal(defaults.items.length,20);
    const empty=await api('GET','/api/diary?month=2026-08',a);assert.deepEqual(empty.items,[]);assert.equal(empty.hasMore,false);
  });
  let oldCursor;
  await t.test('cursor rejects tampering, another actor and another filter without leaking content',async()=>{
    const first=await api('GET','/api/diary?month=2026-09',a);oldCursor=first.nextCursor;
    await api('GET','/api/diary?month=2026-09&cursor='+encodeURIComponent(oldCursor),b,undefined,422);
    await api('GET','/api/diary?month=2026-08&cursor='+encodeURIComponent(oldCursor),a,undefined,422);
    await api('GET','/api/diary?month=2026-09&authorScope=mine&cursor='+encodeURIComponent(oldCursor),a,undefined,422);
    await api('GET','/api/diary?month=2026-09&cursor='+encodeURIComponent('x'+oldCursor),a,undefined,422);
    const outsider=await api('GET','/api/diary?month=2026-09&cursor='+encodeURIComponent(oldCursor),c);assert.equal(outsider.items.length,0);
  });
  await t.test('date movement, deletion and insertion expire snapshots explicitly; refresh restarts safely',async()=>{
    const own=posts[1];await api('PATCH',`/api/diary/${own.id}`,a,{occurredOn:'2026-09-01',expectedVersion:own.version});
    await api('GET','/api/diary?month=2026-09&cursor='+encodeURIComponent(oldCursor),a,undefined,409);
    const fresh=await api('GET','/api/diary?month=2026-09',a);assert.equal(fresh.items.length,20);
    await api('DELETE',`/api/diary/${posts[3].id}`,a,{expectedVersion:posts[3].version,idempotencyKey:key()});
    await api('GET','/api/diary?month=2026-09&cursor='+encodeURIComponent(fresh.nextCursor),a,undefined,409);
    const again=await api('GET','/api/diary?month=2026-09',a);
    await api('POST','/api/diary',a,{body:'new between pages',occurredOn:'2026-09-14',relationshipVersion:context.cycleId,idempotencyKey:key()});
    await api('GET','/api/diary?month=2026-09&cursor='+encodeURIComponent(again.nextCursor),a,undefined,409);
  });
  await t.test('committed publish with lost HTTP response retries same key once without duplicate',async()=>{
    const payload={body:'synthetic unknown-result',occurredOn:'2026-09-01',relationshipVersion:context.cycleId,idempotencyKey:key()};
    await assert.rejects(fetch(base+'/api/diary',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+tokens.get(a),'x-test-drop-response':'once'},body:JSON.stringify(payload)}));
    const replay=await api('POST','/api/diary',a,payload);const again=await api('POST','/api/diary',a,payload);assert.equal(again.id,replay.id);
    const [[count]]=await pool.execute("SELECT COUNT(*) n FROM diary_posts WHERE space_id=? AND body=?",[context.spaceId,payload.body]);assert.equal(count.n,1);
  });

  await t.test('45 replies bounded to 20 per page; deleting root keeps placeholder and surviving replies',async()=>{
    const post=posts[5];const root=await api('POST',`/api/diary/${post.id}/comments`,a,{body:'root',idempotencyKey:key()});
    for(let i=0;i<45;i++)await api('POST',`/api/diary/${post.id}/comments`,b,{body:'reply '+i,rootCommentId:root.id,idempotencyKey:key()});
    const preview=await api('GET',`/api/diary/${post.id}/comments`,a);assert.equal(preview.items[0].replies.length,3);assert.equal(preview.total,46);
    let cursor='',seen=[],sizes=[];
    do {const page=await api('GET',`/api/diary/${post.id}/comments?rootCommentId=${root.id}&limit=100`+(cursor?'&cursor='+encodeURIComponent(cursor):''),a);sizes.push(page.items.length);seen.push(...page.items.map(x=>x.id));cursor=page.nextCursor;}while(cursor);
    assert.deepEqual(sizes,[20,20,5]);assert.equal(new Set(seen).size,45);
    await api('DELETE',`/api/diary-comments/${root.id}`,a,{expectedVersion:root.version,idempotencyKey:key()});
    const deleted=await api('GET',`/api/diary/${post.id}/comments`,a);assert.equal(deleted.items.length,1);assert.equal(deleted.items[0].body,'');assert.equal(deleted.total,45);assert.equal(deleted.items[0].replyCount,45);
    await api('DELETE',`/api/diary-comments/${seen[0]}`,b,{expectedVersion:1,idempotencyKey:key()});
    const rest=await api('GET',`/api/diary/${post.id}/comments?rootCommentId=${root.id}`,a);assert.equal(rest.items.length,20);assert.equal(rest.total,44);assert.equal(rest.items.some(x=>x.id===seen[0]),false);
  });
  await t.test('ten-thousand-row synthetic timeline remains bounded and reports local query cost',async()=>{
    for(let batch=0;batch<10;batch++){
      const values=Array.from({length:1000},()=>[key(),context.spaceId,relationshipId,a,'synthetic scale row','2026-09-01']);
      await pool.query('INSERT INTO diary_posts(diary_id,space_id,relationship_id,author_id,body,occurred_on) VALUES ?', [values]);
    }
    const started=performance.now();const first=await api('GET','/api/diary?month=2026-09',a);const second=await api('GET','/api/diary?month=2026-09&cursor='+encodeURIComponent(first.nextCursor),a);
    assert.equal(first.items.length,20);assert.equal(second.items.length,20);assert.equal(new Set([...first.items,...second.items].map(x=>x.id)).size,40);
    console.log(JSON.stringify({syntheticTimelineRows:10045,twoPageElapsedMs:Math.round(performance.now()-started),pageSizes:[20,20]}));
  });
  await t.test('cursor from prior same-pair binding is rejected in the new space',async()=>{
    await api('DELETE','/api/couple/unbind',a,{});await pool.execute("UPDATE couple_relationships SET status='active' WHERE relationship_id=?",[relationshipId]);await bind();
    await api('GET','/api/diary?month=2026-09&cursor='+encodeURIComponent(oldCursor),a,undefined,422);
    const fresh=await api('GET','/api/diary?month=2026-09',a);assert.equal(fresh.items.length,0);
  });
});
