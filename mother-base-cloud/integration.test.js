const test=require('node:test'),assert=require('node:assert/strict'),crypto=require('node:crypto'),{Pool}=require('pg');
const {createApp,migrate}=require('./server');
if(!process.env.TEST_DATABASE_URL)throw Error('TEST_DATABASE_URL is required for integration verification');
test('real PostgreSQL: authenticated feed → vehicles/history → paired device, retries, corrections, checkpoint CAS and restart',async()=>{
 const pool=new Pool({connectionString:process.env.TEST_DATABASE_URL,max:6});
 const ingestLogs=[];
 const opts={ingestLog:line=>ingestLogs.push(line),masterKey:crypto.randomBytes(32).toString('hex'),readerKey:crypto.randomBytes(32).toString('hex'),fleetKey:crypto.randomBytes(32).toString('hex'),encryptionKey:crypto.randomBytes(32).toString('hex')};
 let app;
 const listen=async()=>{app=createApp(pool,opts);await new Promise(r=>app.listen(0,'127.0.0.1',r));return 'http://127.0.0.1:'+app.address().port;};
 let root;
 const request=async(path,key='master',method='GET',data)=>{const h={'content-type':'application/json'};if(key==='master')h['x-ronin-master-key']=opts.masterKey;if(key==='reader')h['x-ronin-reader-key']=opts.readerKey;if(key==='fleet')h['x-trl-master-key']=opts.fleetKey;if(key.startsWith('device:'))h['x-trl-device-token']=key.slice(7);const r=await fetch(root+path,{method,headers:h,body:data===undefined?undefined:JSON.stringify(data)});return {status:r.status,data:await r.json()};};
 try{
  await migrate(pool);
  await pool.query('TRUNCATE trl_breaches,trl_breach_audit,trl_import_review,trl_vehicle_sources,vehicles,tombstones,corrections,devices,invitations,evidence');
  await pool.query("UPDATE trl_snapshots SET payload='[]',generation=0,received_at=0,central=FALSE");
  root=await listen();
  assert.equal((await request('/v1/activity','none')).status,401);
  const base={id:'silver-123',sourceReportId:'silver-123',guard:'guard',issue:'Parking Breach - 1st Warning',property:'Test property',date:'2026-10-13T06:00:00+10:00',vehicle:{plate:'abc-123',make:'Toyota',model:'Corolla',colour:'Silver'},cookie:'should not leave collector'};
  assert.equal((await request('/v1/activity','reader','POST',{rows:[base]})).status,401);
  let a=await request('/v1/activity','master','POST',{rows:[base]});assert.equal(a.status,200);assert.equal(a.data.imports.created,1);
  a=await request('/v1/activity','master','POST',{rows:[base]});assert.equal(a.data.imports.duplicate,1);
  assert.equal(Number((await pool.query('SELECT COUNT(*) AS n FROM vehicles')).rows[0].n),1);
  assert.equal(Number((await pool.query('SELECT COUNT(*) AS n FROM trl_breaches')).rows[0].n),1);
  assert.equal(JSON.stringify((await request('/v1/activity','reader')).data).includes('should not leave collector'),false);
  const activityLog=ingestLogs.map(JSON.parse).find(x=>x.event==='silvertrack_activity'&&x.status===200&&x.imports.created===1);
  assert.equal(activityLog.rows,1);assert.equal(activityLog.parkingRows,1);
  assert.ok(ingestLogs.map(JSON.parse).some(x=>x.event==='silvertrack_activity'&&x.status===401));
  assert.equal(ingestLogs.some(x=>[base.cookie,base.vehicle.plate,opts.masterKey,base.guard,base.property].some(secret=>x.includes(secret))),false);
  const changed={...base,issue:'Parking Breach - 2nd Warning',vehicle:{...base.vehicle,model:'Camry'}};
  a=await request('/v1/activity','master','POST',{rows:[changed]});assert.equal(a.data.imports.updated,1);
  assert.equal((await pool.query('SELECT revision FROM trl_breaches')).rows[0].revision,'2');
  let v=(await request('/v1/vehicles','fleet')).data.vehicles[0];assert.equal(v.details.make,'Toyota');assert.equal(v.details.model,'Camry');
  assert.equal((await request('/v1/vehicles','fleet','PUT',{plate:v.plate,revision:Number(v.revision),details:{...v.details,colour:'Black'},reason:'Supervisor correction from observation'})).status,200);
  a=await request('/v1/activity','master','POST',{rows:[{...changed,id:'silver-124',sourceReportId:'silver-124'}]});assert.equal(a.data.imports.created,1);
  assert.equal((await request('/v1/vehicles','fleet')).data.vehicles[0].details.colour,'Black');
  const invite=await request('/v1/invite','fleet','POST',{role:'P1'});assert.equal(invite.status,200);
  const pair=await request('/v1/pair','none','POST',{deviceId:'test-phone',code:invite.data.code});assert.equal(pair.status,200);
  assert.equal((await request('/v1/breaches','device:'+pair.data.token)).data.breaches.length,2);
  assert.equal((await request('/v1/import-review','device:'+pair.data.token)).status,403);
  a=await request('/v1/activity','master','POST',{rows:[{...base,id:'bad',sourceReportId:'bad',vehicle:{}}]});assert.equal(a.data.imports.review,1);
  const cp={id:'cp1',name:'Test checkpoint',group:'PBC1',order:1,lat:-27.4,lon:153,radius:40,photoBase64:''};
  assert.equal((await request('/v1/admin/checkpoints','reader','PUT',{generation:0,checkpoints:[cp]})).status,401);
  a=await request('/v1/admin/checkpoints','master','PUT',{generation:0,checkpoints:[cp]});assert.equal(a.status,200);assert.equal(a.data.generation,1);
  assert.equal((await request('/v1/checkpoints','master','POST',{checkpoints:[{...cp,name:'stale phone'}]})).status,409);
  assert.equal((await request('/v1/admin/checkpoints','master','PUT',{generation:0,checkpoints:[]})).status,409);
  assert.equal((await request('/v1/admin/checkpoints','master','PUT',{generation:1,checkpoints:[{...cp,lat:100}]})).status,400);
  assert.equal((await request('/v1/checkpoints','reader')).data.checkpoints[0].name,cp.name);
  await new Promise(r=>app.close(r));root=await listen();
  assert.equal((await request('/v1/checkpoints','reader')).data.generation,1);
  assert.equal((await request('/v1/breaches','device:'+pair.data.token)).data.breaches.length,2);
  assert.equal((await request('/v1/vehicles/delete','fleet','POST',{plate:'ABC123',reason:'Supervisor removed invalid record'})).status,200);
  a=await request('/v1/activity','master','POST',{rows:[{...base,id:'silver-125',sourceReportId:'silver-125'}]});assert.equal(a.data.imports.review,1);
  assert.equal((await request('/v1/vehicles','fleet')).data.vehicles.length,0);
 }finally{if(app?.listening)await new Promise(r=>app.close(r));await pool.end();}
});

