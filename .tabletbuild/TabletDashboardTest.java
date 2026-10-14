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
  android.graphics.Bitmap screenshot=ins.getUiAutomation().takeScreenshot();assertNotNull(screenshot);java.io.File file=new java.io.File(activity.getFilesDir(),"tablet-landscape-test-fixture.png");try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}
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
  boolean found=false;for(int i=0;i<40;i++){android.view.accessibility.AccessibilityNodeInfo root=ins.getUiAutomation().getRootInActiveWindow();if(root!=null&&!root.findAccessibilityNodeInfosByText("Tablet settings").isEmpty()){found=true;break;}Thread.sleep(100);}assertTrue("Tablet settings dialog becomes visible",found);
  main(()->{LocalGpsService.prefs(activity).edit().putLong("fix_time",0).remove("lat").remove("lon").commit();activity.dashboard.refresh();});assertEquals(View.GONE,activity.findViewById(153020).getVisibility());
 }
 @Test public void SubscriberRoleCannotFetchOrDisplaySupervisorFleet()throws Exception{
  int before=activity.loads;main(()->{FleetRelay.configure(activity,false,1);activity.dashboard.requestFleet();});
  assertEquals(before,activity.loads);assertTrue(((Map<?,?>)field("fleet")).isEmpty());for(Marker m:(Marker[])field("patrolMarkers"))assertNull(m);
 }

 @Test public void RelayParserRejectsMissingZeroAndMockCoordinates()throws Exception{
  Method parser=FleetRelay.class.getDeclaredMethod("parse",String.class,Map.class);parser.setAccessible(true);
  Map<Integer,FleetRelay.Patrol> out=new LinkedHashMap<>();
  long now=System.currentTimeMillis();
  String missing="{\"patrols\":[{\"patrol\":1,\"lon\":153,\"time\":"+now+"},{\"patrol\":2,\"lat\":-27,\"time\":"+now+"},{\"patrol\":3,\"lat\":0,\"lon\":0,\"time\":"+now+"}]}";
  parser.invoke(null,missing,out);for(FleetRelay.Patrol p:out.values())assertFalse(p.hasFix);
  out.clear();parser.invoke(null,"{\"patrols\":[{\"patrol\":1,\"lat\":-27.47,\"lon\":153.025,\"time\":"+now+"}]}",out);assertTrue(out.get(1).hasFix);
  out.clear();parser.invoke(null,"{\"patrols\":[{\"patrol\":1,\"lat\":-27.47,\"lon\":153.025,\"time\":"+now+",\"mock\":true}]}",out);assertFalse(out.get(1).hasFix);
 }

 @Test public void LocalMarkerPinKeepsTruePositionAndRemainsIdentifiable()throws Exception{
  Marker own=(Marker)field("ownMarker");assertNotNull(own);
  assertEquals(activity.localLatitude,own.getPosition().getLatitude(),0);
  assertTrue(own.getIcon().getIntrinsicHeight()>own.getIcon().getIntrinsicWidth());
  assertEquals(own,activity.dashboard.map.getOverlays().get(activity.dashboard.map.getOverlays().size()-1));
 }
 @Test public void SupervisorReaderModeKeepsFleetRoleAndLocalSession()throws Exception{
  main(()->{
   android.webkit.CookieManager.getInstance().setCookie("https://trlsecurity.silvertracker.net","observer_fixture=kept");
   TabletSettings.prefs(activity).edit().putBoolean("read_existing_master",true).commit();
   assertTrue(FleetRelay.master(activity));assertFalse(MasterFeedClient.master(activity));assertTrue(MasterFeedClient.subscriber(activity));assertTrue(CheckpointSyncClient.subscriber(activity));
   activity.engine.changeFeedMode();
   assertTrue(android.webkit.CookieManager.getInstance().getCookie("https://trlsecurity.silvertracker.net").contains("observer_fixture=kept"));
  });
 }

 @Test public void OpenQrRetainsPhysicalWorkflowAndShowsActualDistance()throws Exception{
  main(()->{activity.testAccuracy=2;activity.updateLocal();activity.dashboard.refresh();});Thread.sleep(30);main(()->{activity.updateLocal();activity.dashboard.refresh();});
  assertEquals(View.VISIBLE,activity.findViewById(153020).getVisibility());
  main(()->activity.findViewById(153021).performClick());
  boolean found=false;for(int i=0;i<40;i++){android.view.accessibility.AccessibilityNodeInfo root=ins.getUiAutomation().getRootInActiveWindow();if(root!=null&&!root.findAccessibilityNodeInfosByText("Fixture checkpoint · 1 m").isEmpty()){found=true;break;}Thread.sleep(100);}
  assertTrue("QR photo dialog must show measured distance rather than zero",found);
  assertEquals(0,activity.engine.checkpoints.total("Test Alpha"));
 }
}
