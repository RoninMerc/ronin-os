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
 private Instrumentation ins;private Activity activity;private WebView web;
 private static final String HTML="<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head><body style='padding:24px'><label for='reportedIssue'>Reported issue</label><select id='reportedIssue' style='display:block;width:280px;height:48px' onchange='window.changed=(window.changed||0)+1'><option value='all'>All issues</option><option value='parking1'>Parking Breach - 1st Warning</option><option value='parking2'>Parking Breach - 2nd Warning</option><option value='disabled' disabled>Disabled issue</option></select><input id='guard' value='KEEP-GUARD'></body></html>";
 @Before public void setup(){
  ins=InstrumentationRegistry.getInstrumentation();Context c=ins.getTargetContext();
  LocalGpsService.prefs(c).edit().putBoolean("intro_seen",true).putBoolean("background_explained",true).commit();
  activity=ins.startActivitySync(new Intent(c,MonitorFilterHostActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  ins.runOnMainSync(()->{
   web=new WebView(activity.getApplicationContext());web.getSettings().setJavaScriptEnabled(true);activity.setContentView(web);
   MonitorFilterUi.bind(activity,web);web.setWebViewClient(new WebViewClient(){@Override public void onPageFinished(WebView w,String url){MonitorFilterUi.pageReady(w);}});
   web.loadDataWithBaseURL(PatrolEngine.MONITOR,HTML,"text/html","UTF-8",PatrolEngine.MONITOR);
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
  ins.runOnMainSync(()->{url.set(web.getUrl());ready.set(MonitorFilterUi.ready(activity,web));});
  assertEquals(PatrolEngine.MONITOR,url.get());assertTrue("Trusted monitor fixture must enable the picker: "+url.get(),ready.get());
 }
 private AccessibilityNodeInfo find(String label)throws Exception{
  for(int i=0;i<60;i++){AccessibilityNodeInfo root=ins.getUiAutomation().getRootInActiveWindow();if(root!=null){java.util.List<AccessibilityNodeInfo> found=root.findAccessibilityNodeInfosByText(label);for(AccessibilityNodeInfo node:found)if(label.contentEquals(node.getText()==null?"":node.getText()))return node;}Thread.sleep(100);}throw new AssertionError("Native choice not visible: "+label+"; filter events="+js("String(window.__trlFilterClicks||0)")+"; bridge="+js("typeof TRLMonitorFilters.open"));
 }
 private void choose(String label)throws Exception{
  AccessibilityNodeInfo node=find(label);while(node!=null&&!node.isClickable())node=node.getParent();assertNotNull("Clickable native choice",node);assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));ins.waitForIdleSync();Thread.sleep(250);
 }
 @Test public void actualHtmlDropdownTapOpensNativePickerAndKeepsOtherFilters()throws Exception{
  trustedLoaded();assertEquals("function",js("typeof TRLMonitorFilters.open"));assertEquals("true",js("String(!!window.__trlFiltersInstalled)"));ins.waitForIdleSync();Thread.sleep(250);JSONObject pos=new JSONObject(js("JSON.stringify((function(){var r=document.getElementById('reportedIssue').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2};})())"));
  int[] location=new int[2];ins.runOnMainSync(()->web.getLocationOnScreen(location));
  float x=location[0]+(float)pos.getDouble("x")*activity.getResources().getDisplayMetrics().density,y=location[1]+(float)pos.getDouble("y")*activity.getResources().getDisplayMetrics().density;
  long now=SystemClock.uptimeMillis();ins.sendPointerSync(MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0));ins.sendPointerSync(MotionEvent.obtain(now,now+50,MotionEvent.ACTION_UP,x,y,0));
  find("Parking Breach - 1st Warning");
  AtomicReference<Boolean> hold=new AtomicReference<>();ins.runOnMainSync(()->hold.set(MonitorFilterUi.interacting(web)));assertTrue("Refresh deferred while selecting",hold.get());
  choose("Parking Breach - 1st Warning");
  assertEquals("parking1",js("document.getElementById('reportedIssue').value"));assertEquals("1",js("String(window.changed)"));assertEquals("KEEP-GUARD",js("document.getElementById('guard').value"));
 }
 @Test public void toolbarPickerSelectsTheExistingReportedIssueOptions()throws Exception{
  trustedLoaded();ins.runOnMainSync(()->MonitorFilterUi.show(activity,web));choose("Parking Breach - 2nd Warning");
  assertEquals("parking2",js("document.getElementById('reportedIssue').value"));assertEquals("1",js("String(window.changed)"));
  ins.runOnMainSync(()->MonitorFilterUi.pause(activity));AtomicReference<Boolean> hold=new AtomicReference<>();ins.runOnMainSync(()->hold.set(MonitorFilterUi.interacting(web)));assertFalse("Background refresh restored when browser pauses",hold.get());
 }
 @Test public void filterPickerDoesNotChangeAnUntrustedPage()throws Exception{
  loaded();ins.runOnMainSync(()->web.loadDataWithBaseURL("https://example.org/",HTML,"text/html","UTF-8","https://example.org/"));Thread.sleep(600);loaded();
  ins.runOnMainSync(()->MonitorFilterUi.show(activity,web));assertEquals("all",js("document.getElementById('reportedIssue').value"));
 }
}
