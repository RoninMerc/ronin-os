package com.ronin.vanta;

import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;

/** Production request/stream/recovery paths exercised without a provider account. */
public class Source179RegressionTest {
  static final String PREFIX="repair_files_4", PHASE=PREFIX+"_file_2_part_0_expanded_1";
  static ProviderConfig p(String kind){return Planning178RegressionTest.provider(kind);}
  static ModelInfo model(){return Planning178RegressionTest.model("Qwen/Qwen3.8-27B");}
  static JSONObject selection()throws Exception{return JobOperations.selection(p("featherless"),model());}
  static class Docs extends Planning178RegressionTest.Docs {}
  static String event(String field,String text,String finish)throws Exception{
    JSONObject delta=new JSONObject();if(field!=null)delta.put(field,text);
    return "data: "+new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",delta).put("finish_reason",finish==null?JSONObject.NULL:finish))).toString()+"\n\n";
  }
  static class Wire extends HttpURLConnection {
    final ByteArrayOutputStream body=new ByteArrayOutputStream();final byte[] reply;boolean disconnected;
    Wire(URL url,String text){super(url);reply=text.getBytes(StandardCharsets.UTF_8);}
    @Override public OutputStream getOutputStream(){return body;}
    @Override public InputStream getInputStream(){return new ByteArrayInputStream(reply);}
    @Override public int getResponseCode(){return 200;}
    @Override public String getContentType(){return "text/event-stream";}
    @Override public void connect(){}
    @Override public void disconnect(){disconnected=true;}
    @Override public boolean usingProxy(){return false;}
  }
  @Test public void reportedExpandedFilePhaseRequestsDirectOutput()throws Exception{
    JSONObject options=ForgeRequestPolicy.options(p("featherless"),model(),PHASE);
    assertEquals(Boolean.FALSE,options.getJSONObject("chat_template_kwargs").get("enable_thinking"));
    JSONObject diagnostic=ForgeRequestPolicy.diagnostics(p("featherless"),model(),PHASE);
    assertFalse(diagnostic.getBoolean("structured_plan"));assertTrue(diagnostic.getBoolean("source_file"));
    assertEquals("source_file",diagnostic.getString("request_kind"));assertFalse(diagnostic.getBoolean("empty_answer_expansion"));
    assertEquals("179-direct-source-v1",diagnostic.getString("revision"));
  }
  @Test public void everyFileContinuationAndLegacyExpansionGetsSamePolicy()throws Exception{
    for(String prefix:new String[]{"author",PREFIX,"validation_files_4_1","validation_files_4_1_handover_3"})
      for(int part=0;part<4;part++)for(String suffix:new String[]{"","_expanded_1"}){
        String phase=prefix+"_file_17_part_"+part+suffix;
        assertTrue(ForgeRequestPolicy.sourceFile(phase));
        assertFalse(ForgeRequestPolicy.options(p("featherless"),model(),phase).getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"));
      }
  }
  @Test public void ordinaryChatAndAnalysisAreNotConvertedToFileRequests()throws Exception{
    for(String phase:new String[]{"chat","analysis","analysis_compact_1","file_search","author_plan_response","repair_files_4_file_2","author_file_0_part_4","author_file_0_part_1_suffix"}){
      assertFalse(phase,ForgeRequestPolicy.sourceFile(phase));
      if(!ForgeRequestPolicy.planning(phase))assertEquals(0,ForgeRequestPolicy.options(p("featherless"),model(),phase).length());
    }
    assertFalse(ForgeRequestPolicy.sourceFile(null));
  }
  @Test public void otherProviderFileCallsDoNotReceiveFeatherlessControl()throws Exception{
    for(String kind:new String[]{"openai","anthropic","venice","custom"})
      assertFalse(ForgeRequestPolicy.options(p(kind),model(),PHASE).has("chat_template_kwargs"));
  }
  @Test public void planControlIsRetainedAlongsideNewFilePolicy()throws Exception{
    assertFalse(ForgeRequestPolicy.options(p("featherless"),model(),PREFIX+"_plan_response").getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"));
    assertTrue(ForgeRequestPolicy.diagnostics(p("featherless"),model(),PREFIX+"_plan_response").getBoolean("structured_plan"));
  }
  @Test public void actualApiChatBodyCarriesBooleanFalseAndReturnsSource()throws Exception{
    Net.Call call=new Net.Call();AtomicReference<Wire> capture=new AtomicReference<>();
    call.inferenceOptions=ForgeRequestPolicy.options(p("featherless"),model(),PHASE);
    String reply=event("content","class Example {}",null)+event(null,"","stop");
    call.connectionFactory=url->{Wire wire=new Wire(url,reply);capture.set(wire);return wire;};
    String result=ApiClient.chat(p("featherless"),"fixture-not-a-real-key",model().id,new JSONArray().put(new JSONObject().put("role","user").put("content","Return a fixture source file")),"source only",8000,call,text->{});
    JSONObject body=new JSONObject(capture.get().body.toString("UTF-8"));
    assertEquals(Boolean.FALSE,body.getJSONObject("chat_template_kwargs").get("enable_thinking"));
    assertEquals("class Example {}",result);assertEquals("/v1/chat/completions",capture.get().getURL().getPath());
    assertTrue(capture.get().disconnected);
  }
  @Test public void requestPolicyDoesNotCopyUnrecognisedTemplateKeys()throws Exception{
    Net.Call c=new Net.Call();c.inferenceOptions=ForgeRequestPolicy.options(p("featherless"),model(),PHASE);
    c.inferenceOptions.getJSONObject("chat_template_kwargs").put("untrusted","do not forward");
    JSONObject body=new JSONObject();ForgeRequestPolicy.apply(body,p("featherless"),c);
    assertEquals(1,body.getJSONObject("chat_template_kwargs").length());
    assertFalse(body.toString().contains("untrusted"));
  }
  @Test public void sourcePolicyDiagnosticsDoNotContainPromptOrCredentials()throws Exception{
    JSONObject d=ForgeRequestPolicy.diagnostics(p("featherless"),model(),PHASE);
    assertEquals(24000,d.getLong("reasoning_only_character_limit"));assertEquals(180000,d.getLong("reasoning_only_time_limit_ms"));
    for(String forbidden:new String[]{"prompt","reasoning_content","authorization","api_key"})assertFalse(d.has(forbidden));
  }
  @Test public void continuousReasoningStopsAtSourceTimeAllowanceNotIdleDeadline()throws Exception{
    AtomicLong now=new AtomicLong();Net.Call c=new Net.Call();
    try(GenerationWatchdog w=new GenerationWatchdog(c,now::get,240000,720000,PHASE)){
      for(int seconds=30;seconds<=180;seconds+=30){now.set(seconds*1000000000L);c.generation.text("reasoning","ongoing");w.checkNow();if(seconds<180)assertFalse(w.expired());}
      assertTrue(w.expired());assertTrue(w.failure(new IOException()) instanceof GenerationWatchdog.SourceExhausted);
      assertTrue(c.diagnostics().getString("timeout_reason").contains("stream is active"));
    }
  }
  @Test public void sourceCharacterAllowanceStopsWithoutWaitingTwelveMinutes()throws Exception{
    Net.Call c=new Net.Call();try(GenerationWatchdog w=new GenerationWatchdog(c,PHASE)){
      c.generation.text("reasoning","x".repeat(23999));w.checkNow();assertFalse(w.expired());
      c.generation.text("reasoning","x");w.checkNow();assertTrue(w.expired());
      assertTrue(w.failure(new IOException()) instanceof GenerationWatchdog.SourceExhausted);
    }
  }
  @Test public void whitespaceAnswerDoesNotDefeatSourceOnlyReasoningGuard()throws Exception{
    Net.Call c=new Net.Call();try(GenerationWatchdog w=new GenerationWatchdog(c,PHASE)){
      c.generation.text("answer"," \n\t");c.generation.text("reasoning","x".repeat(24000));w.checkNow();assertTrue(w.expired());
      assertEquals(0,c.generation.diagnostics().getLong("answer_characters"));
    }
  }
  @Test public void actualSourceAnswerCanContinueBeyondReasoningOnlyAllowance()throws Exception{
    AtomicLong now=new AtomicLong();Net.Call c=new Net.Call();try(GenerationWatchdog w=new GenerationWatchdog(c,now::get,240000,720000,PHASE)){
      c.generation.text("answer","package example;");now.set(200000000000L);c.generation.text("reasoning","x".repeat(25000));w.checkNow();assertFalse(w.expired());
      c.generation.text("answer","public class Example {}");w.checkNow();assertFalse(w.expired());
    }
  }
  @Test public void totalDeadlineIsDistinctEvenWithContinuousAnswerOutput()throws Exception{
    AtomicLong now=new AtomicLong();Net.Call c=new Net.Call();try(GenerationWatchdog w=new GenerationWatchdog(c,now::get,240000,720000,PHASE)){
      c.generation.text("answer","class Example {");now.set(720026000000L);c.generation.text("answer","}");w.checkNow();
      assertTrue(w.failure(new IOException()) instanceof GenerationWatchdog.TotalLimit);
      assertFalse(w.failure(new IOException()) instanceof GenerationWatchdog.SourceExhausted);
      assertTrue(Errors.summary(w.failure(new IOException()).getMessage()).contains("not necessarily an idle"));
    }
  }
  @Test public void emptyStreamUsesTrueIdleTimeout()throws Exception{
    AtomicLong now=new AtomicLong();Net.Call c=new Net.Call();try(GenerationWatchdog w=new GenerationWatchdog(c,now::get,240000,720000,PHASE)){
      now.set(180000000000L);w.checkNow();assertFalse(w.expired());now.set(240000000000L);w.checkNow();
      assertTrue(w.expired());assertFalse(w.failure(new IOException()) instanceof GenerationWatchdog.SourceExhausted);
      assertTrue(Errors.summary(w.failure(new IOException()).getMessage()).startsWith("Generation stalled"));
    }
  }
  @Test public void heartbeatEventsDoNotQualifyAsGeneratedSource()throws Exception{
    AtomicLong now=new AtomicLong();Net.Call c=new Net.Call();try(GenerationWatchdog w=new GenerationWatchdog(c,now::get,240000,720000,PHASE)){
      for(int second=60;second<=240;second+=60){now.set(second*1000000000L);c.generation.event();w.checkNow();}
      assertTrue(w.expired());assertFalse(w.failure(new IOException()) instanceof GenerationWatchdog.SourceExhausted);
    }
  }
  @Test public void cancellationWinsOverSourceBudget()throws Exception{
    Net.Call c=new Net.Call();try(GenerationWatchdog w=new GenerationWatchdog(c,PHASE)){
      c.cancel();c.generation.text("reasoning","x".repeat(25000));w.checkNow();assertFalse(w.expired());
    }
  }
  @Test public void closeDetachesWatchdogWithoutAbortingCompletedCall()throws Exception{
    Net.Call c=new Net.Call();GenerationWatchdog w=new GenerationWatchdog(c,PHASE);w.close();c.generation.text("reasoning","x".repeat(30000));w.checkNow();assertFalse(w.expired());
  }
  @Test public void ordinaryChatIsNotGivenTheSourceOnlyReasoningCap()throws Exception{
    Net.Call c=new Net.Call();try(GenerationWatchdog w=new GenerationWatchdog(c,"chat")){
      c.generation.text("reasoning","x".repeat(66829));w.checkNow();assertFalse(w.expired());
    }
  }
  @Test public void planningRetainsItsOwnDistinctGuard()throws Exception{
    Net.Call c=new Net.Call();try(GenerationWatchdog w=new GenerationWatchdog(c,PREFIX+"_plan_response")){
      c.generation.text("reasoning","x".repeat(24000));w.checkNow();assertTrue(w.failure(new IOException()) instanceof GenerationWatchdog.PlanningExhausted);
    }
  }
  @Test public void realParserSeparatesReasoningAndSourceWithoutExposingReasoning()throws Exception{
    Net.Call c=new Net.Call();StringBuilder display=new StringBuilder();try(GenerationWatchdog w=new GenerationWatchdog(c,PHASE)){
      String out=ApiClient.readEvents(new StringReader(event("reasoning_content","synthetic reasoning counter fixture",null)+event("content","class Safe {}",null)+event(null,"","stop")),c,x->display.append(x));
      assertEquals("class Safe {}",out);assertFalse(display.toString().contains("synthetic reasoning"));
      assertTrue(c.generation.diagnostics().getLong("reasoning_characters")>0);assertEquals(13,c.generation.diagnostics().getLong("answer_characters"));w.checkNow();assertFalse(w.expired());
    }
  }
  @Test public void confirmedEmptyFileLimitSavesCutoffWithoutLargerReplay()throws Exception{
    Docs d=new Docs();AtomicInteger calls=new AtomicInteger();try{ForgeAuthor.writeFile(PREFIX+"_file_2","s","u",(id,s,u,n)->{calls.incrementAndGet();assertEquals(8000,n);throw new ChatProtocol.Incomplete("cutoff","",true);},d);fail();}
    catch(ForgeOutputRecovery.SourceOutputBudget expected){assertEquals(ForgeRecovery.Kind.STOP,ForgeRecovery.classify(expected,true,false));}
    assertEquals(1,calls.get());assertNotNull(d.get(PREFIX+"_file_2_part_0_output_limit"));assertNull(d.get(PHASE+"_output_limit"));
  }
  @Test public void samePolicyRetryDoesNotPurchaseExhaustedEmptyFileAgain()throws Exception{
    Docs d=new Docs();d.put(PREFIX+"_file_2_part_0_output_limit",new JSONObject().put("confirmed",true).put("partial",""));
    try{ForgeAuthor.writeFile(PREFIX+"_file_2","s","u",(a,b,c,n)->{fail("No new provider request");return "";},d);fail();}catch(ForgeOutputRecovery.SourceOutputBudget expected){}
  }
  @Test public void disabledRecoveryNeverExpandsEmptySourceEither()throws Exception{
    Docs d=new Docs();d.enabled=false;AtomicInteger calls=new AtomicInteger();try{ForgeAuthor.writeFile("author_file_0","s","u",(a,b,c,n)->{calls.incrementAndGet();throw new ChatProtocol.Incomplete("limit","",true);},d);fail();}catch(ForgeOutputRecovery.SourceOutputBudget expected){}assertEquals(1,calls.get());
  }
  @Test public void nonemptyConfirmedCutoffKeepsValidatedContinuation()throws Exception{
    Docs d=new Docs();AtomicInteger calls=new AtomicInteger();String out=ForgeAuthor.writeFile("author_file_0","s","u",(id,s,u,n)->{if(calls.getAndIncrement()==0)throw new ChatProtocol.Incomplete("limit","class Keep {\n",true);assertTrue(id.endsWith("part_1"));return "}\n";},d);
    assertEquals("class Keep {\n}\n",out);assertEquals(2,calls.get());assertFalse(d.get("author_file_0_chunk_0").getBoolean("complete"));assertTrue(d.get("author_file_0_chunk_1").getBoolean("complete"));
  }
  @Test public void savedContinuationDoesNotRepeatThePreviousPart()throws Exception{
    Docs d=new Docs();d.put("author_file_0_chunk_0",new JSONObject().put("text","class Keep {\n").put("complete",false));AtomicInteger calls=new AtomicInteger();
    assertEquals("class Keep {\n}\n",ForgeAuthor.writeFile("author_file_0","s","u",(id,s,u,n)->{calls.incrementAndGet();assertEquals("author_file_0_part_1",id);return "}\n";},d));assertEquals(1,calls.get());
  }
  @Test public void ambiguousStreamFailureIsNotReclassifiedAsAFreeCutoff()throws Exception{
    Docs d=new Docs();AtomicInteger calls=new AtomicInteger();try{ForgeAuthor.writeFile("author_file_0","s","u",(a,b,c,n)->{calls.incrementAndGet();throw new ChatProtocol.Incomplete("disconnected","",false);},d);fail();}catch(ChatProtocol.Incomplete expected){assertFalse(expected.confirmedOutputLimit);}assertEquals(1,calls.get());assertNull(d.get("author_file_0_part_0_output_limit"));
  }
  @Test public void reported178ScopeArchivesOnlyUnvalidatedFailedFileResponses()throws Exception{
    Docs d=legacyFilePass();String plan=d.get(PREFIX+"_plan").toString();ForgePlanState.Change migration=ForgePlanState.prepare(d::get,PREFIX,selection(),false);assertEquals(3,migration.archived);d.apply(migration);
    assertEquals(plan,d.get(PREFIX+"_plan").toString());assertEquals("completed zero",d.get(PREFIX+"_file_0").getString("content"));assertEquals("completed one",d.get(PREFIX+"_file_1").getString("content"));
    assertNull(d.get(PREFIX+"_file_2_part_0_output_limit"));assertNull(d.get(PHASE));assertNull(d.get(PHASE+"_output_limit"));assertNotNull(d.get("recovery"));assertEquals(72,d.get("recovery").getInt("calls"));
  }
  static Docs legacyFilePass()throws Exception{
    Docs d=new Docs();d.put(PREFIX+"_planning_scope",ForgePlanState.scope(selection()).put("revision","178-direct-planning-v1").put("epoch",4));
    d.put(PREFIX+"_plan",Planning178RegressionTest.filePlan(20));d.put(PREFIX+"_file_0",new JSONObject().put("content","completed zero"));d.put(PREFIX+"_file_1",new JSONObject().put("content","completed one"));
    d.put(PREFIX+"_file_2_part_0_output_limit",new JSONObject().put("confirmed",true).put("partial",""));d.put(PHASE,new JSONObject().put("text","unvalidated raw output"));d.put(PHASE+"_output_limit",new JSONObject().put("confirmed",true).put("partial",""));
    d.put("recovery",new JSONObject().put("calls",72).put("switches",4).put("actions",4));return d;
  }
  @Test public void migrationIsOneTimeAndLeavesNewFailureAllowanceIntact()throws Exception{
    Docs d=legacyFilePass();d.apply(ForgePlanState.prepare(d::get,PREFIX,selection(),false));d.put(PREFIX+"_file_2_part_0_output_limit",new JSONObject().put("confirmed",true).put("partial",""));
    assertFalse(ForgePlanState.prepare(d::get,PREFIX,selection(),false).changed());assertEquals(5,d.get(PREFIX+"_planning_scope").getInt("epoch"));
  }
  @Test public void migrationPreservesChunksFromAnUnfinishedFile()throws Exception{
    Docs d=legacyFilePass();d.put(PREFIX+"_file_2_chunk_0",new JSONObject().put("text","validated prefix").put("complete",false));
    d.put(PREFIX+"_file_2_part_1_expanded_1_output_limit",new JSONObject().put("confirmed",true).put("partial",""));
    d.apply(ForgePlanState.prepare(d::get,PREFIX,selection(),false));assertNotNull(d.get(PREFIX+"_file_2_chunk_0"));assertNotNull(d.get(PREFIX+"_file_2_part_0_output_limit"));assertNull(d.get(PREFIX+"_file_2_part_1_expanded_1_output_limit"));
  }
  @Test public void validatedReadyProjectIsNotDiscardedByPolicyUpgrade()throws Exception{
    Docs d=legacyFilePass();d.put(PREFIX+"_ready",new JSONObject().put("valid",true));ForgePlanState.Change c=ForgePlanState.prepare(d::get,PREFIX,selection(),false);assertEquals(0,c.archived);assertTrue(c.deletes.isEmpty());
  }
  @Test public void archivesPreserveExactOldFailedOutputForInspection()throws Exception{
    Docs d=legacyFilePass();String old=d.get(PHASE).toString();ForgePlanState.Change c=ForgePlanState.prepare(d::get,PREFIX,selection(),false);
    JSONObject archived=c.writes.values().stream().filter(x->PHASE.equals(x.optString("original"))).findFirst().orElseThrow();assertEquals(old,archived.getJSONObject("value").toString());
  }
  @Test public void activeReasoningStopIsNotAnAutomaticModelHandover()throws Exception{
    assertEquals(ForgeRecovery.Kind.STOP,ForgeRecovery.classify(new GenerationWatchdog.SourceExhausted("Source output budget: active reasoning but no file"),true,false));
    assertEquals(ForgeRecovery.Kind.STOP,ForgeRecovery.classify(new ForgeOutputRecovery.SourceOutputBudget(null),true,false));
  }
  @Test public void sourceAndTotalAndIdleFailuresHaveDifferentReadableSummaries(){
    assertTrue(Errors.summary("Source output budget: active reasoning").startsWith("No source text"));
    assertTrue(Errors.summary("Generation watchdog: request exceeded its total time limit.").contains("not necessarily an idle"));
    assertTrue(Errors.summary("Generation watchdog: no new answer or reasoning text within the idle time limit.").startsWith("Generation stalled"));
  }
}
