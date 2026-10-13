package au.com.roningroup.patrollink;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;import org.junit.runner.RunWith;import static org.junit.Assert.*;import java.lang.reflect.*;
@RunWith(AndroidJUnit4.class)
public final class StartupRecoveryTest {
 Instrumentation ins;Context c;MainActivity activity;PatrolEngine engine;
 @Before public void setup()throws Exception{
  ins=InstrumentationRegistry.getInstrumentation();c=ins.getTargetContext();
  ins.runOnMainSync(()->{
   c.stopService(new Intent(c,MonitorService.class));StartupHooks.failBrowser=StartupHooks.failMap=StartupHooks.failEngine=false;StartupHooks.engineDelay=0;
   StartupState.prefs(c).edit().clear().commit();
   CaptureQueue.prefs(c).edit().putBoolean("auto_enabled",false).commit();
   LocalGpsService.prefs(c).edit().putBoolean("intro_seen",true).putBoolean("background_explained",true).commit();
   TabletSettings.prefs(c).edit().putBoolean("read_existing_master",true).commit();TabletSettings.saveDisplayMode(c,"tablet");FleetRelay.configure(c,true,1);
   PatrolApp app=(PatrolApp)c.getApplicationContext();
   try{Field f=PatrolApp.class.getDeclaredField("engine");f.setAccessible(true);PatrolEngine old=(PatrolEngine)f.get(app);if(old!=null){old.stopRuntime("TEST_END","Synthetic fixture");if(old.peekWebView()!=null)old.peekWebView().destroy();}f.set(app,null);}
   catch(Exception err){throw new RuntimeException(err);}
   engine=app.engine();engine.prefs.edit().putString("guard0","STARTUP TEST ALPHA").commit();
   try{new MasterFeedSecrets(c).save(false,"SYNTHETIC_STARTUP_READER_1234567890123456");}catch(Exception err){throw new RuntimeException(err);}
  });
 }
 @After public void teardown(){
  ins.runOnMainSync(()->{StartupHooks.failBrowser=StartupHooks.failMap=StartupHooks.failEngine=false;StartupHooks.engineDelay=0;if(activity!=null)activity.finish();engine.stopRuntime("TEST_END","Synthetic fixture");c.stopService(new Intent(c,MonitorService.class));StartupState.prefs(c).edit().clear().commit();});
  ins.waitForIdleSync();
 }
 void launch()throws Exception{
  activity=(MainActivity)ins.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  Thread.sleep(2000);ins.waitForIdleSync();
  ins.runOnMainSync(()->{for(Activity a:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(a instanceof MainActivity)activity=(MainActivity)a;});
 }
 void assertData(){assertEquals("STARTUP TEST ALPHA",engine.prefs.getString("guard0",""));assertEquals("SYNTHETIC_STARTUP_READER_1234567890123456",new MasterFeedSecrets(c).load(false));}
 @Test public void ReaderStartsTabletWithoutCreatingSilverTrackWebView()throws Exception{
  StartupHooks.failBrowser=true;launch();
  ins.runOnMainSync(()->{assertNotNull(activity.findViewById(153001));assertNull(engine.peekWebView());for(int i=0;i<3;i++)assertNotNull(activity.findViewById(153010+i));assertData();});
 }
 @Test public void BrowserFailureKeepsNativeDashboardAndPairing()throws Exception{
  ins.runOnMainSync(()->{TabletSettings.saveDisplayMode(c,"phone");TabletSettings.prefs(c).edit().putBoolean("read_existing_master",false).commit();StartupHooks.failBrowser=true;});
  launch();ins.runOnMainSync(()->{try{Method show=MainActivity.class.getDeclaredMethod("showBrowser");show.setAccessible(true);show.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}assertNotNull(activity.findViewById(153100));assertTrue(StartupState.held(c,"browser"));assertNull(engine.peekWebView());assertData();});
 }
 @Test public void InterruptedBrowserConstructionDoesNotRepeatOnNextLaunch()throws Exception{
  ins.runOnMainSync(()->{StartupState.prefs(c).edit().putString("phase","browser").putInt("pid",-123).commit();StartupState.begin(c);TabletSettings.saveDisplayMode(c,"phone");TabletSettings.prefs(c).edit().putBoolean("read_existing_master",false).commit();});
  launch();ins.runOnMainSync(()->{assertNotNull(activity.findViewById(153100));assertTrue(StartupState.held(c,"browser"));assertNull(engine.peekWebView());assertData();});
 }
 @Test public void MapFailureShowsControlsAndRetryRestoresRealMap()throws Exception{
  StartupHooks.failMap=true;launch();ins.runOnMainSync(()->{assertNotNull(activity.findViewById(157003));assertData();StartupHooks.failMap=false;StartupState.retry(c,"map");try{Method show=MainActivity.class.getDeclaredMethod("showDashboard");show.setAccessible(true);show.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}assertNotNull(activity.findViewById(153005));assertData();});
 }
 @Test public void EngineFailureShowsRecoveryAndCanRetryWithoutResettingData()throws Exception{
  StartupHooks.failEngine=true;launch();ins.runOnMainSync(()->{assertNotNull(activity.findViewById(157002));assertData();StartupHooks.failEngine=false;try{Method show=MainActivity.class.getDeclaredMethod("showStartup");show.setAccessible(true);show.invoke(activity);Method retry=MainActivity.class.getDeclaredMethod("initialise");retry.setAccessible(true);retry.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}assertNotNull(activity.findViewById(153001));assertData();});
 }
 @Test public void SlowSavedStateLoadingKeepsFirstFrameAndMainThreadResponsive()throws Exception{
  ins.runOnMainSync(()->{try{Field field=PatrolApp.class.getDeclaredField("engine");field.setAccessible(true);engine.stopRuntime("TEST_END","Synthetic fixture");field.set(c.getApplicationContext(),null);StartupHooks.engineDelay=1400;StartupHooks.engineWasMain=true;}catch(Exception e){throw new RuntimeException(e);}});
  activity=(MainActivity)ins.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(350);
  ins.runOnMainSync(()->{assertNotNull(activity.findViewById(157001));});
  Thread.sleep(2400);ins.waitForIdleSync();
  ins.runOnMainSync(()->{for(Activity a:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(a instanceof MainActivity)activity=(MainActivity)a;assertNotNull(activity.findViewById(153001));assertFalse(StartupHooks.engineWasMain);engine=((PatrolApp)c.getApplicationContext()).engine();assertData();});
 }
}
