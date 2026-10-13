package au.com.roningroup.patrollink;
import android.app.*;import android.content.*;import android.os.*;import android.webkit.*;import org.json.*;import java.lang.ref.*;import java.util.*;import java.time.ZoneId;
final class AutoCaptureJob {
 private static final Handler UI=new Handler(Looper.getMainLooper());private static final Set<String> visited=new HashSet<>();
 private static WeakReference<Activity> activity=new WeakReference<>(null);private static WeakReference<WebView> browser=new WeakReference<>(null);
 private static boolean running,reading,pageDone,waiting;private static long began,nextAt;private static String fingerprint="",beforeNext="",finished="";
 private static int stable;private static Context context;private static ZoneId zone;private static int generation;
 static boolean enabled(Context c){return CaptureQueue.prefs(c).getBoolean("auto_enabled",true);}
 static boolean running(){return running;}
 static void bind(Activity a,WebView w,ZoneId z){activity=new WeakReference<>(a);browser=new WeakReference<>(w);context=a.getApplicationContext();zone=z;if(TabletSettings.tablet(a)&&enabled(a))UI.postDelayed(()->{if(activity.get()==a&&!running)start(a,w,z,false);},1600);}
 static void filtersChanged(Activity a,WebView w){if(!TabletSettings.tablet(a)||!enabled(a))return;finished="";pause(a,"Filters changed; waiting for the selected results.");UI.postDelayed(()->{if(activity.get()==a)start(a,w,((PatrolApp)a.getApplication()).engine().zone(),true);},1800);}
 static void start(Activity a,WebView w,ZoneId z,boolean explicit){
  if(!TabletSettings.tablet(a)){VehicleCaptureUi.showSinglePage(a,((PatrolApp)a.getApplication()).engine());return;}
  activity=new WeakReference<>(a);browser=new WeakReference<>(w);context=a.getApplicationContext();zone=z;
  if(!MasterFeedClient.master(a)){CaptureQueue.stage(a,"Waiting: this tablet must be the authorised Master collector.");return;}
  if(!MasterFeedClient.ready(a,true)){CaptureQueue.stage(a,"Waiting: configure the Publisher key on this Master collector.");return;}
  if(!PatrolEngine.monitorPage(w.getUrl())){CaptureQueue.stage(a,"Waiting: sign into the SilverTrack Issue Monitor.");return;}
  if(running)return;
  if(!explicit&&!finished.isEmpty()){w.evaluateJavascript("JSON.stringify("+CapturePager.SCRIPT+"(false))",x->{try{JSONObject d=new JSONObject(String.valueOf(new JSONTokener(x).nextValue()));if(!finished.equals(d.optString("fingerprint")))start(a,w,z,true);}catch(Exception ignored){}});return;}
  running=true;reading=false;pageDone=false;waiting=false;stable=0;fingerprint="";beforeNext="";visited.clear();began=SystemClock.elapsedRealtime();nextAt=0;++generation;
  CaptureQueue.prefs(a).edit().putBoolean("auto_enabled",true).putBoolean("job_active",true).commit();CaptureQueue.stage(a,"Reading selected SilverTrack results automatically…");
  if(explicit)SilverTrackVehicleCollector.retryFailures(a);
  SilverTrackVehicleCollector.restore(a,w,z);schedule(generation);
 }
 static void pause(Activity a,String reason){if(activity.get()!=a)return;running=false;reading=false;++generation;if(context!=null){CaptureQueue.prefs(context).edit().putBoolean("job_active",false).commit();CaptureQueue.stage(context,reason);}UI.removeCallbacks(tick);}
 static void stop(Activity a){CaptureQueue.prefs(a).edit().putBoolean("auto_enabled",false).commit();pause(a,"Paused by operator. Queued reports are retained for resume.");}
 private static final Runnable tick=()->check(generation);
 private static void schedule(int token){if(running&&token==generation){UI.removeCallbacks(tick);UI.postDelayed(tick,1000);}}
 private static void check(int token){
  Activity a=activity.get();WebView w=browser.get();
  if(!running||token!=generation)return;
  if(a==null||a.isFinishing()||!((PatrolApp)a.getApplication()).engine().browserVisible){if(a!=null)pause(a,"Paused: reopen the tablet SilverTrack browser to resume.");return;}
  if(!MasterFeedClient.master(a)||!MasterFeedClient.ready(a,true)){pause(a,"Paused: collector role or Publisher pairing changed.");return;}
  if(!PatrolEngine.monitorPage(w.getUrl())){pause(a,"Paused: SilverTrack session changed; sign in again.");return;}
  if(MonitorFilterUi.interacting(w)){schedule(token);return;}
  if(SystemClock.elapsedRealtime()<nextAt){schedule(token);return;}
  if(reading&&SystemClock.elapsedRealtime()-began>25000){failedPage(context,"The page reader did not return usable results.");return;}
  if(SilverTrackVehicleCollector.busy()||reading){schedule(token);return;}
  w.evaluateJavascript("JSON.stringify("+CapturePager.SCRIPT+"(false))",encoded->{
   if(!running||token!=generation)return;
   try{
    JSONObject data=new JSONObject(String.valueOf(new JSONTokener(encoded).nextValue()));String key=data.optString("fingerprint");
    if(waiting){
     if(!key.equals(beforeNext)&&!key.equals("#")){waiting=false;pageDone=false;reading=false;fingerprint="";stable=0;began=SystemClock.elapsedRealtime();}
     else{if(SystemClock.elapsedRealtime()-began>30000)pause(a,"Paused: SilverTrack did not load the next page. Resume after its results appear.");schedule(token);return;}
    }
    if(!key.equals(fingerprint)){fingerprint=key;stable=0;nextAt=SystemClock.elapsedRealtime()+1200;schedule(token);return;}
    if(++stable<2){schedule(token);return;}
    if(!pageDone){
     if(!visited.add(key)){pause(a,"Stopped: repeated page detected; saved imports are retained.");return;}
     if(visited.size()>500){pause(a,"Paused after 500 pages. Resume to continue the selected results.");return;}
     reading=true;began=SystemClock.elapsedRealtime();CaptureQueue.add(context,"pages",1);CaptureQueue.stage(context,"Reading page "+visited.size()+"…");
     SilverTrackVehicleCollector.captureLoadedPage(a,w,zone);schedule(token);return;
    }
    if(data.optBoolean("next")){
     beforeNext=key;waiting=true;began=SystemClock.elapsedRealtime();CaptureQueue.stage(context,"Loading the next selected result page…");
     w.evaluateJavascript("JSON.stringify("+CapturePager.SCRIPT+"(true))",x->{if(running&&token==generation)nextAt=SystemClock.elapsedRealtime()+1600;});schedule(token);
    }else{finished=key;running=false;CaptureQueue.prefs(context).edit().putBoolean("job_active",false).commit();CaptureQueue.stage(context,"Finished the selected result pages. Read cloud counts below; review and failed items still need attention.");}
   }catch(Exception e){pause(a,"Paused: pagination could not be read. Reopen the result page and resume.");}
  });
 }
 static void pageRead(Context c,int linked){CaptureQueue.add(c,"linked",linked);reading=false;pageDone=true;}
 static void failedPage(Context c,String reason){reading=false;if(running){running=false;++generation;CaptureQueue.stage(c,"Paused: "+reason);}CaptureQueue.add(c,"failed",1);}
 static void receipt(Context c,JSONObject imports){for(String k:new String[]{"created","updated","duplicate","review","ignored"})CaptureQueue.add(c,k,imports.optInt(k));}
 static String status(Context c){return CaptureQueue.status(c);}
 private AutoCaptureJob(){}
}
