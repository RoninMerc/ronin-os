package au.com.roningroup.patrollink;
import android.app.*;import android.content.*;import android.os.*;import java.util.*;
/** Local bounded technical diagnostics. Never records throwable messages or page/report contents. */
final class CrashReport {
 static final String FILE="patrol_crash_report_v158";
 static android.content.SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences(FILE,0);}
 static void install(Context c){
  Context app=c.getApplicationContext();Thread.UncaughtExceptionHandler old=Thread.getDefaultUncaughtExceptionHandler();
  if(!(old instanceof Recorder))Thread.setDefaultUncaughtExceptionHandler(new Recorder(app,old));
  previousExit(app);
 }
 static final class Recorder implements Thread.UncaughtExceptionHandler{
  final Context context;final Thread.UncaughtExceptionHandler prior;
  Recorder(Context c,Thread.UncaughtExceptionHandler p){context=c;prior=p;}
  @Override public void uncaughtException(Thread thread,Throwable error){
   try{record(context,error);}catch(Throwable ignored){}
   finally{if(prior!=null)prior.uncaughtException(thread,error);else{android.os.Process.killProcess(android.os.Process.myPid());System.exit(10);}}
  }
 }
 static void record(Context c,Throwable error){
  StringBuilder out=new StringBuilder("TRL Patrol Link "+BuildConfig.VERSION_NAME+" / API "+Build.VERSION.SDK_INT+"\n");
  out.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
  Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
  for(int cause=0;error!=null&&cause<4&&seen.add(error);cause++,error=error.getCause()){
   out.append(cause==0?"Exception: ":"Caused by: ").append(error.getClass().getName()).append("\n");
   StackTraceElement[] frames=error.getStackTrace();for(int i=0;i<Math.min(frames.length,18);i++)
    out.append("  ").append(frames[i].getClassName()).append(".").append(frames[i].getMethodName()).append(":").append(frames[i].getLineNumber()).append("\n");
  }
  prefs(c).edit().putString("report",out.toString()).putLong("at",System.currentTimeMillis()).putBoolean("pending",true).commit();
 }
 private static final java.util.concurrent.ExecutorService TRACE_IO=java.util.concurrent.Executors.newSingleThreadExecutor();
 private static void previousExit(Context c){
  if(Build.VERSION.SDK_INT<30)return;
  try{
   ActivityManager m=c.getSystemService(ActivityManager.class);if(m==null)return;
   List<ApplicationExitInfo> exits=m.getHistoricalProcessExitReasons(c.getPackageName(),0,6);
   for(ApplicationExitInfo exit:exits){
    int reason=exit.getReason();if(reason!=ApplicationExitInfo.REASON_CRASH&&reason!=ApplicationExitInfo.REASON_CRASH_NATIVE&&reason!=ApplicationExitInfo.REASON_ANR)continue;
    long at=exit.getTimestamp();android.content.SharedPreferences p=prefs(c);
    if(at>p.getLong("exit_seen",0)){
     p.edit().putLong("exit_seen",at).apply();
     if(p.getLong("at",0)<at-60000||p.getString("report","").isEmpty())
      p.edit().putString("report","TRL Patrol Link "+BuildConfig.VERSION_NAME+" / API "+Build.VERSION.SDK_INT+"\nPrevious process exit: "+(reason==ApplicationExitInfo.REASON_ANR?"ANR — app not responding":String.valueOf(reason))+"\nLoading available main-thread diagnostics…").putLong("at",at).putBoolean("pending",true).commit();
    }
    if(reason==ApplicationExitInfo.REASON_ANR&&p.getLong("at",0)==at&&p.getLong("trace_seen",0)!=at){
     TRACE_IO.execute(()->{
      String frames;try(java.io.InputStream in=exit.getTraceInputStream()){frames=AnrFrames.read(in);}catch(Exception e){frames="ANR trace unavailable ("+e.getClass().getSimpleName()+").";}
      if(p.getLong("at",0)!=at)return;
      p.edit().putString("report","TRL Patrol Link "+BuildConfig.VERSION_NAME+" / API "+Build.VERSION.SDK_INT+"\nANR — app not responding\n"+frames).putLong("trace_seen",at).apply();
     });
    }
    break;
   }
  }catch(RuntimeException|LinkageError ignored){}
 }
 static boolean pending(Context c){return prefs(c).getBoolean("pending",false);}
 static String read(Context c){return prefs(c).getString("report","No local crash recorded.");}
 static void acknowledge(Context c){prefs(c).edit().putBoolean("pending",false).commit();}
 private CrashReport(){}
}
