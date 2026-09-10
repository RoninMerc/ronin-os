from pathlib import Path
import os, subprocess
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'))
j=root/'app/src/main/java/com/ronin/vanta'
formatter=os.environ.get('VANTA_FORMATTER','/tmp/format.jar')
subprocess.run(['java','-jar',formatter,'--replace']+[str(p) for p in (root/'app/src').rglob('*.java')],check=True)
def change(p,old,new):
 s=p.read_text();assert s.count(old)==1,(p.name,s.count(old),old[:80]);p.write_text(s.replace(old,new,1))
p=j/'BuildDiagnostics.java'
change(p,'      if (errors.size() < 16) errors.add(Errors.redact(cleaned));', '''      // Java often reports the distinguishing method/type on the next lines. Keep those
      // diagnostics with the error: two different missing symbols are not the same repair.
      if(lower.contains("error:") || lower.startsWith("e: ")) {
        int retained=0;
        for(int next=i+1;next<Math.min(lines.length,i+8);next++) {
          String detail=lines[next].trim();
          if(detail.isEmpty() || detail.matches("[\\\\^~ ]+") )continue;
          if(detail.matches("(?i)^(symbol|location|required|found|reason):.*")) {
            cleaned+=" | "+detail.substring(0,Math.min(350,detail.length()));
            if(++retained>=4)break;
          } else if(retained>0 || detail.contains("error:") || detail.startsWith("> Task") || detail.startsWith("*"))break;
        }
      }
      if(cleaned.length()>1800)cleaned=cleaned.substring(0,1800);
      if (errors.size() < 16) errors.add(Errors.redact(cleaned));''')
change(p, '    report.put("build_passed", success);', '''    report.put("build_passed", success);
    report.put("reported_build_failure",log.contains("BUILD FAILED") || !failed.isEmpty());''')
marker='  public static String testSummary(JSONObject report) {'
change(p,marker,'''  /** Only affirmative contradictions block publication; absence of reports stays "unreported". */
  public static String publicationIssue(JSONObject report) {
    if(report==null)return "";
    if(report.optBoolean("reported_build_failure") || !report.optString("failed_task").isEmpty())
      return "The worker marked this run successful, but its saved compiler log reports a failed build. "
          +"Source, run identity and diagnostics are retained; no APK was published.";
    JSONObject tests=report.optJSONObject("tests");
    if(tests!=null && tests.optBoolean("reported") && tests.optInt("failed")>0)
      return "The worker returned an APK but reported failing unit tests. "
          +"The artifact was not published as successful. Review the saved test/build evidence.";
    return "";
  }

'''+marker)
p=j/'JobOperations.java'
change(p, '      if (result.success) {\n        e.event(', '''      if (result.success) {
        String evidenceIssue=BuildDiagnostics.publicationIssue(buildReport);
        if(!evidenceIssue.isEmpty())throw new Blocked(evidenceIssue);
        e.event(''')
