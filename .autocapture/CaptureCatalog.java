package au.com.roningroup.patrollink;
final class CaptureCatalog {
 static String wrap(String reader){return "(function(){const docs=[document];for(let i=0;i<docs.length&&i<8;i++)for(const frame of docs[i].querySelectorAll('iframe,frame'))try{if(frame.contentDocument&&frame.contentDocument.body&&!docs.includes(frame.contentDocument))docs.push(frame.contentDocument);}catch{}const result={allRows:[],recognized:false,loading:false,login:false};const seen=new Set();const run=function(document,window){return "+reader+";};for(const doc of docs){try{const data=JSON.parse(run(doc,doc.defaultView));result.recognized=result.recognized||data.recognized;result.loading=result.loading||data.loading;result.login=result.login||data.login;for(const row of data.allRows||[]){const key=[row.id,row.guard,row.date].join('|');if(!seen.has(key)){seen.add(key);result.allRows.push(row);}}}catch{}}return JSON.stringify(result);})()";}
 private CaptureCatalog(){}
}
