from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'))
j=root/'app/src/main/java/com/ronin/vanta'
def edit(file,old,new,count=1):
 p=j/file;s=p.read_text();assert s.count(old)==count,(file,s.count(old),old[:60]);p.write_text(s.replace(old,new,count))

# Read encrypted records in bounded SQLite windows, not a single multi-megabyte Cursor row.
(j/'DatabaseBlobs.java').write_text('''package com.ronin.vanta;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
final class DatabaseBlobs {
 private DatabaseBlobs() {}
 static byte[] read(SQLiteDatabase db,String table,String column,String where,String[] args,int max)throws IOException {
  long length;
  try(Cursor c=db.rawQuery("SELECT length("+column+") FROM "+table+" WHERE "+where,args)) {
   if(!c.moveToFirst())return null;length=c.getLong(0);
  }
  if(length<0||length>max)throw new IOException("Saved record exceeds its supported size");
  ByteArrayOutputStream result=new ByteArrayOutputStream((int)length);
  for(long offset=0;offset<length;offset+=262144) {
   try(Cursor c=db.rawQuery("SELECT substr("+column+","+(offset+1)+",262144) FROM "+table+" WHERE "+where,args)) {
    if(!c.moveToFirst())throw new IOException("Saved record disappeared during retrieval");
    byte[] part=c.getBlob(0);if(part.length!=Math.min(262144,length-offset))throw new IOException("Saved record is incomplete");result.write(part,0,part.length);
   }
  }
  return result.toByteArray();
 }
}
''')
p=j/'JobStore.java';s=p.read_text();a=s.index('    try (Cursor c =',s.index('public synchronized JSONObject document('));b=s.index('\n  public synchronized void document(',a)
s=s[:a]+'''    byte[] encrypted=DatabaseBlobs.read(getReadableDatabase(),"documents","value","job=? AND name=?",new String[]{id,name},20_000_128);
    return encrypted==null?null:new JSONObject(new String(vault.unseal(label(id,name),encrypted),StandardCharsets.UTF_8));
  }
''' + s[b:];p.write_text(s)
p=j/'RecordStore.java';s=p.read_text();a=s.index('    try (Cursor c =',s.index('public synchronized byte[] bytes('));b=s.index('\n  public synchronized void put(',a)
s=s[:a]+'''    byte[] encrypted=DatabaseBlobs.read(getReadableDatabase(),"records","payload","id=? AND kind=?",new String[]{id,kind},8_000_128);
    return encrypted==null?null:vault.unseal(kind+":"+id,encrypted);
  }
''' + s[b:];p.write_text(s)
# Provider catalogues are read once per cache revision, not repeatedly parsed during navigation.
edit('ModelRegistry.java','  private final SharedPreferences prefs;','  private final SharedPreferences prefs;\n  private final Map<String,List<ModelInfo>> cache=new HashMap<>();')
edit('ModelRegistry.java','  public synchronized List<ModelInfo> models(String provider) {','  public synchronized List<ModelInfo> models(String provider) {\n    if(cache.containsKey(provider))return new ArrayList<>(cache.get(provider));')
edit('ModelRegistry.java','    return out;\n  }\n\n  private void insert','    cache.put(provider,new ArrayList<>(out));\n    return out;\n  }\n\n  private void insert')
edit('ModelRegistry.java','  public synchronized void savePreferences(String p, List<ModelInfo> list) throws Exception {','  public synchronized void savePreferences(String p, List<ModelInfo> list) throws Exception {\n    cache.remove(p);')
edit('ModelRegistry.java','    prefs.edit().putLong("synced_" + p.id, now).apply();','    cache.remove(p.id);\n    prefs.edit().putLong("synced_" + p.id, now).apply();')
edit('ModelRegistry.java','  public synchronized void remove(String p) {','  public synchronized void remove(String p) {\n    cache.remove(p);\n    getWritableDatabase().delete("refresh","provider=?",new String[]{p});')
edit('MainActivity.java','                  vault.deleteSecret(target.id);\n                  models.put','                  vault.deleteSecret(target.id);\n                  registry.remove(target.id);\n                  models.put')
edit('MainActivity.java','                          providers.remove(target);','                          registry.remove(target.id);\n                          providers.remove(target);')
# File reads recover AtomicFile backups left by process death.
edit('JobFiles.java','    if (!f.exists()) throw new IOException("Saved output no longer exists");','    if (!f.exists() && !new File(f.getPath()+".bak").exists()) throw new IOException("Saved output no longer exists");')
edit('JobFiles.java','try (InputStream in = new FileInputStream(f))','try (InputStream in = new AtomicFile(f).openRead())')
# Preserve completed phase estimates through retries/recreation; no timer or token-count estimates.
edit('JobEngine.java','              ProgressState.unknown(\n                  next.equals("QUEUED")','              ProgressState.transition(\n                  j.progress(),\n                  next.equals("QUEUED")')
edit('JobEngine.java','    j.event(event, "RUNNING", ProgressState.stages(stage, action, complete, total));','''    if(j.type().equals("prompt")||j.type().equals("forge_source")) {
      int committed=0;
      for(String phase:new String[]{"analysis","optimized","reviewed","final_prompt","source"})
        if(store.document(j.id(),phase)!=null)committed++;
      complete=Math.max(complete,Math.min(total,committed));
    }
    j.event(event, "RUNNING", ProgressState.stages(stage, action, complete, total));''')
edit('JobEngine.java','          boolean remote = store.document(id, "remote") != null;','''          JSONObject build=store.document(id,"build");
          boolean remote = store.document(id, "remote") != null || (build!=null&&Arrays.asList("poll","upload").contains(build.optString("phase")));''')
