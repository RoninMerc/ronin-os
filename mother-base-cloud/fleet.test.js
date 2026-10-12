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
