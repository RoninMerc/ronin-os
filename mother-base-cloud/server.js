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
  const known=['/health','/v1/activity','/v1/vehicle-reports','/v1/admin/activity','/v1/checkpoints','/v1/admin/checkpoints','/v1/checkpoints/recover'];
  if(!known.includes(path))return fleetServer.emit('request',req,res);
  (async()=>{
   if(path==='/health'&&req.method==='GET'){await pool.query('SELECT 1');const a=await db.snapshot(pool,'activity');return send(res,200,{ok:true,persistent:true,service:'trl-mother-base-cloud',masterOnline:Date.now()-a.receivedAt<90000});}
   const admin=eq(req.headers['x-ronin-master-key'],master),isReader=eq(req.headers['x-ronin-reader-key'],reader);
   if(path.startsWith('/v1/admin/')&&!admin)return send(res,401,{ok:false});
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
    return send(res,200,{ok:true,imports:result});
   }
   if(req.method==='POST'&&path==='/v1/activity'){
    if(!admin)return send(res,401,{ok:false});
    const b=await body(req,2500000);
    if(!Array.isArray(b.rows)||b.rows.length>180)return send(res,400,{ok:false,error:'up to 180 rows required'});
    const rows=b.rows.filter(x=>x&&typeof x==='object'&&!Array.isArray(x)&&clean(x.id,130)&&clean(x.guard,90)&&clean(x.issue,1800));
    return send(res,200,await db.publishActivity(pool,rows));
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
async function start(){
 if(!process.env.DATABASE_URL)throw Error('DATABASE_URL required; no memory fallback');
 const pool=new Pool({connectionString:process.env.DATABASE_URL,max:5,connectionTimeoutMillis:8000});
 await migrate(pool);
 createApp(pool).listen(process.env.PORT||10000,'0.0.0.0',()=>console.log('TRL Mother Base persistent cloud ready'));
}
if(require.main===module)start().catch(()=>{console.error('Database or relay configuration unavailable; refusing insecure start');process.exit(1);});
module.exports={createApp,migrate};