edit('JobEngine.java','  private volatile boolean suspended;','')
edit('JobEngine.java','    if (userInitiated) suspended = false;','')
edit('JobEngine.java','    suspended = true;\n    for (Net.Call c : calls.values()) c.cancel();','    for(String id:calls.keySet())if(!schedulerOwned.contains(id)){Net.Call c=calls.get(id);if(c!=null)c.cancel();}')
edit('JobOperations.java','    long[] last = {0};\n','',2)
# Transfer subprogress is mathematically exact, including a fully transferred but not finalized job.
edit('JobEngine.java','"TRANSFERRING", "Transferring provider data", done, total, false','"TRANSFERRING", "Transferring provider data", done, total, true')
# Manual Prompt targets determine destination modality without a keyword mismatch.
edit('JobOperations.java','      compatible(p, m, category);\n      return new VantaRouter.Candidate','      compatible(p, m, forge ? "code" : targetCategory(request,analysis,m));\n      return new VantaRouter.Candidate')
edit('JobOperations.java','  private static void prompt(','  private static String targetCategory(String request,JSONObject analysis,ModelInfo model){\n    if(model.type.equals("text"))return PromptStrategy.category(request,analysis).equals("code")?"code":"text";\n    return model.type.equals("tts")||model.type.equals("audio")?"speech":model.type;\n  }\n\n  private static void prompt(')
edit('JobOperations.java','.put("mode", PromptStrategy.category(request, analysis))','.put("mode", targetCategory(request, analysis,target.model))')
edit('JobOperations.java','PromptStrategy.explanation(target, PromptStrategy.category(request, analysis))','PromptStrategy.explanation(target, targetCategory(request, analysis,target.model))')
# A handoff to TTS preserves the requested speech model; a matching real voice is selected explicitly.
edit('MainActivity.java','      showMode(destination);\n      if (prompt != null) {','''      if(destination.equals("speech")&&(!p.id.equals(prefs.getString("voice_provider",""))||!m.id.equals(prefs.getString("voice_model",""))))
        prefs.edit().remove("voice_id").remove("voice_label").putString("voice_provider",p.id).putString("voice_model",m.id).apply();
      showMode(destination);
      if (prompt != null) {''')
# Redact common credential forms even when errors originate outside the HTTP adapter.
edit('Errors.java','    String s = (value == null ? "" : value).toLowerCase(Locale.ROOT);','''    if(value!=null)value=value.replaceAll("(?i)(sk-|ghp_|github_pat_)[A-Za-z0-9_-]{8,}","[credential redacted]").replaceAll("(?i)Bearer\\s+[^\\s,;]+","Bearer [redacted]");
    String s = (value == null ? "" : value).toLowerCase(Locale.ROOT);''')
# Storage controls include new encrypted task history/outputs without wiping keys or conversations.
edit('MainActivity.java','    showSheet("Privacy & appearance", box);','''    box.addView(VantaDesign.option(this,"delete","Clear task history & private outputs","Keeps conversations, API keys, projects and shared exports",()->{
      new AlertDialog.Builder(this).setTitle("Delete saved task history?").setMessage("Finished/cancelled task inputs, events, prompts and private output copies will be deleted. Active work must finish or be cancelled first. Saved conversations, project source, API keys and shared exports remain.")
      .setPositiveButton("DELETE",(d,w)->io.submit(()->{try{
        if(jobs.executing()>0||!jobs.store.active().isEmpty())throw new IOException("Finish or cancel active tasks before clearing their history.");
        int count=0;for(VantaJob task:jobs.store.list()){jobs.store.remove(task.id());new JobFiles(this).remove(task.id());count++;}
        final int n=count;ui.post(()->notice("Removed "+n+" saved tasks and private outputs."));
      }catch(Exception e){ui.post(()->error(e.getMessage()));}})).setNegativeButton("CANCEL",null).show();
    }));
    showSheet("Privacy & appearance", box);''')
# New regression coverage in the real Android harness.
test=root/'app/src/androidTest/java/com/ronin/vanta/MasterDeviceTest.java';s=test.read_text().rstrip();assert s.endswith('}')
s=s[:-1]+'''
 @Test public void largeEncryptedDocumentsCrossCursorWindow()throws Exception {
  JobEngine engine=engine();
  String value=new String(new char[3_000_000]).replace('\0','x');
  VantaJob job=engine.store.create("fixture","QA large document","",new JSONObject());
  try{engine.store.document(job.id(),"large",new JSONObject().put("text",value));assertEquals(value,engine.store.document(job.id(),"large").getString("text"));
   RecordStore records=RecordStore.get(ctx());records.put("qa-large","qa-large","qa",value);assertEquals(value,records.text("qa-large","qa-large"));records.delete("qa-large","qa-large");
  }finally{engine.cancel(job.id());engine.store.remove(job.id());}
 }
 @Test public void retryRetainsCommittedStageEstimate()throws Exception {
  JobEngine engine=engine();
  VantaJob job=engine.store.create("prompt","QA retry estimate","",new JSONObject());
  engine.store.document(job.id(),"analysis",new JSONObject().put("text","analysis"));
  engine.step(job,"retry","UNDERSTANDING","Reusing analysis",0,4);
  assertEquals(25,engine.store.get(job.id()).progress().getInt("percent"));engine.cancel(job.id());engine.store.remove(job.id());
 }
}
''';test.write_text(s)
print('Final review: bounded SQLite reads, catalog cache, retry progress, target handoff, credential redaction and task storage controls.')
