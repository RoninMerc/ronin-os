const test=require('node:test'),assert=require('node:assert/strict');
const {normalise,checkpoints}=require('./normalise');
const row={id:'report-123',guard:'test',property:'Test site',issue:'Parking Breach - 3rd Warning',date:'2026-10-13T05:30:00+10:00',vehicleText:'Rego: abC-0O1\nMake: Toyota\nModel: Corolla\nColour: Silver\nNotes: In reserved space'};
test('labelled details normalise and preserve O versus zero',()=>{const {event}=normalise(row);assert.equal(event.plate,'ABC0O1');assert.equal(event.make,'Toyota');assert.equal(event.model,'Corolla');assert.equal(event.colour,'Silver');assert.equal(event.warningLevel,3);assert.equal(event.occurredAt,'2026-10-12T19:30:00.000Z');});
test('general patrol activity never creates vehicles',()=>assert.equal(normalise({...row,issue:'Checkpoint scan'}).ignored,true));
test('ambiguous local timestamps are retained without timezone guessing',()=>{const {event}=normalise({...row,date:'13/10/2026 05:30'});assert.equal(event.occurredAt,null);assert.equal(event.sourceDate,'13/10/2026 05:30');});
test('missing registration and missing event ID are quarantined',()=>{assert.ok(normalise({...row,vehicleText:''}).error);assert.ok(normalise({...row,id:''}).error);});
test('unlabelled guesses cannot become registration',()=>assert.ok(normalise({...row,vehicleText:'Silver Toyota ABC123'}).error));
test('credential and unknown fields are not retained',()=>{const {event}=normalise({...row,password:'forbidden',vehicle:{plate:'ABC123',cookie:'forbidden'}});assert.ok(!JSON.stringify(event).includes('forbidden'));});
const cp={id:'c1',name:'Gate',group:'PBC1',order:1,lat:-27.4,lon:153,radius:40,photoBase64:''};
test('checkpoint sets validate range, duplicates and photo formats atomically',()=>{assert.equal(checkpoints([cp]).length,1);for(const rows of [[cp,cp],[{...cp,lat:91}],[{...cp,radius:1}],[{...cp,photoBase64:Buffer.from('not an image').toString('base64')}]])assert.throws(()=>checkpoints(rows));});

test('labelled multiline issue text is parsed without flattening field boundaries',()=>{const {event}=normalise({...row,vehicleText:undefined,issue:'Vehicle Breach - 2nd Warning\nRego: XYZ123\nMake: Ford\nColour: Blue'});assert.equal(event.plate,'XYZ123');assert.equal(event.make,'Ford');});
