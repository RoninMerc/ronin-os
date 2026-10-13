package au.com.roningroup.patrollink;
import android.app.*;import android.content.*;import android.os.*;import androidx.test.ext.junit.runners.AndroidJUnit4;import androidx.test.platform.app.InstrumentationRegistry;import org.junit.*;import org.junit.runner.RunWith;import static org.junit.Assert.*;
/** Runs under the original release certificate before and after a real PackageManager upgrade. */
@RunWith(AndroidJUnit4.class)
public final class TabletUpgradeTest {
 private static final String READER="FIXTURE-READER-NOT-A-LIVE-KEY-1234567890";
 @Test public void writeBaseline()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();assertEquals(162,c.getPackageManager().getPackageInfo(c.getPackageName(),0).getLongVersionCode());
  c.getSharedPreferences("tablet_upgrade_probe",0).edit().putInt("uid",android.os.Process.myUid()).commit();
  c.getSharedPreferences("patrol_settings",0).edit().putString("guard0","Upgrade Test Guard").putBoolean("voice",false).commit();
  c.getSharedPreferences("patrol_checkpoint_proximity_v1",0).edit().putString("checkpoints_json","[{\"id\":\"upgrade-qr\",\"name\":\"Upgrade QR\",\"group\":\"PBC1\",\"order\":1,\"lat\":-27.47,\"lon\":153.025,\"radius\":5,\"photoPath\":\"\",\"assetPhoto\":\"\"}]").commit();
  LocalGpsService.prefs(c).edit().putBoolean("intro_seen",true).putBoolean("background_explained",true).putBoolean("enabled",false).putBoolean("device_mode_configured",false).commit();
  new MasterFeedSecrets(c).save(false,READER);
  assertEquals("Baseline can decrypt its saved pairing",READER,new MasterFeedSecrets(c).load(false));
  SharedPreferences encrypted=c.getApplicationContext().getSharedPreferences("patrol_master_feed_v1",0);
  assertTrue(encrypted.edit().putBoolean("upgrade_probe_flushed",true).commit());
  c.getSharedPreferences("tablet_upgrade_probe",0).edit().putString("cipher",encrypted.getString("reader_cipher","")).commit();
 }
 @Test public void verifyUpgradeAndLaunchTablet()throws Exception{
  Instrumentation ins=InstrumentationRegistry.getInstrumentation();Context c=ins.getTargetContext();
  assertEquals(165,c.getPackageManager().getPackageInfo(c.getPackageName(),0).getLongVersionCode());
  assertEquals(c.getSharedPreferences("tablet_upgrade_probe",0).getInt("uid",-1),android.os.Process.myUid());
  assertEquals("Upgrade Test Guard",c.getSharedPreferences("patrol_settings",0).getString("guard0",""));assertFalse(c.getSharedPreferences("patrol_settings",0).getBoolean("voice",true));
  assertTrue(c.getSharedPreferences("patrol_checkpoint_proximity_v1",0).getString("checkpoints_json","").contains("upgrade-qr"));
  assertEquals("Encrypted preference survived upgrade",c.getSharedPreferences("tablet_upgrade_probe",0).getString("cipher",""),c.getApplicationContext().getSharedPreferences("patrol_master_feed_v1",0).getString("reader_cipher",""));
  java.security.KeyStore keystore=java.security.KeyStore.getInstance("AndroidKeyStore");keystore.load(null);assertTrue("Existing pairing keystore alias survived",keystore.containsAlias("patrol_link_master_feed_pairing_v1"));
  assertEquals(READER,new MasterFeedSecrets(c).load(false));
  Activity main=ins.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));ins.waitForIdleSync();Thread.sleep(1000);
  ins.runOnMainSync(()->{assertNotNull(main.findViewById(153001));assertNotNull(main.findViewById(153005));assertTrue(main.getResources().getDisplayMetrics().widthPixels>main.getResources().getDisplayMetrics().heightPixels);main.finish();});
 }
}