package au.com.roningroup.patrollink;
import android.app.*;import android.content.*;import android.graphics.*;import android.os.*;import android.view.*;import android.widget.*;import androidx.test.ext.junit.runners.AndroidJUnit4;import androidx.test.platform.app.InstrumentationRegistry;import org.junit.*;import org.junit.runner.RunWith;import static org.junit.Assert.*;import org.osmdroid.views.overlay.*;import java.util.*;import java.lang.reflect.*;
@RunWith(AndroidJUnit4.class)
public final class TabletDashboardTest {
 Instrumentation ins;TabletHostActivity activity;
 @Before public void start()throws Exception{ins=InstrumentationRegistry.getInstrumentation();TabletHostActivity.preserveSettings=false;activity=(TabletHostActivity)ins.startActivitySync(new Intent(ins.getTargetContext(),TabletHostActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));ins.waitForIdleSync();Thread.sleep(1500);}
 @After public void end(){ins.runOnMainSync(()->activity.finish());ins.waitForIdleSync();}
 private void main(Runnable r){ins.runOnMainSync(r);ins.waitForIdleSync();}
 private Object field(String name)throws Exception{Field f=TabletDashboard.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity.dashboard);}
 @Test public void LandscapeDashboardKeepsThreePanelsAndNativeStreetMap()throws Exception{
  assertTrue(activity.getResources().getDisplayMetrics().widthPixels>activity.getResources().getDisplayMetrics().heightPixels);
  main(()->{View left=activity.findViewById(153002),right=activity.findViewById(153004);assertTrue(left.getWidth()>0&&right.getWidth()>left.getWidth());for(int i=0;i<3;i++){View card=activity.findViewById(153010+i);assertEquals(View.VISIBLE,card.getVisibility());assertTrue(card.getHeight()>80);}assertEquals(View.VISIBLE,activity.findViewById(153003).getVisibility());assertEquals("OpenStreetMap",activity.dashboard.map.getTileProvider().getTileSource().name());});
  android.graphics.Bitmap screenshot=ins.getUiAutomation().takeScreenshot();assertNotNull(screenshot);java.io.File file=new java.io.File(activity.getExternalFilesDir(null),"tablet-landscape-test-fixture.png");try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}
 }
 @Test public void RealMarkerAdapterMovesAndRetainsStalePositionsOnLoss()throws Exception{
  Marker[] markers=(Marker[])field("patrolMarkers");assertNotNull(markers[0]);double before=markers[0].getPosition().getLatitude();
  main(()->{activity.fleetLatitude=-27.471;activity.dashboard.requestFleet();});assertNotEquals(before,markers[0].getPosition().getLatitude(),0);
  double retained=markers[0].getPosition().getLatitude();main(()->{activity.failFleet=true;activity.dashboard.requestFleet();});
  assertEquals(retained,markers[0].getPosition().getLatitude(),0);assertTrue(markers[0].getTitle().contains("STALE"));
 }
 @Test public void ActualFiveMetrePanelRequiresAccurateDistinctFixesAndDismissesOnExit()throws Exception{
  main(()->{TabletSettings.saveRadius(activity,true,5);activity.testAccuracy=8;activity.updateLocal();activity.dashboard.refresh();});Thread.sleep(20);main(()->{activity.updateLocal();activity.dashboard.refresh();});assertEquals(View.GONE,activity.findViewById(153020).getVisibility());
  main(()->{activity.testAccuracy=2;activity.updateLocal();activity.dashboard.refresh();});Thread.sleep(25);main(()->{activity.updateLocal();activity.dashboard.refresh();});assertEquals(View.VISIBLE,activity.findViewById(153020).getVisibility());
  main(()->{activity.localLatitude=-27.471;activity.updateLocal();activity.dashboard.refresh();});assertEquals(View.GONE,activity.findViewById(153020).getVisibility());
 }
 @Test public void RadiusAndRefreshSettingsSurviveActivityRestart()throws Exception{
  main(()->{TabletSettings.saveRadius(activity,true,5);TabletSettings.prefs(activity).edit().putInt("fleet_seconds",30).commit();activity.finish();});TabletHostActivity.preserveSettings=true;
  activity=(TabletHostActivity)ins.startActivitySync(new Intent(ins.getTargetContext(),TabletHostActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));assertEquals(5,TabletSettings.radius(activity,70),0);assertEquals(30,TabletSettings.refresh(activity));
 }
 @Test public void CogOpensOrganisedSettingsAndMissingPermissionDoesNotCrash()throws Exception{
  main(()->activity.findViewById(153003).performClick());Thread.sleep(500);
  assertTrue(ins.getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText("Tablet settings").size()>0);
  main(()->{LocalGpsService.prefs(activity).edit().putLong("fix_time",0).remove("lat").remove("lon").commit();activity.dashboard.refresh();});assertEquals(View.GONE,activity.findViewById(153020).getVisibility());
 }
 @Test public void SubscriberRoleCannotFetchOrDisplaySupervisorFleet()throws Exception{
  int before=activity.loads;main(()->{FleetRelay.configure(activity,false,1);activity.dashboard.requestFleet();});
  assertEquals(before,activity.loads);assertTrue(((Map<?,?>)field("fleet")).isEmpty());
 }
}