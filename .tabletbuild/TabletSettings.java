package au.com.roningroup.patrollink;
import android.content.*;
final class TabletSettings {
 static final String PREFS="patrol_tablet_display_v153";
 static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREFS,0);}
 static int refresh(Context c){return TabletPolicy.interval(prefs(c).getInt("fleet_seconds",15));}
 static float radius(Context c,float individual){
  float r=prefs(c).getBoolean("radius_override",false)?prefs(c).getFloat("radius_metres",20):individual;
  try{return TabletPolicy.radius(r);}catch(Exception e){return 20;}
 }
 static void saveRadius(Context c,boolean override,float r){TabletPolicy.radius(r);prefs(c).edit().putBoolean("radius_override",override).putFloat("radius_metres",r).apply();}
 private TabletSettings(){}
}