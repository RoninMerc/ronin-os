const test=require('node:test'),assert=require('node:assert/strict');
const {toNative,validNative}=require('./native-breach');
const when=new Date(Date.now()-60000).toISOString();
const row={plate:'abc-0O1',revision:4,updated_at:when,details:{make:'Toyota',model:'Corolla',colour:'silver',address:'1 Synthetic Test Street',relation:'at',parking:'visitor'}};
const event={received_at:when,event:{occurredAt:when,warningLevel:3}};
test('native projection uses the existing Breach backup fields and true source warning date',()=>{
 const n=toNative(row,event);assert.equal(n.native.plate,'ABC0O1');assert.equal(n.native.vehicle,'Toyota Corolla');assert.equal(n.native.breach_level,3);assert.equal(n.native.last_breach_at,Date.parse(when));assert.equal(n.revision,4);assert.equal(validNative(n.native),true);
});
test('incomplete parking and ambiguous source dates are withheld rather than invented',()=>{
 assert.ok(toNative({...row,details:{...row.details,parking:''}},event).review);
 assert.ok(toNative(row,{...event,event:{warningLevel:2,occurredAt:null}}).review);
});
test('reviewed manual warning state is not overwritten by source projection',()=>{
 const r={...row,manual:true,details:{...row.details,breach_level:1,last_breach_at:Date.parse(when)-1000}};
 assert.equal(toNative(r,event).native.breach_level,1);
});
test('one-character native registrations and malformed source fields are checked',()=>{
 const n=toNative({...row,plate:'A'},event).native;assert.equal(validNative(n),true);assert.equal(validNative({...n,breach_level:4}),false);assert.equal(validNative({...n,address:''}),false);
});

test('reviewed manual zero-warning state can resolve an ambiguous source date',()=>{
 const r={...row,manual:true,details:{...row.details,breach_level:0,last_breach_at:0}};
 assert.equal(toNative(r,{received_at:when,event:{warningLevel:3,occurredAt:null}}).native.breach_level,0);
});
