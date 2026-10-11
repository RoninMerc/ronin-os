'use strict';
/* Fail-closed, PostgreSQL-backed fleet and evidence service. No public photo endpoints. */
const http=require('node:http'),crypto=require('node:crypto'),{Pool}=require('pg');
const {URL}=require('node:url');
const MASTER_KEY=process.env.TRL_MASTER_KEY||'',ENC_HEX=process.env.TRL_EVIDENCE_KEY||'',DATABASE_URL=process.env.DATABASE_URL||'';
const hash=v=>crypto.createHash('sha256').update(String(v)).digest('hex');
const eq=(a,b)=>{const x=Buffer.from(String(a||'')),y=Buffer.from(String(b||''));return x.length===y.length&&crypto.timingSafeEqual(x,y);};
const plate=v=>String(v||'').toUpperCase().replace(/[\s-]/g,'');
const validPlate=v=>/^[A-Z0-9]{3,10}$/.test(plate(v));
const clean=(v,n)=>typeof v==='string'?v.trim().replace(/[\u0000-\u001f\u007f]/g,' ').slice(0,n):'';
function send(res,code,obj){res.writeHead(code,{'content-type':'application/json','cache-control':'no-store','x-content-type-options':'nosniff'});res.end(JSON.stringify(obj));}
function enc(buf,key){const iv=crypto.randomBytes(12),c=crypto.createCipheriv('aes-256-gcm',key,iv);return {iv,data:Buffer.concat([c.update(buf),c.final()]),tag:c.getAuthTag()};}
function dec(row,key){const c=crypto.createDecipheriv('aes-256-gcm',key,row.iv);c.setAuthTag(row.tag);return Buffer.concat([c.update(row.ciphertext),c.final()]);}
const statements=[
"CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY,role TEXT NOT NULL,token_sha TEXT UNIQUE NOT NULL,revoked BOOLEAN NOT NULL DEFAULT FALSE,created_at TIMESTAMPTZ DEFAULT now())",
"CREATE TABLE IF NOT EXISTS invitations(code_sha TEXT PRIMARY KEY,role TEXT NOT NULL,expires_at TIMESTAMPTZ NOT NULL,used BOOLEAN DEFAULT FALSE)",
"CREATE TABLE IF NOT EXISTS vehicles(plate TEXT PRIMARY KEY,details JSONB NOT NULL,revision BIGINT NOT NULL DEFAULT 1,updated_at TIMESTAMPTZ NOT NULL DEFAULT now())",
"CREATE TABLE IF NOT EXISTS tombstones(plate TEXT PRIMARY KEY,deleted_at TIMESTAMPTZ DEFAULT now(),reason TEXT NOT NULL)",
"CREATE TABLE IF NOT EXISTS corrections(id BIGSERIAL PRIMARY KEY,plate TEXT NOT NULL,actor TEXT NOT NULL,action TEXT NOT NULL,old JSONB,new JSONB,reason TEXT NOT NULL,recorded_at TIMESTAMPTZ DEFAULT now())",
"CREATE TABLE IF NOT EXISTS evidence(id UUID PRIMARY KEY,plate TEXT NOT NULL,device TEXT NOT NULL,iv BYTEA NOT NULL,ciphertext BYTEA NOT NULL,tag BYTEA NOT NULL,meta JSONB NOT NULL,sha256 TEXT NOT NULL,stored_at TIMESTAMPTZ DEFAULT now())"
];
async function readBody(req,limit){
 const chunks=[];let size=0;
 for await(const c of req){size+=c.length;if(size>limit)throw Error('payload too large');chunks.push(c);}
 return JSON.parse(Buffer.concat(chunks).toString('utf8')||'{}');
}
function createApp(pool,opts={}){
 const key=Buffer.from(opts.encryptionKey||ENC_HEX,'hex');
 const master=opts.masterKey||MASTER_KEY;
 if(key.length!==32||master.length<32)throw Error('Secret configuration missing');
 return http.createServer((req,res)=>{
   (async()=>{
    const route=new URL(req.url,'http://local').pathname,method=req.method;
    if(route==='/health'&&method==='GET'){
       try{await pool.query('SELECT 1');return send(res,200,{ok:true,persistent:true});}
       catch{return send(res,503,{ok:false,persistent:false});}
    }
    if(route==='/v1/pair'&&method==='POST'){
       const b=await readBody(req,2000),id=clean(b.deviceId,90),code=String(b.code||'');
       if(!/^[a-zA-Z0-9_-]{2,90}$/.test(id)||code.length<25)return send(res,400,{ok:false});
       const client=await pool.connect();
       try{
         await client.query('BEGIN');
         const invite=(await client.query("SELECT role FROM invitations WHERE code_sha=$1 AND used=FALSE AND expires_at>now() FOR UPDATE",[hash(code)])).rows[0];
         if(!invite){await client.query('ROLLBACK');return send(res,403,{ok:false,error:'invalid or expired pairing code'});}
         const token=crypto.randomBytes(32).toString('base64url');
         await client.query('INSERT INTO devices(id,role,token_sha) VALUES($1,$2,$3) ON CONFLICT(id) DO UPDATE SET role=EXCLUDED.role,token_sha=EXCLUDED.token_sha,revoked=FALSE',[id,invite.role,hash(token)]);
         await client.query('UPDATE invitations SET used=TRUE WHERE code_sha=$1',[hash(code)]);
         await client.query('COMMIT');
         return send(res,200,{ok:true,deviceId:id,role:invite.role,token});
       }catch(e){await client.query('ROLLBACK');throw e;}finally{client.release();}
    }
    let actor='MASTER',role='MASTER';
    if(!eq(req.headers['x-trl-master-key'],master)){
       const token=String(req.headers['x-trl-device-token']||'');
       if(token.length<32||token.length>128)return send(res,401,{ok:false});
       const row=(await pool.query('SELECT id,role FROM devices WHERE token_sha=$1 AND revoked=FALSE',[hash(token)])).rows[0];
       if(!row)return send(res,401,{ok:false});
       actor=row.id;role=row.role;
    }
    if(route==='/v1/invite'&&method==='POST'){
       if(role!=='MASTER')return send(res,403,{ok:false});
       const b=await readBody(req,1000),roleName=clean(b.role,8);
       if(!['P1','P2','P3'].includes(roleName))return send(res,400,{ok:false});
       const code=crypto.randomBytes(24).toString('base64url');
       await pool.query("INSERT INTO invitations(code_sha,role,expires_at) VALUES($1,$2,now()+interval '10 minutes')",[hash(code),roleName]);
       return send(res,200,{ok:true,code,role:roleName,expiresInSeconds:600});
    }
    if(route==='/v1/vehicles'&&method==='GET'){
       const data=(await pool.query('SELECT plate,details,revision,updated_at FROM vehicles ORDER BY updated_at DESC LIMIT 10000')).rows;
       const deleted=(await pool.query('SELECT plate,deleted_at FROM tombstones ORDER BY deleted_at DESC LIMIT 10000')).rows;
       return send(res,200,{ok:true,vehicles:data,tombstones:deleted});
    }
    if(route==='/v1/vehicles'&&method==='PUT'){
       const b=await readBody(req,12000),p=plate(b.plate),rev=Number(b.revision||0),reason=clean(b.reason,500);
       if(!validPlate(p)||!b.details||typeof b.details!=='object'||Array.isArray(b.details)||JSON.stringify(b.details).length>3000)return send(res,400,{ok:false});
       const client=await pool.connect();
       try{
          await client.query('BEGIN');
          const gone=(await client.query('SELECT plate FROM tombstones WHERE plate=$1',[p])).rows[0];
          if(gone){await client.query('ROLLBACK');return send(res,409,{ok:false,error:'supervisor review required for deleted record'});}
          const old=(await client.query('SELECT details,revision FROM vehicles WHERE plate=$1 FOR UPDATE',[p])).rows[0];
          if(rev!==Number(old?.revision||0)){await client.query('ROLLBACK');return send(res,409,{ok:false,error:'revision conflict'});}
          if(old&&reason.length<10){await client.query('ROLLBACK');return send(res,400,{ok:false,error:'correction reason required'});}
          const state=JSON.stringify(b.details);
          if(old)await client.query('UPDATE vehicles SET details=$2::jsonb,revision=revision+1,updated_at=now() WHERE plate=$1',[p,state]);
          else await client.query('INSERT INTO vehicles(plate,details) VALUES($1,$2::jsonb)',[p,state]);
          await client.query('INSERT INTO corrections(plate,actor,action,old,new,reason) VALUES($1,$2,$3,$4::jsonb,$5::jsonb,$6)',[p,actor,old?'CORRECTION':'CREATE',JSON.stringify(old?.details??null),state,reason||'New field observation']);
          await client.query('COMMIT');return send(res,200,{ok:true,revision:rev+1});
       }catch(e){await client.query('ROLLBACK');throw e;}finally{client.release();}
    }
    if(route==='/v1/vehicles/delete'&&method==='POST'){
       if(role!=='MASTER')return send(res,403,{ok:false});
       const b=await readBody(req,3000),p=plate(b.plate),reason=clean(b.reason,500);
       if(!validPlate(p)||reason.length<10)return send(res,400,{ok:false});
       const client=await pool.connect();
       try{
          await client.query('BEGIN');
          const old=(await client.query('SELECT details FROM vehicles WHERE plate=$1 FOR UPDATE',[p])).rows[0];
          await client.query('INSERT INTO tombstones(plate,reason) VALUES($1,$2) ON CONFLICT(plate) DO UPDATE SET deleted_at=now(),reason=EXCLUDED.reason',[p,reason]);
          await client.query('DELETE FROM vehicles WHERE plate=$1',[p]);
          await client.query('INSERT INTO corrections(plate,actor,action,old,reason) VALUES($1,$2,$3,$4::jsonb,$5)',[p,actor,'DELETE',JSON.stringify(old?.details??null),reason]);
          await client.query('COMMIT');return send(res,200,{ok:true});
       }catch(e){await client.query('ROLLBACK');throw e;}finally{client.release();}
    }
    if(route==='/v1/evidence'&&method==='POST'){
       const b=await readBody(req,2900000),p=plate(b.plate),str=String(b.jpegBase64||'');
       if(b.explicitAuthorisation!==true||!validPlate(p)||str.length>2800000||!/^[A-Za-z0-9+/=]+$/.test(str))return send(res,400,{ok:false});
       const file=Buffer.from(str,'base64');
       if(file.length<50||file.length>2000000||file[0]!==0xff||file[1]!==0xd8)return send(res,400,{ok:false,error:'invalid image'});
       const packet=enc(file,key),id=crypto.randomUUID();
       const meta={lighting:clean(b.lighting,60),capturedAt:clean(b.capturedAt,40),note:clean(b.note,200)};
       await pool.query('INSERT INTO evidence(id,plate,device,iv,ciphertext,tag,meta,sha256) VALUES($1,$2,$3,$4,$5,$6,$7::jsonb,$8)',[id,p,actor,packet.iv,packet.data,packet.tag,JSON.stringify(meta),hash(file)]);
       return send(res,200,{ok:true,id,sha256:hash(file)});
    }
    if(method==='GET'&&route.startsWith('/v1/evidence/')){
       const id=route.slice(13);
       if(!/^[0-9a-f-]{36}$/i.test(id))return send(res,400,{ok:false});
       const row=(await pool.query('SELECT device,iv,ciphertext,tag FROM evidence WHERE id=$1',[id])).rows[0];
       if(!row)return send(res,404,{ok:false});
       if(role!=='MASTER'&&actor!==row.device)return send(res,403,{ok:false,error:'not authorised for this evidence'});
       const image=dec(row,key);
       res.writeHead(200,{'content-type':'image/jpeg','cache-control':'no-store','x-content-type-options':'nosniff'});return res.end(image);
    }
    return send(res,404,{ok:false,error:'unknown route'});
   })().catch(()=>{if(!res.headersSent)send(res,503,{ok:false,error:'request unavailable'});else res.end();});
 });
}
async function start(){
 if(!DATABASE_URL||!MASTER_KEY||!ENC_HEX)throw Error('DB or encryption configuration missing');
 const pool=new Pool({connectionString:DATABASE_URL,connectionTimeoutMillis:8000,max:5});
 for(const stmt of statements)await pool.query(stmt);
 createApp(pool).listen(process.env.PORT||10000,'0.0.0.0',()=>console.log('TRL protected fleet ready'));
}
if(require.main===module)start().catch(()=>{console.error('No persistent database; refusing insecure start');process.exit(1);});
module.exports={plate,validPlate,enc,dec,createApp,statements};
