'use strict';
const crypto=require('node:crypto');
const clean=(v,n=250)=>typeof v==='string'?v.trim().replace(/[\u0000-\u001f\u007f]/g,' ').slice(0,n):'';
const plate=v=>clean(v,30).toUpperCase().replace(/[\s-]/g,'');
function normalise(row){
 if(!row||typeof row!=='object'||Array.isArray(row))return {error:'invalid row'};
 const issue=clean(row.issue,1800);
 if(!/\b(?:parking breach|vehicle breach|parking violation)\b/i.test(issue))return {ignored:true};
 const v=row.vehicle&&typeof row.vehicle==='object'&&!Array.isArray(row.vehicle)?row.vehicle:{};
 const text=typeof row.vehicleText==='string'?row.vehicleText.slice(0,12000):typeof row.issue==='string'?row.issue.slice(0,12000):'';
 function field(names,n=120){
  for(const k of names)if(clean(v[k],n))return clean(v[k],n);
  const escaped=names.map(k=>k.replace(/[.*+?^$()|[\]\\{}]/g,'\\$&')).join('|');
  const m=text.match(new RegExp('(?:^|[\\n\\r;|])\\s*(?:'+escaped+')\\s*[:=]\\s*([^\\n\\r;|]+)','i'));
  return m?clean(m[1],n):'';
 }
 const reg=plate(field(['plate','rego','registration','registration number','vehicle registration'],30));
 const id=clean(row.sourceReportId||row.id,130),property=clean(row.property,260);
 if(!id)return {error:'missing source report ID'};
 if(!/^[A-Z0-9]{1,10}$/.test(reg))return {error:'missing or invalid registration',id};
 const warning=issue.match(/\b([123])(?:st|nd|rd)?\s*(?:warning|breach)\b/i);
 const happened=clean(row.occurredAt||row.date,100);
 const when=/^\d{4}-\d\d-\d\dT\d\d:\d\d(?::\d\d(?:\.\d+)?)?(?:Z|[+-]\d\d:\d\d)$/.test(happened)&&Number.isFinite(Date.parse(happened))?new Date(happened).toISOString():null;
 const event={source:'SilverTrack',sourceReportId:id,plate:reg,make:field(['make']),model:field(['model']),colour:field(['colour','color']),description:field(['vehicle description','description'],500),warningLevel:warning?Number(warning[1]):null,property,sourceDate:happened,occurredAt:when,notes:field(['notes','comments'],1500),parkingPosition:field(['parking position','location'],400),vehicle:field(['vehicle','make/model','make and model'],160),address:field(['address','street address'],240),relation:field(['relation'],20),parking:field(['parking','parking area'],16),issue};
 return {event,hash:crypto.createHash('sha256').update(JSON.stringify(event)).digest('hex')};
}
function checkpoints(rows){
 if(!Array.isArray(rows)||rows.length>120)throw Error('Supply up to 120 checkpoints');
 const seen=new Set();
 return rows.map(a=>{
  if(!a||typeof a!=='object'||Array.isArray(a))throw Error('Invalid checkpoint');
  const id=clean(a.id,120),name=clean(a.name,180),group=clean(a.group,12);
  const order=a.order,lat=a.lat,lon=a.lon,radius=a.radius;
  if(!id||id.length!==String(a.id).trim().length||!name||seen.has(id)||!['PBC1','PBC2','PBC3'].includes(group))throw Error('Invalid checkpoint ID, name or group');
  if(!Number.isInteger(order)||order<0||order>10000||!Number.isFinite(lat)||Math.abs(lat)>90||!Number.isFinite(lon)||Math.abs(lon)>180||!Number.isFinite(radius)||radius<10||radius>500)throw Error('Invalid checkpoint order, GPS or distance');
  const photoBase64=typeof a.photoBase64==='string'?a.photoBase64:'';
  if(photoBase64.length>900000||!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(photoBase64))throw Error('Invalid QR photo');
  if(photoBase64){
   const b=Buffer.from(photoBase64,'base64');
   const jpeg=b.length>3&&b[0]===255&&b[1]===216&&b[2]===255;
   const png=b.length>8&&b.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10]));
   if(b.length>650000||(!jpeg&&!png))throw Error('QR photo must be a JPEG or PNG below 650 KB');
  }
  seen.add(id);return {id,name,group,order,lat,lon,radius,photoBase64};
 });
}
module.exports={normalise,checkpoints,plate,clean};
