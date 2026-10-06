package com.ronin.vanta;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Uses the real encrypted store, retry controller and source workflow with isolated transports. */
@RunWith(AndroidJUnit4.class)
public class Source179DeviceTest {
  final Planning178DeviceTest g=new Planning178DeviceTest();JobEngine e;
  static final String PREFIX="repair_files_4", FILE=PREFIX+"_file_2", PHASE=FILE+"_part_0_expanded_1";
  @Before public void prepare()throws Exception{g.prepare();e=g.e;}
  @After public void cleanup()throws Exception{g.cleanup();}
  VantaJob seed()throws Exception{
    VantaJob j=g.seed();j.json.put("phase",PHASE).put("files_completed",2).put("files_planned",20);e.store.save(j);
    JSONObject recovery=e.store.document(j.id(),"recovery");recovery.put("calls",72);e.store.document(j.id(),"recovery",recovery);
    e.store.document(j.id(),PREFIX+"_planning_scope",ForgePlanState.scope(JobOperations.selection(g.p,g.m)).put("revision","178-direct-planning-v1").put("epoch",2));
    JSONArray files=new JSONArray();for(int i=0;i<20;i++)files.put(new JSONObject().put("path","File"+i+".java"));
    e.store.document(j.id(),PREFIX+"_plan",new JSONObject().put("files",files));
    e.store.document(j.id(),PREFIX+"_file_0",new JSONObject().put("path","File0.java").put("content","completed first file"));
    e.store.document(j.id(),PREFIX+"_file_1",new JSONObject().put("path","File1.java").put("content","completed second file"));
    e.store.document(j.id(),FILE+"_part_0_output_limit",new JSONObject().put("confirmed",true).put("partial",""));
    e.store.document(j.id(),PHASE,new JSONObject().put("text","unvalidated response retained for diagnosis"));
    e.store.document(j.id(),PHASE+"_output_limit",new JSONObject().put("confirmed",true).put("partial",""));
    return j;
  }
  @Test public void updateRetryArchivesReportedFailedFileFamilyAndRetainsValidatedWork()throws Exception{
    VantaJob j=seed();String plan=g.document(j,PREFIX+"_plan"),build=g.document(j,"build"),project=g.document(j,"project"),partial=g.document(j,"partial");
    AtomicInteger runs=new AtomicInteger();e.setHandlerForTests((en,job,in,c)->{
      runs.incrementAndGet();assertNull(en.store.document(job.id(),FILE+"_part_0_output_limit"));assertNull(en.store.document(job.id(),PHASE));assertNull(en.store.document(job.id(),PHASE+"_output_limit"));
      assertEquals(3,job.json.getInt("planning_records_archived"));assertEquals("completed first file",en.store.document(job.id(),PREFIX+"_file_0").getString("content"));assertEquals("completed second file",en.store.document(job.id(),PREFIX+"_file_1").getString("content"));
      throw new JobOperations.Blocked("Fixture stopped before paid work");
    });g.retry(j);assertEquals(1,runs.get());g.assertBudget(j,72);assertEquals(plan,g.document(j,PREFIX+"_plan"));assertEquals(build,g.document(j,"build"));assertEquals(project,g.document(j,"project"));assertEquals(partial,g.document(j,"partial"));
    assertEquals("179-direct-source-v1",e.store.document(j.id(),PREFIX+"_planning_scope").getString("revision"));
  }
  @Test public void repeatedOrdinaryRetryDoesNotResetNewEmptyFileCutoff()throws Exception{
    VantaJob j=seed();e.setHandlerForTests((en,job,in,c)->{throw new JobOperations.Blocked("Fixture stop");});g.retry(j);
    e.store.document(j.id(),FILE+"_part_0_output_limit",new JSONObject().put("confirmed",true).put("partial",""));
    e.setHandlerForTests((en,job,in,c)->{assertNotNull(en.store.document(job.id(),FILE+"_part_0_output_limit"));throw new JobOperations.Blocked("No duplicate source request");});g.retry(e.store.get(j.id()));
    assertEquals(3,e.store.document(j.id(),PREFIX+"_planning_scope").getInt("epoch"));g.assertBudget(j,72);
  }
  @Test public void sourceOutputBudgetGetsItsOwnActionWithoutAutomaticModelSwitch()throws Exception{
    VantaJob j=seed();e.setHandlerForTests((en,job,in,c)->{job.json.put("request_started",true);en.store.save(job);throw new ForgeOutputRecovery.SourceOutputBudget(null);});g.retry(j);
    VantaJob stopped=e.store.get(j.id());assertEquals("ACTION_REQUIRED",stopped.status());assertEquals("SOURCE_OUTPUT_BUDGET",stopped.json.getString("action_kind"));assertTrue(stopped.json.getString("error").startsWith("No source text"));
    assertEquals("SOURCE_OUTPUT_BUDGET",e.store.document(j.id(),"diagnostics").getString("category"));assertFalse(stopped.json.getString("error").contains("handover"));g.assertBudget(j,72);
  }
  @Test public void activeReasoningWatchdogStopDoesNotBecomeANetworkRetry()throws Exception{
    VantaJob j=seed();e.setHandlerForTests((en,job,in,c)->{
      job.json.put("request_started",true);en.store.save(job);
      try(GenerationWatchdog w=new GenerationWatchdog(c,PHASE)){c.generation.text("reasoning","x".repeat(24000));w.checkNow();throw w.failure(new IOException("fixture closed socket"));}
    });g.retry(j);VantaJob stopped=e.store.get(j.id());assertEquals("ACTION_REQUIRED",stopped.status());assertEquals("SOURCE_OUTPUT_BUDGET",stopped.json.getString("action_kind"));assertFalse(stopped.json.getString("error").contains("Generation stalled"));g.assertBudget(j,72);
  }
  @Test public void totalDeadlineGetsItsOwnCategoryNotTheIdleSummary()throws Exception{
    VantaJob j=seed();e.setHandlerForTests((en,job,in,c)->{
      job.json.put("request_started",true);en.store.save(job);AtomicLong now=new AtomicLong();
      try(GenerationWatchdog w=new GenerationWatchdog(c,now::get,240000,720000,PHASE)){c.generation.text("answer","class Fixture {");now.set(720026000000L);c.generation.text("answer","}");w.checkNow();throw w.failure(new IOException("fixture socket closed by deadline"));}
    });g.retry(j);VantaJob stopped=e.store.get(j.id());assertEquals("ACTION_REQUIRED",stopped.status());assertEquals("GENERATION_TOTAL_LIMIT",stopped.json.getString("action_kind"));assertTrue(stopped.json.getString("error").contains("not necessarily an idle"));g.assertBudget(j,72);
  }
  @Test public void approvedModelRenewalPreservesContinuationAndDoesNotResetBudgets()throws Exception{
    VantaJob j=seed();e.store.document(j.id(),FILE+"_chunk_0",new JSONObject().put("text","valid source prefix").put("complete",false));
    e.store.document(j.id(),FILE+"_part_1_output_limit",new JSONObject().put("confirmed",true).put("partial",""));
    j.json.put("planning_renew_authorized",true);e.store.save(j);ForgeRecoveryController.manual(e,j,e.store.document(j.id(),"input"),JobOperations.selection(g.p,g.m));
    e.setHandlerForTests((en,job,in,c)->{assertEquals("valid source prefix",en.store.document(job.id(),FILE+"_chunk_0").getString("text"));assertNotNull(en.store.document(job.id(),FILE+"_part_0_output_limit"));assertNull(en.store.document(job.id(),FILE+"_part_1_output_limit"));throw new JobOperations.Blocked("Fixture stops before external request");});g.retry(j);g.assertBudget(j,72);
  }
  @Test public void completedSignedArtifactIsNotReauthoredByUpdateMigration()throws Exception{
    VantaJob j=seed();e.store.document(j.id(),"signed_apk",new JSONObject().put("fixture","keep exact signed result"));
    e.setHandlerForTests((en,job,in,c)->{assertEquals("keep exact signed result",en.store.document(job.id(),"signed_apk").getString("fixture"));assertNotNull(en.store.document(job.id(),PHASE));throw new JobOperations.Blocked("Fixture local finalisation only");});g.retry(j);assertEquals("178-direct-planning-v1",e.store.document(j.id(),PREFIX+"_planning_scope").getString("revision"));g.assertBudget(j,72);
  }
  @Test public void retainedTwoFilesSkipPlannerAndOnlyUnfinishedFileReachesWorkerSigningPipeline()throws Exception{
    VantaJob j=seed();JSONObject source=g.f.project();JSONArray original=source.getJSONArray("files");String correct=original.getJSONObject(5).getString("content");
    String path=original.getJSONObject(5).getString("path");original.getJSONObject(5).put("content","package com.ronin.forge.verification; public final class ReportFormatter { public static String report(String location,String plate){return missingCompileSymbol;} }");
    JSONObject plan=new JSONObject().put("name","Retained source fixture").put("architecture","Preserve API and actual tests").put("files",new JSONArray().put(new JSONObject().put("path",original.getJSONObject(0).getString("path"))).put(new JSONObject().put("path",original.getJSONObject(1).getString("path"))).put(new JSONObject().put("path",path).put("purpose","Repair only the undefined symbol")));
    e.store.document(j.id(),PREFIX+"_plan",plan);e.store.document(j.id(),PREFIX+"_file_0",new JSONObject(original.getJSONObject(0).toString()));e.store.document(j.id(),PREFIX+"_file_1",new JSONObject(original.getJSONObject(1).toString()));
    e.store.document(j.id(),"project",source);e.store.document(j.id(),"build",g.f.session(source,g.p,4).put("phase","repair").put("log",path+": error: cannot find symbol missingCompileSymbol"));
    e.setHandlerForTests((en,job,in,c)->{throw new JobOperations.Blocked("Approved local migration before isolated full-pipeline test");});g.retry(j);
    AtomicInteger ai=new AtomicInteger();e.overrideChat(j.id(),(provider,key,id,history,system,max,nativePrompt,web,call,stream)->{
      ai.incrementAndGet();assertTrue(call.generationPolicy.getBoolean("source_file"));assertFalse(call.generationPolicy.getBoolean("structured_plan"));assertFalse(call.inferenceOptions.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"));
      assertEquals(8000,max);assertEquals(FILE+"_part_0",e.store.get(j.id()).json.getString("phase"));return correct;
    });
    PipelineDeviceTest.Worker worker=g.f.new Worker(false);worker.pending=true;Net.Call c=new Net.Call();c.transport=worker;
    try{JobOperations.run(e,e.store.get(j.id()),e.store.document(j.id(),"input"),c);fail("Worker fixture must defer");}catch(JobEngine.Deferred expected){}
    assertEquals(1,ai.get());assertEquals(1,worker.puts);assertFalse(worker.uploaded.contains("missingCompileSymbol"));g.assertBudget(j,73);
    worker.pending=false;JobOperations.run(e,e.store.get(j.id()),e.store.document(j.id(),"input"),c);g.f.assertReady(j);
    assertEquals(1,ai.get());assertEquals(1,worker.puts);g.assertBudget(j,73);
  }
}
