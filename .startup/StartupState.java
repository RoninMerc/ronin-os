package au.com.roningroup.patrollink;
import android.content.*;import android.os.*;
/** Private phase markers only. No credentials, URLs, report data or exception messages. */
final class StartupState {
 static final String FILE="patrol_startup_health_v157";
 static SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences(FILE,0);}
 static void begin(Context c){
  SharedPreferences p=prefs(c);String prior=p.getString("phase","");int pid=p.getInt("pid",-1);
  if(pid!=android.os.Process.myPid()&&!prior.isEmpty()){
   SharedPreferences.Editor e=p.edit().putString("last_failure",prior+"/interrupted");
   if("browser".equals(prior))e.putBoolean("browser_held",true);
   if("map".equals(prior))e.putBoolean("map_held",true);
   e.commit();
  }
  p.edit().putInt("pid",android.os.Process.myPid()).putString("phase","").commit();
 }
 static void phase(Context c,String phase){prefs(c).edit().putInt("pid",android.os.Process.myPid()).putString("phase",phase).commit();}
 static void done(Context c){prefs(c).edit().putString("phase","").apply();}
 static void failed(Context c,String phase,Throwable failure){
  prefs(c).edit().putString("phase","").putString("last_failure",phase+"/"+failure.getClass().getSimpleName())
   .putBoolean(phase+"_held",true).commit();
 }
 static boolean held(Context c,String phase){return prefs(c).getBoolean(phase+"_held",false);}
 static void retry(Context c,String phase){prefs(c).edit().putBoolean(phase+"_held",false).putString("phase","").commit();}
 static String summary(Context c){return "Startup: "+prefs(c).getString("last_failure","no recorded failure")+" · browser "+(held(c,"browser")?"awaiting retry":"available")+" · map "+(held(c,"map")?"awaiting retry":"available");}
 private StartupState(){}
}
