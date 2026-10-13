'use strict';
const http=require('node:http'),crypto=require('node:crypto'),{Pool}=require('pg');
const fleet=require('./fleet'),db=require('./database'),{checkpoints,clean}=require('./normalise');
function eq(a,b){const x=Buffer.from(String(a||'')),y=Buffer.from(String(b||''));return y.length>=32&&x.length===y.length&&crypto.timingSafeEqual(x,y);}
function send(res,status,value){res.writeHead(status,{'content-type':'application/json','cache-control':'no-store','x-content-type-options':'nosniff'});res.end(JSON.stringify(value));}
async function body(req,max){const parts=[];let size=0;for await(const b of req){size+=b.length;if(size>max){const e=Error('payload too large');e.status=413;throw e;}parts.push(b);}try{return JSON.parse(Buffer.concat(parts).toString('utf8'));}catch{const e=Error('invalid JSON');e.status=400;throw e;}}
function createApp(pool,opts={}){
 const master=opts.masterKey||process.env.MASTER_FEED_KEY||'',reader=opts.readerKey||process.env.READER_FEED_KEY||'';
 if(master.length<32||reader.length<32||master===reader)throw Error('Two distinct feed keys required');
 const fleetServer=fleet.createApp(pool,{masterKey:opts.fleetKey||process.env.TRL_MASTER_KEY,encryptionKey:opts.encryptionKey||process.env.TRL_EVIDENCE_KEY});
 const server=http.createServer((req,res)=>{
  const path=new URL(req.url,'http://local').pathname;
  const known=['/health','/v1/activity','/v1/vehicle-reports','/v1/admin/activity','/v1/checkpoints','/v1/admin/checkpoints','/v1/checkpoints/recover','/v1/report-evidence','/v1/training-evidence'];
  const trainingImage=/^\/v1\/training-evidence\/[0-9a-f-]{36}\.jpg$/i.test(path);
  if(!known.includes(path)&&!trainingImage)return fleetServer.emit('request',req,res);
  // Fixed route labels and bounded counts only: never log report text, plates, keys or cookies.
  let ingest=null;
  if(req.method==='POST'&&(path==='/v1/activity'||path==='/v1/vehicle-reports')){
   ingest={};
   res.once('finish',()=>{
    const event={event:path==='/v1/activity'?'silvertrack_activity':'silvertrack_vehicle_reports',status:res.statusCode};
    if(res.statusCode===200)Object.assign(event,ingest);
    try{(opts.ingestLog||console.info)(JSON.stringify(event));}catch{}
   });
  }
  (async()=>{
   if(path==='/health'&&req.method==='GET'){await pool.query('SELECT 1');const a=await db.snapshot(pool,'activity');return send(res,200,{ok:true,persistent:true,service:'trl-mother-base-cloud',masterOnline:Date.now()-a.receivedAt<90000});}
   const admin=eq(req.headers['x-ronin-master-key'],master),isReader=eq(req.headers['x-ronin-reader-key'],reader);
   if(path.startsWith('/v1/admin/')&&!admin)return send(res,401,{ok:false});
   if(path==='/v1/report-evidence'&&req.method==='POST'){
    res.once('finish',()=>{try{(opts.ingestLog||console.info)(JSON.stringify({event:'silvertrack_report_photo',status:res.statusCode}));}catch{}});
    if(!admin)return send(res,401,{ok:false});
    const b=await body(req,2900000);
    try{return send(res,200,await require('./report-evidence').store(pool,Buffer.from(opts.encryptionKey||process.env.TRL_EVIDENCE_KEY,'hex'),b));}
    catch(e){return send(res,e.status||400,{ok:false,error:e.status?e.message:'invalid report image'});}
   }
   if(path==='/v1/training-evidence'||trainingImage){
    if(!eq(req.headers['x-trl-master-key'],opts.fleetKey||process.env.TRL_MASTER_KEY))return send(res,403,{ok:false});
    if(req.method!=='GET')return send(res,405,{ok:false});
    const evidence=require('./report-evidence');
    if(!trainingImage)return send(res,200,await evidence.manifest(pool,new URL(req.url,'http://local').searchParams.get('after')||''));
    const image=await evidence.image(pool,Buffer.from(opts.encryptionKey||process.env.TRL_EVIDENCE_KEY,'hex'),path.slice('/v1/training-evidence/'.length,-4));
    if(!image)return send(res,404,{ok:false});
    res.writeHead(200,{'content-type':'image/jpeg','cache-control':'no-store','x-content-type-options':'nosniff'});return res.end(image);
   }
   if(req.method==='GET'){
    if(!admin&&!isReader)return send(res,401,{ok:false});
    if(path.endsWith('/activity')){
     const a=await db.snapshot(pool,'activity'),ageMs=a.receivedAt?Date.now()-a.receivedAt:null,masterOnline=ageMs!==null&&ageMs<90000;
     return send(res,200,{ok:true,masterOnline,receivedAt:a.receivedAt,ageMs,generation:a.generation,rows:admin||masterOnline?a.payload:[]});
    }
    const c=await db.snapshot(pool,'checkpoints');return send(res,200,{ok:true,receivedAt:c.receivedAt,generation:c.generation,central:c.central,checkpoints:c.payload});
   }
   if(req.method==='POST'&&path==='/v1/vehicle-reports'){
    if(!admin)return send(res,401,{ok:false});
    const b=await body(req,2500000);
    if(!Array.isArray(b.rows)||b.rows.length>30||b.rows.some(x=>!x||typeof x!=='object'||Array.isArray(x)||!clean(x.sourceReportId,130)))return send(res,400,{ok:false,error:'stable source report IDs required'});
    const result=await db.transaction(pool,async c=>{
     await c.query("SELECT kind FROM trl_snapshots WHERE kind='activity' FOR UPDATE");
     const imports={created:0,updated:0,duplicate:0,ignored:0,review:0};
     for(const row of b.rows)imports[await db.importRow(c,row)]++;
     return imports;
    });
    Object.assign(ingest,{rows:b.rows.length,imports:result});
    return send(res,200,{ok:true,imports:result});
   }
   if(req.method==='POST'&&path==='/v1/activity'){
    if(!admin)return send(res,401,{ok:false});
    const b=await body(req,2500000);
    if(!Array.isArray(b.rows)||b.rows.length>180)return send(res,400,{ok:false,error:'up to 180 rows required'});
    const rows=b.rows.filter(x=>x&&typeof x==='object'&&!Array.isArray(x)&&clean(x.id,130)&&clean(x.guard,90)&&clean(x.issue,1800));
    const result=await db.publishActivity(pool,rows);
    Object.assign(ingest,{rows:rows.length,parkingRows:rows.filter(x=>/\b(?:parking breach|vehicle breach|parking violation)\b/i.test(x.issue)).length,imports:result.imports});
    return send(res,200,result);
   }
   if(path==='/v1/checkpoints/recover')return send(res,403,{ok:false,error:'Checkpoint recovery requires the administrator; use Mother Base import'});
   const central=path==='/v1/admin/checkpoints'&&req.method==='PUT';
   if(central||(path==='/v1/checkpoints'&&req.method==='POST')){
    if(!admin)return send(res,401,{ok:false});
    const b=await body(req,12500000);let rows;try{rows=checkpoints(b.checkpoints);}catch(e){return send(res,400,{ok:false,error:e.message});}
    if(central&&!Number.isSafeInteger(b.generation))return send(res,400,{ok:false,error:'generation required'});
    const result=await db.publishCheckpoints(pool,rows,b.generation,central);return send(res,result.conflict?409:200,result);
   }
   return send(res,405,{ok:false});
  })().catch(e=>{if(!res.headersSent)send(res,e.status||503,{ok:false,error:e.status?e.message:'persistent relay unavailable'});else res.end();});
 });
 server.headersTimeout=15000;server.requestTimeout=30000;return server;
}
async function migrate(pool){for(const stmt of [...fleet.statements,...db.schema])await pool.query(stmt);}
let startupStage='configuration';
async function start(){
 const missing=[];
 if(!process.env.DATABASE_URL)missing.push('DATABASE_URL');
 if((process.env.MASTER_FEED_KEY||'').length<32)missing.push('MASTER_FEED_KEY (at least 32 characters)');
 if((process.env.READER_FEED_KEY||'').length<32)missing.push('READER_FEED_KEY (at least 32 characters)');
 if(process.env.MASTER_FEED_KEY===process.env.READER_FEED_KEY)missing.push('feed keys must differ');
 if((process.env.TRL_MASTER_KEY||'').length<32)missing.push('TRL_MASTER_KEY (at least 32 characters)');
 if(!/^[0-9a-fA-F]{64}$/.test(process.env.TRL_EVIDENCE_KEY||''))missing.push('TRL_EVIDENCE_KEY (64 hexadecimal characters)');
 if(missing.length){console.error('Invalid or missing environment settings: '+missing.join(', '));throw Error('configuration');}
 startupStage='database connection/migration';
 const pool=new Pool({connectionString:process.env.DATABASE_URL,max:5,connectionTimeoutMillis:8000});
 await migrate(pool);
 startupStage='relay setup';
 createApp(pool).listen(process.env.PORT||10000,'0.0.0.0',()=>console.log('TRL Mother Base persistent cloud ready'));
}
if(require.main===module)start().catch(e=>{const code=String(e.code||'');console.error('Startup failed at '+startupStage+(/^[A-Z0-9_]{2,24}$/.test(code)?' (code '+code+')':'')+'; no credentials logged');process.exit(1);});
module.exports={createApp,migrate};
