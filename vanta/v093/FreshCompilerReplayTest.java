package com.ronin.vanta;

import static org.junit.Assert.*;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.android.apksig.ApkVerifier;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Replays THIS CI run's real compiler outputs against byte-identical project repairs in Vanta. */
@RunWith(AndroidJUnit4.class)
public class FreshCompilerReplayTest {
  final PipelineDeviceTest f = new PipelineDeviceTest();
  JobEngine engine;
  ProviderConfig provider;
  final String testPath = "app/src/test/java/com/ronin/forge/foundationcheck/ReportViewModelTest.java";
  @Before public void prepare() throws Exception { f.prepare(); engine=f.e; provider=f.h.seed("venice"); }
  @After public void clean() throws Exception { f.cleanup(); }
  byte[] data(String name) throws Exception { return f.asset("fresh-compiler/"+name); }
  JSONObject project(int stage) throws Exception { return ProjectArchive.read(data("stage-"+stage+".zip")); }
  String text(JSONObject p,String path) throws Exception {
    JSONArray files=p.getJSONArray("files");
    for(int i=0;i<files.length();i++)if(path.equals(files.getJSONObject(i).getString("path")))return files.getJSONObject(i).getString("content");
    throw new AssertionError("Missing neutral fixture source: "+path);
  }
  byte[] archive(String name,byte[] contents)throws Exception {
    ByteArrayOutputStream out=new ByteArrayOutputStream();
    try(ZipOutputStream z=new ZipOutputStream(out)){z.putNextEntry(new ZipEntry(name));z.write(contents);z.closeEntry();}
    return out.toByteArray();
  }
  byte[] readEntry(InputStream in)throws Exception {
    ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[16384];int n;
    while((n=in.read(bytes))!=-1){if(out.size()+n>16000000)throw new IOException("Neutral fixture entry exceeds inspection limit");out.write(bytes,0,n);}
    return out.toByteArray();
  }
  final class Worker extends PipelineDeviceTest.Transport {
    int stage=3,puts=0;
    JSONObject run()throws Exception{return new JSONObject().put("id",1000+stage).put("head_sha","real-fixture-"+stage)
      .put("head_branch","vanta-forge-worker").put("path",".github/workflows/vanta-forge-worker.yml")
      .put("status","completed").put("conclusion",stage==5?"success":"failure");}
    @Override public Net.Response request(String method,String url,Map<String,String> headers,byte[] body,String ct,int limit,Net.Call call)throws Exception {
      call.check();assertTrue(url.startsWith("https://api.github.com/repos/Fixture/PrivateBuild"));
      if(method.equals("PUT")){
        JSONObject req=new JSONObject(new String(Base64.getDecoder().decode(new JSONObject(new String(body,StandardCharsets.UTF_8)).getString("content")),StandardCharsets.UTF_8));
        int next=req.getInt("attempt");assertEquals(stage+1,next);
        assertEquals("The client must submit the exact source already compiled in this CI run",ProjectArchive.fingerprint(project(next)),ProjectArchive.fingerprint(req.getJSONObject("project")));
        stage=next;puts++;return f.json(new JSONObject().put("commit",new JSONObject().put("sha","real-fixture-"+stage)));
      }
      if(url.contains("/contents/"))throw new Net.HttpError(404,"No earlier submission");
      if(url.endsWith("/artifacts")){
        JSONArray items=new JSONArray().put(new JSONObject().put("id",200+stage).put("name","vanta-forge-log-neutral"));
        if(stage==5)items.put(new JSONObject().put("id",305).put("name","vanta-forge-apk-neutral"));
        return f.json(new JSONObject().put("artifacts",items));
      }
      if(url.contains("/actions/runs?"))return f.json(new JSONObject().put("workflow_runs",new JSONArray().put(run())));
      if(url.contains("/actions/runs/"))return f.json(run());
      if(url.endsWith("PrivateBuild"))return f.json(new JSONObject().put("private",true));
      throw new AssertionError("Unexpected neutral request: "+url);
    }
    @Override public Net.Response download(String url,Map<String,String> headers,int limit,Net.Call call)throws Exception {
      call.check();assertTrue(headers.containsKey("Authorization"));
      if(url.endsWith("/305/zip"))return new Net.Response(archive("app-built.apk",data("generated-foundation-unsigned.apk")),"application/zip");
      return new Net.Response(archive("build.log",data("stage-"+stage+".log")),"application/zip");
    }
  }
  @Test public void realCompilerFailuresRepairToExactFreshApkAndReportedTests()throws Exception {
    JSONObject source=project(3);
    JSONObject session=ForgeSession.start("Fixture","PrivateBuild","vanta-forge-worker",provider.id,"qa-model",source,"release")
      .put("phase","poll").put("attempt",3).put("sha","real-fixture-3").put("run_id",1003);
    JSONObject input=f.h.input(provider).put("session",session).put("target",JobOperations.selection(provider,engine.registry.find(provider.id,"qa-model")))
      .put("recovery_policy",ForgeRecovery.policy(true,120,4,new JSONArray().put(provider.toJson())));
    VantaJob job=f.job("build",input);Worker wire=new Worker();Net.Call call=new Net.Call();call.transport=wire;
    AtomicInteger requests=new AtomicInteger();
    engine.overrideChat(job.id(),(p,key,model,messages,system,max,np,web,c,stream)->{
      assertEquals("qa-model",model);int index=requests.incrementAndGet();
      assertTrue("Test repairs receive the production ViewModel interface",messages.toString().contains("getError"));
      if(system.contains("Do not include file content"))return new JSONObject().put("name","Neutral report app")
        .put("files",new JSONArray().put(new JSONObject().put("path",testPath).put("purpose","Exercise the public validation method; preserve all three tests"))).toString();
      return text(project(index<=2?4:5),testPath);
    });
    for(int pass=0;pass<3;pass++){
      try{JobOperations.run(engine,engine.store.get(job.id()),input,call);}
      catch(JobEngine.Deferred expected){assertTrue(pass<2);}
    }
    VantaJob finished=engine.store.get(job.id());
    assertEquals(finished.json.toString(),"COMPLETED",finished.status());
    assertEquals(4,requests.get());assertEquals(2,wire.puts);
    assertEquals(0,engine.store.document(job.id(),"recovery").optInt("switches"));
    assertEquals("TEST_COMPILE",engine.store.document(job.id(),"build_report_3").getString("stage"));
    assertEquals("TESTS",engine.store.document(job.id(),"build_report_4").getString("stage"));
    JSONObject report=engine.store.document(job.id(),"build_report_5");assertEquals(3,report.getJSONObject("tests").getInt("passed"));
    JSONObject result=engine.store.document(job.id(),"result");
    assertEquals("com.ronin.forge.foundationcheck",result.getString("package"));
    assertTrue(result.getString("test_result").contains("3 passed"));
    Uri uri=Uri.parse(result.getString("uri"));f.shared.add(uri);
    File apk=new File(f.h.ctx().getExternalFilesDir(null),"generated-foundation-client-signed.apk");
    try(InputStream in=f.h.ctx().getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(apk)){out.write(Net.read(in,128000000,new Net.Call()));}
    assertTrue(new ApkVerifier.Builder(apk).build().verify().isVerified());
    Map<String,byte[]> compiled=new TreeMap<>(),signed=new TreeMap<>();
    try(ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(data("generated-foundation-unsigned.apk")))){ZipEntry entry;while((entry=z.getNextEntry())!=null)if(entry.getName().endsWith(".dex"))compiled.put(entry.getName(),readEntry(z));}
    try(ZipInputStream z=new ZipInputStream(new FileInputStream(apk))){ZipEntry entry;while((entry=z.getNextEntry())!=null)if(entry.getName().endsWith(".dex"))signed.put(entry.getName(),readEntry(z));}
    assertFalse(compiled.isEmpty());assertEquals(compiled.keySet(),signed.keySet());for(String path:compiled.keySet())assertArrayEquals(compiled.get(path),signed.get(path));
  }
}
