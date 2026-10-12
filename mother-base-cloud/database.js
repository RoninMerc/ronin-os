'use strict';
const {normalise,clean}=require('./normalise');
const schema=[
 "CREATE TABLE IF NOT EXISTS trl_snapshots(kind TEXT PRIMARY KEY,payload JSONB NOT NULL,generation BIGINT NOT NULL DEFAULT 0,received_at BIGINT NOT NULL DEFAULT 0,central BOOLEAN NOT NULL DEFAULT FALSE)",
 "INSERT INTO trl_snapshots(kind,payload) VALUES('activity','[]'),('checkpoints','[]') ON CONFLICT DO NOTHING",
 "CREATE TABLE IF NOT EXISTS trl_breaches(source_id TEXT PRIMARY KEY,plate TEXT NOT NULL,event JSONB NOT NULL,payload_hash TEXT NOT NULL,revision BIGINT NOT NULL DEFAULT 1,received_at TIMESTAMPTZ NOT NULL DEFAULT now())",
 "CREATE INDEX IF NOT EXISTS trl_breaches_plate ON trl_breaches(plate)",
 "CREATE TABLE IF NOT EXISTS trl_breach_audit(id BIGSERIAL PRIMARY KEY,source_id TEXT NOT NULL,event JSONB NOT NULL,revision BIGINT NOT NULL,changed_at TIMESTAMPTZ NOT NULL DEFAULT now())",
 "CREATE TABLE IF NOT EXISTS trl_import_review(source_id TEXT PRIMARY KEY,reason TEXT NOT NULL,source JSONB NOT NULL,received_at TIMESTAMPTZ NOT NULL DEFAULT now())"
];
async function review(client,id,reason,row){
 const minimal={id:clean(row?.id,130),property:clean(row?.property,260),issue:clean(row?.issue,1800),date:clean(row?.date,100)};
 await client.query("INSERT INTO trl_import_review(source_id,reason,source) VALUES($1,$2,$3::jsonb) ON CONFLICT(source_id) DO UPDATE SET reason=EXCLUDED.reason,source=EXCLUDED.source,received_at=now()",[id,reason,JSON.stringify(minimal)]);
}
async function importRow(client,row){
 const n=normalise(row);
 if(n.ignored)return 'ignored';
 if(n.error){await review(client,n.id||clean(row?.id,130)||'missing-id',n.error,row);return 'review';}
 const e=n.event;
 await client.query('SELECT pg_advisory_xact_lock(hashtext($1))',[e.plate]);
 const oldEvent=(await client.query('SELECT * FROM trl_breaches WHERE source_id=$1 FOR UPDATE',[e.sourceReportId])).rows[0];
 if(oldEvent&&oldEvent.plate!==e.plate){await review(client,e.sourceReportId,'Registration changed; supervisor review required',row);return 'review';}
 if((await client.query('SELECT plate FROM tombstones WHERE plate=$1',[e.plate])).rows.length){await review(client,e.sourceReportId,'Deleted vehicle; supervisor review required',row);return 'review';}
 if(oldEvent?.payload_hash===n.hash)return 'duplicate';
 const old=(await client.query('SELECT details,revision FROM vehicles WHERE plate=$1 FOR UPDATE',[e.plate])).rows[0];
 const details={...(old?.details||{})};
 for(const k of ['make','model','colour','description'])if(!details[k]&&e[k])details[k]=e[k];
 if(!old)details.source='SilverTrack';
 const changed=!old||JSON.stringify(old.details)!==JSON.stringify(details);
 if(changed){
  if(old)await client.query('UPDATE vehicles SET details=$2::jsonb,revision=revision+1,updated_at=now() WHERE plate=$1',[e.plate,JSON.stringify(details)]);
  else await client.query('INSERT INTO vehicles(plate,details) VALUES($1,$2::jsonb)',[e.plate,JSON.stringify(details)]);
  await client.query('INSERT INTO corrections(plate,actor,action,old,new,reason) VALUES($1,$2,$3,$4::jsonb,$5::jsonb,$6)',[e.plate,'SILVERTRACK_COLLECTOR',old?'IMPORT_UPDATE':'IMPORT_CREATE',JSON.stringify(old?.details??null),JSON.stringify(details),'SilverTrack report '+e.sourceReportId]);
 }
 const revision=Number(oldEvent?.revision||0)+1;
 await client.query("INSERT INTO trl_breaches(source_id,plate,event,payload_hash) VALUES($1,$2,$3::jsonb,$4) ON CONFLICT(source_id) DO UPDATE SET event=EXCLUDED.event,payload_hash=EXCLUDED.payload_hash,revision=trl_breaches.revision+1,received_at=now()",[e.sourceReportId,e.plate,JSON.stringify(e),n.hash]);
 await client.query('INSERT INTO trl_breach_audit(source_id,event,revision) VALUES($1,$2::jsonb,$3)',[e.sourceReportId,JSON.stringify(e),revision]);
 await client.query('DELETE FROM trl_import_review WHERE source_id=$1',[e.sourceReportId]);
 return oldEvent?'updated':'created';
}
async function transaction(pool,fn){const c=await pool.connect();try{await c.query('BEGIN');const answer=await fn(c);await c.query('COMMIT');return answer;}catch(e){await c.query('ROLLBACK');throw e;}finally{c.release();}}
async function snapshot(pool,kind){const r=(await pool.query('SELECT * FROM trl_snapshots WHERE kind=$1',[kind])).rows[0];return {payload:r.payload,generation:Number(r.generation),receivedAt:Number(r.received_at),central:r.central};}
async function publishActivity(pool,rows){
 return transaction(pool,async c=>{
  await c.query("SELECT kind FROM trl_snapshots WHERE kind='activity' FOR UPDATE");
  const counts={created:0,updated:0,duplicate:0,ignored:0,review:0};
  for(const r of rows)counts[await importRow(c,r)]++;
  const publicRows=rows.map(r=>({id:clean(r.id,130),guard:clean(r.guard,90),property:clean(r.property,260),issue:clean(r.issue,1800),date:clean(r.date,100)}));
  const answer=(await c.query("UPDATE trl_snapshots SET payload=$1::jsonb,generation=generation+1,received_at=GREATEST(received_at+1,$2) WHERE kind='activity' RETURNING generation,received_at",[JSON.stringify(publicRows),Date.now()])).rows[0];
  return {ok:true,accepted:rows.length,generation:Number(answer.generation),receivedAt:Number(answer.received_at),imports:counts};
 });
}
async function publishCheckpoints(pool,rows,generation,central){
 return transaction(pool,async c=>{
  const old=(await c.query("SELECT * FROM trl_snapshots WHERE kind='checkpoints' FOR UPDATE")).rows[0];
  if((!central&&old.central)||(central&&generation!==Number(old.generation)))return {conflict:true,error:old.central&&!central?'Mother Base manages checkpoints':'revision conflict'};
  const answer=(await c.query("UPDATE trl_snapshots SET payload=$1::jsonb,generation=generation+1,received_at=GREATEST(received_at+1,$2),central=$3 WHERE kind='checkpoints' RETURNING generation,received_at",[JSON.stringify(rows),Date.now(),central])).rows[0];
  return {ok:true,accepted:rows.length,generation:Number(answer.generation),receivedAt:Number(answer.received_at),central};
 });
}
module.exports={schema,importRow,transaction,snapshot,publishActivity,publishCheckpoints};
