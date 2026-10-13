package au.com.roningroup.patrollink;
import android.app.*;import android.content.*;import android.webkit.*;import android.graphics.*;import androidx.test.ext.junit.runners.AndroidJUnit4;import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;import org.junit.rules.TestName;import org.junit.runner.RunWith;import static org.junit.Assert.*;import org.json.*;import java.util.*;import java.io.*;import java.lang.reflect.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
@RunWith(AndroidJUnit4.class)
public final class AutoCaptureFlowTest {
 @Rule public TestName name=new TestName();
 Instrumentation ins;Context c;MainActivity a;PatrolEngine engine;final Map<String,JSONObject> vehicles=new java.util.concurrent.ConcurrentHashMap<>();final Set<String> images=java.util.concurrent.ConcurrentHashMap.newKeySet();
 final AtomicInteger posts=new AtomicInteger();volatile int failUploads;volatile boolean single,unsafe;byte[] photo;
 @Before public void start()throws Exception{
  ins=InstrumentationRegistry.getInstrumentation();c=ins.getTargetContext();single=false;unsafe=false;
  Bitmap bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.RED);ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,90,out);photo=out.toByteArray();bitmap.recycle();
  ins.runOnMainSync(()->{
   SilverTrackVehicleCollector.resetForDebug();CaptureQueue.prefs(c).edit().clear().putBoolean("auto_enabled",false).commit();
   TabletSettings.prefs(c).edit().putBoolean("read_existing_master",false).commit();TabletSettings.saveDisplayMode(c,name.getMethodName().startsWith("Phone")?"phone":"tablet");
   LocalGpsService.prefs(c).edit().putBoolean("intro_seen",true).putBoolean("background_explained",true).commit();FleetRelay.configure(c,true,1);
   try{new MasterFeedSecrets(c).save(true,"SYNTHETIC_PUBLISHER_KEY_1234567890123456");}catch(Exception e){throw new RuntimeException(e);}
   engine=((PatrolApp)c.getApplicationContext()).engine();engine.running=true;
   engine.webView().setWebViewClient(new WebViewClient(){
    @Override public WebResourceResponse shouldInterceptRequest(WebView w,WebResourceRequest r){return response(r);}
    @Override public void onPageFinished(WebView w,String url){MonitorFilterUi.pageReady(w);}
   });
   CaptureHooks.pages=new CaptureHooks.Pages(){public WebResourceResponse response(WebResourceRequest r){return AutoCaptureFlowTest.this.response(r);}public byte[] image(String url){return photo;}};
   CaptureHooks.poster=(path,body)->{
    if("/v1/vehicle-reports".equals(path)){
     posts.incrementAndGet();if(failUploads>0){failUploads--;throw new IOException("HTTP 503");}
     JSONObject row=body.getJSONArray("rows").getJSONObject(0);String id=row.getString("sourceReportId");boolean duplicate=vehicles.putIfAbsent(id,new JSONObject(row.toString()))!=null;
     return new JSONObject().put("ok",true).put("imports",new JSONObject().put("created",duplicate?0:1).put("duplicate",duplicate?1:0).put("updated",0).put("review",0).put("ignored",0)).toString();
    }
    if("/v1/report-evidence".equals(path)){assertTrue(body.getBoolean("trainingRequested"));assertTrue(vehicles.containsKey(body.getString("sourceReportId")));boolean duplicate=!images.add(body.getString("sourceReportId"));return new JSONObject().put("ok",true).put("duplicate",duplicate).put("id","synthetic-photo").toString();}
    return "{\"ok\":true,\"rows\":[]}";
   };
   engine.webView().loadUrl(PatrolEngine.MONITOR);
  });
  Thread.sleep(500);a=(MainActivity)ins.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));ins.waitForIdleSync();
  for(int attempt=0;attempt<40;attempt++){ins.runOnMainSync(()->{for(Activity active:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(active instanceof MainActivity&&!active.isFinishing())a=(MainActivity)active;});Thread.sleep(100);}
  Method show=MainActivity.class.getDeclaredMethod("showBrowser");show.setAccessible(true);ins.runOnMainSync(()->{try{show.invoke(a);engine.webView().setWebViewClient(new WebViewClient(){@Override public WebResourceResponse shouldInterceptRequest(WebView w,WebResourceRequest r){return response(r);}@Override public void onPageFinished(WebView w,String url){MonitorFilterUi.pageReady(w);}});engine.webView().loadUrl(PatrolEngine.MONITOR);}catch(Exception e){throw new RuntimeException(e);}});ins.waitForIdleSync();
 }
 @After public void stop(){ins.runOnMainSync(()->{AutoCaptureJob.stop(a);engine.stopRuntime("TEST_END","Synthetic capture fixture complete");SilverTrackVehicleCollector.resetForDebug();CaptureHooks.pages=null;CaptureHooks.poster=null;new MasterFeedSecrets(c).clear();a.finish();});ins.waitForIdleSync();}
 WebResourceResponse response(WebResourceRequest r){
  String url=r.getUrl().toString();if(url.contains("/Uploads/"))return new WebResourceResponse("image/jpeg",null,new ByteArrayInputStream(photo));
  String body;
  if(url.contains("IssueDetails.aspx")){String id=url.contains("2000002")?"2000002":"2000001";body="<label for='plate'>Registration</label><input id='plate' value='"+(id.endsWith("2")?"XYZ456":"ABC123")+"'><table><tr><td>Vehicle</td><td>Toyota Corolla</td></tr><tr><td>Address</td><td>Synthetic Street</td></tr><tr><td>Relation</td><td>at</td></tr><tr><td>Parking</td><td>visitor</td></tr><tr><td>Reported Date</td><td>14/07/2026 06:00</td></tr></table><div id='reportPhotos'><img id='vehiclePhoto' width='120' height='120' src='https://trlsecurity.silvertracker.net/Uploads/photos/"+id+".jpg'></div>";}
  else{
   boolean second=url.contains("page=2");String id=second?"2000002":"2000001";
   String next=second||single?"":"<button class='rgPageNext' title='Next page' onclick=\"location.href='Monitor.aspx?page=2'\">Next</button>";
   if(unsafe)next="<a class='rgPageNext' href='EditIssue.aspx?IssueID=2000001' title='Next page'>Next</a><a href='Monitor.aspx?page=9' title='Last page'>Last</a>";
   body="<h1>ISSUE MONITOR — SYNTHETIC CAPTURE TEST</h1><table><thead><tr><th>Issue ID</th><th>Property</th><th>Reported Issue</th><th>Created Date</th><th>Created By</th><th>Report</th></tr></thead><tbody><tr><td>"+id+"</td><td>Synthetic Property</td><td>Parking Breach - 1st Warning</td><td>14/07/2026 06:00</td><td>Synthetic Guard</td><td><a href='IssueDetails.aspx?IssueID="+id+"'>View</a></td></tr></tbody></table><span aria-current='page'>"+(second?"2":"1")+"</span>"+next;
  }
  if(r.isForMainFrame()&&!url.contains("IssueDetails.aspx")&&name.getMethodName().startsWith("TabletCapture")){
   body=body.replace("<th>Property</th>","").replace("<th>Created Date</th>","").replace("<th>Created By</th>","").replace("<td>Synthetic Property</td>","").replace("<td>14/07/2026 06:00</td>","").replace("<td>Synthetic Guard</td>","");
   String inner=("<html><body>"+body+"</body></html>").replace("&","&amp;").replace("\"","&quot;");
   body="<h1>SYNTHETIC FRAME CAPTURE FIXTURE</h1><iframe style='width:100%;height:650px' srcdoc=\""+inner+"\"></iframe>";
  }
  return new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(("<html><body>"+body+"</body></html>").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
 }
 String js(String value)throws Exception{CountDownLatch done=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>();ins.runOnMainSync(()->engine.webView().evaluateJavascript(value,x->{result.set(x);done.countDown();}));assertTrue(done.await(10,TimeUnit.SECONDS));return String.valueOf(new JSONTokener(result.get()).nextValue());}
 void ready()throws Exception{
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=c.getAssets().open("extract.js")){byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))>0)bytes.write(buffer,0,n);}
  String reader=bytes.toString("UTF-8").replace("__GUARD_IDS__","[]");
  for(int i=0;i<100;i++){JSONObject page=new JSONObject(js(CaptureCatalog.wrap(reader)));if(page.getJSONArray("allRows").length()>0)return;Thread.sleep(100);}
  fail("Synthetic report row not readable: "+js(CaptureCatalog.wrap(reader))+"; fixture signature="+js("JSON.stringify({url:location.pathname,fixture:document.body.innerText.includes('SYNTHETIC'),tables:document.querySelectorAll('table').length,frames:document.querySelectorAll('iframe').length})"));
 }
 void begin(){ins.runOnMainSync(()->{CaptureQueue.prefs(c).edit().putBoolean("auto_enabled",true).commit();AutoCaptureJob.start(a,engine.webView(),engine.zone(),true);});}
 void done()throws Exception{for(int i=0;i<800;i++){if(!AutoCaptureJob.running()&&CaptureQueue.prefs(c).getString("stage","").startsWith("Finished"))return;Thread.sleep(100);}fail(AutoCaptureJob.status(c));}
 @Test public void TabletCaptureTraversesPagesReadsDetailsAndReceivesVehicleAndPhotoReceipts()throws Exception{
  ready();begin();done();assertEquals(AutoCaptureJob.status(c),2,vehicles.size());assertEquals(AutoCaptureJob.status(c),2,images.size());assertEquals(2,CaptureQueue.count(c,"created"));assertEquals(2,CaptureQueue.count(c,"photos"));assertEquals(0,CaptureQueue.load(c).length());
  for(JSONObject row:vehicles.values()){assertTrue(row.getString("sourceReportId").startsWith("SilverTrack:"));assertTrue(row.has("occurredAt"));assertEquals("Toyota Corolla",row.getJSONObject("vehicle").getString("vehicle"));}
 }
 @Test public void TransientTransportFailuresRetryAndOnlyCountConfirmedImports()throws Exception{
  single=true;failUploads=2;CaptureQueue.prefs(c).edit().putBoolean("capture_photos",false).commit();ins.runOnMainSync(()->engine.webView().reload());Thread.sleep(500);
  ready();begin();done();assertEquals(AutoCaptureJob.status(c),1,vehicles.size());assertTrue(posts.get()>=3);assertEquals(1,CaptureQueue.count(c,"created"));assertTrue(CaptureQueue.count(c,"failed")>=2);assertEquals(0,images.size());
 }
 @Test public void PagerRefusesLastPageAndMutatingLinks()throws Exception{
  unsafe=true;ins.runOnMainSync(()->engine.webView().reload());Thread.sleep(500);
  CountDownLatch latch=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>();ins.runOnMainSync(()->engine.webView().evaluateJavascript("JSON.stringify("+CapturePager.SCRIPT+"(true))",x->{result.set(x);latch.countDown();}));
  assertTrue(latch.await(10,TimeUnit.SECONDS));JSONObject data=new JSONObject(String.valueOf(new JSONTokener(result.get()).nextValue()));assertFalse(data.getBoolean("next"));assertFalse(data.optBoolean("clicked"));
 }
 @Test public void PhoneKeepsItsOriginalToolbarAndDoesNotAutomaticallyPage()throws Exception{
  assertNull(a.findViewById(155201));assertNull(a.findViewById(156201));assertFalse(AutoCaptureJob.running());Thread.sleep(1800);
  AtomicReference<String> url=new AtomicReference<>();ins.runOnMainSync(()->url.set(engine.webView().getUrl()));assertEquals(PatrolEngine.MONITOR,url.get());assertEquals(0,images.size());
 }
}
