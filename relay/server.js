const http = require('http');
const { URL } = require('url');
const PORT = process.env.PORT || 10000;
const API_KEY = process.env.RONIN_PATROL_KEY || '';
const fleet = new Map();
let authBundle = { bundle:'', updatedAt:0, source:'' };

function send(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, {
    'content-type':'application/json; charset=utf-8',
    'cache-control':'no-store',
    'access-control-allow-origin':'*',
    'access-control-allow-headers':'content-type,x-ronin-key',
    'access-control-allow-methods':'GET,POST,OPTIONS'
  });
  res.end(body);
}
function okKey(req) { return API_KEY && req.headers['x-ronin-key'] === API_KEY; }
function cleanHistory(v) {
  if (!Array.isArray(v)) return [];
  return v.slice(-80).map(x => ({
    state: x && (x.state === 'MOVING' || x.state === 'STATIONARY') ? x.state : 'UNKNOWN',
    start: Number(x && x.start) || 0,
    end: Number(x && x.end) || 0,
    durationMs: Math.max(0, Number(x && x.durationMs) || 0)
  }));
}
http.createServer((req,res)=>{
  if (req.method === 'OPTIONS') return send(res,200,{ok:true});
  const u = new URL(req.url,'http://localhost');
  if (u.pathname === '/health') return send(res,200,{ok:true,service:'ronin-patrol-link-relay'});
  if (!okKey(req)) return send(res,401,{ok:false,error:'unauthorised'});
  if (req.method === 'POST' && u.pathname === '/api/update') {
    let raw='';
    req.on('data',d=>{ raw+=d; if(raw.length>200000) req.destroy(); });
    req.on('end',()=>{
      try {
        const b=JSON.parse(raw||'{}');
        const id=String(b.patrolId||'').toUpperCase();
        if(!['P1','P2','P3'].includes(id)) return send(res,400,{ok:false,error:'invalid patrol'});
        const now=Date.now();
        const item = {
          patrolId:id, deviceLabel:String(b.deviceLabel||id).slice(0,64), onDuty:b.onDuty!==false,
          lat:Number(b.lat), lon:Number(b.lon), accuracy:Number(b.accuracy), speed:Number(b.speed),
          provider:String(b.provider||'').slice(0,32), fixTime:Number(b.fixTime)||now, receivedAt:now,
          state:['MOVING','STATIONARY','UNKNOWN'].includes(String(b.state)) ? String(b.state) : 'UNKNOWN',
          stateSince:Number(b.stateSince)||now, history:cleanHistory(b.history),
          versionCode:Number(b.versionCode)||0, versionName:String(b.versionName||'').slice(0,32), deviceMode:String(b.deviceMode||'USER').slice(0,16)
        };
        if(!Number.isFinite(item.lat)||!Number.isFinite(item.lon)) return send(res,400,{ok:false,error:'invalid coordinates'});
        if (item.deviceMode === 'MASTER' && typeof b.authBundle === 'string' && b.authBundle.length > 20 && b.authBundle.length < 20000) {
          authBundle = { bundle:b.authBundle, updatedAt:now, source:id };
        }
        fleet.set(id,item); return send(res,200,{ok:true,receivedAt:now});
      } catch(e) { return send(res,400,{ok:false,error:'bad json'}); }
    }); return;
  }
  if (req.method === 'POST' && u.pathname === '/api/off-duty') {
    let raw=''; req.on('data',d=>raw+=d); req.on('end',()=>{
      try{
        const b=JSON.parse(raw||'{}'), id=String(b.patrolId||'').toUpperCase();
        if(!['P1','P2','P3'].includes(id)) return send(res,400,{ok:false,error:'invalid patrol'});
        const prev=fleet.get(id)||{patrolId:id,deviceLabel:id,lat:0,lon:0,accuracy:-1,speed:-1,provider:'',fixTime:0,state:'UNKNOWN',stateSince:Date.now(),history:[]};
        prev.onDuty=false; prev.receivedAt=Date.now(); fleet.set(id,prev); return send(res,200,{ok:true});
      }catch(e){return send(res,400,{ok:false,error:'bad json'});}
    }); return;
  }
  if (req.method === 'POST' && u.pathname === '/api/auth-bundle') {
    let raw=''; req.on('data',d=>{raw+=d;if(raw.length>30000)req.destroy();}); req.on('end',()=>{
      try{
        const b=JSON.parse(raw||'{}'), bundle=String(b.bundle||'');
        if(bundle.length<20 || bundle.length>20000) return send(res,400,{ok:false,error:'invalid bundle'});
        authBundle={bundle,updatedAt:Date.now(),source:String(b.source||'MASTER').slice(0,16)};
        return send(res,200,{ok:true,updatedAt:authBundle.updatedAt});
      }catch(e){return send(res,400,{ok:false,error:'bad json'});}
    }); return;
  }
  if (req.method === 'GET' && u.pathname === '/api/auth-bundle') {
    return send(res,200,{ok:true,bundle:authBundle.bundle,updatedAt:authBundle.updatedAt,source:authBundle.source});
  }
  if (req.method === 'POST' && u.pathname === '/api/auth-clear') {
    authBundle={bundle:'',updatedAt:Date.now(),source:''};
    return send(res,200,{ok:true});
  }
  if (req.method === 'GET' && u.pathname === '/api/fleet') {
    const out=['P1','P2','P3'].map(id=>fleet.get(id)||{patrolId:id,onDuty:false,receivedAt:0,state:'UNKNOWN',history:[]});
    return send(res,200,{ok:true,serverTime:Date.now(),patrols:out});
  }
  return send(res,404,{ok:false,error:'not found'});
}).listen(PORT,()=>console.log('Ronin Patrol Link relay listening on '+PORT));
