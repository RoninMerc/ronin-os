package au.com.roningroup.patrollink;
import android.app.*;import android.content.*;import androidx.test.ext.junit.runners.AndroidJUnit4;import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;import org.junit.runner.RunWith;import static org.junit.Assert.*;
/** Runs against the release APK, after adb force-stop, without warming the engine. */
@RunWith(AndroidJUnit4.class)
public final class ReleaseColdStartTest {
 @Test public void ColdReleaseLaunchShowsThreePatrolPanelsAndKeepsPairing()throws Exception{
  Instrumentation ins=InstrumentationRegistry.getInstrumentation();Context c=ins.getTargetContext();
  assertFalse("This probe must target the signed release",BuildConfig.DEBUG);
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
}
