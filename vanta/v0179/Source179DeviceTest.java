package com.ronin.vanta;
import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class Source179DeviceTest {
  final Planning178DeviceTest g=new Planning178DeviceTest(); PipelineDeviceTest f; JobEngine e; ProviderConfig p;
  static final String PREFIX="repair_files_4", PHASE=PREFIX+"_file_2_part_0";
  @Before public void prepare()throws Exception{g.prepare();f=g.f;e=g.e;p=g.p;}
  @After public void cleanup()throws Exception{g.cleanup();}
  static String opaque(){return "e: Could not load module <Error module>\n> Task :app:kaptGenerateStubsReleaseKotlin FAILED\nBUILD FAILED in 2m 12s\n";}
  byte[] zipLog(String log)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(b)){z.putNextEntry(new ZipEntry("build.log"));z.write(log.getBytes(StandardCharsets.UTF_8));z.closeEntry();}return b.toByteArray();}
  @Test public void approvedRetryMigratesFileTwoFailureAndPreservesCompletedFilesCountersAndPartial()throws Exception{
    VantaJob j=g.seed();j.json.put("phase",PHASE+"_expanded_1").put("files_completed",2).put("files_planned",20);e.store.save(j);
    JSONObject recovery=e.store.document(j.id(),"recovery").put("calls",72);e.store.document(j.id(),"recovery",recovery);
    e.store.document(j.id(),PREFIX+"_plan",new JSONObject().put("files",new JSONArray().put(new JSONObject().put("path","a.java")).put(new JSONObject().put("path","b.java")).put(new JSONObject().put("path","c.java"))));
    e.store.document(j.id(),PREFIX+"_file_0",new JSONObject().put("content","complete A"));e.store.document(j.id(),PREFIX+"_file_1",new JSONObject().put("content","complete B"));
    e.store.document(j.id(),PHASE+"_output_limit",new JSONObject().put("confirmed",true).put("partial",""));
    String input=g.document(j,"input"),source=g.document(j,"project"),partial=g.document(j,"partial");
    e.setHandlerForTests((en,job,in,c)->{
      assertNull(en.store.document(job.id(),PHASE+"_output_limit"));assertNotNull(en.store.document(job.id(),PREFIX+"_plan"));
      assertEquals("complete A",en.store.document(job.id(),PREFIX+"_file_0").getString("content"));assertEquals("complete B",en.store.document(job.id(),PREFIX+"_file_1").getString("content"));throw new JobOperations.Blocked("Fixture stops without inference");
    });g.retry(j);g.assertBudget(j,72);assertEquals(input,g.document(j,"input"));assertEquals(source,g.document(j,"project"));assertEquals(partial,g.document(j,"partial"));
  }
  @Test public void completeFileReplyUsesDirectPolicyThenNormalWorkerArtifactIsSignedAndPublished()throws Exception{
    VantaJob j=g.seed();JSONObject source=f.project();String path="app/src/main/java/com/ronin/forge/verification/ReportFormatter.java";
    String correct=source.getJSONArray("files").getJSONObject(5).getString("content");source.getJSONArray("files").getJSONObject(5).put("content","package com.ronin.forge.verification; public final class ReportFormatter { public static String report(String location,String plate){return missingCompileSymbol;} }");
    e.store.document(j.id(),"build",f.session(source,p,4).put("phase","repair").put("log",path+":7:1: error: cannot find symbol missingCompileSymbol"));e.store.document(j.id(),"project",source);
    // First retry has already cleared the legacy planning allowance in production.
    ForgePlanState.Change changed=ForgePlanState.prepare(k->{try{return e.store.document(j.id(),k);}catch(Exception x){throw new RuntimeException(x);}},PREFIX,JobOperations.selection(p,g.m),false);
    for(Map.Entry<String,JSONObject> item:changed.writes.entrySet())e.store.document(j.id(),item.getKey(),item.getValue());for(String key:changed.deletes)e.store.deleteDocument(j.id(),key);
    AtomicInteger ai=new AtomicInteger();e.overrideChat(j.id(),(provider,key,id,history,system,max,nativePrompt,web,call,stream)->{
      assertFalse(call.inferenceOptions.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"));
      if(ai.getAndIncrement()==0)return new JSONObject().put("name","Verification").put("architecture","Preserve formatter contract").put("files",new JSONArray().put(new JSONObject().put("path",path).put("purpose","Fix missing symbol"))).toString();
      assertTrue(call.generationPolicy.getBoolean("source_file"));assertFalse(call.generationPolicy.getBoolean("structured_plan"));assertEquals(1800000,call.generationPolicy.getLong("overall_time_limit_ms"));return correct;
    });
    PipelineDeviceTest.Worker worker=f.new Worker(false);worker.pending=true;Net.Call call=new Net.Call();call.transport=worker;
    try{JobOperations.run(e,e.store.get(j.id()),e.store.document(j.id(),"input"),call);fail();}catch(JobEngine.Deferred expected){}
    assertEquals(2,ai.get());assertEquals(1,worker.puts);worker.pending=false;JobOperations.run(e,e.store.get(j.id()),e.store.document(j.id(),"input"),call);f.assertReady(j);assertEquals(2,ai.get());assertEquals(1,worker.puts);
  }
  @Test public void sourceEmptyLimitDoesNotSpendAnExpandedRequestInActualAskPath()throws Exception{
    VantaJob j=g.seed();JSONObject source=f.project();String path="app/src/main/java/com/ronin/forge/verification/ReportFormatter.java";
    e.store.document(j.id(),"build",f.session(source,p,4).put("phase","repair").put("log",path+":7:1: error: Missing"));e.store.document(j.id(),"project",source);
    e.store.document(j.id(),PREFIX+"_plan",new JSONObject().put("name","Fixture").put("architecture","Preserve API").put("files",new JSONArray().put(new JSONObject().put("path",path).put("purpose","Repair missing symbol"))));
    AtomicInteger ai=new AtomicInteger();e.overrideChat(j.id(),(provider,key,id,history,system,max,nativePrompt,web,call,stream)->{ai.incrementAndGet();assertTrue(call.generationPolicy.getBoolean("source_file"));throw new ChatProtocol.Incomplete("Output limit reached","",true);});
    Net.Call call=new Net.Call();call.transport=f.new Worker(false);
    try{JobOperations.run(e,e.store.get(j.id()),e.store.document(j.id(),"input"),call);fail();}catch(ForgeOutputRecovery.SourceAnswerMissing expected){}
    assertEquals(1,ai.get());g.assertBudget(j,67);assertNotNull(e.store.document(j.id(),PREFIX+"_file_0_part_0_output_limit"));assertNull(e.store.document(j.id(),PREFIX+"_file_0_part_0_expanded_1_output_limit"));
  }
  @Test public void opaqueCompilerRefreshReusesSameUploadAndDoesNotBuyInference()throws Exception{
    VantaJob j=g.seed();JSONObject source=f.project(),session=f.session(source,p,4).put("phase","repair").put("log",opaque());e.store.document(j.id(),"build",session);
    PipelineDeviceTest.Worker worker=f.new Worker(false);worker.pending=true;Net.Call call=new Net.Call();call.transport=worker;
    try{ForgeDiagnosticRefresh.obtain(e,j,session,source,"fixture",call);fail();}catch(JobEngine.Deferred expected){}
    assertEquals(1,worker.puts);JSONObject saved=e.store.document(j.id(),"build");assertTrue(saved.getBoolean("diagnostic_refresh_pending"));
    try{ForgeDiagnosticRefresh.obtain(e,j,saved,source,"fixture",call);fail();}catch(JobEngine.Deferred expected){}
    assertEquals(1,worker.puts);worker.pending=false;ForgeClient.BuildResult result=ForgeDiagnosticRefresh.obtain(e,j,saved,source,"fixture",call);
    assertTrue(result.success);assertNotNull(result.apk);assertEquals(1,worker.puts);g.assertBudget(j,66);assertFalse(e.store.document(j.id(),"build").getBoolean("diagnostic_refresh_pending"));
  }
  @Test public void failedDiagnosticResultIsCachedAndNeverBecomesAnApk()throws Exception{
    VantaJob j=g.seed();JSONObject source=f.project(),session=f.session(source,p,4).put("phase","repair").put("log",opaque());e.store.document(j.id(),"build",session);
    PipelineDeviceTest.Worker delegate=f.new Worker(false);AtomicInteger requests=new AtomicInteger();
    String enriched=opaque()+ForgeCodeDiagnostics.MARKER+"\ne: file:///work/project/app/src/main/java/qa/A.kt:12:8 Unresolved reference: Missing\nDIAGNOSTIC_ONLY_END original_build_failed\n";
    Net.Call call=new Net.Call();call.transport=new PipelineDeviceTest.Transport(){
      public Net.Response request(String method,String url,Map<String,String> headers,byte[] body,String type,int limit,Net.Call c)throws Exception{
        requests.incrementAndGet();Net.Response response=delegate.request(method,url,headers,body,type,limit,c);
        if(method.equals("PUT")){delegate.failure=true;return f.json(new JSONObject().put("commit",new JSONObject().put("sha","failed-sha")));}return response;
      }
      public Net.Response download(String url,Map<String,String> headers,int limit,Net.Call c)throws Exception{requests.incrementAndGet();return new Net.Response(zipLog(enriched),"application/zip");}
    };
    ForgeClient.BuildResult first=ForgeDiagnosticRefresh.obtain(e,j,session,source,"fixture",call);assertFalse(first.success);assertNull(first.apk);assertFalse(ForgeCodeDiagnostics.errors(first.log).isEmpty());int count=requests.get();
    ForgeClient.BuildResult second=ForgeDiagnosticRefresh.obtain(e,j,session,source,"fixture",call);assertFalse(second.success);assertEquals(first.log,second.log);assertEquals(count,requests.get());assertEquals(1,delegate.puts);g.assertBudget(j,66);
  }
  @Test public void completeSourceIdentityIsNotChangedByDiagnosticState()throws Exception{
    VantaJob j=g.seed();JSONObject source=f.project(),session=f.session(source,p,4).put("phase","repair").put("log",opaque());String before=source.toString();
    PipelineDeviceTest.Worker worker=f.new Worker(false);worker.pending=true;Net.Call call=new Net.Call();call.transport=worker;
    try{ForgeDiagnosticRefresh.obtain(e,j,session,source,"fixture",call);fail();}catch(JobEngine.Deferred expected){}assertEquals(before,source.toString());assertEquals(ProjectArchive.fingerprint(source),e.store.document(j.id(),ForgeDiagnosticRefresh.key(source,4)).getString("source_fingerprint"));
  }
}