test('native Breach sync: uploads, projections, revision conflicts, manual corrections and tombstones',async()=>{
 const pool=new Pool({connectionString:process.env.TEST_DATABASE_URL,max:6});
 const ingestLogs=[];
 const opts={ingestLog:line=>ingestLogs.push(line),masterKey:crypto.randomBytes(32).toString('hex'),readerKey:crypto.randomBytes(32).toString('hex'),fleetKey:crypto.randomBytes(32).toString('hex'),encryptionKey:crypto.randomBytes(32).toString('hex')};
 const app=createApp(pool,opts);let root;
 const req=async(path,key='fleet',method='GET',data)=>{
  const h={'content-type':'application/json'};if(key==='fleet')h['x-trl-master-key']=opts.fleetKey;else if(key==='master')h['x-ronin-master-key']=opts.masterKey;else if(key.startsWith('device:'))h['x-trl-device-token']=key.slice(7);
  const r=await fetch(root+path,{method,headers:h,body:data===undefined?undefined:JSON.stringify(data)});return {status:r.status,data:await r.json()};
 };
 try{
  await migrate(pool);await new Promise(r=>app.listen(0,'127.0.0.1',r));root='http://127.0.0.1:'+app.address().port;
  const invite=await req('/v1/invite','fleet','POST',{role:'P2'}),pair=await req('/v1/pair','none','POST',{deviceId:'native-breach-test',code:invite.data.code}),device='device:'+pair.data.token;
  assert.equal((await req('/v1/breach-sync','none')).status,401);
  const now=Date.now()-5000,v={plate:'NAT617',colour:'silver',vehicle:'Toyota Corolla',address:'1 Synthetic Test Street',relation:'at',parking:'visitor',saved_at:now,breach_level:2,last_breach_at:now,revision:0};
  let r=await req('/v1/breach-sync',device,'POST',{vehicles:[v],tombstones:[]});assert.equal(r.data.results[0].status,'created');
  let received=r.data.vehicles.find(x=>x.plate==='NAT617');assert.equal(received.vehicle,v.vehicle);assert.equal(received.breach_level,2);
  r=await req('/v1/breach-sync',device,'POST',{vehicles:[{...v,revision:1}],tombstones:[]});assert.equal(r.data.results[0].status,'unchanged');
  r=await req('/v1/breach-sync',device,'POST',{vehicles:[{...v,colour:'black',saved_at:now+1000,revision:0,reason:'Synthetic manual correction'}],tombstones:[]});assert.equal(r.data.results[0].status,'conflict');
  r=await req('/v1/breach-sync',device,'POST',{vehicles:[{...v,colour:'black',saved_at:now+1000,revision:1,reason:'Synthetic manual correction'}],tombstones:[]});assert.equal(r.data.results[0].status,'updated');
  received=r.data.vehicles.find(x=>x.plate==='NAT617');assert.equal(received.colour,'black');assert.equal(received.manual,true);
  r=await req('/v1/breach-sync',device,'POST',{vehicles:[],tombstones:[{plate_key:'NAT617',deleted_at:now+2000,revision:1}]});assert.equal(r.data.results[0].status,'conflict');
  r=await req('/v1/breach-sync',device,'POST',{vehicles:[],tombstones:[{plate_key:'NAT617',deleted_at:now+2000,revision:2}]});assert.equal(r.data.results[0].status,'deleted');assert.ok(r.data.tombstones.some(x=>x.plate_key==='NAT617'));
  assert.equal(r.data.vehicles.some(x=>x.plate==='NAT617'),false);
  const source={id:'native-source-event',sourceReportId:'native-source-event',guard:'Synthetic',property:'Synthetic site',issue:'Parking Breach - 3rd Warning',date:new Date(now).toISOString(),vehicle:{plate:'SRC617',make:'Toyota',model:'Corolla',colour:'silver',address:'2 Synthetic Test Street',relation:'at',parking:'visitor'}};
  assert.equal((await req('/v1/vehicle-reports',device,'POST',{rows:[source]})).status,401);
  assert.equal((await req('/v1/vehicle-reports','master','POST',{rows:[{...source,sourceReportId:''}]})).status,400);
  const before=(await pool.query("SELECT generation FROM trl_snapshots WHERE kind='activity'")).rows[0].generation;
  assert.equal((await req('/v1/vehicle-reports','master','POST',{rows:[source]})).status,200);
  const replay=await req('/v1/vehicle-reports','master','POST',{rows:[source]});assert.equal(replay.data.imports.duplicate,1);
  const vehicleLogs=ingestLogs.map(JSON.parse).filter(x=>x.event==='silvertrack_vehicle_reports');
  assert.ok(vehicleLogs.some(x=>x.status===401));assert.ok(vehicleLogs.some(x=>x.status===400));
  assert.ok(vehicleLogs.some(x=>x.status===200&&x.rows===1&&x.imports.created===1));
  assert.ok(vehicleLogs.some(x=>x.status===200&&x.imports.duplicate===1));
  assert.equal(ingestLogs.some(x=>[source.vehicle.plate,source.vehicle.address,opts.masterKey,pair.data.token].some(secret=>x.includes(secret))),false);
  assert.equal((await pool.query("SELECT generation FROM trl_snapshots WHERE kind='activity'")).rows[0].generation,before);
  r=await req('/v1/breach-sync',device);received=r.data.vehicles.find(x=>x.plate==='SRC617');assert.equal(received.breach_level,3);assert.equal(received.last_breach_at,now);
  assert.ok(r.data.breaches.some(x=>x.source_id==='native-source-event'));
 }finally{if(app.listening)await new Promise(r=>app.close(r));await pool.end();}
});

