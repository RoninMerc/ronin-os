package au.com.roningroup.patrollink;
import android.app.*;import android.content.*;import androidx.test.ext.junit.runners.AndroidJUnit4;import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;import org.junit.runner.RunWith;import static org.junit.Assert.*;
/** Runs against the release APK, after adb force-stop, without warming the engine. */
@RunWith(AndroidJUnit4.class)
public final class ReleaseColdStartTest {
 @Test public void ColdReleaseLaunchShowsThreePatrolPanelsAndKeepsPairing()throws Exception{
  Instrumentation ins=InstrumentationRegistry.getInstrumentation();Context c=ins.getTargetContext();
  assertFalse("This probe must target the signed release",(c.getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0);
  ins.runOnMainSync(()->{TabletSettings.saveDisplayMode(c,"tablet");TabletSettings.prefs(c).edit().putBoolean("read_existing_master",true).commit();FleetRelay.configure(c,true,1);});
  final Activity[] current={ins.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))};
  boolean ready=false;
  for(int attempt=0;attempt<80&&!ready;attempt++){
   Thread.sleep(150);final boolean[] drawn={false};
   ins.runOnMainSync(()->{for(Activity a:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(a instanceof MainActivity)current[0]=a;drawn[0]=current[0].findViewById(153010)!=null&&current[0].findViewById(153011)!=null&&current[0].findViewById(153012)!=null;});
   ready=drawn[0];
  }
  assertTrue("Native patrol panels must load after a cold launch",ready);
  ins.runOnMainSync(()->{assertNotNull(current[0].findViewById(153005));assertNotNull(current[0].findViewById(153003));assertEquals("FIXTURE-READER-NOT-A-LIVE-KEY-1234567890",new MasterFeedSecrets(c).load(false));assertNull(((PatrolApp)c.getApplicationContext()).engine().peekWebView());current[0].finish();});
 }
 @Test public void ColdMasterPhoneLaunchLoadsNativeDashboardAndBrowser()throws Exception{
  Instrumentation ins=InstrumentationRegistry.getInstrumentation();Context c=ins.getTargetContext();
  assertFalse("Must test the signed release",(c.getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0);
  ins.runOnMainSync(()->{TabletSettings.saveDisplayMode(c,"phone");TabletSettings.prefs(c).edit().putBoolean("read_existing_master",false).commit();FleetRelay.configure(c,true,1);});
  final Activity[] current={ins.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))};
  boolean ready=false;
  for(int attempt=0;attempt<100&&!ready;attempt++){Thread.sleep(150);final boolean[] drawn={false};
   ins.runOnMainSync(()->{for(Activity a:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(a instanceof MainActivity)current[0]=a;
    drawn[0]=current[0].findViewById(153100)!=null&&((PatrolApp)c.getApplicationContext()).engine().peekWebView()!=null;});ready=drawn[0];
  }
  assertTrue("Master native phone dashboard and browser must load",ready);
  ins.runOnMainSync(()->{assertEquals("FIXTURE-READER-NOT-A-LIVE-KEY-1234567890",new MasterFeedSecrets(c).load(false));current[0].finish();c.stopService(new Intent(c,MonitorService.class));});
 }

}
