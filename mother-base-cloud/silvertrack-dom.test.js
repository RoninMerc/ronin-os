const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm');
const {links,fields}=require('./silvertrack-dom');
const origin='https://trlsecurity.silvertracker.net',location={origin,href:origin+'/AdminCustomer/Issues/Monitor.aspx'};
const row={id:'row-hash',guard:'Synthetic Guard',property:'Synthetic Property',issue:'Parking Breach - 3rd Warning',date:'13/10/2026 06:00'};
const anchor=url=>({getAttribute:key=>key==='href'?url:''});
function linkTest(url,duplicate=false){
 const tr={innerText:Object.values(row).join(' '),querySelectorAll:()=>[anchor(url)]};
 const document={querySelectorAll:()=>duplicate?[tr,tr]:[tr]};
 return vm.runInNewContext(links,{document,location,URL,Set})([row]);
}
test('stable report IDs come from same-origin detail links, not changing row hashes',()=>{
 const a=linkTest('IssueDetails.aspx?IssueID=123');assert.equal(a.length,1);assert.equal(a[0].identity,'/admincustomer/issues/issuedetails.aspx:issueid=123');assert.equal(a[0].source.id,'row-hash');
});
test('cross-origin, credential-bearing, non-report and ambiguous links are rejected',()=>{
 for(const u of ['https://evil.example/collect?IssueID=123','https://user:password@trlsecurity.silvertracker.net/AdminCustomer/Issues/View.aspx?IssueID=123','/AdminCustomer/Delete.aspx?IssueID=123','javascript:alert(1)','IssueDetails.aspx?IssueID=not-a-stable-id'])assert.equal(linkTest(u).length,0);
 assert.equal(linkTest('IssueDetails.aspx?IssueID=123',true).length,0);
});
function fieldTest(body,login=false){
 const document={body:{innerText:body},querySelector:()=>login?{}:null,querySelectorAll:()=>[],getElementById:()=>null};
 return vm.runInNewContext(fields,{document})();
}
test('existing TRL report narrative yields explicit native vehicle and parking fields',()=>{
 const text='At approximately “0600” TRL Security Officer identified a silver Toyota Corolla, registration ABC0O1, in breach of the parking By-Laws.\nVehicle was found at 1 Synthetic Test Street, stationed in the visitor carpark.';
 const r=fieldTest(text);assert.equal(r.vehicle.plate,'ABC0O1');assert.equal(r.vehicle.vehicle,'Toyota Corolla');assert.equal(r.vehicle.colour,'silver');assert.equal(r.vehicle.parking,'visitor');assert.equal(r.vehicle.address,'1 Synthetic Test Street');
});
test('login forms and unlabelled registration guesses never produce a vehicle payload',()=>{
 assert.equal(fieldTest('ABC123 Toyota silver',true).login,true);assert.equal(fieldTest('ABC123 Toyota silver').incomplete,true);
});

test('report table labels tolerate trailing spaces after a colon',()=>{
 const values={'Registration: ':'ABC123','Make: ':'Toyota','Model: ':'Corolla','Colour: ':'silver','Address: ':'1 Synthetic Test Street','Parking area: ':'visitor'};
 const labels=Object.entries(values).map(([label,text])=>({innerText:label,nextElementSibling:{innerText:text,querySelector:()=>null}}));
 const document={body:{innerText:''},querySelector:()=>null,querySelectorAll:()=>labels,getElementById:()=>null};
 const r=vm.runInNewContext(fields,{document})();assert.equal(r.vehicle.plate,'ABC123');assert.equal(r.vehicle.make,'Toyota');assert.equal(r.vehicle.parking,'visitor');
});

test('automatic capture cannot follow edit/delete routes or mutation query modes',()=>{
 for(const u of ['DeleteIssue.aspx?IssueID=123','EditReport.aspx?ReportID=123','IssueDetails.aspx?IssueID=123&action=delete'])assert.equal(linkTest(u).length,0);
 assert.equal(linkTest('IssueDetails.aspx?IssueID=123&mode=view').length,1);
});
