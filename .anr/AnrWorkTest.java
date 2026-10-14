package au.com.roningroup.patrollink;
import android.app.*;import android.content.*;import android.os.*;import org.json.*;import androidx.test.ext.junit.runners.AndroidJUnit4;import androidx.test.platform.app.InstrumentationRegistry;import org.junit.*;import org.junit.runner.RunWith;import java.util.concurrent.*;import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)public final class AnrWorkTest{
 Instrumentation ins;Context c;Activity activity;
 @Before public void setup()throws Exception{
  ins=InstrumentationRegistry.getInstrumentation();c=ins.getTargetContext();AnrHooks.cryptoDelay=AnrHooks.photoDelay=0;AnrHooks.cryptoMain=AnrHooks.photoMain=true;AnrHooks.mainCryptoCalls.set(0);
  new MasterFeedSecrets(c).save(false,"ANR_TEST_READER_KEY_1234567890123456789");new MasterFeedSecrets(c).save(true,"ANR_TEST_MASTER_KEY_1234567890123456789");
  CaptureHooks.poster=(path,body)->"{\"ok\":true,\"checkpoints\":[],\"rows\":[]}";
 }
 @After public void cleanup(){AnrHooks.cryptoDelay=AnrHooks.photoDelay=0;CaptureHooks.poster=null;if(activity!=null)ins.runOnMainSync(()->activity.finish());}
 @Test public void ConfiguredChecksDoNotDecryptOnTheUiThread(){
  AnrHooks.cryptoDelay=1000;long start=SystemClock.elapsedRealtime();
  ins.runOnMainSync(()->{assertTrue(MasterFeedClient.ready(c,false));assertTrue(MasterFeedClient.ready(c,true));});
  assertTrue("Configured checks must not invoke a blocked Keystore",SystemClock.elapsedRealtime()-start<600);
  assertEquals("No main-thread decryption hook should run",0,AnrHooks.mainCryptoCalls.get());
 }
 @Test public void FeedAndCheckpointPollingKeepMainThreadResponsive()throws Exception{
  AnrHooks.cryptoDelay=1000;CountDownLatch done=new CountDownLatch(2);long start=SystemClock.elapsedRealtime();
  ins.runOnMainSync(()->{MasterFeedClient.poll(c,(data,error)->{assertNotNull(data);assertNull(error);done.countDown();});CheckpointSyncClient.download(c,(data,error)->{assertNotNull(data);assertNull(error);done.countDown();});});
  assertTrue("Polling must return without waiting for Keystore",SystemClock.elapsedRealtime()-start<600);
  final boolean[] heartbeat={false};ins.runOnMainSync(()->heartbeat[0]=true);assertTrue(heartbeat[0]);
  assertTrue("Worker callbacks finish",done.await(8,TimeUnit.SECONDS));assertFalse("Decryption runs on a worker",AnrHooks.cryptoMain);
 }
 @Test public void CheckpointPhotoApplyRunsOffMainAndPersistsTheSameMetadata()throws Exception{
  activity=ins.startActivitySync(new Intent(c,MonitorFilterHostActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  final CheckpointProximityUi[] ui={null};ins.runOnMainSync(()->ui[0]=new CheckpointProximityUi(activity));
  long revision=System.currentTimeMillis()+10000;JSONArray rows=new JSONArray().put(new JSONObject().put("id","ANR-FIXTURE").put("name","ANR synthetic checkpoint").put("group","PBC3").put("order",7).put("lat",-27.93).put("lon",153.4).put("radius",10).put("photoBase64","U1lOVEhFVElD"));
  AnrHooks.photoDelay=1200;long start=SystemClock.elapsedRealtime();
  ins.runOnMainSync(()->{try{java.lang.reflect.Method method=CheckpointProximityUi.class.getDeclaredMethod("applyRemote",JSONArray.class,long.class);method.setAccessible(true);method.invoke(ui[0],rows,revision);}catch(Exception e){throw new RuntimeException(e);}});
  assertTrue("Photo apply must return before worker delay",SystemClock.elapsedRealtime()-start<700);
  android.content.SharedPreferences p=c.getSharedPreferences("patrol_checkpoint_proximity_v1",0);
  for(int attempt=0;attempt<60&&p.getLong("checkpoint_sync_received_at",0)!=revision;attempt++)Thread.sleep(100);
  assertEquals(revision,p.getLong("checkpoint_sync_received_at",0));assertFalse("Photo work must run off main",AnrHooks.photoMain);
  JSONObject stored=new JSONArray(p.getString("checkpoints_json","[]")).getJSONObject(0);assertEquals("ANR-FIXTURE",stored.getString("id"));assertEquals(7,stored.getInt("order"));assertEquals(10,stored.getDouble("radius"),0);assertTrue(new java.io.File(stored.getString("photoPath")).isFile());
 }
}
