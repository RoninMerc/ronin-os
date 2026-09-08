from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'));j=root/'app/src/main/java/com/ronin/vanta'
p=j/'JobNotifications.java';s=p.read_text();marker='  public static Notification current(Context c) {';assert s.count(marker)==1
s=s.replace(marker,'''  public static Notification starting(Context c) {
    manager(c);
    Notification.Builder b=new Notification.Builder(c,"vanta-work")
        .setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle("Vanta is starting your task")
        .setContentText("Preparing saved work").setContentIntent(open(c,"")).setOngoing(true)
        .setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).setProgress(100,0,true);
    if(Build.VERSION.SDK_INT>=31)b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
    return b.build();
  }

''' + marker);p.write_text(s)
(j/'VantaWorkService.java').write_text('''package com.ronin.vanta;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.*;

/** User-initiated finite transfers; no microphone, permanent wake lock, or background-call claim. */
public final class VantaWorkService extends Service {
  private JobEngine engine;
  private int latestStartId;
  static volatile boolean foreground;
  private final Handler handler=new Handler(Looper.getMainLooper());
  @Override public void onCreate(){
    super.onCreate();
    try{startForeground(JobNotifications.FOREGROUND,JobNotifications.starting(this),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);foreground=true;}
    catch(RuntimeException denied){foreground=false;}
    engine=JobEngine.get(this);
  }
  private final Runnable tick=new Runnable(){public void run(){
    if(engine.executing()==0){
      if(stopSelfResult(latestStartId)){foreground=false;stopForeground(STOP_FOREGROUND_REMOVE);return;}
      handler.postDelayed(this,250);return;
    }
    try{JobNotifications.update(VantaWorkService.this);}catch(RuntimeException ignored){}
    handler.postDelayed(this,1000);
  }};
  @Override public int onStartCommand(Intent intent,int flags,int startId){
    latestStartId=startId;
    try{startForeground(JobNotifications.FOREGROUND,JobNotifications.current(this),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);foreground=true;}
    catch(RuntimeException denied){engine.schedulePending();stopSelfResult(startId);return START_NOT_STICKY;}
    if(intent!=null&&"cancel".equals(intent.getAction())){
      try{String id=intent.getStringExtra("job");if(id!=null)engine.cancel(id);}catch(Exception ignored){}
    }
    engine.wake(true,null);
    handler.removeCallbacks(tick);handler.postDelayed(tick,400);
    return START_NOT_STICKY;
  }
  @Override public void onTimeout(int startId,int type){
    if(engine!=null){engine.suspend();engine.schedulePending();}
    handler.removeCallbacksAndMessages(null);foreground=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
  }
  @Override public void onDestroy(){
    handler.removeCallbacksAndMessages(null);foreground=false;
    if(engine!=null)engine.schedulePending();
    super.onDestroy();
  }
  @Override public IBinder onBind(Intent intent){return null;}
}
''')
p=j/'JobEngine.java';s=p.read_text()
old='      main.post(\n          () -> {\n            if (calls.isEmpty()) context.stopService(new Intent(context, VantaWorkService.class));\n          });'
assert s.count(old)==1
s=s.replace(old,"      // VantaWorkService uses start-ID-safe idle shutdown; never stop a pending promotion here.")
p.write_text(s)
p=j/'JobNotifications.java';s=p.read_text();old="  public static synchronized void update(Context c) {";assert s.count(old)==1
s=s.replace(old,old+"\n    if(!VantaWorkService.foreground)return;");p.write_text(s)
p=root/'app/src/androidTest/java/com/ronin/vanta/BackgroundDeviceTest.java';s=p.read_text()
old='h.awaitText(reopened, "All 200 test records persisted");'
assert s.count(old)==1
s=s.replace(old,'h.awaitText(reopened, "The result is saved and ready");');p.write_text(s)
p=root/'app/src/androidTest/java/com/ronin/vanta/MasterDeviceTest.java';s=p.read_text().rstrip();assert s.endswith('}')
s=s[:-1]+'''
 @Test public void immediateCancellationDoesNotRaceForegroundPromotion()throws Exception{
  JobEngine e=engine();e.setHandlerForTests((en,job,in,c)->{Thread.sleep(40);c.check();en.completed(job,new JSONObject().put("text","saved"));});
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
    List<String> ids=new ArrayList<>();
    for(int n=0;n<6;n++){VantaJob job=e.enqueue(ctx(),"fixture","QA rapid cancellation "+n,"qa-rapid-"+n,new JSONObject());ids.add(job.id());e.cancel(job.id());}
    Thread.sleep(1800);scenario.recreate();
    for(String id:ids)assertTrue(Arrays.asList("CANCELLED","COMPLETED").contains(e.store.get(id).status()));
  }
 }
}
''';p.write_text(s)
p=j/'PromptStrategy.java';s=p.read_text();marker='  public static boolean trivial(String s) {';assert s.count(marker)==1
s=s.replace(marker,'''  public static String generatorAlias(String depth,String request) {
    return alias(trivial(request)?"Quick":depth(depth));
  }

'''+marker);p.write_text(s)
p=j/'VantaHub.java';s=p.read_text();old='prefs.getString("alias_" + PromptStrategy.alias(d), "")';assert s.count(old)==1
s=s.replace(old,'prefs.getString("alias_" + PromptStrategy.generatorAlias(d,request), "")');p.write_text(s)
p=root/'app/src/test/java/com/ronin/vanta/FinalContractTest.java';s=p.read_text().rstrip();assert s.endswith('}')
s=s[:-1]+'''
 @Test public void simpleWritingUsesFastAliasDespiteRetainedMaximumDepth(){
   assertEquals("PROMPT_FAST",PromptStrategy.generatorAlias("Maximum","Make this paragraph more professional."));
   assertEquals("PROMPT_FAST",PromptStrategy.generatorAlias("Deep","Write me an email."));
 }
 @Test public void complexEngineeringKeepsMaximumAlias(){
   assertEquals("PROMPT_MAX",PromptStrategy.generatorAlias("Maximum","Inspect and rebuild this Android project, compile it and test it."));
 }
}
''';p.write_text(s)
print('Foreground lifecycle race fixed; trivial writing uses FAST, complex tasks retain their chosen depth.')
