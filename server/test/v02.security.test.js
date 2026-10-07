const { test } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const path = require('node:path');
// Explicit opt-in to a socket-only disposable database. Never uses application .env or TCP.
test('v0.2 invitation and permission gates', { skip: !process.env.V02_TEST_SOCKET }, async t => {
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
  app.locals.authPool=pool;
  const pass=(req,res,next)=>next();
  app.use('/api/auth',require('../src/routes/auth').createAuthRouter({pool,config,authLimiter:pass,strictLimiter:pass}));
  app.use('/api/invitations',require('../src/routes/invitations').createInvitationsRouter({pool,requireAuth:require('../src/middleware/auth').createRequireAuth(pool)}));
  app.use('/api/diary-media',require('../src/routes/diaryMedia').createDiaryMediaRouter({pool,config}));
  app.use(require('../src/middleware/auth').createRequireAuth(pool));
  app.use('/api',require('../src/routes/diary').createDiaryRouter({pool,config}));
  app.use('/api',require('../src/routes/goals').createGoalsRouter({pool}));
  app.use('/api',require('../src/routes/relationships').createRelationshipsRouter({pool}));
  app.use('/api/couple',require('../src/routes/couple').createCoupleRouter({pool,config}));
  app.use('/api/bills',require('../src/routes/bills').createBillsRouter({pool}));
  app.use('/api/inventory',require('../src/routes/inventory').createInventoryRouter({pool}));
  app.use('/api/housework',require('../src/routes/housework').createHouseworkRouter({pool,config}));
  app.use((error,req,res,next)=>{if(!error.status) console.error('Unexpected route error:', error.code || error.message);require('../src/errors').sendError(res,error);});
  const server=app.listen(0,'127.0.0.1');await new Promise(resolve=>server.once('listening',resolve));t.after(()=>new Promise(resolve=>server.close(resolve)));
  const base='http://127.0.0.1:'+server.address().port;
  const key=()=>crypto.randomUUID();
  async function api(method,url,user,body,status=200){const r=await fetch(base+url,{method,headers:{'Content-Type':'application/json',...(user?{Authorization:'Bearer '+tokens.get(user)}:{})},body:body?JSON.stringify(body):undefined});const j=await r.json();assert.equal(r.status,status,method+' '+url+' '+(j.error?.code || 'status mismatch'));return j.data;}


  async function user(){const id=key().replace(/-/g,'');const [r]=await pool.execute("INSERT INTO users(username,email,password,status)VALUES(?,?,?,'active')",['gate'+id,id+'@example.test','synthetic-only']);tokens.set(r.insertId,signToken({userId:r.insertId}));return r.insertId;}
  const personal=async u=>api('POST','/api/goals',u,{title:'private synthetic title',targetAmountFen:150000,idempotencyKey:key()});
  const invite=async(u,g)=>api('POST','/api/invitations',u,{goalId:g.id});
  const accept=async(u,token,k=key())=>{const r=await fetch(base+'/api/invitations/accept',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+tokens.get(u)},body:JSON.stringify({token,idempotencyKey:k})});return {status:r.status,body:await r.json()};};

  await t.test('beta admission rejects wrong code; admitted account authenticates and can explicitly accept the original invitation',async()=>{
    const inviter=await user(),g=await personal(inviter),i=await invite(inviter,g);const name='gate'+crypto.randomBytes(5).toString('hex');const form={username:name,email:name+'@example.test',password:'synthetic-local-only',inviteCode:'wrong'};
    await api('POST','/api/auth/register',null,form,403);const registered=await api('POST','/api/auth/register',null,{...form,inviteCode:'synthetic-test'},201);const logged=await api('POST','/api/auth/login',null,{username:name,password:form.password});tokens.set(logged.userId,logged.accessToken);assert.equal((await api('GET','/api/invitations/preview?token='+i.token,logged.userId)).goalTitle,g.title);assert.equal((await accept(logged.userId,i.token)).status,200);
  });
  await t.test('goal creation rejects empty/zero/negative/fraction/overflow; minimum one fen remains exact',async()=>{
    const u=await user();for(const patch of [{title:''},{targetAmountFen:0},{targetAmountFen:-1},{targetAmountFen:1.001},{targetAmountFen:100000000}])await api('POST','/api/goals',u,{title:'boundary',targetAmountFen:1,...patch,idempotencyKey:key()},422);const goal=await api('POST','/api/goals',u,{title:'minimum',targetAmountFen:1,idempotencyKey:key()});assert.equal(goal.targetAmountFen,1);
  });
  await t.test('anonymous invitation reveals no title/nickname/amount/history; authenticated preview still works',async()=>{
    const u=await user(),v=await user(),g=await personal(u),i=await invite(u,g);
    const preview=await api('GET','/api/invitations/preview?token='+i.token,null);assert.equal(preview.goalTitle,undefined);assert.equal(preview.inviterNickname,undefined);assert.equal(preview.requiresLogin,true);
    const signed=await api('GET','/api/invitations/preview?token='+i.token,v);assert.equal(signed.goalTitle,g.title);assert.equal(signed.targetAmountFen,undefined);
    const [[r]]=await pool.execute("SELECT COUNT(*) n FROM couple_relationships WHERE status='active' AND(user_id_1=? OR user_id_2=?)",[u,u]);assert.equal(r.n,0);
  });
  await t.test('revoked/expired/self/existing third party cannot bind or replace a relationship',async()=>{
    const u=await user(),g=await personal(u),one=await invite(u,g),two=await invite(u,g);
    assert.equal((await accept(c,one.token)).status,404);assert.equal((await accept(u,two.token)).status,409);assert.equal((await accept(a,two.token)).status,409);
    await pool.execute("UPDATE goal_invitations SET expires_at=DATE_SUB(NOW(),INTERVAL 1 DAY) WHERE inviter_id=?",[u]);assert.equal((await accept(c,two.token)).status,404);
    const [[r]]=await pool.execute('SELECT status FROM couple_relationships WHERE relationship_id=?',[relationshipId]);assert.equal(r.status,'active');
  });
  await t.test('same invitation concurrent accept binds one pair; same-key replay returns exact cycle',async()=>{
    const u=await user(),v=await user(),w=await user(),g=await personal(u),i=await invite(u,g),k=key();
    const result=await Promise.all([accept(v,i.token,k),accept(w,i.token)]);assert.deepEqual(result.map(x=>x.status).sort(),[200,404]);
    const winner=result.find(x=>x.status===200).body.data;const winnerId=result[0].status===200?v:w;
    const ctx=await api('GET','/api/spaces/current/diary-context',winnerId);assert.equal(winner.relationship.version,ctx.cycleId);
    if(winnerId===v)assert.equal((await accept(v,i.token,k)).body.data.relationship.version,ctx.cycleId);
    const [[n]]=await pool.execute("SELECT COUNT(*) n FROM couple_relationships WHERE status='active' AND(user_id_1=? OR user_id_2=?)",[u,u]);assert.equal(n.n,1);
  });
  await t.test('two different inviters racing for one recipient produce one success and one conflict, no 500',async()=>{
    for(let attempt=0;attempt<3;attempt++){const u=await user(),v=await user(),w=await user();const [gu,gv]=await Promise.all([personal(u),personal(v)]);const [iu,iv]=await Promise.all([invite(u,gu),invite(v,gv)]);const result=await Promise.all([accept(w,iu.token),accept(w,iv.token)]);assert.deepEqual(result.map(x=>x.status).sort(),[200,409]);}
  });
  await t.test('two concurrent active-goal creates produce at most one; exact 1500/600/100 ledger and optimistic conflicts',async()=>{
    const u=await user();const created=await Promise.all([0,1].map(async()=>{const r=await fetch(base+'/api/goals',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+tokens.get(u)},body:JSON.stringify({title:'race',targetAmountFen:150000,idempotencyKey:key()})});return {status:r.status,body:await r.json()};}));assert.deepEqual(created.map(r=>r.status).sort(),[200,409]);
    const g=created.find(r=>r.status===200).body.data;const add=await api('POST',`/api/goals/${g.id}/entries`,u,{type:'increase',amountFen:60000,idempotencyKey:key()});const sub=await api('POST',`/api/goals/${g.id}/entries`,u,{type:'decrease',amountFen:10000,idempotencyKey:key()});const detail=await api('GET','/api/spaces/current/goal',u);assert.equal(detail.goal.currentAmountFen,50000);
    await api('PATCH',`/api/goals/${g.id}`,u,{title:'stale',expectedVersion:g.version},409);
    await api('POST',`/api/entries/${add.entry.id}/reverse`,c,{expectedVersion:sub.version,idempotencyKey:key()},404);
  });
  let post,comment;
  await t.test('A/B/C post/comment rights reject forged target/root/space and other-author writes',async()=>{
    post=await api('POST','/api/diary',a,{body:'private synthetic',occurredOn:'2026-10-01',relationshipVersion:context.cycleId,idempotencyKey:key()});
    comment=await api('POST',`/api/diary/${post.id}/comments`,b,{body:'B private reply',idempotencyKey:key()});
    for(const method of ['GET','PATCH','DELETE'])await api(method,`/api/diary/${post.id}`,c,method==='GET'?undefined:{body:'forged',expectedVersion:post.version,idempotencyKey:key()},404);
    await api('PATCH',`/api/diary/${post.id}`,b,{body:'not author',expectedVersion:post.version},403);
    await api('GET',`/api/diary/${post.id}/comments`,c,undefined,404);
    await api('PATCH',`/api/diary-comments/${comment.id}`,a,{body:'not author',expectedVersion:comment.version},403);
    for(const forged of [{rootCommentId:key()},{replyToCommentId:key()}])await api('POST',`/api/diary/${post.id}/comments`,a,{body:'forged',...forged,idempotencyKey:key()},404);
    await api('POST','/api/diary',a,{body:'forged',authorId:b,spaceId:'other',occurredOn:'2026-10-01',relationshipVersion:context.cycleId,idempotencyKey:key()},422);
    await api('POST',`/api/diary/${post.id}/comments`,a,{body:'forged',authorId:b,spaceId:'other',idempotencyKey:key()},422);
    for(const field of ['authorId','spaceId','userId']) {
      await api('POST','/api/diary',a,{body:'forged',occurredOn:'2026-10-01',[field]:c,relationshipVersion:context.cycleId,idempotencyKey:key()},422);
      await api('POST',`/api/diary/${post.id}/comments`,a,{body:'forged',[field]:c,idempotencyKey:key()},422);
    }
    await api('POST',`/api/diary/${post.id}/comments`,a,{body:'forged target user',replyToCommentId:comment.id,replyToUserId:c,idempotencyKey:key()},422);
    const other=await api('POST','/api/diary',b,{body:'other post',occurredOn:'2026-10-01',relationshipVersion:context.cycleId,idempotencyKey:key()});
    const otherRoot=await api('POST',`/api/diary/${other.id}/comments`,b,{body:'other root',idempotencyKey:key()});
    for(const target of [{rootCommentId:otherRoot.id},{replyToCommentId:otherRoot.id}])await api('POST',`/api/diary/${post.id}/comments`,a,{body:'wrong post',...target,idempotencyKey:key()},404);

  });
  await t.test('reply-to-reply remains two levels; deleted target rejects instead of silently becoming root',async()=>{
    const reply=await api('POST',`/api/diary/${post.id}/comments`,a,{body:'reply',replyToCommentId:comment.id,idempotencyKey:key()});const nested=await api('POST',`/api/diary/${post.id}/comments`,b,{body:'nested',replyToCommentId:reply.id,idempotencyKey:key()});assert.equal(nested.rootCommentId,comment.id);
    await api('DELETE',`/api/diary-comments/${reply.id}`,a,{expectedVersion:reply.version,idempotencyKey:key()});await api('POST',`/api/diary/${post.id}/comments`,b,{body:'must reject',replyToCommentId:reply.id,idempotencyKey:key()},404);
  });
  await t.test('bills/inventory/housework list and ID writes cannot be impersonated by C; payer does not change sharing',async()=>{
    const bill=await api('POST','/api/bills',a,{title:'synthetic bill',type:'其他',amount:12.34,date:'2026-10-01',time:'12:00',incomeType:0,billOwner:'自己'},201);
    const bid=bill.billId;assert.ok(bid);const list=await api('GET','/api/bills?year=2026&month=10',b);assert.ok(list.bills.some(x=>x.billId===bid));const outsiders=await api('GET','/api/bills?userId='+a+'&year=2026&month=10',c);assert.equal(outsiders.bills.some(x=>x.billId===bid),false);
    await api('PUT','/api/bills/'+bid,c,{title:'forged',type:'其他',amount:12.34,date:'2026-10-01',time:'12:00',incomeType:0,billOwner:'自己'},404);await api('DELETE','/api/bills/'+bid,c,{},404);
    const item=await api('POST','/api/inventory',a,{name:'synthetic stock',category:'其他',quantity:10,unit:'件'},201);const iid=item.inventoryId;const theirs=await api('GET','/api/inventory?userId='+a,c);assert.equal(theirs.items.some(x=>x.inventoryId===iid),false);
    await api('POST',`/api/inventory/${iid}/consume`,c,{consumeAmount:1},404);await api('PUT',`/api/inventory/${iid}`,c,{quantity:99},404);await api('DELETE',`/api/inventory/${iid}`,c,{},404);
    await api('POST',`/api/inventory/${iid}/consume`,b,{consumeAmount:2});await api('PUT',`/api/inventory/${iid}`,a,{name:'renamed synthetic'});const own=await api('GET','/api/inventory',a);assert.equal(Number(own.items.find(x=>x.inventoryId===iid).quantity),8);
    await api('GET',`/api/housework/spaces/${context.spaceId}/config`,c,undefined,403);await api('GET',`/api/housework/spaces/${context.spaceId}/records`,c,undefined,403);
    const config=await api('GET',`/api/housework/spaces/${context.spaceId}/config`,a);const categoryId=config.categories.find(x=>x.isFallback).categoryId;
    const tpl=(await api('POST',`/api/housework/spaces/${context.spaceId}/templates`,a,{clientMutationId:key(),name:'synthetic chore',categoryId,measureMode:'quantity',unit:'件'})).entity;
    await api('PATCH',`/api/housework/spaces/${context.spaceId}/templates/${tpl.templateId}`,c,{clientMutationId:key(),expectedVersion:tpl.version,name:'forged'},403);
    const record=(await api('POST',`/api/housework/spaces/${context.spaceId}/records`,a,{clientMutationId:key(),templateId:tpl.templateId,templateVersion:tpl.version,completedDate:'2026-10-01',participants:[{userId:a,shareBps:10000}],quantity:'2.00'})).entity;
    await api('DELETE',`/api/housework/spaces/${context.spaceId}/records/${record.recordId}?expectedVersion=${record.version}&clientMutationId=${key()}`,a);
    const records=await api('GET',`/api/housework/spaces/${context.spaceId}/records?from=2026-10-01&to=2026-10-07`,b);assert.equal(records.items.some(x=>x.recordId===record.recordId),false);

  });
  await t.test('successful notifications commit once with business; read markers scoped to recipient and deletion revokes',async()=>{
    const payload={body:'private notification fixture',occurredOn:'2026-10-01',relationshipVersion:context.cycleId,idempotencyKey:key()};const results=await Promise.all([api('POST','/api/diary',a,payload),api('POST','/api/diary',a,payload)]);assert.equal(results[0].id,results[1].id);
    const id=results[0].id;const [[n]]=await pool.execute('SELECT COUNT(*) n FROM diary_update_events WHERE diary_id=?',[id]);assert.equal(n.n,1);
    const inbox=await api('GET','/api/spaces/current/diary-updates',b);const event=inbox.items.find(x=>x.diaryId===id);assert.ok(event);assert.deepEqual(Object.keys(event).sort(),['commentId','diaryId','eventId']);assert.equal((await api('GET','/api/spaces/current/diary-updates',c)).items.length,0);
    await api('PATCH','/api/spaces/current/diary-updates/seen',c,{eventIds:[event.eventId]});assert.ok((await api('GET','/api/spaces/current/diary-updates',b)).items.some(x=>x.eventId===event.eventId));
    await api('PATCH','/api/diary/'+id,a,{body:'edited without a second notification',expectedVersion:1});const [[count]]=await pool.execute('SELECT COUNT(*) n FROM diary_update_events WHERE diary_id=?',[id]);assert.equal(count.n,1);
    await api('DELETE','/api/diary/'+id,a,{expectedVersion:2,idempotencyKey:key()});assert.equal((await api('GET','/api/spaces/current/diary-updates',b)).items.some(x=>x.diaryId===id),false);
    const [[state]]=await pool.execute('SELECT state FROM diary_update_events WHERE diary_id=?',[id]);assert.equal(state.state,'revoked');
    await api('POST','/api/diary/'+id+'/restore',a,{idempotencyKey:key()});assert.equal((await api('GET','/api/spaces/current/diary-updates',b)).items.some(x=>x.diaryId===id),false);
  });

  await t.test('unbind then different-partner bind keeps old diary/media/goal/housework away from new partner',async()=>{
    const goal=await api('POST','/api/goals',a,{title:'old shared goal',targetAmountFen:150000,idempotencyKey:key()});await api('POST',`/api/goals/${goal.id}/entries`,a,{type:'increase',amountFen:60000,idempotencyKey:key()});const down=await api('POST',`/api/goals/${goal.id}/entries`,b,{type:'decrease',amountFen:10000,idempotencyKey:key()});assert.equal(down.currentAmountFen,50000);
    await api('DELETE','/api/couple/unbind',a,{});
    const [rel2]=await pool.execute("INSERT INTO couple_relationships(user_id_1,user_id_2,status)VALUES(?,?,'active')",[a,c]);const next=await withTransaction(pool,conn=>ensureCycleForBind(conn,{relationship_id:rel2.insertId,user_id_1:a,user_id_2:c},new Map([[a,'Synthetic A'],[c,'Synthetic C']])));assert.notEqual(next.spaceId,context.spaceId);
    await api('GET',`/api/diary/${post.id}`,c,undefined,404);await api('GET',`/api/diary/${post.id}/comments`,c,undefined,404);await api('GET',`/api/goals/${goal.id}/entries`,c,undefined,404);await api('POST',`/api/goals/${goal.id}/entries`,c,{type:'increase',amountFen:1,idempotencyKey:key()},404);
    assert.equal((await api('GET','/api/diary',c)).items.length,0);assert.equal((await api('GET','/api/me/diary-archives',c)).items.length,0);assert.equal((await api('GET','/api/spaces/current/diary-updates',c)).items.length,0);
    await api('GET',`/api/housework/spaces/${context.spaceId}/config`,c,undefined,403);await api('PATCH',`/api/diary-comments/${comment.id}`,b,{body:'closed space',expectedVersion:1},404);
  });

});
