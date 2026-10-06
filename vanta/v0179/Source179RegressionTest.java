package com.ronin.vanta;
import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;

public class Source179RegressionTest {
  static final String PHASE="repair_files_4_file_2_part_0";
  static ProviderConfig p(String kind){return Planning178RegressionTest.provider(kind);}
  static ModelInfo m(){return Planning178RegressionTest.model("qa-source-model");}
  static JSONObject project(String path,String content)throws Exception{return new JSONObject().put("name","QA").put("files",new JSONArray().put(new JSONObject().put("path",path).put("content",content)));}
  static String opaque(){return "e: Could not load module <Error module>\n> Task :app:kaptGenerateStubsReleaseKotlin FAILED\nBUILD FAILED in 2m 12s\n";}
  @Test public void exactReportedSourceAndExpandedRequestsUseDirectControl()throws Exception{
    for(String phase:new String[]{PHASE,PHASE+"_expanded_1","author_file_0_part_0","validation_files_4_2_file_5_part_3"})
      assertFalse(ForgeRequestPolicy.options(p("featherless"),m(),phase).getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"));
  }
  @Test public void regularChatAndAnalysisKeepTheirOriginalRequestShape()throws Exception{
    for(String phase:new String[]{"chat","analysis","voice","research_file_2","source_text"})assertEquals(0,ForgeRequestPolicy.options(p("featherless"),m(),phase).length());
  }
  @Test public void sourcePhaseClassifierRejectsUnrelatedAndOutOfRangeParts(){
    for(String phase:new String[]{"chat", "author_file_0_part_4", "author_file_0_part_0_extra", "file_2_part_0"})assertFalse(ForgeRequestPolicy.sourceFile(phase));assertFalse(ForgeRequestPolicy.sourceFile(null));
  }
  @Test public void unrelatedProvidersAreNotSentFeatherlessTemplateOptions()throws Exception{
    for(String kind:new String[]{"openai","anthropic","venice","custom"})assertFalse(ForgeRequestPolicy.options(p(kind),m(),PHASE).has("chat_template_kwargs"));
  }
  @Test public void actualBodyReceivesOnlyWhitelistedControl()throws Exception{
    Net.Call call=new Net.Call();call.inferenceOptions=ForgeRequestPolicy.options(p("featherless"),m(),PHASE);call.inferenceOptions.put("never_copy","private-placeholder");
    JSONObject body=new JSONObject().put("model","qa");ForgeRequestPolicy.apply(body,p("featherless"),call);
    assertFalse(body.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"));assertFalse(body.toString().contains("never_copy"));
  }
  @Test public void sourcePolicyDiagnosticContainsSeparateLimitsNotRawReasoning()throws Exception{
    JSONObject d=ForgeRequestPolicy.diagnostics(p("featherless"),m(),PHASE);
    assertTrue(d.getBoolean("source_file"));assertFalse(d.getBoolean("structured_plan"));assertEquals(48000,d.getLong("reasoning_only_character_limit"));assertEquals(360000,d.getLong("reasoning_only_time_limit_ms"));assertEquals(1800000,d.getLong("overall_time_limit_ms"));assertFalse(d.has("reasoning_content"));
  }
  @Test public void emptyConfirmedSourceLimitNeverPurchasesExpandedRequest()throws Exception{
    AtomicInteger calls=new AtomicInteger();OutputRecoveryTest.Docs d=new OutputRecoveryTest.Docs();
    try{ForgeOutputRecovery.filePart(PHASE,"sys","input",(id,s,u,n)->{calls.incrementAndGet();throw new ChatProtocol.Incomplete("limit","",true);},d);fail();}
    catch(ForgeOutputRecovery.SourceAnswerMissing expected){assertEquals(1,calls.get());assertNotNull(d.get(PHASE+"_output_limit"));assertNull(d.get(PHASE+"_expanded_1_output_limit"));}
  }
  @Test public void nonemptyConfirmedSourceCutoffRemainsAContinuationNotARewrite()throws Exception{
    AtomicInteger calls=new AtomicInteger();
    try{ForgeOutputRecovery.filePart(PHASE,"sys","input",(id,s,u,n)->{calls.incrementAndGet();throw new ChatProtocol.Incomplete("limit","package qa;\n",true);},new OutputRecoveryTest.Docs());fail();}
    catch(ChatProtocol.Incomplete expected){assertTrue(expected.confirmedOutputLimit);assertEquals("package qa;\n",expected.partial);assertEquals(1,calls.get());}
  }
  @Test public void uncertainInterruptedSourceNeverCountsAsCapacityOrSafeReplay()throws Exception{
    AtomicInteger calls=new AtomicInteger();
    try{ForgeOutputRecovery.filePart(PHASE,"s","u",(id,s,u,n)->{calls.incrementAndGet();throw new ChatProtocol.Incomplete("lost socket","half",false);},new OutputRecoveryTest.Docs());fail();}
    catch(ChatProtocol.Incomplete expected){assertFalse(expected.confirmedOutputLimit);assertEquals(1,calls.get());}
  }
  @Test public void sourceRefusalIsNotReroutedByOutputRecovery()throws Exception{
    AtomicInteger calls=new AtomicInteger();try{ForgeOutputRecovery.filePart(PHASE,"s","u",(id,s,u,n)->{calls.incrementAndGet();throw new ChatProtocol.Refused("declined");},new OutputRecoveryTest.Docs());fail();}catch(ChatProtocol.Refused expected){assertEquals(1,calls.get());}
  }
  @Test public void successfulSourceIsReturnedByteForByte()throws Exception{
    String text="package qa;\nclass Actual {}\n";assertEquals(text,ForgeOutputRecovery.filePart(PHASE,"s","u",(id,s,u,n)->text,new OutputRecoveryTest.Docs()));
  }
  @Test public void sourceReasoningLimitIsNotMislabelledIdle(){
    AtomicLong time=new AtomicLong();Net.Call c=new Net.Call();try(GenerationWatchdog w=new GenerationWatchdog(c,time::get,240000,1800000,false,true)){
      w.advanced("reasoning",48000);w.checkNow();assertTrue(w.expired());assertTrue(w.failure(new IOException()) instanceof GenerationWatchdog.SourceExhausted);assertTrue(w.failure(new IOException()).getMessage().contains("actively reasoning"));
    }
  }
  @Test public void sourceReasoningTimeBoundUsesMeaningfulActivity(){
    AtomicLong time=new AtomicLong();Net.Call c=new Net.Call();try(GenerationWatchdog w=new GenerationWatchdog(c,time::get,240000,1800000,false,true)){
      time.set(TimeUnit.MILLISECONDS.toNanos(360001));w.advanced("reasoning",1);w.checkNow();assertTrue(w.failure(new IOException()) instanceof GenerationWatchdog.SourceExhausted);
    }
  }
  @Test public void actualCodeOutputIsAllowedPastFormerTwelveMinuteDeadline(){
    AtomicLong time=new AtomicLong();try(GenerationWatchdog w=new GenerationWatchdog(new Net.Call(),time::get,240000,1800000,false,true)){
      time.set(TimeUnit.MINUTES.toNanos(13));w.advanced("answer",4000);w.checkNow();assertFalse(w.expired());
    }
  }
  @Test public void outputStillHasAnOverallBound(){
    AtomicLong time=new AtomicLong();try(GenerationWatchdog w=new GenerationWatchdog(new Net.Call(),time::get,240000,1800000,false,true)){
      time.set(TimeUnit.MINUTES.toNanos(31));w.advanced("answer",4000);w.checkNow();assertTrue(w.expired());assertTrue(w.failure(new IOException()).getMessage().contains("total time limit"));
    }
  }
  @Test public void transportHeartbeatsDoNotExtendSourceIdleAllowance(){
    AtomicLong time=new AtomicLong();try(GenerationWatchdog w=new GenerationWatchdog(new Net.Call(),time::get,240000,1800000,false,true)){
      time.set(TimeUnit.MILLISECONDS.toNanos(240001));w.advanced("heartbeat",50);w.checkNow();assertTrue(w.expired());assertTrue(w.failure(new IOException()).getMessage().contains("idle time limit"));
    }
  }
  @Test public void sourceWithSomeAnswerDoesNotTriggerReasoningOnlyGuard(){
    AtomicLong time=new AtomicLong();try(GenerationWatchdog w=new GenerationWatchdog(new Net.Call(),time::get,240000,1800000,false,true)){w.advanced("answer",20);w.advanced("reasoning",66000);w.checkNow();assertFalse(w.expired());}
  }
  @Test public void closedWatchdogCannotAbortSubsequentRequest(){
    AtomicLong time=new AtomicLong();Net.Call c=new Net.Call();GenerationWatchdog w=new GenerationWatchdog(c,time::get,1,2,false,true);w.close();time.set(TimeUnit.SECONDS.toNanos(10));w.checkNow();assertFalse(w.expired());
  }
  @Test public void timeoutSummariesDistinguishActualCauses(){
    String total=Errors.summary("Generation watchdog: request exceeded its total time limit.");String idle=Errors.summary("Generation watchdog: no new answer or reasoning text within the idle time limit.");String source=Errors.summary("Source output budget: actively reasoning");
    assertNotEquals(total,idle);assertFalse(total.contains("stalled"));assertTrue(source.contains("No source-file answer"));
  }
  @Test public void exactLegacyFileFailureMigrationKeepsPlanAndCompletedWork()throws Exception{
    Planning178RegressionTest.Docs d=new Planning178RegressionTest.Docs();String prefix="repair_files_4";
    d.put(prefix+"_plan",Planning178RegressionTest.filePlan(3));d.put(prefix+"_file_0",new JSONObject().put("content","done0"));d.put(prefix+"_file_1",new JSONObject().put("content","done1"));
    d.put(PHASE+"_output_limit",new JSONObject().put("confirmed",true));d.put(PHASE+"_expanded_1_output_limit",new JSONObject().put("confirmed",true));
    d.apply(ForgePlanState.prepare(d::get,prefix,Planning178RegressionTest.selection("q"),false));
    assertNotNull(d.get(prefix+"_plan"));assertEquals("done0",d.get(prefix+"_file_0").getString("content"));assertEquals("done1",d.get(prefix+"_file_1").getString("content"));assertNull(d.get(PHASE+"_output_limit"));
  }
  @Test public void validatedChunkBeforeUnfinishedSourcePartSurvivesMigration()throws Exception{
    Planning178RegressionTest.Docs d=new Planning178RegressionTest.Docs();String prefix="repair_files_4";d.put(prefix+"_plan",Planning178RegressionTest.filePlan(3));
    d.put(prefix+"_file_2_chunk_0",new JSONObject().put("text","validated"));d.put(prefix+"_file_2_part_1_expanded_1_output_limit",new JSONObject().put("confirmed",true));
    d.apply(ForgePlanState.prepare(d::get,prefix,Planning178RegressionTest.selection("q"),false));assertEquals("validated",d.get(prefix+"_file_2_chunk_0").getString("text"));assertNull(d.get(prefix+"_file_2_part_1_expanded_1_output_limit"));
  }
  @Test public void compilerParserReadsRealKotlinSourceCoordinates(){
    String log="e: file:///work/project/app/src/main/java/qa/A.kt:12:8 Unresolved reference: Missing\ne: /work/project/app/src/main/java/qa/B.kt: (9, 2): Type mismatch\n";
    Map<String,List<String>> errors=ForgeCodeDiagnostics.errors(log);assertEquals(2,errors.size());assertTrue(errors.get("app/src/main/java/qa/A.kt").get(0).contains(":12:8:"));
  }
  @Test public void repeatedCompilerEntriesAreDeduplicated(){String row="e: file:///work/project/app/src/main/java/qa/A.kt:12:8 Unresolved reference: Missing\n";assertEquals(1,ForgeCodeDiagnostics.errors(row.repeat(30)).get("app/src/main/java/qa/A.kt").size());}
  @Test public void compactDiagnosticsPrioritiseTheCurrentFileInsteadOfStackTrace(){
    StringBuilder log=new StringBuilder(opaque());for(int i=0;i<80;i++)log.append("e: file:///work/project/app/src/main/java/qa/A.kt:").append(i+1).append(":8 Unknown A\n");log.append("e: file:///work/project/app/src/main/java/qa/Z.kt:7:1 TargetProblem\n");
    String context=ForgeCodeDiagnostics.compact(log.toString(),"app/src/main/java/qa/Z.kt",2200);assertTrue(context.contains("TargetProblem"));assertTrue(context.length()<=2200);
  }
  @Test public void errorFactsDoNotBecomeACommandToSkipProductionTests(){String log=opaque()+ForgeCodeDiagnostics.MARKER+"\ne: file:///work/project/app/src/main/java/qa/A.kt:2:1 Missing\nBUILD SUCCESSFUL\n";String context=ForgeCodeDiagnostics.compact(log,null,1500);assertTrue(context.contains("normal build failed"));assertTrue(context.contains("Never use these exclusions"));}
  @Test public void opaqueKaptOnlyTriggersBeforeUsefulFactsAreAvailable(){assertTrue(ForgeCodeDiagnostics.opaqueKapt(opaque()));assertFalse(ForgeCodeDiagnostics.opaqueKapt(opaque()+ForgeCodeDiagnostics.MARKER));assertFalse(ForgeCodeDiagnostics.opaqueKapt(opaque()+"e: file:///work/project/app/src/main/java/qa/A.kt:2:1 Missing\n"));assertFalse(ForgeCodeDiagnostics.opaqueKapt("network failed"));}
  @Test public void otherKotlinBuildFailuresAreNotMisclassifiedAsOpaqueKapt(){assertFalse(ForgeCodeDiagnostics.opaqueKapt("Could not load module <Error module>\n:app:compileReleaseKotlin FAILED"));}
  @Test public void unknownLogKeepsActualBuildFailureAndDoesNotInventFileLocations(){String out=ForgeCodeDiagnostics.compact(opaque(),null,2500);assertTrue(out.contains("Full original log"));assertTrue(ForgeCodeDiagnostics.errors(opaque()).isEmpty());}
  @Test public void declaredClassNameNotFilenameDeterminesImportDependency()throws Exception{
    JSONObject p=project("app/src/main/java/qa/Database.kt","package qa\nabstract class RoninDatabase {}\n");p.getJSONArray("files").put(new JSONObject().put("path","app/src/main/java/qa/Repository.kt").put("content","package qa\nimport qa.RoninDatabase\nclass Repository {}\n"));
    assertTrue(ForgeCodeDiagnostics.related(p,"app/src/main/java/qa/Repository.kt").contains("app/src/main/java/qa/Database.kt"));assertTrue(ForgeCodeDiagnostics.inventory(p,2000).contains("qa.RoninDatabase"));
  }
  @Test public void declarationInventoryNeverSilentlyMovesTheClassPackage(){assertEquals(Collections.singletonList("qa.data.Capture"),ForgeCodeDiagnostics.declaredTypes("package qa.data\ndata class Capture(val id: String)\n"));}
  @Test public void memberImportResolvesItsDeclaredOwner()throws Exception{
    JSONObject p=project("app/src/main/java/qa/Database.kt","package qa\nclass RoninDatabase {\n  class Dao {}\n}\n");p.getJSONArray("files").put(new JSONObject().put("path","app/src/main/java/qa/R.kt").put("content","import qa.RoninDatabase.Dao\n"));assertTrue(ForgeCodeDiagnostics.related(p,"app/src/main/java/qa/R.kt").contains("app/src/main/java/qa/Database.kt"));
  }
  @Test public void commentTextCannotInventDeclarations(){assertTrue(ForgeCodeDiagnostics.declaredTypes("/* package fake\nclass Fake {} */\n// class NotAType\n").isEmpty());}
  @Test public void diagnosticsDoNotAlterSourceBytes()throws Exception{JSONObject p=project("app/src/main/java/qa/A.kt","package qa\nclass A {}\n");String before=p.toString();ForgeCodeDiagnostics.inventory(p,900);ForgeCodeDiagnostics.related(p,"app/src/main/java/qa/A.kt");assertEquals(before,p.toString());}
  @Test public void diagnosticRefreshIsBoundToSourceAndRound()throws Exception{
    JSONObject p=project("app/src/main/java/qa/A.kt","package qa; class A {}");String first=ForgeDiagnosticRefresh.key(p,4);assertEquals(first,ForgeDiagnosticRefresh.key(new JSONObject(p.toString()),4));assertNotEquals(first,ForgeDiagnosticRefresh.key(p,5));p.getJSONArray("files").getJSONObject(0).put("content","package qa; class B {}");assertNotEquals(first,ForgeDiagnosticRefresh.key(p,4));
  }
}
