const http = require('http');
const { URL } = require('url');
const PORT = process.env.PORT || 10000;
const API_KEY = process.env.RONIN_PATROL_KEY || '';
const fleet = new Map();
const vehicles = new Map();
const vehicleDeletes = new Map();
const observations = new Map();
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
function plateKey(v) { return String(v || '').toUpperCase().replace(/[^A-Z0-9]/g,'').slice(0,24); }
function cleanText(v,max) {
  const s=String(v==null?'':v).trim();
  if(s.length>max || /[\r\n\0]/.test(s)) throw new Error('invalid text');
  return s;
}
function cleanVehicle(v) {
  const now=Date.now(), key=plateKey(v && v.plate);
  const relation=cleanText(v && v.relation,20), parking=cleanText(v && v.parking,16);
  const saved=Number(v && v.saved_at), level=Number(v && v.breach_level)||0, last=Number(v && v.last_breach_at)||0;
  if(key.length<2 || !Number.isFinite(saved) || saved<0 || saved>now+86400000 || !Number.isInteger(level) || level<0 || level>3 || !Number.isFinite(last) || last<0) throw new Error('invalid vehicle');
  if(!['at','on','across from','in front of'].includes(relation)) throw new Error('invalid relation');
  if(!['common','visitor'].includes(parking)) throw new Error('invalid parking');
  return {
    plate_key:key, plate:cleanText(v.plate,24).toUpperCase(), colour:cleanText(v.colour,48).toLowerCase(),
    vehicle:cleanText(v.vehicle,160), address:cleanText(v.address,240), relation, parking,
    saved_at:saved, breach_level:level, last_breach_at:level===0?0:last
  };
}
function newerVehicle(a,b) {
  if(!b) return true;
  if(a.saved_at!==b.saved_at) return a.saved_at>b.saved_at;
  if(a.last_breach_at!==b.last_breach_at) return a.last_breach_at>b.last_breach_at;
  if(a.breach_level!==b.breach_level) return a.breach_level>b.breach_level;
  return JSON.stringify(a)>JSON.stringify(b);
}
function mergeVehicle(v) {
  const tomb=vehicleDeletes.get(v.plate_key)||0;
  if(tomb>=v.saved_at) return;
  const old=vehicles.get(v.plate_key);
  if(newerVehicle(v,old)) vehicles.set(v.plate_key,v);
  if(vehicleDeletes.has(v.plate_key) && v.saved_at>tomb) vehicleDeletes.delete(v.plate_key);
}
function mergeDelete(key,when) {
  key=plateKey(key); when=Number(when)||0;
  if(!key || when<=0 || when>Date.now()+86400000) return;
  const oldDelete=vehicleDeletes.get(key)||0;
  if(when>oldDelete) vehicleDeletes.set(key,when);
  const current=vehicles.get(key);
  if(current && when>=current.saved_at) vehicles.delete(key);
}
function cleanObservation(v) {
  const key=cleanText(v && v.sync_key,96), plate=cleanText(v && v.plate,24).toUpperCase(), observed=Number(v && v.observed_at);
  if(key.length<8 || !plateKey(plate) || !Number.isFinite(observed) || observed<=0 || observed>Date.now()+86400000) throw new Error('invalid observation');
  const lat=v && v.latitude!=null?Number(v.latitude):null, lon=v && v.longitude!=null?Number(v.longitude):null, accuracy=v && v.accuracy!=null?Number(v.accuracy):null;
  if((lat!==null&&!Number.isFinite(lat))||(lon!==null&&!Number.isFinite(lon))||(accuracy!==null&&!Number.isFinite(accuracy))) throw new Error('invalid observation coordinates');
  return {
    sync_key:key, plate, vin:cleanText(v && v.vin,40).toUpperCase(), year:cleanText(v && v.year,8),
    colour:cleanText(v && v.colour,48).toLowerCase(), vehicle:cleanText(v && v.vehicle,160),
    address:cleanText(v && v.address,240), latitude:lat, longitude:lon, accuracy,
    observed_at:observed, uncertain:!!(v && v.uncertain)
  };
}
function vehicleSnapshot() {
  return {
    ok:true, serverTime:Date.now(),
    vehicles:[...vehicles.values()].sort((a,b)=>a.plate_key.localeCompare(b.plate_key)),
    tombstones:[...vehicleDeletes.entries()].map(([plate_key,deleted_at])=>({plate_key,deleted_at})),
    observations:[...observations.values()].sort((a,b)=>a.observed_at-b.observed_at)
  };
}
http.createServer((req,res)=>{
  if (req.method === 'OPTIONS') return send(res,200,{ok:true});
  const u = new URL(req.url,'http://localhost');
  if (u.pathname === '/health') return send(res,200,{ok:true,service:'ronin-patrol-link-relay',sharedVehicleSync:true,vehicleCount:vehicles.size,observationCount:observations.size});
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
  if (req.method === 'POST' && u.pathname === '/api/shared/sync') {
    let raw=''; let tooLarge=false;
    req.on('data',d=>{ if(tooLarge)return; raw+=d; if(raw.length>8*1024*1024){tooLarge=true;req.destroy();} });
    req.on('end',()=>{
      if(tooLarge) return;
      try{
        const b=JSON.parse(raw||'{}'), rows=Array.isArray(b.vehicles)?b.vehicles:[], tombs=Array.isArray(b.tombstones)?b.tombstones:[], obs=Array.isArray(b.observations)?b.observations:[];
        if(rows.length>50000 || tombs.length>50000 || obs.length>20000) return send(res,413,{ok:false,error:'shared register too large'});
        for(const row of rows) mergeVehicle(cleanVehicle(row));
        for(const t of tombs) mergeDelete(t && t.plate_key,t && t.deleted_at);
        for(const item of obs){const clean=cleanObservation(item);if(!observations.has(clean.sync_key)) observations.set(clean.sync_key,clean);}
        return send(res,200,vehicleSnapshot());
      }catch(e){return send(res,400,{ok:false,error:'invalid shared sync payload'});}
    }); return;
  }
  if (req.method === 'GET' && u.pathname === '/api/shared') return send(res,200,vehicleSnapshot());
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
