'use strict';
const http=require('node:http'),crypto=require('node:crypto');
const {URL}=require('node:url');
const master=String(process.env.MASTER_FEED_KEY||'');
const reader=String(process.env.READER_FEED_KEY||'');
if(master.length<32||reader.length<32||master===reader)throw Error('Two distinct feed keys of at least 32 characters are required.');
let snapshot={rows:[],receivedAt:0,generation:0};
const liveMs=90000;
function auth(got,key){const a=Buffer.from(String(got||'')),b=Buffer.from(key);return a.length===b.length&&crypto.timingSafeEqual(a,b);}
function send(res,status,data){res.writeHead(status,{'content-type':'application/json; charset=utf-8','cache-control':'no-store','x-content-type-options':'nosniff','referrer-policy':'no-referrer'});res.end(JSON.stringify(data));}
function clean(v,n){return typeof v==='string'?v.trim().replace(/[\u0000-\u001f\u007f]/g,' ').slice(0,n):'';}
function scrub(rows){
 if(!Array.isArray(rows)||rows.length>180)throw Error('bad rows');
 const seen=new Set(),out=[];
 for(const a of rows){if(!a||typeof a!=='object'||Array.isArray(a))continue;
 const r={id:clean(a.id,130),guard:clean(a.guard,90),property:clean(a.property,260),issue:clean(a.issue,1800),date:clean(a.date,100)};
 if(!r.id||!r.guard||!r.issue||seen.has(r.id))continue;
 seen.add(r.id);out.push(r);
 }return out;
}
function createApp(){return http.createServer((req,res)=>{
 const path=new URL(req.url,'http://localhost').pathname;
 if(req.method==='GET'&&path==='/health')return send(res,200,{ok:true,service:'ronin-silvertrack-master-feed',masterOnline:Date.now()-snapshot.receivedAt<liveMs});
 if(req.method==='GET'&&path==='/v1/activity'){
   if(!auth(req.headers['x-ronin-reader-key'],reader))return send(res,401,{ok:false,error:'unauthorised'});
   const age=snapshot.receivedAt?Date.now()-snapshot.receivedAt:null,online=age!==null&&age<liveMs;
   return send(res,200,{ok:true,masterOnline:online,receivedAt:snapshot.receivedAt,ageMs:age,generation:snapshot.generation,rows:online?snapshot.rows:[]});
 }
 if(req.method==='POST'&&path==='/v1/activity'){
   if(!auth(req.headers['x-ronin-master-key'],master))return send(res,401,{ok:false,error:'unauthorised'});
   let size=0,tooLarge=false,chunks=[];
   req.on('data',v=>{size+=v.length;if(size>160000){tooLarge=true;chunks=[];}else if(!tooLarge)chunks.push(v);});
   req.on('end',()=>{
      if(tooLarge)return send(res,413,{ok:false,error:'payload too large'});
      try{const body=JSON.parse(Buffer.concat(chunks).toString('utf8'));
      const rows=scrub(body.rows);
      snapshot={rows,receivedAt:Date.now(),generation:snapshot.generation+1};
      return send(res,200,{ok:true,receivedAt:snapshot.receivedAt,generation:snapshot.generation,accepted:rows.length});
      }catch(e){return send(res,400,{ok:false,error:'invalid activity snapshot'});}
   });return;
 }
 return send(res,404,{ok:false,error:'not found'});
});}
if(require.main===module)createApp().listen(process.env.PORT||10000,'0.0.0.0',()=>console.log('Ronin SilverTrack feed ready'));
module.exports={createApp,scrub};
