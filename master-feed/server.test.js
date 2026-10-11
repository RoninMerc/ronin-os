const test=require('node:test'),assert=require('node:assert/strict');
process.env.MASTER_FEED_KEY='master_testing_secret_longer_than_32_chars';
process.env.READER_FEED_KEY='reader_testing_secret_longer_than_32_chars';
const {createApp,scrub}=require('./server');
test('scrub avoids credentials',()=>{const rows=scrub([{id:'a',guard:'P1',issue:'scan',cookie:'SECRET'}, {id:'a',guard:'P1',issue:'duplicate'}]);assert.equal(rows.length,1);assert.equal(JSON.stringify(rows).includes('SECRET'),false);});
test('read and publish scopes',async()=>{
 const server=createApp();await new Promise(done=>server.listen(0,'127.0.0.1',done));
 try{
 const url='http://127.0.0.1:'+server.address().port+'/v1/activity';
 let r=await fetch(url);assert.equal(r.status,401);
 r=await fetch(url,{method:'POST',headers:{'x-ronin-reader-key':process.env.READER_FEED_KEY}});assert.equal(r.status,401);
 r=await fetch(url,{method:'POST',headers:{'x-ronin-master-key':process.env.MASTER_FEED_KEY,'content-type':'application/json'},body:JSON.stringify({rows:[{id:'1',guard:'P1',issue:'checkpoint',property:'estate',date:'2026-10-11'}]})});
 assert.equal(r.status,200);
 r=await fetch(url,{headers:{'x-ronin-reader-key':process.env.READER_FEED_KEY}});const body=await r.json();assert.equal(body.masterOnline,true);assert.equal(body.rows[0].issue,'checkpoint');
 }finally{server.close();}
});
