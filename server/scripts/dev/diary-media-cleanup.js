// Explicit maintenance command. Default is dry-run; retention must be approved outside this script.
// DIARY_MEDIA_RETENTION_HOURS=24 node scripts/dev/diary-media-cleanup.js [--apply]
const fs = require('fs'), path = require('path');
async function cleanup(pool, { retentionHours, apply = false, mediaDir = path.resolve(__dirname,'../../media/diary') }) {
  if (!Number.isFinite(retentionHours) || retentionHours <= 0) throw Error('Approved retentionHours is required');
  const cutoff = new Date(Date.now() - retentionHours * 3600000);
  const [candidates] = await pool.execute("SELECT media_id FROM diary_media m WHERE diary_id IS NULL AND created_at < ? AND status IN ('pending','transcoded','ready','failed') AND NOT EXISTS (SELECT 1 FROM space_diary_theme t WHERE t.media_id=m.media_id) LIMIT 100",[cutoff]);
  let revoked=0, deletedFiles=0;
  if (apply) for (const {media_id:id} of candidates) {
    const conn=await pool.getConnection();let row;
    try {
      await conn.beginTransaction();
      const [rows]=await conn.execute('SELECT * FROM diary_media WHERE media_id=? FOR UPDATE',[id]);row=rows[0];
      const [themes]=await conn.execute('SELECT space_id FROM space_diary_theme WHERE media_id=? LIMIT 1 FOR UPDATE',[id]);
      if (!row || row.diary_id || themes.length || new Date(row.created_at)>=cutoff) { await conn.rollback();continue; }
      await conn.execute("UPDATE diary_media SET status='failed', auth_version=auth_version+1 WHERE media_id=?",[id]);
      await conn.commit();revoked++;
    } catch(e) { await conn.rollback();throw e; } finally {conn.release();}
    for(const file of [row.storage_path,row.thumb_path]) if(file) {
      try {await fs.promises.unlink(path.join(mediaDir,path.basename(file)));deletedFiles++;}catch(e){if(e.code!=='ENOENT')throw e;}
    }
  }
  return {dryRun:!apply,candidates:candidates.length,revoked,deletedFiles};
}
if (require.main === module) {
  // Use the normal existing application configuration, never print secrets or media names.
  for (const line of fs.readFileSync(path.resolve(__dirname,'../../.env'),'utf8').split(/\r?\n/)) {
    const i=line.indexOf('=');if(i>0&&!line.trim().startsWith('#'))process.env[line.slice(0,i).trim()]??=line.slice(i+1).trim();
  }
  const pool=require('../../src/db').createPool(require('../../src/config').readConfig());
  cleanup(pool,{retentionHours:Number(process.env.DIARY_MEDIA_RETENTION_HOURS),apply:process.argv.includes('--apply')})
    .then(result=>console.log(JSON.stringify(result))).catch(e=>{console.error(e.code || e.message);process.exitCode=1;}).finally(()=>pool.end());
}
module.exports={cleanup};
