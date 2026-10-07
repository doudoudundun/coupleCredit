const { test } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const path = require('node:path');
// Explicit opt-in to a socket-only disposable database. Never uses application .env or TCP.
test('v0.2 media, notification and lifecycle gates', { skip: !process.env.V02_TEST_SOCKET }, async t => {
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
  let failNotification=false;
  const diaryPool=new Proxy(pool,{get(target,prop){if(prop==='getConnection')return async()=>{const conn=await target.getConnection();return new Proxy(conn,{get(db,key){if(key==='execute')return async(sql,args)=>{if(failNotification && sql.includes('INSERT INTO diary_update_events')){failNotification=false;throw new (require('../src/errors').ApiError)(503,'TEST_EVENT_FAILURE','synthetic failure');}return db.execute(sql,args);};return typeof db[key]==='function'?db[key].bind(db):db[key];}});};return typeof target[prop]==='function'?target[prop].bind(target):target[prop];}});
  const app=express();app.use(express.json());
  app.use((req,res,next)=>{ if(req.headers['x-test-drop-response']==='once') res.json=()=>res.destroy(); next(); });
  app.use('/api/diary-media',require('../src/routes/diaryMedia').createDiaryMediaRouter({pool,config}));
  app.use(require('../src/middleware/auth').createRequireAuth(pool));
  app.use('/api',require('../src/routes/diary').createDiaryRouter({pool:diaryPool,config}));
  app.use('/api',require('../src/routes/goals').createGoalsRouter({pool}));
  app.use('/api',require('../src/routes/relationships').createRelationshipsRouter({pool}));
  app.use('/api/couple',require('../src/routes/couple').createCoupleRouter({pool,config}));
  app.use((error,req,res,next)=>{if(!error.status) console.error('Unexpected route error:', error.code || error.message);require('../src/errors').sendError(res,error);});
  const server=app.listen(0,'127.0.0.1');await new Promise(resolve=>server.once('listening',resolve));t.after(()=>new Promise(resolve=>server.close(resolve)));
  const base='http://127.0.0.1:'+server.address().port;
  const key=()=>crypto.randomUUID();
  async function api(method,url,user,body,status=200){const r=await fetch(base+url,{method,headers:{'Content-Type':'application/json',...(user?{Authorization:'Bearer '+tokens.get(user)}:{})},body:body?JSON.stringify(body):undefined});const j=await r.json();assert.equal(r.status,status,method+' '+url+' '+(j.error?.code || 'status mismatch'));return j.data;}


  const sharp=require('sharp');
  async function metadataFixture(){
    const original=await sharp({create:{width:96,height:48,channels:3,background:'#db8e99'}}).jpeg().toBuffer();
    const thumbnail=await sharp({create:{width:12,height:6,channels:3,background:'#00ff00'}}).jpeg().toBuffer();
    const header=Buffer.alloc(188);header.write('II');header.writeUInt16LE(42,2);header.writeUInt32LE(8,4);const extras=[];let offset=188;
    const append=b=>{const start=offset;extras.push(b);offset+=b.length;return start;};
    const ascii=t=>{const b=Buffer.from(t+'\0');return [b.length,append(b)];};
    const rational=values=>{const b=Buffer.alloc(values.length*8);values.forEach((v,i)=>{b.writeUInt32LE(v,i*8);b.writeUInt32LE(1,i*8+4);});return append(b);};
    function ifd(at,entries,next=0){header.writeUInt16LE(entries.length,at);entries.forEach(([tag,type,count,value],i)=>{const p=at+2+i*12;header.writeUInt16LE(tag,p);header.writeUInt16LE(type,p+2);header.writeUInt32LE(count,p+4);header.writeUInt32LE(value,p+8);});header.writeUInt32LE(next,at+2+12*entries.length);}
    const make=ascii('SyntheticPrivateCamera'),model=ascii('SyntheticPrivateDevice'),date=ascii('2026:10:01 12:34:56');const lat=rational([31,12,34]),lon=rational([121,28,56]),thumb=append(thumbnail);
    ifd(8,[[0x10f,2,...make],[0x110,2,...model],[0x112,3,1,6],[0x8769,4,1,128],[0x8825,4,1,74]],146);
    ifd(74,[[1,2,2,78],[2,5,3,lat],[3,2,2,69],[4,5,3,lon]]);ifd(128,[[0x9003,2,...date]]);ifd(146,[[0x103,3,1,6],[0x201,4,1,thumb],[0x202,4,1,thumbnail.length]]);
    const exif=Buffer.concat([Buffer.from('Exif\0\0'),header,...extras]);const length=Buffer.alloc(2);length.writeUInt16BE(exif.length+2);const result=Buffer.concat([original.subarray(0,2),Buffer.from([0xff,0xe1]),length,exif,original.subarray(2)]);
    const meta=await sharp(result).metadata();assert.equal(meta.orientation,6);assert.ok(meta.exif.includes(Buffer.from('SyntheticPrivateDevice')));assert.equal(meta.exif.readUInt32LE(6+8+2+4*12+8),74);assert.ok(meta.exif.includes(thumbnail));return result;
  }
  async function session(user,buffer,purpose='diary'){return api('POST','/api/diary-media/uploads',user,{purpose,mime:'image/jpeg',size:buffer.length,relationshipVersion:context.cycleId},201);}
  async function raw(user,session,buffer){const fd=new FormData();fd.append('file',new Blob([buffer],{type:'image/jpeg'}),'synthetic.jpg');const r=await fetch(base+session.uploadUrl,{method:'POST',headers:{Authorization:'Bearer '+tokens.get(user)},body:fd});return {status:r.status,body:await r.json()};}
  const fixture=await metadataFixture();let ninePost,mediaIds=[],bg;
  await t.test('nine actual uploads allow pure-image publish; blank/10 rejected; full+thumb strip GPS/device/embedded thumbnail',async()=>{
    await api('POST','/api/diary',a,{body:' \n ',occurredOn:'2026-10-01',relationshipVersion:context.cycleId,idempotencyKey:key()},422);
    for(let i=0;i<9;i++){const upload=await session(a,fixture);assert.equal((await raw(a,upload,fixture)).status,200);const done=await api('POST','/api/diary-media/'+upload.mediaId+'/complete',a,{});mediaIds.push(done.mediaId);}
    const payload={body:'',occurredOn:'2026-10-01',mediaIds,relationshipVersion:context.cycleId,idempotencyKey:key()};ninePost=await api('POST','/api/diary',a,payload);assert.equal(ninePost.media.length,9);
    await api('POST','/api/diary',a,{...payload,mediaIds:mediaIds.concat(key()),idempotencyKey:key()},422);
    for(const variant of ['full','thumb']){const r=await fetch(base+`/api/diary-media/${mediaIds[0]}/content?variant=${variant}`,{headers:{Authorization:'Bearer '+tokens.get(b)}});assert.equal(r.status,200);const bytes=Buffer.from(await r.arrayBuffer()),meta=await sharp(bytes).metadata();for(const field of ['exif','xmp','iptc','icc','orientation'])assert.equal(meta[field],undefined,field);assert.equal(meta.width,48);assert.equal(meta.height,96);assert.equal(bytes.includes(Buffer.from('SyntheticPrivate')),false);}
  });
  await t.test('parallel raw uploads cannot overwrite one media; draft images, thumbnail, background and forged IDs stay private',async()=>{
    const upload=await session(a,fixture);const results=await Promise.all([raw(a,upload,fixture),raw(a,upload,fixture)]);assert.deepEqual(results.map(r=>r.status).sort(),[200,409]);
    const done=await api('POST','/api/diary-media/'+upload.mediaId+'/complete',a,{});for(const user of [b,c])for(const variant of ['full','thumb'])assert.equal((await fetch(base+`/api/diary-media/${done.mediaId}/content?variant=${variant}`,{headers:{Authorization:'Bearer '+tokens.get(user)}})).status,404);
    await api('POST','/api/diary',b,{body:'stolen',mediaIds:[done.mediaId],occurredOn:'2026-10-01',relationshipVersion:context.cycleId,idempotencyKey:key()},409);
    const background=await session(a,fixture,'background');assert.equal((await raw(a,background,fixture)).status,200);bg=await api('POST','/api/diary-media/'+background.mediaId+'/complete',a,{});await api('PATCH','/api/spaces/current/diary-theme',a,{mediaId:bg.mediaId,relationshipVersion:context.cycleId,expectedVersion:0});assert.equal((await fetch(base+`/api/diary-media/${bg.mediaId}/content`,{headers:{Authorization:'Bearer '+tokens.get(c)}})).status,404);
    for(const id of mediaIds)assert.equal((await fetch(base+`/api/diary-media/${id}/content?variant=thumb`,{headers:{Authorization:'Bearer '+tokens.get(c)}})).status,404);
  });
  await t.test('comment same-key emits one private event; invalid target emits none; deletion and restore do not resurrect it',async()=>{
    const payload={body:'synthetic private response',idempotencyKey:key()};const responses=await Promise.all([api('POST',`/api/diary/${ninePost.id}/comments`,b,payload),api('POST',`/api/diary/${ninePost.id}/comments`,b,payload)]);assert.equal(responses[0].id,responses[1].id);
    const [[count]]=await pool.execute('SELECT COUNT(*) n FROM diary_update_events WHERE comment_id=?',[responses[0].id]);assert.equal(count.n,1);
    await api('POST',`/api/diary/${ninePost.id}/comments`,a,{body:'invalid',replyToCommentId:key(),idempotencyKey:key()},404);
    await api('DELETE','/api/diary-comments/'+responses[0].id,b,{expectedVersion:1,idempotencyKey:key()});const [[state]]=await pool.execute('SELECT state FROM diary_update_events WHERE comment_id=?',[responses[0].id]);assert.equal(state.state,'revoked');
    await api('DELETE','/api/diary/'+ninePost.id,a,{expectedVersion:1,idempotencyKey:key()});await api('POST','/api/diary/'+ninePost.id+'/restore',a,{idempotencyKey:key()});assert.equal((await api('GET',`/api/diary/${ninePost.id}/comments`,a)).total,0);
  });

  await t.test('event insert failure rolls back post and receipt; retry and lost response produce one event only',async()=>{
    const payload={body:'synthetic atomic event failure '+key(),occurredOn:'2026-10-01',relationshipVersion:context.cycleId,idempotencyKey:key()};failNotification=true;await api('POST','/api/diary',a,payload,503);
    const [[before]]=await pool.execute('SELECT COUNT(*) n FROM diary_posts WHERE space_id=? AND body=?',[context.spaceId,payload.body]);assert.equal(before.n,0);const [[receipt]]=await pool.execute('SELECT COUNT(*) n FROM idempotency_records WHERE user_id=? AND idem_key=?',[a,payload.idempotencyKey]);assert.equal(receipt.n,0);
    const saved=await api('POST','/api/diary',a,payload);await api('POST','/api/diary',a,payload);const [[count]]=await pool.execute('SELECT COUNT(*) n FROM diary_update_events WHERE diary_id=?',[saved.id]);assert.equal(count.n,1);
    const lost={...payload,body:'synthetic lost notification response',idempotencyKey:key()};await assert.rejects(fetch(base+'/api/diary',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+tokens.get(a),'x-test-drop-response':'once'},body:JSON.stringify(lost)}));const recovered=await api('POST','/api/diary',a,lost);const [[events]]=await pool.execute('SELECT COUNT(*) n FROM diary_update_events WHERE diary_id=?',[recovered.id]);assert.equal(events.n,1);
  });
  await t.test('publish/unbind race cannot leave readable partner media or unread old-space updates; same-pair rebind stays isolated',async()=>{
    const published=api('POST','/api/diary',a,{body:'race',occurredOn:'2026-10-01',relationshipVersion:context.cycleId,idempotencyKey:key()}).catch(e=>{if(!e.message.includes('RELATIONSHIP')&&!e.message.includes('SPACE'))throw e;});
    await Promise.all([published,api('POST',`/api/relationships/${relationshipId}/unbind`,b,{expectedVersion:context.cycleId,idempotencyKey:key()})]);
    assert.equal((await api('GET','/api/spaces/current/diary-updates',b)).items.length,0);for(const id of [...mediaIds,...(bg?[bg.mediaId]:[])])assert.equal((await fetch(base+`/api/diary-media/${id}/content`,{headers:{Authorization:'Bearer '+tokens.get(b)}})).status,404);
    await pool.execute("UPDATE couple_relationships SET status='active' WHERE relationship_id=?",[relationshipId]);const next=await bind();assert.notEqual(next.spaceId,context.spaceId);assert.equal((await api('GET','/api/spaces/current/diary-updates',b)).items.length,0);await api('GET','/api/diary/'+ninePost.id,b,undefined,404);
    const fresh=await api('POST','/api/diary',b,{body:'new space safe',occurredOn:'2026-10-01',relationshipVersion:next.cycleId,idempotencyKey:key()});await api('POST',`/api/relationships/${relationshipId}/unbind`,a,{expectedVersion:next.cycleId,idempotencyKey:key()});
    const [newRel]=await pool.execute("INSERT INTO couple_relationships(user_id_1,user_id_2,status)VALUES(?,?,'active')",[a,c]);const ac=await withTransaction(pool,conn=>ensureCycleForBind(conn,{relationship_id:newRel.insertId,user_id_1:a,user_id_2:c},new Map([[a,'Synthetic A'],[c,'Synthetic C']])));
    await api('GET','/api/diary/'+ninePost.id,c,undefined,404);await api('GET','/api/diary/'+fresh.id,c,undefined,404);
    for(const id of [...mediaIds,bg.mediaId])for(const variant of ['full','thumb'])assert.equal((await fetch(base+`/api/diary-media/${id}/content?variant=${variant}`,{headers:{Authorization:'Bearer '+tokens.get(c)}})).status,404);
    const current=await api('POST','/api/diary',c,{body:'current AC space',occurredOn:'2026-10-01',relationshipVersion:ac.cycleId,idempotencyKey:key()});
    const old=await api('GET','/api/diary/'+ninePost.id,a);await api('DELETE','/api/diary/'+ninePost.id,a,{expectedVersion:old.version,idempotencyKey:key()});await api('GET','/api/diary/'+current.id,c);await api('GET','/api/diary/'+fresh.id,b);
  });
});
