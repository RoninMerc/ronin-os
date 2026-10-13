package au.com.roningroup.patrollink;
import android.app.*;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public final class MonitorFilterUiTest {
 private Instrumentation ins;private Activity activity;private WebView web;private volatile String fixtureContent;
 private static final String HTML="<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head><body style='padding:24px'><label for='reportedIssue'>Reported issue</label><select id='reportedIssue' style='display:block;width:280px;height:48px' onchange='window.changed=(window.changed||0)+1'><option value='all'>All issues</option><option value='parking1'>Parking Breach - 1st Warning</option><option value='parking2'>Parking Breach - 2nd Warning</option><option value='disabled' disabled>Disabled issue</option></select><input id='guard' value='KEEP-GUARD'></body></html>";
 @Before public void setup(){
  ins=InstrumentationRegistry.getInstrumentation();Context c=ins.getTargetContext();
  LocalGpsService.prefs(c).edit().putBoolean("intro_seen",true).putBoolean("background_explained",true).commit();
  c.getSharedPreferences("patrol_silvertrack_issue_filters_v155",0).edit().clear().commit();
  TabletSettings.saveDisplayMode(c,"tablet");CaptureQueue.prefs(c).edit().putBoolean("auto_enabled",false).commit();
  fixtureContent=HTML;
  activity=ins.startActivitySync(new Intent(c,MonitorFilterHostActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  ins.runOnMainSync(()->{
   web=new WebView(activity.getApplicationContext());web.getSettings().setJavaScriptEnabled(true);activity.setContentView(web);
   MonitorFilterUi.bind(activity,web);web.setWebViewClient(new WebViewClient(){
    @Override public WebResourceResponse shouldInterceptRequest(WebView w,WebResourceRequest request){
     if(request.isForMainFrame()&&PatrolEngine.MONITOR.equals(request.getUrl().toString()))return new WebResourceResponse("text/html","UTF-8",new java.io.ByteArrayInputStream(fixtureContent.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
     return super.shouldInterceptRequest(w,request);
    }
    @Override public void onPageFinished(WebView w,String url){MonitorFilterUi.pageReady(w);}
   });
   web.loadUrl(PatrolEngine.MONITOR);
  });
 }
 @After public void cleanup(){ins.runOnMainSync(()->{MonitorFilterUi.unbind(activity);web.destroy();activity.finish();});}
 private String js(String code)throws Exception{
  CountDownLatch done=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>();
  ins.runOnMainSync(()->web.evaluateJavascript(code,s->{result.set(s);done.countDown();}));
  assertTrue("JavaScript callback",done.await(10,TimeUnit.SECONDS));Object out=new JSONTokener(result.get()).nextValue();return String.valueOf(out);
 }
 private void loaded()throws Exception{for(int i=0;i<80;i++){if("true".equals(js("!!document.getElementById('reportedIssue')")))return;Thread.sleep(100);}fail("Fixture monitor did not load");}
 private void trustedLoaded()throws Exception{
  loaded();AtomicReference<String> url=new AtomicReference<>();AtomicReference<Boolean> ready=new AtomicReference<>();
  for(int attempt=0;attempt<80;attempt++){
   ins.runOnMainSync(()->{url.set(web.getUrl());ready.set(MonitorFilterUi.ready(activity,web));});
   if(PatrolEngine.MONITOR.equals(url.get())&&Boolean.TRUE.equals(ready.get())&&"true".equals(js("String(!!window.__trlFiltersInstalled)")))break;
   Thread.sleep(100);
  }
  assertEquals(PatrolEngine.MONITOR,url.get());assertTrue("Trusted monitor fixture must enable the picker: "+url.get(),ready.get());
 }
 private AccessibilityNodeInfo find(String label)throws Exception{
  for(int i=0;i<60;i++){AccessibilityNodeInfo root=ins.getUiAutomation().getRootInActiveWindow();if(root!=null){java.util.List<AccessibilityNodeInfo> found=root.findAccessibilityNodeInfosByText(label);for(AccessibilityNodeInfo node:found)if(node.isVisibleToUser()&&label.equalsIgnoreCase(node.getText()==null?"":node.getText().toString()))return node;}Thread.sleep(100);}throw new AssertionError("Native choice not visible: "+label+"; filter events="+js("String(window.__trlFilterClicks||0)")+"; bridge="+js("typeof TRLMonitorFilters.open"));
 }
 private void choose(String label)throws Exception{
  ins.waitForIdleSync();Thread.sleep(200);AccessibilityNodeInfo node=find(label);
  boolean clicked=false;for(AccessibilityNodeInfo target=node;target!=null;target=target.getParent()){
   if(target.isClickable()&&target.performAction(AccessibilityNodeInfo.ACTION_CLICK)){clicked=true;break;}
  }
  assertTrue("Native option must accept accessibility click: "+label,clicked);
  ins.waitForIdleSync();Thread.sleep(300);
 }
 @Test public void actualHtmlDropdownTapOpensNativePickerAndKeepsOtherFilters()throws Exception{
  trustedLoaded();assertEquals("function",js("typeof TRLMonitorFilters.open"));assertEquals("true",js("String(!!window.__trlFiltersInstalled)"));ins.waitForIdleSync();Thread.sleep(250);JSONObject pos=new JSONObject(js("JSON.stringify((function(){var r=document.getElementById('reportedIssue').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2};})())"));
  ins.runOnMainSync(()->{
   float scale=web.getScale();float x=(float)pos.optDouble("x")*scale,y=(float)pos.optDouble("y")*scale;
   long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0),up=MotionEvent.obtain(now,now+80,MotionEvent.ACTION_UP,x,y,0);
   assertTrue("HTML dropdown receives touch",web.dispatchTouchEvent(down));web.dispatchTouchEvent(up);down.recycle();up.recycle();
  });
  for(int attempt=0;attempt<50&&"0".equals(js("String(window.__trlFilterClicks||0)"));attempt++)Thread.sleep(100);
  assertNotEquals("Custom filter must receive the real HTML touch","0",js("String(window.__trlFilterClicks||0)"));
  find("Parking Breach - 1st Warning");
  AtomicReference<Boolean> hold=new AtomicReference<>();ins.runOnMainSync(()->hold.set(MonitorFilterUi.interacting(web)));assertTrue("Refresh deferred while selecting",hold.get());
  choose("Parking Breach - 1st Warning");choose("Apply");
  assertEquals("parking1",js("document.getElementById('reportedIssue').value"));assertEquals("1",js("String(window.changed)"));assertEquals("KEEP-GUARD",js("document.getElementById('guard').value"));
 }
 @Test public void toolbarPickerSelectsTheExistingReportedIssueOptions()throws Exception{
  trustedLoaded();ins.runOnMainSync(()->MonitorFilterUi.show(activity,web));choose("Parking Breach - 2nd Warning");choose("Apply");
  assertEquals("parking2",js("document.getElementById('reportedIssue').value"));assertEquals("1",js("String(window.changed)"));
  ins.runOnMainSync(()->MonitorFilterUi.pause(activity));AtomicReference<Boolean> hold=new AtomicReference<>();ins.runOnMainSync(()->hold.set(MonitorFilterUi.interacting(web)));assertFalse("Background refresh restored when browser pauses",hold.get());
 }
 @Test public void filterPickerDoesNotChangeAnUntrustedPage()throws Exception{
  loaded();ins.runOnMainSync(()->web.loadDataWithBaseURL("https://example.org/",HTML,"text/html","UTF-8","https://example.org/"));Thread.sleep(600);loaded();
  ins.runOnMainSync(()->MonitorFilterUi.show(activity,web));assertEquals("all",js("document.getElementById('reportedIssue').value"));
 }

 @Test public void julyParkingRowsExposeStableReadOnlyReportLinks()throws Exception{
  String fixture="<html><body><table><tr><td>Synthetic Guard</td><td>Synthetic Property</td><td>Parking Breach - 1st Warning</td><td>14/07/2026 06:00</td><td><a href='IssueDetails.aspx?IssueID=717'>View</a></td></tr></table></body></html>";
  ins.runOnMainSync(()->web.loadDataWithBaseURL(PatrolEngine.MONITOR,fixture,"text/html","UTF-8",PatrolEngine.MONITOR));
  for(int i=0;i<80;i++){if("true".equals(js("!!document.querySelector('a')")))break;Thread.sleep(100);}
  java.lang.reflect.Field f=SilverTrackVehicleCollector.class.getDeclaredField("LINKS");f.setAccessible(true);
  JSONObject row=new JSONObject().put("id","synthetic").put("guard","Synthetic Guard").put("property","Synthetic Property").put("issue","Parking Breach - 1st Warning").put("date","14/07/2026 06:00");
  JSONArray result=new JSONArray(js("JSON.stringify("+f.get(null)+"("+new JSONArray().put(row)+"))"));
  assertEquals(1,result.length());
  assertEquals("/admincustomer/issues/issuedetails.aspx:issueid=717",result.getJSONObject(0).getString("identity"));
  assertEquals("14/07/2026 06:00",result.getJSONObject(0).getJSONObject("source").getString("date"));
  assertEquals("https://trlsecurity.silvertracker.net/AdminCustomer/Issues/IssueDetails.aspx?IssueID=717",result.getJSONObject(0).getString("url"));
 }
 private static final String MULTI=HTML.replace("</body>","<label for='other'>Guard filter</label><select id='other'><option value='a'>Fixture Guard A</option><option value='b'>Fixture Guard B</option></select><input id='date' value='2026-07-14'><table><tr id='r1'><td>2000001</td><td>Parking Breach - 1st Warning</td></tr><tr id='r2'><td>2000002</td><td>Parking Breach - 2nd Warning</td></tr><tr id='r3'><td>2000003</td><td>General Patrol</td><td>Earlier Parking Breach - 1st Warning mentioned in notes</td></tr></table></body>").replace("<option value='disabled'","<option value='general'>General Patrol</option><option value='disabled'");
 private void multiLoaded()throws Exception{trustedLoaded();fixtureContent=MULTI;ins.runOnMainSync(()->web.loadUrl(PatrolEngine.MONITOR));for(int i=0;i<80;i++){if("true".equals(js("!!document.getElementById('date')")))break;Thread.sleep(100);}trustedLoaded();}
 @Test public void MultipleReportedIssuesApplyOrFilteringWithoutChangingOtherControls()throws Exception{
  multiLoaded();ins.runOnMainSync(()->MonitorFilterUi.show(activity,web));choose("Parking Breach - 1st Warning");choose("Parking Breach - 2nd Warning");choose("Apply");
  assertEquals("all",js("document.getElementById('reportedIssue').value"));
  assertEquals("2",js("String(window.__trlIssueSelection.values.length)"));assertEquals("none",js("document.getElementById('r3').style.display"));
  assertNotEquals("none",js("document.getElementById('r1').style.display"));assertNotEquals("none",js("document.getElementById('r2').style.display"));
  assertEquals("KEEP-GUARD",js("document.getElementById('guard').value"));assertEquals("2026-07-14",js("document.getElementById('date').value"));
  js("document.documentElement.setAttribute('data-old-fixture','1')");
  ins.runOnMainSync(()->web.reload());
  for(int i=0;i<80;i++){if("true".equals(js("!!document.getElementById('reportedIssue')&&!document.documentElement.hasAttribute('data-old-fixture')")))break;Thread.sleep(100);}
  trustedLoaded();
  assertEquals("2",js("String(window.__trlIssueSelection.values.length)"));assertEquals("none",js("document.getElementById('r3').style.display"));
 }
 @Test public void GuardSelectionKeepsIssueSelectionAndUnbindingRestoresUnfilteredDom()throws Exception{
  multiLoaded();ins.runOnMainSync(()->MonitorFilterUi.show(activity,web));choose("Parking Breach - 1st Warning");choose("Parking Breach - 2nd Warning");choose("Apply");
  js("TRLMonitorFilters.open(1)");choose("Fixture Guard B");assertEquals("b",js("document.getElementById('other').value"));
  assertEquals("2",js("String(window.__trlIssueSelection.values.length)"));
  ins.runOnMainSync(()->MonitorFilterUi.unbind(activity));Thread.sleep(100);assertNotEquals("none",js("document.getElementById('r3').style.display"));
 }

}
