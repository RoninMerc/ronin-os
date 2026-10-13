package au.com.roningroup.patrollink;
import android.content.*;import org.json.*;
/** Private, durable queue. Stores read-only source URLs and parsed records, never cookies or passwords. */
final class CaptureQueue {
 static SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences("patrol_capture_jobs_v156",0);}
 static synchronized void save(Context c,java.util.Collection<JSONObject> pending,JSONObject active){
  JSONArray list=new JSONArray();if(active!=null)list.put(active);for(JSONObject x:pending)list.put(x);
  prefs(c).edit().putString("queue",list.toString()).commit();
 }
 static synchronized JSONArray load(Context c){try{return new JSONArray(prefs(c).getString("queue","[]"));}catch(Exception e){return new JSONArray();}}
 static void add(Context c,String key,int n){if(n<=0)return;SharedPreferences p=prefs(c);p.edit().putInt(key,p.getInt(key,0)+n).commit();}
 static int count(Context c,String key){return prefs(c).getInt(key,0);}
 static String status(Context c){
  SharedPreferences p=prefs(c);
  return p.getString("stage","Automatic capture is ready.")+"\nPages read: "+p.getInt("pages",0)+" · reports linked: "+p.getInt("linked",0)+"\nCloud confirmed — created: "+p.getInt("created",0)+" · updated: "+p.getInt("updated",0)+" · duplicate: "+p.getInt("duplicate",0)+"\nReview needed: "+p.getInt("review",0)+" · skipped: "+p.getInt("ignored",0)+" · failed: "+p.getInt("failed",0)+"\nPhotos confirmed: "+p.getInt("photos",0)+" · photo failures: "+p.getInt("photo_failed",0)+"\nUnresolved report links: "+p.getInt("unlinked",0)+" · photo duplicates: "+p.getInt("photo_duplicate",0)+"\n"+p.getString("last","No cloud receipt yet.");
 }
 static void stage(Context c,String s){prefs(c).edit().putString("stage",s).commit();}
 private CaptureQueue(){}
}
