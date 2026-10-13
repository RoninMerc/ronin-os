const test=require('node:test'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {plate,validPlate,enc,dec,createApp,statements}=require('./fleet');
const key=crypto.randomBytes(32);
test('Australian-style registrations normalise without silently replacing O with 0',()=>{
 assert.equal(plate('abC-0O1'),'ABC0O1');
 assert.equal(validPlate('abc 123'),true);
 assert.equal(validPlate('unknown vehicle'),false);
});
test('evidence encryption is authenticated and detects tampering',()=>{
 const input=Buffer.from([0xff,0xd8,1,2,3,4,0xff,0xd9]);
 const e=enc(input,key);assert.deepEqual(dec({iv:e.iv,ciphertext:e.data,tag:e.tag},key),input);
 const modified=Buffer.from(e.data);modified[0]^=1;
 assert.throws(()=>dec({iv:e.iv,ciphertext:modified,tag:e.tag},key));
});
test('migrations include durable records and correction audit',()=>{
 const all=statements.join(' ');
 for(const name of ['vehicles','tombstones','corrections','evidence','devices','invitations'])assert.ok(all.includes(name));
});
test('startup refuses empty encryption master secret',()=>{
 assert.throws(()=>createApp({},{encryptionKey:'1',masterKey:'short'}));
});

test('readable invitations remain hashed, single-use and compatible with existing device pairing',async t=>{
 let storedHash='',used=false;
 const pool={
  async query(sql,params){if(sql.startsWith('INSERT INTO invitations'))storedHash=params[0];return {rows:[]};},
  async connect(){return {async query(sql,params){
   if(sql.startsWith('SELECT role FROM invitations'))return {rows:!used&&params[0]===storedHash?[{role:'P1'}]:[]};
   if(sql.startsWith('UPDATE invitations'))used=true;
   return {rows:[]};
  },release(){}};}
 };
 const server=createApp(pool,{masterKey:'M'.repeat(32),encryptionKey:'ab'.repeat(32)});
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 t.after(()=>new Promise(resolve=>{server.close(resolve);server.closeAllConnections();}));
 const root='http://127.0.0.1:'+server.address().port;
 const invite=await fetch(root+'/v1/invite',{method:'POST',headers:{'content-type':'application/json','x-trl-master-key':'M'.repeat(32)},body:JSON.stringify({role:'P1'})});
 assert.equal(invite.status,200);
 const {code}=await invite.json();
 assert.match(code,/^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{32}$/);
 assert.equal(storedHash,crypto.createHash('sha256').update(code).digest('hex'));
 const wrong=await fetch(root+'/v1/pair',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({deviceId:'breach-test',code:'I'.repeat(32)})});
 assert.equal(wrong.status,403);assert.equal(used,false);
 const body=JSON.stringify({deviceId:'breach-test',code});
 const paired=await fetch(root+'/v1/pair',{method:'POST',headers:{'content-type':'application/json'},body});
 assert.equal(paired.status,200);assert.equal((await paired.json()).role,'P1');
 const replay=await fetch(root+'/v1/pair',{method:'POST',headers:{'content-type':'application/json'},body});
 assert.equal(replay.status,403);
});
