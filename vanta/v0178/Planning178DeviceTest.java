package com.ronin.vanta;

import static org.junit.Assert.*;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Executes production Android storage, retry and authoring paths; no paid external API calls. */
@RunWith(AndroidJUnit4.class)
public class Planning178DeviceTest {
  final PipelineDeviceTest f=new PipelineDeviceTest();JobEngine e;ProviderConfig p;ModelInfo m;
  static final String PREFIX="repair_files_4";
  @Before public void prepare()throws Exception{f.prepare();e=f.e;p=f.h.seed("featherless");m=e.registry.find(p.id,"qa-model");assertNotNull(m);}
  @After public void cleanup()throws Exception{f.cleanup();}
  VantaJob seed()throws Exception{
    JSONObject input=f.h.input(p).put("recovery_policy",ForgeRecovery.policy(true,120,4,new JSONArray().put(p.toJson())));
    VantaJob j=f.job("build",input);
    j.event("reported_failure","BLOCKED",ProgressState.unknown("ACTION REQUIRED","Planning output allowances exhausted"));
    j.json.put("forge_inference",true).put("request_started",false).put("phase",PREFIX+"_plan_response_compact_2")
        .put("source_prefix",PREFIX).put("files_completed",31).put("files_planned",31);e.store.save(j);
    JSONObject build=f.session(f.project(),p,4).put("phase","repair").put("log","e: Could not load module <Error module>");
    e.store.document(j.id(),"build",build);
    e.store.document(j.id(),"project",f.project());
    e.store.document(j.id(),"partial",new JSONObject().put("text","retained partial output, not a completed project"));
    e.store.document(j.id(),"recovery",new JSONObject().put("calls",66).put("switches",4).put("actions",4)
        .put("active",JobOperations.selection(p,m)).put("visited",new JSONArray().put(p.id+"/"+m.id)).put("cooldowns",new JSONObject()));
    for(String key:ForgeOutputRecovery.records(PREFIX+"_plan_response",PREFIX+"_plan_correction"))
      e.store.document(j.id(),key,key.endsWith("_output_limit")?new JSONObject().put("confirmed",true).put("partial",""):
          new JSONObject().put("text","old unvalidated plan"));
    return j;
  }
  void retry(VantaJob j)throws Exception{
    try(ActivityScenario<MainActivity> a=ActivityScenario.launch(MainActivity.class)){
      a.onActivity(host->{try{e.retry(host,j.id(),"");}catch(Exception error){throw new AssertionError(error);}});
      f.h.waitDone(j.id(),15000);
    }
    long deadline=SystemClock.elapsedRealtime()+5000;
    while(e.executing()>0&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(25);
    assertEquals(0,e.executing());
  }
  String document(VantaJob j,String key)throws Exception{return e.store.document(j.id(),key).toString();}
  void assertBudget(VantaJob j,int calls)throws Exception{
    JSONObject r=e.store.document(j.id(),"recovery");assertEquals(calls,r.getInt("calls"));assertEquals(4,r.getInt("switches"));assertEquals(4,r.getInt("actions"));
  }

  @Test public void realRetryMigratesReportedCutoffsOnceWithoutChangingSourceOrCounters()throws Exception{
    VantaJob j=seed();String input=document(j,"input"),build=document(j,"build"),project=document(j,"project"),partial=document(j,"partial");AtomicInteger invoked=new AtomicInteger();
    e.setHandlerForTests((en,job,in,c)->{
      invoked.incrementAndGet();
      for(String key:ForgeOutputRecovery.records(PREFIX+"_plan_response",PREFIX+"_plan_correction"))assertNull(en.store.document(job.id(),key));
      assertEquals(12,job.json.getInt("planning_records_archived"));assertFalse(job.json.has("files_completed"));assertFalse(job.json.has("files_planned"));
      throw new JobOperations.Blocked("Fixture stopped before external generation");
    });
    retry(j);assertEquals(1,invoked.get());assertEquals("BLOCKED",e.store.get(j.id()).status());assertBudget(j,66);
    assertEquals(input,document(j,"input"));assertEquals(build,document(j,"build"));assertEquals(project,document(j,"project"));assertEquals(partial,document(j,"partial"));
    assertEquals(ForgePlanState.REVISION,e.store.document(j.id(),PREFIX+"_planning_scope").getString("revision"));
  }
  @Test public void anotherPlainRetryDoesNotEraseNewlyExhaustedAllowance()throws Exception{
    VantaJob j=seed();e.setHandlerForTests((en,job,in,c)->{throw new JobOperations.Blocked("Fixture stop");});retry(j);
    String key=PREFIX+"_plan_response_output_limit";e.store.document(j.id(),key,new JSONObject().put("confirmed",true).put("partial",""));
    AtomicInteger calls=new AtomicInteger();e.setHandlerForTests((en,job,in,c)->{calls.incrementAndGet();assertNotNull(en.store.document(job.id(),key));throw new JobOperations.Blocked("No duplicate generation");});
    retry(e.store.get(j.id()));assertEquals(1,calls.get());assertEquals(1,e.store.document(j.id(),PREFIX+"_planning_scope").getInt("epoch"));assertBudget(j,66);
  }
  @Test public void explicitModelRenewalAtFourHandoversKeepsValidatedContinuationAndCompletedFiles()throws Exception{
    VantaJob j=seed();
    e.store.document(j.id(),PREFIX+"_plan",new JSONObject().put("files",new JSONArray().put(new JSONObject().put("path","complete.java")).put(new JSONObject().put("path","unfinished.java"))));
    e.store.document(j.id(),PREFIX+"_file_0",new JSONObject().put("path","complete.java").put("content","completed source"));
    e.store.document(j.id(),PREFIX+"_file_1_chunk_0",new JSONObject().put("text","validated continuation prefix").put("complete",false));
    e.store.document(j.id(),PREFIX+"_file_1_part_1_expanded_1_output_limit",new JSONObject().put("confirmed",true).put("partial",""));
    ModelInfo next=new ModelInfo("qa-alternative","Alternative fixture","text","");next.contextTokens=131072;next.codeCapable=true;
    JSONObject selected=JobOperations.selection(p,next),in=e.store.document(j.id(),"input");
    in.put("model",selected.getJSONObject("model"));JSONObject build=e.store.document(j.id(),"build");build.put("model",next.id);e.store.document(j.id(),"build",build);
    j.json.put("planning_renew_authorized",true).put("model",next.id);e.store.save(j);ForgeRecoveryController.manual(e,j,in,selected);
    e.setHandlerForTests((en,job,input,c)->{
      assertEquals(next.id,input.getJSONObject("model").getString("id"));assertNotNull(en.store.document(job.id(),PREFIX+"_plan"));
      assertEquals("completed source",en.store.document(job.id(),PREFIX+"_file_0").getString("content"));
      assertEquals("validated continuation prefix",en.store.document(job.id(),PREFIX+"_file_1_chunk_0").getString("text"));
      assertNull(en.store.document(job.id(),PREFIX+"_file_1_part_1_expanded_1_output_limit"));
      throw new JobOperations.Blocked("Fixture stops before external generation");
    });retry(j);assertBudget(j,66);assertFalse(e.store.get(j.id()).json.has("planning_renew_authorized"));
  }
  @Test public void archiveWriteFailureRollsBackDeletionAndQueueState()throws Exception{
    VantaJob j=seed();String source=document(j,"project");VantaJob queued=new VantaJob(new JSONObject(j.json.toString()));queued.json.put("status","QUEUED");
    Map<String,JSONObject> writes=new LinkedHashMap<>();writes.put("archive-first",new JSONObject().put("value","old reply"));writes.put("archive-too-large",new JSONObject().put("text","x".repeat(20000001)));
    try{e.store.retryCheckpoint(queued,writes,Collections.singletonList(PREFIX+"_plan_response"));fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("20 MB"));}
    assertNull(e.store.document(j.id(),"archive-first"));assertNotNull(e.store.document(j.id(),PREFIX+"_plan_response"));assertEquals("BLOCKED",e.store.get(j.id()).status());assertEquals(source,document(j,"project"));
  }
  @Test public void cancelledBackgroundCannotMutateButUserCanExplicitlyRetry()throws Exception{
    VantaJob j=seed();j.json.put("status","CANCELLED");e.store.save(j);
    try{e.store.recoveryCheckpoint(j,Collections.singletonMap("must-not-write",new JSONObject()),Collections.emptyList());fail();}catch(InterruptedIOException expected){}
    assertNull(e.store.document(j.id(),"must-not-write"));e.setHandlerForTests((en,job,in,c)->{throw new JobOperations.Blocked("User-approved fixture retry");});retry(j);assertEquals("BLOCKED",e.store.get(j.id()).status());assertBudget(j,66);
  }
  @Test public void originalPlanningFailureRemainsVisibleWhenHandoverBudgetIsExhausted()throws Exception{
    VantaJob j=seed();j.json.put("forge_inference",true);e.store.save(j);
    ForgeRecovery.InvalidOutput problem=new ForgeRecovery.InvalidOutput("Planning output budget: no usable plan answer",ForgeOutputRecovery.records(PREFIX+"_plan_response"),null);
    assertTrue(ForgeRecoveryController.recover(e,j,problem,new Net.Call()));
    String message=e.store.get(j.id()).json.getString("error");assertTrue(message.contains("No usable repair plan"));assertTrue(message.contains("model-handover limit"));assertBudget(j,66);
  }
  @Test public void completedSignedResultDoesNotRequireOrResetPlanning()throws Exception{
    VantaJob j=seed();e.store.document(j.id(),"signed_apk",new JSONObject().put("fixture","do not replace"));
    e.setHandlerForTests((en,job,in,c)->{assertNotNull(en.store.document(job.id(),PREFIX+"_plan_response_compact_2_output_limit"));assertEquals("do not replace",en.store.document(job.id(),"signed_apk").getString("fixture"));throw new JobOperations.Blocked("Local finalisation fixture");});
    retry(j);assertNull(e.store.document(j.id(),PREFIX+"_planning_scope"));assertBudget(j,66);
  }
  @Test public void exactOldCutoffsResumeOneTargetedRepairThenRetrieveSignAndPublishFixtureApk()throws Exception{
    VantaJob j=seed();JSONObject source=f.project();String path="app/src/main/java/com/ronin/forge/verification/ReportFormatter.java";
    String correct=source.getJSONArray("files").getJSONObject(5).getString("content");
    source.getJSONArray("files").getJSONObject(5).put("content","package com.ronin.forge.verification; public final class ReportFormatter { public static String report(String location,String plate){return missingCompileSymbol;} }");
    JSONObject session=f.session(source,p,4).put("phase","repair").put("log",path+": error: cannot find symbol missingCompileSymbol");
    e.store.document(j.id(),"build",session);e.store.document(j.id(),"project",source);
    AtomicInteger ai=new AtomicInteger();e.overrideChat(j.id(),(provider,key,id,history,system,max,nativePrompt,web,call,stream)->{
      assertEquals(p.id,provider.id);
      if(ai.getAndIncrement()==0){
        assertFalse(call.inferenceOptions.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"));
        assertTrue(call.generationPolicy.getBoolean("structured_plan"));
        assertFalse(e.store.get(j.id()).json.has("files_completed"));
        assertEquals("PLANNING REPAIR",e.store.get(j.id()).progress().getString("stage"));
        return new JSONObject().put("name","Verification fixture").put("architecture","Preserve formatting and validation API")
            .put("files",new JSONArray().put(new JSONObject().put("path",path).put("purpose","Repair the undefined symbol"))).toString();
      }
      assertFalse(call.inferenceOptions.has("chat_template_kwargs"));return correct;
    });
    PipelineDeviceTest.Worker worker=f.new Worker(false);worker.pending=true;Net.Call c=new Net.Call();c.transport=worker;
    try{JobOperations.run(e,e.store.get(j.id()),e.store.document(j.id(),"input"),c);fail("Worker fixture should defer");}catch(JobEngine.Deferred expected){}
    assertEquals(2,ai.get());assertEquals(1,worker.puts);assertFalse(worker.uploaded.contains("missingCompileSymbol"));assertBudget(j,68);
    worker.pending=false;JobOperations.run(e,e.store.get(j.id()),e.store.document(j.id(),"input"),c);
    f.assertReady(j);assertEquals(1,worker.puts);assertEquals(2,ai.get());assertBudget(j,68);
  }
}
