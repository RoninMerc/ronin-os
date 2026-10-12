'use strict';
const {plate,clean}=require('./normalise');
const {transaction}=require('./database');
const millis=v=>v instanceof Date?v.getTime():Date.parse(v)||0;
const isTime=n=>Number.isSafeInteger(n)&&n>=0&&n<=Date.now()+86400000;
function validNative(v){
 if(!v||typeof v!=='object'||Array.isArray(v))return false;
 return /^[A-Z0-9]{1,10}$/.test(plate(v.plate))&&typeof v.vehicle==='string'&&v.vehicle.trim().length>0&&v.vehicle.length<=160&&typeof v.address==='string'&&v.address.trim().length>0&&v.address.length<=240&&typeof v.colour==='string'&&v.colour.length<=48&&['at','on','across from','in front of'].includes(v.relation)&&['common','visitor'].includes(v.parking)&&isTime(v.saved_at)&&Number.isInteger(v.breach_level)&&v.breach_level>=0&&v.breach_level<=3&&isTime(v.last_breach_at)&&(v.breach_level===0?v.last_breach_at===0:v.last_breach_at>0);
}
function toNative(row,eventRow){
 const d=row.details||{},e=eventRow?.event||{};
 const vehicle=clean(d.vehicle||[d.make,d.model].filter(Boolean).join(' ')||d.description,160);
 let level=Number(d.breach_level||0),last=Number(d.last_breach_at||0);
 const sourceWhen=e.occurredAt?Date.parse(e.occurredAt):0;
 if(!row.manual&&sourceWhen&&e.warningLevel&&sourceWhen>=last){level=e.warningLevel;last=sourceWhen;}
 const native={plate:plate(row.plate),colour:clean(d.colour,48),vehicle,address:clean(d.address,240),relation:clean(d.relation,20)||'at',parking:clean(d.parking,16),saved_at:Math.max(Number(d.saved_at||0),millis(row.updated_at),millis(eventRow?.received_at)),breach_level:level,last_breach_at:level?last:0};
 if(e.warningLevel&&!sourceWhen&&!last)return {review:'Source breach date needs timezone/date confirmation'};
 if(!validNative(native))return {review:'Vehicle, confirmed address, parking area or warning date is incomplete'};
 return {native,revision:Number(row.revision),manual:!!row.manual,correctionReason:row.correction_reason||''};
}
async function readSnapshot(pool){
 const rows=(await pool.query("SELECT v.plate,v.details,v.revision,v.updated_at,COALESCE((SELECT actor<>'SILVERTRACK_COLLECTOR' FROM corrections c WHERE c.plate=v.plate ORDER BY id DESC LIMIT 1),FALSE) AS manual,COALESCE((SELECT reason FROM corrections c WHERE c.plate=v.plate ORDER BY id DESC LIMIT 1),'') AS correction_reason FROM vehicles v ORDER BY v.updated_at DESC LIMIT 10000")).rows;
 const events=(await pool.query('SELECT source_id,plate,event,revision,received_at FROM trl_breaches ORDER BY received_at DESC LIMIT 10000')).rows;
 const latest=new Map();
 for(const r of events){
  const old=latest.get(r.plate),stamp=r.event.occurredAt?Date.parse(r.event.occurredAt):0;
  const oldStamp=old?.event.occurredAt?Date.parse(old.event.occurredAt):0;
  if(!old||stamp>oldStamp)latest.set(r.plate,r);
 }
 const vehicles=[],review=[];
 for(const r of rows){const projected=toNative(r,latest.get(r.plate));if(projected.native)vehicles.push({...projected.native,revision:projected.revision,manual:projected.manual,correction_reason:projected.correctionReason});else review.push({plate:r.plate,reason:projected.review});}
 const tombstones=(await pool.query('SELECT plate,deleted_at FROM tombstones ORDER BY deleted_at DESC LIMIT 50000')).rows.map(r=>({plate_key:plate(r.plate),deleted_at:millis(r.deleted_at)}));
 return {ok:true,format:'trl-motherbase-sync',version:1,vehicles,tombstones,breaches:events,review};
}
async function snapshot(pool){return transaction(pool,async c=>{await c.query('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ');return readSnapshot(c);});}
async function upload(pool,actor,body){
 if(!Array.isArray(body.vehicles)||body.vehicles.length>50000||!Array.isArray(body.tombstones)||body.tombstones.length>50000)throw Error('Invalid native snapshot');
 const results=[];
 for(const v of body.vehicles){
  if(!validNative(v)||!Number.isSafeInteger(v.revision)||v.revision<0){results.push({plate:plate(v?.plate),status:'invalid'});continue;}
  const p=plate(v.plate),reason=clean(v.reason,500);
  const result=await transaction(pool,async c=>{
   await c.query('SELECT pg_advisory_xact_lock(hashtext($1))',[p]);
   if((await c.query('SELECT plate FROM tombstones WHERE plate=$1',[p])).rows.length)return 'deleted';
   const old=(await c.query('SELECT plate,details,revision,updated_at FROM vehicles WHERE plate=$1 FOR UPDATE',[p])).rows[0];
   if(old){
    const existing=toNative(old,null).native;
    const fields=['plate','colour','vehicle','address','relation','parking','breach_level','last_breach_at'];
    if(existing&&fields.every(k=>String(existing[k])===String(v[k])))return 'unchanged';
    if(Number(old.revision)!==v.revision)return 'conflict';
    if(reason.length<10)return 'reason required';
    if(v.saved_at<Number(old.details.saved_at||0))return 'stale';
   }else if(v.revision!==0)return 'conflict';
   const details={...(old?.details||{})};
   for(const k of ['colour','vehicle','address','relation','parking','saved_at','breach_level','last_breach_at'])details[k]=v[k];
   if(old)await c.query('UPDATE vehicles SET details=$2::jsonb,revision=revision+1,updated_at=now() WHERE plate=$1',[p,JSON.stringify(details)]);
   else await c.query('INSERT INTO vehicles(plate,details) VALUES($1,$2::jsonb)',[p,JSON.stringify(details)]);
   await c.query('INSERT INTO corrections(plate,actor,action,old,new,reason) VALUES($1,$2,$3,$4::jsonb,$5::jsonb,$6)',[p,actor,old?'DEVICE_UPDATE':'DEVICE_CREATE',JSON.stringify(old?.details??null),JSON.stringify(details),reason||'New native Breach field record']);
   return old?'updated':'created';
  });
  results.push({plate:p,status:result});
 }
 for(const t of body.tombstones){
  const p=plate(t?.plate_key),revision=Number(t?.revision);
  if(!/^[A-Z0-9]{1,10}$/.test(p)||!isTime(t?.deleted_at)||t.deleted_at<=0||!Number.isSafeInteger(revision)||revision<0){results.push({plate:p,status:'invalid deletion'});continue;}
  const result=await transaction(pool,async c=>{
   await c.query('SELECT pg_advisory_xact_lock(hashtext($1))',[p]);
   if((await c.query('SELECT plate FROM tombstones WHERE plate=$1',[p])).rows.length)return 'unchanged';
   const old=(await c.query('SELECT details,revision FROM vehicles WHERE plate=$1 FOR UPDATE',[p])).rows[0];
   if(Number(old?.revision||0)!==revision)return 'conflict';
   if(old&&t.deleted_at<Number(old.details.saved_at||0))return 'stale';
   await c.query('INSERT INTO tombstones(plate,reason,deleted_at) VALUES($1,$2,to_timestamp($3/1000.0))',[p,'Deliberate deletion on paired Breach device',t.deleted_at]);
   await c.query('DELETE FROM vehicles WHERE plate=$1',[p]);
   await c.query('INSERT INTO corrections(plate,actor,action,old,reason) VALUES($1,$2,$3,$4::jsonb,$5)',[p,actor,'DEVICE_DELETE',JSON.stringify(old?.details??null),'Deliberate deletion on paired Breach device']);
   return 'deleted';
  });
  results.push({plate:p,status:result});
 }
 return results;
}
module.exports={validNative,toNative,snapshot,upload};