test('SilverTrack photos: actual import, encrypted storage, idempotent retry, protected training manifest and image retrieval',async()=>{
 const pool=new Pool({connectionString:process.env.TEST_DATABASE_URL,max:5}),opts={masterKey:'m'.repeat(32),readerKey:'r'.repeat(32),fleetKey:'f'.repeat(32),encryptionKey:'a'.repeat(64),ingestLog:()=>{}};
 const app=createApp(pool,opts);await migrate(pool);await new Promise(r=>app.listen(0,'127.0.0.1',r));
 const root='http://127.0.0.1:'+app.address().port;
 const req=async(path,headers={},data)=>fetch(root+path,{method:data?'POST':'GET',headers:{'content-type':'application/json',...headers},body:data?JSON.stringify(data):undefined});
 try{
  const sourceReportId='photo-synthetic-report',plate='PHO156';
  let r=await req('/v1/vehicle-reports',{'x-ronin-master-key':opts.masterKey},{rows:[{id:'2000001',sourceReportId,guard:'Synthetic',property:'Synthetic',issue:'Parking Breach - 1st Warning',occurredAt:'2026-07-14T06:00:00+10:00',vehicle:{plate,vehicle:'Toyota Corolla',address:'Synthetic Street',relation:'at',parking:'visitor'}}]});
  assert.equal(r.status,200);
  const jpg=Buffer.concat([Buffer.from([255,216,255]),Buffer.alloc(60,12),Buffer.from([255,217])]);
  const b={sourceReportId,plate,trainingRequested:true,filename:'synthetic.jpg',jpegBase64:jpg.toString('base64')};
  assert.equal((await req('/v1/report-evidence',{},b)).status,401);
  assert.equal((await req('/v1/report-evidence',{'x-ronin-master-key':opts.masterKey},{...b,sourceReportId:'missing'})).status,409);
  r=await req('/v1/report-evidence',{'x-ronin-master-key':opts.masterKey},b);const first=await r.json();assert.equal(r.status,200);assert.equal(first.duplicate,false);
  const retry=await (await req('/v1/report-evidence',{'x-ronin-master-key':opts.masterKey},b)).json();assert.equal(retry.id,first.id);assert.equal(retry.duplicate,true);
  const stored=(await pool.query('SELECT iv,ciphertext,tag FROM evidence WHERE id=$1',[first.id])).rows[0];assert.notDeepEqual(stored.ciphertext,jpg);
  assert.equal((await req('/v1/training-evidence',{'x-ronin-reader-key':opts.readerKey})).status,403);
  const manifest=await (await req('/v1/training-evidence',{'x-trl-master-key':opts.fleetKey})).json();
  const photo=manifest.photos.find(x=>x.id===first.id);assert.equal(photo.meta.sourceReportId,sourceReportId);
  assert.equal((await req(photo.downloadPath)).status,403);
  const fetched=await req(photo.downloadPath,{'x-trl-master-key':opts.fleetKey});assert.equal(fetched.status,200);assert.deepEqual(Buffer.from(await fetched.arrayBuffer()),jpg);
 }finally{await new Promise(r=>app.close(r));await pool.end();}
});
