package au.com.roningroup.patrollink;
import android.app.*;import android.content.*;import android.os.*;import android.content.pm.ActivityInfo;import android.view.*;import android.widget.*;import org.json.*;import java.util.*;
/** Debug-only synthetic fixture. This Activity is excluded from the release APK. */
public final class TabletHostActivity extends Activity {
 public TabletDashboard dashboard;public PatrolEngine engine;public CheckpointProximityUi checkpointUi;
 public boolean failFleet;public int loads;public double fleetLatitude=-27.47;public float testAccuracy=2;public double localLatitude=-27.47001;
 public static boolean preserveSettings;
 @Override public void onCreate(Bundle state){
  super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
  getSharedPreferences("patrol_settings",0).edit().putString("guard0","Test Alpha").putString("guard1","Test Bravo").putString("guard2","N/A").apply();
  LocalGpsService.prefs(this).edit().putBoolean("device_mode_configured",true).putString("device_mode","master").putBoolean("intro_seen",true).putBoolean("background_explained",true).apply();
  if(!preserveSettings)TabletSettings.prefs(this).edit().clear().commit();
  try{JSONArray a=new JSONArray().put(new JSONObject().put("id","fixture-qr").put("name","Fixture checkpoint").put("group","PBC3").put("order",1).put("lat",-27.47).put("lon",153.025).put("radius",5).put("photoPath","").put("assetPhoto",""));getSharedPreferences("patrol_checkpoint_proximity_v1",0).edit().putString("checkpoints_json",a.toString()).apply();}catch(Exception e){throw new RuntimeException(e);}
  engine=new PatrolEngine(this);checkpointUi=new CheckpointProximityUi(this);
  updateLocal();
  dashboard=new TabletDashboard(this,engine,checkpointUi,action->{if(action.equals("settings"))dashboard.settings();},callback->{
   loads++;if(failFleet){callback.done(Collections.emptyMap(),"TEST network outage");return;}
   LinkedHashMap<Integer,FleetRelay.Patrol> fleet=new LinkedHashMap<>();
   for(int i=1;i<=3;i++){FleetRelay.Patrol p=new FleetRelay.Patrol();p.number=i;p.lat=fleetLatitude+(i-1)*.0005;p.lon=153.025+(i-1)*.0004;p.time=System.currentTimeMillis();p.accuracy=3;p.onDuty=true;p.state="Test position";p.hasFix=true;fleet.put(i,p);}callback.done(fleet,null);
  });
  LinearLayout host=new LinearLayout(this);host.setOrientation(LinearLayout.VERTICAL);
  TextView warning=new TextView(this);warning.setText("TEST FIXTURE — SYNTHETIC GPS, NOT LIVE FLEET");warning.setTextColor(0xffffffff);warning.setBackgroundColor(0xff9e202a);warning.setGravity(Gravity.CENTER);warning.setTextSize(12);host.addView(warning,new LinearLayout.LayoutParams(-1,24));
  host.addView(dashboard.view,new LinearLayout.LayoutParams(-1,0,1));setContentView(host);
 }
 public void updateLocal(){LocalGpsService.prefs(this).edit().putBoolean("enabled",true).putBoolean("mock",false).putLong("lat",Double.doubleToRawLongBits(localLatitude)).putLong("lon",Double.doubleToRawLongBits(153.025)).putFloat("accuracy",testAccuracy).putLong("fix_time",System.currentTimeMillis()).putLong("fix_elapsed",SystemClock.elapsedRealtime()).putLong("boot",System.currentTimeMillis()-SystemClock.elapsedRealtime()).apply();}
 @Override public void onResume(){super.onResume();if(dashboard!=null)dashboard.resume();}
 @Override public void onPause(){if(dashboard!=null)dashboard.pause();super.onPause();}
 @Override public void onDestroy(){if(dashboard!=null)dashboard.close();super.onDestroy();}
}