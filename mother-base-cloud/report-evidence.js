'use strict';
const crypto=require('node:crypto'),{enc,dec,validPlate,plate}=require('./fleet');
const {clean}=require('./normalise');
function decode(b){
 if(!b||b.trainingRequested!==true||!clean(b.sourceReportId,130)||!validPlate(b.plate))throw Error('source report, registration and training request required');
 const s=String(b.jpegBase64||'');if(s.length>2800000||!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(s))throw Error('invalid image encoding');
 const image=Buffer.from(s,'base64');
 if(image.length<50||image.length>2000000||image[0]!==255||image[1]!==216||image[2]!==255||image.at(-2)!==255||image.at(-1)!==217)throw Error('JPEG below 2 MB required');
 return {image,source:clean(b.sourceReportId,130),plate:plate(b.plate),sha:crypto.createHash('sha256').update(image).digest('hex'),filename:clean(b.filename,120).replace(/[^a-zA-Z0-9._ -]/g,'_')};
}
async function store(pool,key,b){
 const data=decode(b),c=await pool.connect();
 try{
  await c.query('BEGIN');
  await c.query('SELECT pg_advisory_xact_lock(hashtext($1))',[data.source+':'+data.sha]);
  const report=(await c.query('SELECT plate,event FROM trl_breaches WHERE source_id=$1',[data.source])).rows[0];
  if(!report||report.plate!==data.plate){const e=Error('matching imported source report required');e.status=409;throw e;}
  const old=(await c.query("SELECT id FROM evidence WHERE device='SILVERTRACK_COLLECTOR' AND meta->>'sourceReportId'=$1 AND sha256=$2",[data.source,data.sha])).rows[0];
  if(old){await c.query('COMMIT');return {ok:true,id:old.id,duplicate:true,sha256:data.sha};}
  const packet=enc(data.image,key),id=crypto.randomUUID();
  const meta={source:'SilverTrack',sourceReportId:data.source,trainingRequested:true,filename:data.filename,contentType:'image/jpeg',sourceDate:report.event.sourceDate||'',warningLevel:report.event.warningLevel??null,property:report.event.property||'',plateLabelProvenance:'report transcription; verify before training'};
  await c.query('INSERT INTO evidence(id,plate,device,iv,ciphertext,tag,meta,sha256) VALUES($1,$2,$3,$4,$5,$6,$7::jsonb,$8)',[id,data.plate,'SILVERTRACK_COLLECTOR',packet.iv,packet.data,packet.tag,JSON.stringify(meta),data.sha]);
  await c.query('COMMIT');return {ok:true,id,duplicate:false,sha256:data.sha};
 }catch(e){await c.query('ROLLBACK');throw e;}finally{c.release();}
}
async function manifest(pool,after=''){
 if(after&&!/^[0-9a-f-]{36}$/i.test(after)){const e=Error('invalid cursor');e.status=400;throw e;}
 const rows=(await pool.query("SELECT id,plate,meta,sha256,stored_at FROM evidence WHERE device='SILVERTRACK_COLLECTOR' AND ($1='' OR id::text>$1) ORDER BY id::text LIMIT 501",[after])).rows;
 const photos=rows.slice(0,500).map(x=>({...x,downloadPath:'/v1/training-evidence/'+x.id+'.jpg'}));
 return {ok:true,photos,nextCursor:rows.length>500?photos.at(-1).id:null};
}
async function image(pool,key,id){
 if(!/^[0-9a-f-]{36}$/i.test(id))return null;
 const row=(await pool.query("SELECT iv,ciphertext,tag FROM evidence WHERE id=$1 AND device='SILVERTRACK_COLLECTOR'",[id])).rows[0];
 return row?dec(row,key):null;
}
module.exports={decode,store,manifest,image};
