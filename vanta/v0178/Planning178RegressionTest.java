package com.ronin.vanta;

import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;

public class Planning178RegressionTest {
  static final String PREFIX="repair_files_4";
  static ProviderConfig provider(String kind) { return new ProviderConfig("fixture-"+kind,"Fixture",kind,"https://example.invalid/v1"); }
  static ModelInfo model(String id) { ModelInfo m=new ModelInfo(id,id,"text","");m.codeCapable=true;m.contextTokens=262144;return m; }
  static JSONObject selection(String id)throws Exception{return JobOperations.selection(provider("featherless"),model(id));}
  static class Docs extends OutputRecoveryTest.Docs {
    final Map<String,JSONObject> data=new LinkedHashMap<>();
    @Override public JSONObject get(String k){return data.get(k);}
    @Override public void put(String k,JSONObject v){data.put(k,v);}
    void apply(ForgePlanState.Change c){data.putAll(c.writes);for(String k:c.deletes)data.remove(k);}
  }
  static void exhausted(Docs d)throws Exception {
    for(String key:ForgeOutputRecovery.records(PREFIX+"_plan_response",PREFIX+"_plan_correction"))
      d.put(key,key.endsWith("_output_limit")?new JSONObject().put("confirmed",true).put("partial",""):
        new JSONObject().put("text","invalid or truncated raw reply"));
  }
  static JSONObject filePlan(int count)throws Exception {
    JSONArray files=new JSONArray();for(int i=0;i<count;i++)files.put(new JSONObject().put("path","File"+i+".java"));
    return new JSONObject().put("files",files);
  }

