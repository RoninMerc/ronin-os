from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'));j=root/'app/src/main/java/com/ronin/vanta'
p=j/'JobEngine.java';s=p.read_text()
a='  private volatile Handler testHandler;';assert s.count(a)==1
s=s.replace(a,a+'''
  private final java.util.concurrent.atomic.AtomicBoolean networkCheckQueued=new java.util.concurrent.atomic.AtomicBoolean();
  private ConnectivityManager.NetworkCallback networkCallback;
''',1)
a='    registry = ModelRegistry.get(c);\n    recover();';assert s.count(a)==1
s=s.replace(a,a+'\n    watchConnectivity();',1)
a='  public synchronized void wake(boolean userInitiated, Runnable finished) {';assert s.count(a)==1
s=s.replace(a,a+'''
    // User return or OS dispatch may happen after connectivity came back. Do not keep
    // an unsubmitted request asleep until its previous network-backoff deadline.
    resumeNetworkWaits();
''',1)
a='  boolean online() {';assert s.count(a)==1
s=s.replace(a,'''
  /** One application-lifetime subscription; no Activity reference, polling or wake lock. */
  private void watchConnectivity() {
    try {
      ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
      networkCallback=new ConnectivityManager.NetworkCallback(){
        @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities capabilities){
          if(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))queueNetworkRecovery();
        }
      };
      manager.registerDefaultNetworkCallback(networkCallback);
    }catch(RuntimeException unavailable){networkCallback=null;}
  }
  private void queueNetworkRecovery() {
    if(!networkCheckQueued.compareAndSet(false,true))return;
    workers.execute(()->{
      try {
        if(resumeNetworkWaits()==0)return;
        if(VantaWorkService.foreground)wake(true,null);
        else if(!listeners.isEmpty()) {
          // MainActivity registers its observer only while STARTED. Android still
          // authorizes the promotion; a concurrent background transition is caught.
          main.post(()->{try{
            if(!listeners.isEmpty())androidx.core.content.ContextCompat.startForegroundService(context,new Intent(context,VantaWorkService.class));
            else schedulePending();
          }catch(RuntimeException denied){schedulePending();}});
        }else schedulePending();
      }finally{networkCheckQueued.set(false);}
    });
  }
  /** Never releases a Retry-After delay, uncertain paid request, or cancelled/blocked job. */
  synchronized int resumeNetworkWaits() {
    if(!online())return 0;
    int resumed=0;
    try {
      for(VantaJob job:store.active()) {
        if(!"WAITING_NETWORK".equals(job.status())||calls.containsKey(job.id())||job.json.optBoolean("request_started"))continue;
        job.json.put("next_run",0);
        job.event("network_restored","QUEUED",ProgressState.transition(job.progress(),"RESUMING","Network connection restored. Continuing the saved, unsubmitted step."));
        try{store.recoveryCheckpoint(job,Collections.emptyMap(),Collections.emptyList());resumed++;signal(job.id());}
        catch(java.io.InterruptedIOException cancelled){/* Cancellation committed first. */}
      }
    }catch(Exception failure){recoveryError=Errors.summary(failure.getMessage());}
    return resumed;
  }

'''+a,1)
p.write_text(s)
p=root/'app/src/androidTest/java/com/ronin/vanta/MasterDeviceTest.java';s=p.read_text();a='  public void prepare() throws Exception {';assert s.count(a)==1
s=s.replace(a,a+'''
    long networkDeadline=SystemClock.elapsedRealtime()+45000;
    while(!engine().online()&&SystemClock.elapsedRealtime()<networkDeadline)Thread.sleep(100);
    assertTrue("QA prerequisite: Android must report an internet-capable default network before starting this online fixture",engine().online());
''',1);p.write_text(s)
p=root/'app/src/androidTest/java/com/ronin/vanta/NetworkRecoveryDeviceTest.java'
p.write_text('''package com.ronin.vanta;
import static org.junit.Assert.*;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
@RunWith(AndroidJUnit4.class)
public final class NetworkRecoveryDeviceTest {
  private final MasterDeviceTest h=new MasterDeviceTest();
  @Before public void prepare()throws Exception{h.prepare();}
  @After public void cleanup()throws Exception{h.cleanup();}
  private VantaJob waitRecord(String status,boolean dispatched)throws Exception {
    JobEngine e=h.engine();VantaJob job=e.store.create("fixture","QA network eligibility","",new JSONObject());
    job.json.put("next_run",System.currentTimeMillis()+600000).put("request_started",dispatched);
    job.event("network_test",status,ProgressState.stages("WRITING CODE","Completed analysis saved",1,4));e.store.save(job);return job;
  }
  @Test public void reconnectReleasesOnlyUnsubmittedNetworkWaits()throws Exception {
    JobEngine e=h.engine();
    synchronized(e){
      VantaJob eligible=waitRecord("WAITING_NETWORK",false),uncertain=waitRecord("WAITING_NETWORK",true);
      assertEquals(1,e.resumeNetworkWaits());
      assertEquals("QUEUED",e.store.get(eligible.id()).status());assertEquals(0,e.store.get(eligible.id()).json.optLong("next_run"));
      assertEquals(25,e.store.get(eligible.id()).progress().getInt("percent"));assertEquals("WAITING_NETWORK",e.store.get(uncertain.id()).status());
      assertTrue(e.store.get(uncertain.id()).json.optLong("next_run")>System.currentTimeMillis());
      e.cancel(eligible.id());e.cancel(uncertain.id());
    }
  }
  @Test public void reconnectDoesNotResetProviderDelayOrResurrectCancelledTasks()throws Exception {
    JobEngine e=h.engine();
    synchronized(e){
      VantaJob provider=waitRecord("WAITING_PROVIDER",false),cancelled=waitRecord("CANCELLED",false),blocked=waitRecord("BLOCKED",false);
      long due=e.store.get(provider.id()).json.getLong("next_run");assertEquals(0,e.resumeNetworkWaits());
      assertEquals(due,e.store.get(provider.id()).json.getLong("next_run"));assertEquals("CANCELLED",e.store.get(cancelled.id()).status());assertEquals("BLOCKED",e.store.get(blocked.id()).status());e.cancel(provider.id());
    }
  }
  @Test public void actualConnectivityCallbackResumesWithoutAnotherUserAction()throws Exception {
    JobEngine e=h.engine();ProviderConfig p=h.seed("anthropic");AtomicInteger requests=new AtomicInteger();
    try(ActivityScenario<MainActivity> a=ActivityScenario.launch(MainActivity.class)) {
      h.shell("svc wifi disable");h.shell("svc data disable");
      try {
        long deadline=SystemClock.elapsedRealtime()+10000;while(e.online()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);assertFalse("Android reports offline",e.online());
        VantaJob job=h.enqueue(a,"prompt",h.input(p).put("request","Write me an email.").put("depth","Maximum"),(provider,key,model,history,system,max,np,web,call,stream)->{requests.incrementAndGet();return "Draft a concise factual email.";});
        deadline=SystemClock.elapsedRealtime()+8000;while(!"WAITING_NETWORK".equals(e.store.get(job.id()).status())&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(50);
        assertEquals("WAITING_NETWORK",e.store.get(job.id()).status());assertEquals(0,requests.get());
        h.shell("svc wifi enable");h.shell("svc data enable");
        deadline=SystemClock.elapsedRealtime()+30000;while(!e.online()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);assertTrue("Default network restored",e.online());
        VantaJob result=h.waitDone(job.id(),15000);assertEquals(result.json.toString(),"COMPLETED",result.status());assertEquals(1,requests.get());
        assertTrue(result.json.getJSONArray("events").toString().contains("network_restored"));a.recreate();Thread.sleep(300);assertEquals(1,requests.get());
      } finally {h.shell("svc wifi enable");h.shell("svc data enable");}
    }
  }
}
''')
print('Event-driven network restoration added. QA explicitly establishes connectivity; no replay of uncertain requests or reset of provider backoff.')
