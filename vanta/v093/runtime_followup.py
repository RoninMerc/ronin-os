from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal')); j=root/'app/src/main/java/com/ronin/vanta'
def edit(path,old,new):
 s=path.read_text(); assert s.count(old)==1,(path,s.count(old),old[:80]);path.write_text(s.replace(old,new,1))
p=j/'AndroidBuildFoundation.java'
edit(p,r'UnitTest)\\3\\s*\\)\\s*;?\\s*}"',r'UnitTest)\\3\\s*\\)\\s*;?\\s*\\}"')
p=j/'JobEngine.java'
old='''    } finally {
      calls.remove(id);'''
new='''    } catch (LinkageError runtimeFailure) {
      // An adapter/class initialization defect is not a provider retry. Persist a terminal
      // state before releasing this worker, otherwise the saved RUNNING record spins forever.
      if (job != null) try {
        VantaJob current=store.get(id);
        if(current!=null && !current.status().equals("CANCELLED")) {
          String detail=Errors.redact(runtimeFailure.toString());
          store.document(id,"diagnostics",new JSONObject().put("category","CLIENT_RUNTIME")
            .put("operation",job.json.optString("phase",job.type())).put("error",detail)
            .put("timestamp",System.currentTimeMillis()));
          job.json.put("error","Vanta encountered an internal runtime error. Saved work is preserved.")
            .put("action_kind","CLIENT_RUNTIME");
          job.json.remove("next_run"); job.json.remove("transfer");job.json.remove("question");
          job.event("client_runtime_failed","FAILED",ProgressState.transition(job.progress(),"ACTION REQUIRED",
              "Vanta could not execute this step. Saved work is preserved; no automatic request was replayed."));
          store.save(job);
        }
      }catch(Exception recording){recoveryError="Runtime failure state could not be saved: "+Errors.summary(recording.getMessage());}
    } finally {
      calls.remove(id);'''
edit(p,old,new)
p=root/'app/src/androidTest/java/com/ronin/vanta/AutomaticHandoverDeviceTest.java'
old='''    assertFalse(
        ForgeRecoveryController.buildFailed(e, j, session, "error: previous compiler failure"));'''
new='''    String previousLog;
    try(java.util.zip.ZipInputStream zip=new java.util.zip.ZipInputStream(new ByteArrayInputStream(f.zip("worker-failure")))) {
      assertNotNull(zip.getNextEntry());previousLog=new String(Net.read(zip,4_000_000,new Net.Call()),java.nio.charset.StandardCharsets.UTF_8);
    }
    // The same root error in a distinct previous compiler run must switch. Rechecking
    // the exact same run is separately covered and must not count as another failure.
    JSONObject previous=new JSONObject(session.toString()).put("attempt",2).put("run_id",100);
    assertFalse(ForgeRecoveryController.buildFailed(e,j,previous,previousLog));'''
edit(p,old,new)
p=root/'app/src/androidTest/java/com/ronin/vanta/FoundationDeviceTest.java';s=p.read_text().rstrip();assert s.endswith('}')
s=s[:-1]+'''
  @Test public void buildFoundationPatternsAndCorrectionsRunOnAndroid()throws Exception {
    JSONObject project=f.project();
    for(int i=0;i<project.getJSONArray("files").length();i++) {
      JSONObject file=project.getJSONArray("files").getJSONObject(i);
      if(file.getString("path").equals("app/build.gradle"))file.put("content",file.getString("content")+
        "\\ntasks.named(\\"assembleRelease\\") { dependsOn(\\"testReleaseUnitTest\\") }\\n");
    }
    AndroidBuildFoundation.Prepared prepared=AndroidBuildFoundation.prepare(project);
    assertTrue(prepared.changes.length()>0);
    assertFalse(prepared.project.toString().contains("tasks.named"));
    assertEquals(0,AndroidBuildFoundation.findings(prepared.project).length());
    ForgeRepairGuard.check(project,prepared.project);
  }
  @Test public void internalClassFailureStopsRatherThanReplayingTheSavedJob()throws Exception {
    AtomicInteger attempts=new AtomicInteger();
    e.setHandlerForTests((engine,job,in,call)->{attempts.incrementAndGet();throw new NoClassDefFoundError("neutral injected adapter initialization failure");});
    try(ActivityScenario<MainActivity> activity=ActivityScenario.launch(MainActivity.class)) {
      VantaJob job=h.enqueue(activity,"build",new JSONObject(),null);
      VantaJob done=h.waitDone(job.id(),12000);
      assertEquals("FAILED",done.status());assertFalse(done.active());assertEquals(1,attempts.get());
      assertEquals("CLIENT_RUNTIME",e.store.document(job.id(),"diagnostics").getString("category"));
      Thread.sleep(1500);assertEquals(1,attempts.get());assertFalse(e.store.get(job.id()).status().equals("COMPLETED"));
    }
  }
}
''';p.write_text(s)
print('Escaped ICU regex delimiter; terminalized adapter initialization faults; added Android-specific and non-replay regressions.')
