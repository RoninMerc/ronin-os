package au.com.roningroup.patrollink;
import android.app.*;import android.content.*;import android.view.*;import android.webkit.*;import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;import org.junit.runner.RunWith;import static org.junit.Assert.*;import java.lang.reflect.*;
@RunWith(AndroidJUnit4.class)
public final class BrowserUiTest {
 @Test public void ProductionBrowserUsesFullPageOneCogAndOverviewScaling()throws Exception{
  Instrumentation ins=InstrumentationRegistry.getInstrumentation();Context c=ins.getTargetContext();
  final PatrolEngine[] engine={null};ins.runOnMainSync(()->{
   LocalGpsService.prefs(c).edit().putBoolean("intro_seen",true).putBoolean("background_explained",true).putBoolean("device_mode_configured",true).putString("device_mode","master").commit();
   TabletSettings.saveDisplayMode(c,"auto");engine[0]=((PatrolApp)c.getApplicationContext()).engine();engine[0].running=true;
   engine[0].webView().loadDataWithBaseURL(PatrolEngine.MONITOR,"<html><body style='width:980px'><h1>ISSUE MONITOR — SYNTHETIC TEST FIXTURE</h1><p>No records to display</p></body></html>","text/html","UTF-8",PatrolEngine.MONITOR);
  });
  Thread.sleep(600);Activity a=ins.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));ins.waitForIdleSync();Thread.sleep(500);
  Method show=MainActivity.class.getDeclaredMethod("showBrowser");show.setAccessible(true);
  ins.runOnMainSync(()->{try{show.invoke(a);}catch(Exception e){throw new RuntimeException(e);}});
  ins.waitForIdleSync();Thread.sleep(500);
  ins.runOnMainSync(()->{
   FrameLayout page=a.findViewById(155200);assertNotNull(page);assertEquals(3,page.getChildCount());assertNotNull(a.findViewById(156201));assertNotNull(a.findViewById(155201));assertTrue(page.getHeight()>0);
   assertTrue(engine[0].webView().getSettings().getLoadWithOverviewMode());assertTrue(engine[0].webView().getSettings().getUseWideViewPort());assertEquals(100,engine[0].webView().getSettings().getTextZoom());
   assertEquals(1,countButtons(page));assertEquals(page.getHeight(),engine[0].webView().getHeight());
   engine[0].stopRuntime("TEST_END","Fixture finished");a.finish();
  });ins.waitForIdleSync();
 }
 private int countButtons(ViewGroup group){int n=0;for(int i=0;i<group.getChildCount();i++){View v=group.getChildAt(i);if(v instanceof Button)n++;if(v instanceof ViewGroup)n+=countButtons((ViewGroup)v);}return n;}
}
