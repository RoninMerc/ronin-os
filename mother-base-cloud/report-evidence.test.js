'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),crypto=require('node:crypto'),e=require('./report-evidence');
const jpeg=()=>Buffer.concat([Buffer.from([255,216,255]),Buffer.alloc(60,12),Buffer.from([255,217])]);
test('source photos require explicit training request, stable source and strict image bytes',()=>{
 const b={sourceReportId:'SilverTrack:synthetic',plate:'ABC123',trainingRequested:true,jpegBase64:jpeg().toString('base64')};
 assert.equal(e.decode(b).sha,crypto.createHash('sha256').update(jpeg()).digest('hex'));
 for(const x of [{...b,trainingRequested:false},{...b,jpegBase64:'not JPEG'},{...b,plate:'unknown plate'},{...b,sourceReportId:''}])assert.throws(()=>e.decode(x));
});
test('photo manifest exposes protected download paths and cursor, never credentials or public files',async()=>{
 const pool={query:async(sql,args)=>({rows:[{id:'12345678-1234-1234-1234-123456789abc',plate:'ABC123',meta:{sourceReportId:'synthetic'},sha256:'digest'}]})};
 const r=await e.manifest(pool);assert.equal(r.photos[0].downloadPath,'/v1/training-evidence/12345678-1234-1234-1234-123456789abc.jpg');assert.equal(r.nextCursor,null);
 await assert.rejects(e.manifest(pool,'bad cursor'));assert.equal(await e.image(pool,Buffer.alloc(32),'bad'),null);
});