  @Test public void firstPlanUsesDocumentedThinkingToggle()throws Exception{
    JSONObject o=ForgeRequestPolicy.options(provider("featherless"),model("Qwen/Qwen3.8-27B"),PREFIX+"_plan_response");
    assertEquals(Boolean.FALSE,o.getJSONObject("chat_template_kwargs").get("enable_thinking"));assertEquals(1,o.length());
  }
  @Test public void correctionsAndCompactionsUseSamePlanControl()throws Exception{
    for(String phase:new String[]{PREFIX+"_plan_correction",PREFIX+"_plan_response_compact_1",PREFIX+"_plan_response_compact_2","author_plan_response","validation_files_4_1_plan_correction_compact_2"})
      assertFalse(ForgeRequestPolicy.options(provider("featherless"),model("fixture"),phase).getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"));
  }
  @Test public void normalChatAnalysisAndFileRequestsKeepTheirOriginalPolicy()throws Exception{
    for(String phase:new String[]{"chat","analysis","analysis_compact_1","author_file_0_part_0",PREFIX+"_file_1_part_0_expanded_1"})
      assertEquals(0,ForgeRequestPolicy.options(provider("featherless"),model("fixture"),phase).length());
  }
  @Test public void unrelatedProvidersDoNotReceiveFeatherlessFields()throws Exception{
    for(String kind:new String[]{"openai","anthropic","custom","venice"})
      assertFalse(ForgeRequestPolicy.options(provider(kind),model("fixture"),PREFIX+"_plan_response").has("chat_template_kwargs"));
  }
  @Test public void bodyApplyCopiesOnlyWhitelistedBoolean()throws Exception{
    Net.Call c=new Net.Call();c.inferenceOptions=new JSONObject().put("chat_template_kwargs",new JSONObject().put("enable_thinking",false).put("secret","must-not-copy"));
    JSONObject body=new JSONObject();ForgeRequestPolicy.apply(body,provider("featherless"),c);
    assertEquals(1,body.getJSONObject("chat_template_kwargs").length());assertFalse(body.toString().contains("secret"));
    body=new JSONObject();ForgeRequestPolicy.apply(body,provider("anthropic"),c);assertEquals(0,body.length());
  }
  @Test public void stringFalseIsNotAcceptedAsAControlBoolean()throws Exception{
    Net.Call c=new Net.Call();c.inferenceOptions=new JSONObject().put("chat_template_kwargs",new JSONObject().put("enable_thinking","false"));
    JSONObject body=new JSONObject();ForgeRequestPolicy.apply(body,provider("featherless"),c);assertEquals(0,body.length());
  }
  @Test public void requestDiagnosticContainsSafePolicyNotPromptOrKey()throws Exception{
    JSONObject d=ForgeRequestPolicy.diagnostics(provider("featherless"),model("fixture"),PREFIX+"_plan_response");
    assertFalse(d.getBoolean("thinking_requested"));assertEquals(24000,d.getInt("reasoning_only_character_limit"));assertEquals(180000,d.getLong("reasoning_only_time_limit_ms"));
    assertFalse(d.has("api_key"));assertFalse(d.has("prompt"));assertFalse(d.has("reasoning_content"));
  }
  @Test public void exactReportedLegacyStateArchivesAllTwelveRawDocuments()throws Exception{
    Docs d=new Docs();exhausted(d);ForgePlanState.Change c=ForgePlanState.prepare(d::get,PREFIX,selection("Qwen/Qwen3.8-27B"),false);
    assertEquals(12,c.archived);assertEquals(12,c.deletes.size());assertEquals(14,c.writes.size());
    for(String name:c.deletes)assertTrue(c.writes.values().stream().anyMatch(v->name.equals(v.optString("original"))&&v.optJSONObject("value")!=null));
    d.apply(c);for(String name:ForgeOutputRecovery.records(PREFIX+"_plan_response",PREFIX+"_plan_correction"))assertNull(d.get(name));
    assertEquals(1,d.get(PREFIX+"_planning_scope").getInt("epoch"));
  }
  @Test public void originalRequirementsSourceCompilerSigningAndCountersNeverDeleted()throws Exception{
    Docs d=new Docs();exhausted(d);
    for(String key:new String[]{"input","project","build","recovery","signed_apk","partial","source","repair_3","author_file_0",PREFIX+"_file_0"})d.put(key,new JSONObject().put("keep",key));
    ForgePlanState.Change c=ForgePlanState.prepare(d::get,PREFIX,selection("q"),false);d.apply(c);
    for(String key:new String[]{"input","project","build","recovery","signed_apk","partial","source","repair_3","author_file_0",PREFIX+"_file_0"})assertEquals(key,d.get(key).getString("keep"));
  }
  @Test public void repeatedPlainRetryDoesNotResetSameModelBudget()throws Exception{
    Docs d=new Docs();d.apply(ForgePlanState.prepare(d::get,PREFIX,selection("q"),false));exhausted(d);
    ForgePlanState.Change c=ForgePlanState.prepare(d::get,PREFIX,selection("q"),false);assertFalse(c.changed());assertNotNull(d.get(PREFIX+"_plan_response_compact_2_output_limit"));
  }
  @Test public void changedModelCannotInheritTheOldExhaustedAllowance()throws Exception{
    Docs d=new Docs();d.apply(ForgePlanState.prepare(d::get,PREFIX,selection("old"),false));exhausted(d);
    ForgePlanState.Change c=ForgePlanState.prepare(d::get,PREFIX,selection("new"),false);assertEquals(12,c.archived);d.apply(c);
    assertEquals("new",d.get(PREFIX+"_planning_scope").getString("model"));assertEquals(2,d.get(PREFIX+"_planning_scope").getInt("epoch"));
  }
  @Test public void explicitSameModelChoiceRenewsOnlyUnvalidatedPlanning()throws Exception{
    Docs d=new Docs();d.apply(ForgePlanState.prepare(d::get,PREFIX,selection("q"),false));exhausted(d);
    ForgePlanState.Change c=ForgePlanState.prepare(d::get,PREFIX,selection("q"),true);assertEquals(12,c.archived);
    assertEquals("explicit_model_choice",c.writes.get(PREFIX+"_planning_scope").getString("reason"));
  }
  @Test public void differentEndpointInvalidatesSameNamedModelRawResponses()throws Exception{
    Docs d=new Docs();JSONObject choice=selection("q");d.apply(ForgePlanState.prepare(d::get,PREFIX,choice,false));exhausted(d);
    choice.getJSONObject("provider").put("baseUrl","https://other.invalid/v1");
    assertEquals(12,ForgePlanState.prepare(d::get,PREFIX,choice,false).archived);
  }
  @Test public void policyRevisionMigrationIsAppliedOnlyOnce()throws Exception{
    Docs d=new Docs();d.apply(ForgePlanState.prepare(d::get,PREFIX,selection("q"),false));exhausted(d);
    d.get(PREFIX+"_planning_scope").put("revision","legacy");d.apply(ForgePlanState.prepare(d::get,PREFIX,selection("q"),false));
    assertFalse(ForgePlanState.prepare(d::get,PREFIX,selection("q"),false).changed());
  }
  @Test public void validatedPlanAndCompleteFilesAreReused()throws Exception{
    Docs d=new Docs();exhausted(d);d.put(PREFIX+"_plan",filePlan(2));d.put(PREFIX+"_file_0",new JSONObject().put("content","complete"));
    d.put(PREFIX+"_file_0_part_0_output_limit",new JSONObject().put("partial","kept"));
    ForgePlanState.Change c=ForgePlanState.prepare(d::get,PREFIX,selection("q"),true);d.apply(c);
    assertNotNull(d.get(PREFIX+"_plan"));assertNotNull(d.get(PREFIX+"_plan_response"));assertNotNull(d.get(PREFIX+"_file_0_part_0_output_limit"));
  }
  @Test public void validatedContinuationChunksSurviveManualModelSelection()throws Exception{
    Docs d=new Docs();d.put(PREFIX+"_plan",filePlan(1));
    d.put(PREFIX+"_file_0_chunk_0",new JSONObject().put("content","valid first part"));
    d.put(PREFIX+"_file_0_part_0",new JSONObject().put("text","first response"));
    d.put(PREFIX+"_file_0_part_1_expanded_1_output_limit",new JSONObject().put("confirmed",true));
    ForgePlanState.Change c=ForgePlanState.prepare(d::get,PREFIX,selection("q"),true);assertEquals(1,c.archived);d.apply(c);
    assertNotNull(d.get(PREFIX+"_file_0_chunk_0"));assertNotNull(d.get(PREFIX+"_file_0_part_0"));assertNull(d.get(PREFIX+"_file_0_part_1_expanded_1_output_limit"));
  }
  @Test public void readyProjectIsNeverInvalidatedOrReauthored()throws Exception{
    Docs d=new Docs();exhausted(d);d.put(PREFIX+"_ready",new JSONObject().put("ready",true));
    ForgePlanState.Change c=ForgePlanState.prepare(d::get,PREFIX,selection("new"),true);assertEquals(0,c.archived);assertTrue(c.deletes.isEmpty());
  }
  @Test public void otherRepairRoundsKeepTheirRawRecords()throws Exception{
    Docs d=new Docs();exhausted(d);d.put("repair_files_3_plan_response_output_limit",new JSONObject().put("keep",true));
    d.apply(ForgePlanState.prepare(d::get,PREFIX,selection("q"),false));assertNotNull(d.get("repair_files_3_plan_response_output_limit"));
  }
  @Test public void buildRoundWinsOverStaleSourcePrefix()throws Exception{
    assertEquals(PREFIX,ForgePlanState.prefix(new JSONObject().put("source_prefix","repair_files_3"),new JSONObject().put("phase","repair").put("attempt",4)));
  }
  @Test public void sourceTargetSelectionMatchesBuildAndIgnoresUnrelatedGenerator()throws Exception{
    JSONObject input=selection("wrong").put("target",selection("correct"));JSONObject build=new JSONObject().put("provider","fixture-featherless").put("model","correct");
    assertEquals("correct",ForgePlanState.selection(input,build).getJSONObject("model").getString("id"));
  }
  @Test public void mismatchedSelectionStopsBeforeClearingAnything()throws Exception{
    try{ForgePlanState.selection(selection("wrong"),new JSONObject().put("provider","fixture-featherless").put("model","right"));fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("do not match"));}
  }
  @Test public void invalidCheckpointPrefixRejected()throws Exception{
    try{ForgePlanState.prepare(k->null,"../../other",selection("q"),true);fail();}catch(IOException expected){}
  }
  @Test public void emptyConfirmedPlanDoesNotEscalateToTwoLargerRequests()throws Exception{
    Docs d=new Docs();AtomicInteger calls=new AtomicInteger();
    try{ForgeOutputRecovery.plan(PREFIX+"_plan_response","system","request",64000,(id,s,u,t)->{calls.incrementAndGet();throw new ChatProtocol.Incomplete("Output limit reached","",true);},d);fail();}
    catch(ForgeRecovery.InvalidOutput expected){assertTrue(expected.getMessage().startsWith("Planning output budget:"));}
    assertEquals(1,calls.get());assertNotNull(d.get(PREFIX+"_plan_response_output_limit"));assertNull(d.get(PREFIX+"_plan_response_compact_1_output_limit"));
  }
  @Test public void existingEmptyConfirmedPlanNeedsNoAdditionalPaidRequest()throws Exception{
    Docs d=new Docs();d.put(PREFIX+"_plan_response_output_limit",new JSONObject().put("confirmed",true).put("partial",""));
    try{ForgeOutputRecovery.plan(PREFIX+"_plan_response","s","u",60000,(a,b,c,t)->{fail("No request expected");return "";},d);fail();}
    catch(ForgeRecovery.InvalidOutput expected){}
  }
  @Test public void truncatedNonemptyJsonCanStillRecoverWithinBoundedAllowance()throws Exception{
    Docs d=new Docs();AtomicInteger calls=new AtomicInteger();
    String out=ForgeOutputRecovery.plan(PREFIX+"_plan_response","s","u",60000,(a,b,c,t)->{if(calls.getAndIncrement()==0)throw new ChatProtocol.Incomplete("Output limit reached","{\"name\":",true);return "{\"files\":[]}";},d);
    assertEquals(2,calls.get());assertEquals("{\"files\":[]}",out);
  }
  @Test public void unknownInterruptedResponseDoesNotBecomePlanningRetry()throws Exception{
    Docs d=new Docs();AtomicInteger calls=new AtomicInteger();
    try{ForgeOutputRecovery.plan(PREFIX+"_plan_response","s","u",64000,(a,b,c,t)->{calls.incrementAndGet();throw new ChatProtocol.Incomplete("broken stream","",false);},d);fail();}
    catch(ChatProtocol.Incomplete expected){assertFalse(expected.confirmedOutputLimit);}
    assertEquals(1,calls.get());assertNull(d.get(PREFIX+"_plan_response_output_limit"));
  }
  @Test public void plannerGuardRecognisesActiveReasoningWithoutCallingItIdle()throws Exception{
    AtomicLong now=new AtomicLong();Net.Call c=new Net.Call();
    try(GenerationWatchdog w=new GenerationWatchdog(c,now::get,240000,720000,true)){
      c.generation.text("reasoning","x".repeat(24000));w.checkNow();assertTrue(w.expired());
      assertTrue(w.failure(new IOException()) instanceof GenerationWatchdog.PlanningExhausted);
      assertEquals(ForgeRecovery.Kind.STOP,ForgeRecovery.classify(w.failure(new IOException()),true,false));
      assertTrue(c.diagnostics().getString("timeout_reason").contains("stream is active"));
    }
  }
  @Test public void plannerTimeAllowanceStopsContinuousReasoningAfterThreeMinutes()throws Exception{
    AtomicLong now=new AtomicLong();Net.Call c=new Net.Call();
    try(GenerationWatchdog w=new GenerationWatchdog(c,now::get,240000,720000,true)){
      for(int second=30;second<=180;second+=30){now.set(second*1000000000L);c.generation.text("reasoning","short");w.checkNow();if(second<180)assertFalse(w.expired());}
      assertTrue(w.failure(new IOException()) instanceof GenerationWatchdog.PlanningExhausted);
    }
  }
  @Test public void actualAnswerPreventsReasoningOnlyGuardButNotOverallTimeout()throws Exception{
    AtomicLong now=new AtomicLong();Net.Call c=new Net.Call();
    try(GenerationWatchdog w=new GenerationWatchdog(c,now::get,240000,720000,true)){
      c.generation.text("answer","{");c.generation.text("reasoning","x".repeat(25000));w.checkNow();assertFalse(w.expired());
      now.set(720000000000L);c.generation.text("answer","}");w.checkNow();assertTrue(w.expired());assertFalse(w.failure(new IOException()) instanceof GenerationWatchdog.PlanningExhausted);
    }
  }
  @Test public void ordinaryCodeRequestsCanReasonWithoutPlannerOnlyCharacterCap()throws Exception{
    Net.Call c=new Net.Call();try(GenerationWatchdog w=new GenerationWatchdog(c)){
      c.generation.text("reasoning","x".repeat(80000));w.checkNow();assertFalse(w.expired());
    }
  }
  @Test public void emptyTransportStillUsesTrueIdleDeadlineNotReasoningPolicy()throws Exception{
    AtomicLong now=new AtomicLong();Net.Call c=new Net.Call();
    try(GenerationWatchdog w=new GenerationWatchdog(c,now::get,240000,720000,true)){
      now.set(180000000000L);w.checkNow();assertFalse(w.expired());now.set(240000000000L);w.checkNow();assertTrue(w.expired());assertFalse(w.failure(new IOException()) instanceof GenerationWatchdog.PlanningExhausted);
    }
  }
  @Test public void whitespaceIsNotAUsablePlanAnswer()throws Exception{
    Net.Call c=new Net.Call();try(GenerationWatchdog w=new GenerationWatchdog(c,true)){
      c.generation.text("answer"," \n");c.generation.text("reasoning","x".repeat(24000));w.checkNow();assertTrue(w.expired());
    }
  }
  @Test public void userCancellationHasPriorityOverPlannerBudget()throws Exception{
    Net.Call c=new Net.Call();try(GenerationWatchdog w=new GenerationWatchdog(c,true)){
      c.cancel();c.generation.text("reasoning","x".repeat(24000));w.checkNow();assertFalse(w.expired());
    }
  }
  @Test public void newRequestResetsReasoningAndAnswerCounters()throws Exception{
    Net.Call c=new Net.Call();try(GenerationWatchdog w=new GenerationWatchdog(c,true)){c.generation.text("reasoning","first");}
    try(GenerationWatchdog w=new GenerationWatchdog(c,true)){assertEquals(0,c.generation.diagnostics().getInt("reasoning_characters"));c.generation.text("answer","{}");w.checkNow();assertFalse(w.expired());}
  }
  @Test public void planningStopHasADistinctReadableError()throws Exception{
    String summary=Errors.summary("Planning output budget: generated reasoning but no final plan.");assertTrue(summary.contains("No usable repair plan"));assertFalse(summary.contains("Generation stalled"));
  }
}
