const { test } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const path = require('node:path');
// Explicit opt-in to a socket-only disposable database. Never uses application .env or TCP.
test('v0.2 isolated MySQL + HTTP regression', { skip: !process.env.V02_TEST_SOCKET }, async t => {
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
  let goal,post,comment,media,themeUrl;
  await t.test('atomic receipt failure rolls back business; same key concurrent retry executes once',async()=>{
    const {withIdempotency}=require('../src/utils/idempotency');
    const options={userId:a,scope:'v02.test',key:key(),payload:{fixed:true}};
    const faulty={getConnection:async()=>{const conn=await pool.getConnection();return new Proxy(conn,{get(target,prop){if(prop==='execute')return async(sql,args)=>{if(sql.includes("SET status = 'completed'"))throw Error('receipt fault');return target.execute(sql,args);};return typeof target[prop]==='function'?target[prop].bind(target):target[prop];}});}};
    const execute=async conn=>{const [r]=await conn.execute("INSERT INTO goal_invitations (invitation_id,goal_id,inviter_id,token_hash,expires_at) VALUES (?,?,?,?,NOW())",[key(),context.spaceId,a,key().replace(/-/g,'')]);return {saved:true};};
    await assert.rejects(withIdempotency(faulty,options,execute),/receipt fault/);
    const [[before]]=await pool.execute('SELECT COUNT(*) n FROM goal_invitations WHERE inviter_id=?',[a]);assert.equal(before.n,0);
    const results=await Promise.all([withIdempotency(pool,options,execute),withIdempotency(pool,options,execute)]);assert.equal(results.filter(r=>r.replayed).length,1);
    const [[after]]=await pool.execute('SELECT COUNT(*) n FROM goal_invitations WHERE inviter_id=?',[a]);assert.equal(after.n,1);
    await assert.rejects(withIdempotency(pool,{...options,payload:{fixed:false}},execute),e=>e.code==='IDEMPOTENCY_CONFLICT');
    const nullOptions={...options,key:key()};
    await withIdempotency(pool,nullOptions,async()=>null);
    assert.equal((await withIdempotency(pool,nullOptions,()=>{throw Error('must replay null');})).result,null);
    const orphan={...options,key:key()};const hash=crypto.createHash('sha256').update(JSON.stringify(orphan.payload)).digest('hex');await pool.execute("INSERT INTO idempotency_records (user_id,scope,idem_key,request_hash,status,created_at) VALUES (?,?,?,?,'processing',DATE_SUB(NOW(),INTERVAL 5 MINUTE))",[a,orphan.scope,orphan.key,hash]);
    await assert.rejects(withIdempotency(pool,orphan,()=>{throw Error('must not execute');}),e=>e.code==='IDEMPOTENCY_IN_PROGRESS');
  });
  await t.test('amount bounds, 100 - 80 reversal rejection and valid ledger/cache equality',async()=>{
    goal=await api('POST','/api/goals',a,{title:'Synthetic goal',targetAmountFen:10000,idempotencyKey:key()});
    const e=await api('POST',`/api/goals/${goal.id}/entries`,a,{type:'increase',amountFen:10000,idempotencyKey:key()});
    const d=await api('POST',`/api/goals/${goal.id}/entries`,b,{type:'decrease',amountFen:8000,idempotencyKey:key()});
    await api('POST',`/api/entries/${e.entry.id}/reverse`,a,{expectedVersion:d.version,idempotencyKey:key()},422);
    for(const amountFen of [100000000,Number.MAX_SAFE_INTEGER+1,'9007199254740993'])await api('POST',`/api/goals/${goal.id}/entries`,a,{type:'increase',amountFen,idempotencyKey:key()},422);
    const [[row]]=await pool.execute("SELECT current_amount_fen,(SELECT SUM(IF(type='increase',amount_fen,-amount_fen)) FROM couple_goal_entries WHERE goal_id=? AND status='valid') total FROM couple_goals WHERE goal_id=?",[goal.id,goal.id]);assert.equal(Number(row.current_amount_fen),2000);assert.equal(Number(row.total),2000);
  });
  await t.test('concurrent amount writes, completed history and reopen enforce lifecycle',async()=>{
    await Promise.all([a,b].map(u=>api('POST',`/api/goals/${goal.id}/entries`,u,{type:'increase',amountFen:1000,idempotencyKey:key()})));
    const attempts=await Promise.all([a,b].map(u=>fetch(base+`/api/goals/${goal.id}/entries`,{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+tokens.get(u)},body:JSON.stringify({type:'decrease',amountFen:3000,idempotencyKey:key()})})));
    assert.deepEqual(attempts.map(r=>r.status).sort(),[200,422]);
    const [[balance]]=await pool.execute('SELECT current_amount_fen FROM couple_goals WHERE goal_id=?',[goal.id]);assert.equal(Number(balance.current_amount_fen),1000);
    const full=await api('POST',`/api/goals/${goal.id}/entries`,a,{type:'increase',amountFen:9000,idempotencyKey:key()});
    const done=await api('POST',`/api/goals/${goal.id}/transitions`,a,{action:'complete',expectedVersion:full.version});
    await api('POST',`/api/goals/${goal.id}/entries`,a,{type:'increase',amountFen:1,idempotencyKey:key()},409);
    const history=await api('GET','/api/goals',a);assert.ok(history.items.some(g=>g.id===goal.id&&g.status==='completed'));
    const reopened=await api('POST',`/api/goals/${goal.id}/transitions`,b,{action:'reopen',expectedVersion:done.goal.version});assert.equal(reopened.goal.status,'active');
  });
  await t.test('image type/pixel preflight, upload, metadata stripping, publish and three-account ACL',async()=>{
    const sharp=require('sharp');
    async function upload(purpose,buffer,mime='image/png',expected=200){const session=await api('POST','/api/diary-media/uploads',a,{purpose,mime,size:buffer.length,relationshipVersion:context.cycleId},201);const fd=new FormData();fd.append('file',new Blob([buffer],{type:mime}),'synthetic.png');const raw=await fetch(base+session.uploadUrl,{method:'POST',headers:{Authorization:'Bearer '+tokens.get(a)},body:fd});assert.equal(raw.status,expected,await raw.text());if(expected!==200)return;return api('POST',`/api/diary-media/${session.mediaId}/complete`,a,{});}
    await upload('diary',Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"/>'),'image/png',422);
    const oversized=await sharp({create:{width:7000,height:6000,channels:3,background:'#fff'}}).png().toBuffer();
    await upload('diary',oversized,'image/png',422);
    const img=await sharp({create:{width:64,height:32,channels:3,background:'#ffbbcc'}}).jpeg().withMetadata({orientation:6}).toBuffer();
    media=await upload('diary',img,'image/jpeg');
    post=await api('POST','/api/diary',a,{body:'synthetic diary',occurredOn:'2026-10-01',mediaIds:[media.mediaId],relationshipVersion:context.cycleId,idempotencyKey:key()});
    await api('GET',`/api/diary/${post.id}`,b);await api('GET',`/api/diary/${post.id}`,c,undefined,404);
    assert.equal((await fetch(base+post.media[0].url)).status,401);
    const content=await fetch(base+post.media[0].url,{headers:{Authorization:"Bearer "+tokens.get(a)}});assert.equal(content.status,200);const meta=await sharp(Buffer.from(await content.arrayBuffer())).metadata();assert.equal(meta.exif,undefined);assert.equal(meta.orientation,undefined);assert.equal(meta.width,32);
    assert.equal((await fetch(base+post.media[0].url,{headers:{Authorization:'Bearer '+tokens.get(c)}})).status,404);
    const bg=await upload('background',img,'image/jpeg');
    const theme=await api('PATCH','/api/spaces/current/diary-theme',a,{mediaId:bg.mediaId,expectedVersion:0,relationshipVersion:context.cycleId});themeUrl=theme.mediaUrl;
    await api('PATCH','/api/spaces/current/diary-theme',b,{templateId:'sunset',expectedVersion:0,relationshipVersion:context.cycleId},409);
  });
  await t.test('comment parent deletion blocks edits, media blocked for author and partner, restore works',async()=>{
    comment=await api('POST',`/api/diary/${post.id}/comments`,b,{body:'synthetic comment',idempotencyKey:key()});
    const commentId=comment.commentId || comment.id || comment.comment?.id;
    await api('DELETE',`/api/diary/${post.id}`,a,{expectedVersion:post.version,idempotencyKey:key()});
    await api('PATCH',`/api/diary-comments/${commentId}`,b,{body:'changed',expectedVersion:1},404);
    for(const u of [a,b,c])assert.equal((await fetch(base+`/api/diary-media/${media.mediaId}/content`,{headers:{Authorization:'Bearer '+tokens.get(u)}})).status,404);
    assert.equal((await fetch(base+post.media[0].url,{headers:{Authorization:"Bearer "+tokens.get(a)}})).status,404);
    const trash=await api('GET','/api/me/diary-trash',a);assert.ok(trash.items.some(x=>x.id===post.id&&x.restorable));
    await api('POST',`/api/diary/${post.id}/restore`,a,{idempotencyKey:key()});
  });
  await t.test('5000-character boundary, clear mood, bounded reply preview and full reply pagination',async()=>{
    const draft={body:'文'.repeat(5000),occurredOn:'2026-10-01',moodCode:'happy',moodText:'synthetic',relationshipVersion:context.cycleId,idempotencyKey:key()};
    const boundary=await api('POST','/api/diary',a,draft);
    await api('POST','/api/diary',a,{...draft,body:'文'.repeat(5001),idempotencyKey:key()},422);
    await api('POST','/api/diary',a,{...draft,mediaIds:Array.from({length:10},()=>key()),idempotencyKey:key()},422);
    await api('PATCH',`/api/diary/${boundary.id}`,a,{moodCode:null,moodText:null,expectedVersion:boundary.version});
    const clear=await api('GET',`/api/diary/${boundary.id}`,a);assert.equal(clear.moodCode,null);assert.equal(clear.moodText,null);
    const root=await api('POST',`/api/diary/${boundary.id}/comments`,a,{body:'root',idempotencyKey:key()});
    const rootId=root.commentId || root.id || root.comment?.id;
    for(let i=0;i<10;i++)await api('POST',`/api/diary/${boundary.id}/comments`,b,{body:'reply '+i,rootCommentId:rootId,idempotencyKey:key()});
    const roots=await api('GET',`/api/diary/${boundary.id}/comments`,a);assert.equal(roots.items[0].replies.length,3);assert.equal(roots.items[0].replyCount,10);
    const replies=await api('GET',`/api/diary/${boundary.id}/comments?rootCommentId=${rootId}&limit=100`,a);assert.equal(replies.items.length,10);
  });
  await t.test('orphan ready media dry-run preserves data; explicit isolated cleanup revokes before deleting',async()=>{
    const {cleanup}=require('../scripts/dev/diary-media-cleanup');
    const orphan=key();await pool.execute("INSERT INTO diary_media (media_id,owner_id,space_id,purpose,status,mime,created_at) VALUES (?,?,?,'diary','ready','image/png',DATE_SUB(NOW(),INTERVAL 2 DAY))",[orphan,a,context.spaceId]);
    const dry=await cleanup(pool,{retentionHours:24});assert.ok(dry.candidates>=1);assert.equal(dry.revoked,0);
    const [[before]]=await pool.execute('SELECT status FROM diary_media WHERE media_id=?',[orphan]);assert.equal(before.status,'ready');
    await cleanup(pool,{retentionHours:24,apply:true,mediaDir:process.env.DIARY_MEDIA_DIR});
    const [[after]]=await pool.execute('SELECT status,auth_version FROM diary_media WHERE media_id=?',[orphan]);assert.equal(after.status,'failed');assert.equal(after.auth_version,2);
    const [[linked]]=await pool.execute('SELECT status FROM diary_media WHERE media_id=?',[media.mediaId]);assert.equal(linked.status,'ready');
  });
  await t.test('feature switch rejects new writes while retaining authorized reads',async()=>{
    process.env.DIARY_WRITES_ENABLED='false';
    try { await api('POST','/api/diary',a,{body:'disabled',occurredOn:'2026-10-01',relationshipVersion:context.cycleId,idempotencyKey:key()},503);await api('GET',`/api/diary/${post.id}`,a); }
    finally {delete process.env.DIARY_WRITES_ENABLED;}
  });
  await t.test('legacy unbind freezes goal, invalidates invites/media, same pair rebind rejects old cycle',async()=>{
    await api('DELETE','/api/couple/unbind',a,{});
    const [[g]]=await pool.execute('SELECT status FROM couple_goals WHERE goal_id=?',[goal.id]);assert.equal(g.status,'frozen');
    assert.equal((await fetch(base+themeUrl,{headers:{Authorization:"Bearer "+tokens.get(b)}})).status,404);
    await api('GET',`/api/diary/${post.id}`,b,undefined,404);
    const archives=await api('GET','/api/me/diary-archives',a);assert.ok(archives.items.some(x=>x.id===post.id));
    await pool.execute("UPDATE couple_relationships SET status='active' WHERE relationship_id=?",[relationshipId]);const rebound=await bind();assert.notEqual(rebound.spaceId,context.spaceId);
    await api('POST','/api/diary',a,{body:'late',occurredOn:'2026-10-01',relationshipVersion:context.cycleId,idempotencyKey:key()},409);
    await api('POST',`/api/relationships/${relationshipId}/unbind`,a,{expectedVersion:context.cycleId,idempotencyKey:key()},409);
    await api('POST','/api/diary',a,{body:'new cycle old media',occurredOn:'2026-10-01',mediaIds:[media.mediaId],relationshipVersion:rebound.cycleId,idempotencyKey:key()},409);
    await api('POST',`/api/relationships/${relationshipId}/unbind`,a,{expectedVersion:rebound.cycleId,idempotencyKey:key()});
  });
});