p=root/'app/src/test/java/com/ronin/vanta/BuildEvidenceSafetyTest.java'
p.write_text('''package com.ronin.vanta;
import static org.junit.Assert.*;
import org.json.*;
import org.junit.Test;
public class BuildEvidenceSafetyTest {
 private static String missing(String symbol,String line){return "> Task :app:compileReleaseJavaWithJavac FAILED\\napp/src/main/java/Example.java:"+line+": error: cannot find symbol\\n    return missing();\\n           ^\\n  symbol:   method "+symbol+"()\\n  location: class Example\\nBUILD FAILED in 3s\\n";}
 @Test public void differentMissingMethodsDoNotBecomeRepeatedRepairs()throws Exception{
   assertEquals(BuildDiagnostics.Change.CHANGED,BuildDiagnostics.compare(BuildDiagnostics.inspect(missing("load","7")),BuildDiagnostics.inspect(missing("save","9"))));
 }
 @Test public void sameMissingMethodIgnoresLineChangesAndStackTrace()throws Exception{
   assertEquals(BuildDiagnostics.Change.REPEATED,BuildDiagnostics.compare(BuildDiagnostics.inspect(missing("load","7")),BuildDiagnostics.inspect(missing("load","40")+"at app.Builder(Build.java:68)\\n")));
 }
 @Test public void argumentMismatchIncludesActualAndExpectedTypes()throws Exception{
  JSONObject report=BuildDiagnostics.inspect("> Task :app:compileReleaseJavaWithJavac FAILED\\napp/src/main/java/Example.java:3: error: method apply cannot be applied to given types\\n  required: String\\n  found: int\\n  reason: argument mismatch\\nBUILD FAILED\\n");
  assertTrue(report.getJSONArray("errors").getString(0).contains("required: String"));assertTrue(report.getJSONArray("errors").getString(0).contains("found: int"));
 }
 @Test public void contradictoryWorkerSuccessCannotPublish()throws Exception{
  JSONObject report=BuildDiagnostics.inspect(missing("load","7")).put("worker_success",true);
  assertTrue(BuildDiagnostics.publicationIssue(report).contains("no APK was published"));
 }
 @Test public void failingReportedTestsCannotPublish()throws Exception{
  JSONObject report=BuildDiagnostics.inspect("VANTA_TEST_REPORT|v1|:app:testReleaseUnitTest|3|2|1|0\\nBUILD SUCCESSFUL in 3s\\n");
  assertTrue(BuildDiagnostics.publicationIssue(report).contains("failing unit tests"));
 }
 @Test public void unreportedTestsRemainExplicitlyUnreportedNotInvented()throws Exception{
  JSONObject report=BuildDiagnostics.inspect("BUILD SUCCESSFUL in 3s\\n");assertEquals("",BuildDiagnostics.publicationIssue(report));assertTrue(BuildDiagnostics.testSummary(report).contains("not reported"));
 }
 @Test public void passingWorkerReportCanPublishWithoutClaimingDeviceTests()throws Exception{
  JSONObject report=BuildDiagnostics.inspect("VANTA_TEST_REPORT|v1|:app:testReleaseUnitTest|3|3|0|0\\nBUILD SUCCESSFUL in 3s\\n");assertEquals("",BuildDiagnostics.publicationIssue(report));assertFalse(report.getJSONObject("tests").getBoolean("runtime_verified"));
 }
 @Test public void retainedCompilerDetailsRemainBounded()throws Exception{
  StringBuilder detail=new StringBuilder();for(int i=0;i<20000;i++)detail.append('x');JSONObject report=BuildDiagnostics.inspect("error: incompatible types\\nrequired: "+detail+"\\nfound: "+detail+"\\n");assertTrue(report.getJSONArray("errors").getString(0).length()<=1800);
 }
}
''')
p=root/'app/src/androidTest/java/com/ronin/vanta/BuildEvidenceDeviceTest.java'
p.write_text('''package com.ronin.vanta;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
/** Real client pipeline, neutral isolated wire fixture; verifies no signing/publication on contradictory evidence. */
@RunWith(AndroidJUnit4.class)
public class BuildEvidenceDeviceTest {
 final PipelineDeviceTest fixture=new PipelineDeviceTest();
 @Before public void before()throws Exception{fixture.prepare();}
 @After public void after()throws Exception{fixture.cleanup();}
 private void blocked(String log)throws Exception{
  JobEngine e=fixture.e;ProviderConfig p=fixture.h.seed("venice");JSONObject in=fixture.h.input(p).put("session",fixture.session(fixture.project(),p,4));VantaJob job=fixture.job("build",in);
  PipelineDeviceTest.Worker worker=fixture.new Worker(false);
  Net.Call call=new Net.Call();call.transport=new PipelineDeviceTest.Transport(){
   @Override public Net.Response request(String method,String url,Map<String,String> headers,byte[] body,String ct,int limit,Net.Call c)throws Exception{return worker.request(method,url,headers,body,ct,limit,c);}
   @Override public Net.Response download(String url,Map<String,String> headers,int limit,Net.Call c)throws Exception{
    if(url.contains("/12/")){ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(bytes)){z.putNextEntry(new ZipEntry("build.log"));z.write(log.getBytes(StandardCharsets.UTF_8));z.closeEntry();}return new Net.Response(bytes.toByteArray(),"application/zip");}
    return worker.download(url,headers,limit,c);
   }
  };
  try{JobOperations.run(e,job,in,call);fail("Contradictory evidence was treated as success");}catch(JobOperations.Blocked correct){assertTrue(correct.getMessage().contains("worker"));}
  assertNull(e.store.document(job.id(),"result"));assertNull(e.store.document(job.id(),"signed_apk"));assertNotNull(e.store.document(job.id(),"build_report"));assertEquals(4,e.store.document(job.id(),"build").getInt("attempt"));assertEquals(0,worker.puts);
 }
 @Test public void failedCompilerLogWithSuccessfulRunDoesNotPublish()throws Exception{blocked("> Task :app:compileReleaseJavaWithJavac FAILED\\nerror: missing method\\nBUILD FAILED in 4s\\n");}
 @Test public void failingTestReportWithSuccessfulRunDoesNotPublish()throws Exception{blocked("VANTA_TEST_REPORT|v1|:app:testReleaseUnitTest|3|2|1|0\\nBUILD SUCCESSFUL in 4s\\n");}
}
''')
print('Final evidence review: contextual compiler fingerprints and fail-closed contradictory APK publication, with unit and Android pipeline regressions.')
