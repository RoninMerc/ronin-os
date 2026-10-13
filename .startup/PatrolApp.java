package au.com.roningroup.patrollink;
import android.app.Application;import android.os.*;import java.util.*;import java.util.concurrent.*;
public final class PatrolApp extends Application {
 private volatile PatrolEngine engine;
 private final ExecutorService startup=Executors.newSingleThreadExecutor();
 private final Handler main=new Handler(Looper.getMainLooper());
 private final List<Ready> waiting=new ArrayList<>();private boolean preparing;
 @Override public void onCreate(){super.onCreate();StartupState.begin(this);}
 interface Ready{void done(PatrolEngine engine,Throwable failure);}
 public PatrolEngine engine(){if(engine==null)engine=new PatrolEngine(this);return engine;}
 /** Saved voice/checkpoint files are read off the UI thread on operational startup. */
 void prepare(Ready ready){
  if(Looper.myLooper()!=Looper.getMainLooper()){main.post(()->prepare(ready));return;}
  if(engine!=null){ready.done(engine,null);return;}
  waiting.add(ready);if(preparing)return;preparing=true;
  startup.execute(()->{
   PatrolEngine built=null;Throwable failure=null;
   try{
    if(BuildConfig.DEBUG){StartupHooks.engineWasMain=Looper.myLooper()==Looper.getMainLooper();if(StartupHooks.engineDelay>0)try{Thread.sleep(StartupHooks.engineDelay);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
    built=new PatrolEngine(this);
   }catch(RuntimeException|LinkageError problem){failure=problem;}
   final PatrolEngine result=built;final Throwable error=failure;
   main.post(()->{
    if(error==null&&engine==null)engine=result;
    preparing=false;List<Ready> callbacks=new ArrayList<>(waiting);waiting.clear();
    for(Ready callback:callbacks)callback.done(engine,error);
   });
  });
 }
}
