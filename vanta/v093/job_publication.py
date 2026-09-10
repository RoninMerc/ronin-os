from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'));j=root/'app/src/main/java/com/ronin/vanta'
p=j/'JobEngine.java';s=p.read_text();old='''  public VantaJob enqueue(
      Context userContext,
      String type,
      String title,
      String scope,
      JSONObject input,
      MainActivity.ChatGateway gateway)''';assert s.count(old)==1
s=s.replace(old,'''  // wake() shares this monitor: a QUEUED record must not become executable before
  // its selected in-process adapter and initial scheduling state have been registered.
  public synchronized VantaJob enqueue(
      Context userContext,
      String type,
      String title,
      String scope,
      JSONObject input,
      MainActivity.ChatGateway gateway)''');p.write_text(s)
p=root/'app/src/androidTest/java/com/ronin/vanta/MasterDeviceTest.java';s=p.read_text();old='''      assertEquals("COMPLETED", waitDone(j.id(), 12000).status());
      assertEquals(1, count.get());''';assert s.count(old)==1
s=s.replace(old,'''      VantaJob observed=waitDone(j.id(),12000);
      assertEquals("Neutral prompt task: "+observed.json+"; diagnostic: "+engine().store.document(j.id(),"diagnostics"),"COMPLETED",observed.status());
      assertEquals(1, count.get());''');p.write_text(s)
p=root/'app/src/androidTest/java/com/ronin/vanta/JobPublicationDeviceTest.java'
p.write_text('''package com.ronin.vanta;
import static org.junit.Assert.*;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Real scheduler coordination with neutral gateways; no paid API requests. */
@RunWith(AndroidJUnit4.class)
public final class JobPublicationDeviceTest {
  private final MasterDeviceTest h=new MasterDeviceTest();
  @Before public void prepare()throws Exception {h.prepare();}
  @After public void cleanup()throws Exception {h.cleanup();}
  private void completed(VantaJob job)throws Exception {
    VantaJob state=h.waitDone(job.id(),15000);
    assertEquals("Task: "+state.json+"; diagnostic: "+h.engine().store.document(job.id(),"diagnostics"),"COMPLETED",state.status());
  }
  @Test public void queuedRecordAndGatewayPublishUnderTheSchedulingMonitor()throws Exception {
    JobEngine e=h.engine();ProviderConfig p=h.seed("anthropic");
    JSONObject in=h.input(p).put("request","Write me an email.").put("depth","Maximum");
    String scope="qa-publication-"+UUID.randomUUID();
    AtomicReference<VantaJob> created=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
    AtomicInteger calls=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1);
    try(ActivityScenario<MainActivity> activity=ActivityScenario.launch(MainActivity.class)) {
      Thread producer=new Thread(()->{entered.countDown();try {
        created.set(e.enqueue(h.ctx(),"prompt","QA atomic publication",scope,in,(provider,key,model,history,system,max,np,web,call,stream)->{calls.incrementAndGet();return "Draft a concise email preserving supplied facts.";}));
      }catch(Throwable failure){error.set(failure);}},"qa-job-producer");
      try {
        synchronized(e) {
          producer.start();assertTrue(entered.await(2,TimeUnit.SECONDS));
          long until=SystemClock.elapsedRealtime()+2000;
          while(producer.isAlive()&&producer.getState()!=Thread.State.BLOCKED&&SystemClock.elapsedRealtime()<until)Thread.sleep(10);
          assertEquals("Enqueue must coordinate with the same monitor as wake",Thread.State.BLOCKED,producer.getState());
          assertNull("No executable record may be published before its gateway",e.store.latest(scope));
        }
      }finally {producer.join(5000);}
      assertFalse("Producer completed after the scheduling lock was released",producer.isAlive());
      if(error.get()!=null)throw new AssertionError(error.get());
      assertNotNull(created.get());e.wake();completed(created.get());assertEquals(1,calls.get());
    }
  }
  @Test public void twentyRapidPromptsKeepTheirSelectedGateways()throws Exception {
    JobEngine e=h.engine();ProviderConfig p=h.seed("anthropic");AtomicInteger calls=new AtomicInteger();
    try(ActivityScenario<MainActivity> activity=ActivityScenario.launch(MainActivity.class)) {
      for(int round=0;round<10;round++) {
        List<VantaJob> jobs=new ArrayList<>();
        for(int n=0;n<2;n++) {
          final String marker="Email prompt "+round+"/"+n;
          jobs.add(h.enqueue(activity,"prompt",h.input(p).put("request","Write me an email.").put("depth","Maximum"),(provider,key,model,history,system,max,np,web,call,stream)->{
            calls.incrementAndGet();assertEquals(p.id,provider.id);assertTrue(max<=1500);return marker;
          }));
        }
        for(VantaJob job:jobs)completed(job);
        for(int n=0;n<jobs.size();n++)assertEquals("Email prompt "+round+"/"+n,e.store.document(jobs.get(n).id(),"result").getString("prompt"));
      }
      assertEquals(20,calls.get());activity.recreate();
    }
  }
}
''')
print('Queued job publication is coordinated with scheduling; deterministic lock and twenty-prompt gateway regressions added.')
