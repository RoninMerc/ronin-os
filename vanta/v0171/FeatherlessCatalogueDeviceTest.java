package com.ronin.vanta;

import static org.junit.Assert.*;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class FeatherlessCatalogueDeviceTest {
  Context ctx(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
  JobEngine engine(){return JobEngine.get(ctx());}
  ProviderConfig provider(){return ModelRegistry.defaults().get(3);}
  final List<String> owned=new ArrayList<>();
  @Before public void prepare() throws Exception { engine().testTransport=null; engine().setHandlerForTests(null); }
  @After public void cleanup() throws Exception {
    for(String id:owned){VantaJob j=engine().store.get(id);if(j!=null&&j.active())engine().cancel(id);}
    long deadline=SystemClock.elapsedRealtime()+10000;
    while(engine().executing()>0&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(50);
    engine().testTransport=null;
  }
  abstract static class Transport implements Net.Transport {
    public Net.Response download(String u,Map<String,String>h,int n,Net.Call c){throw new AssertionError("Unexpected download");}
  }
  Net.Response response(String text){return new Net.Response(text.getBytes(StandardCharsets.UTF_8),"application/json");}
  VantaJob start(String scope) throws Exception {
    VantaJob j=engine().enqueue(ctx(),"catalogue","Featherless catalogue",scope,new JSONObject().put("provider",provider().toJson()));
    owned.add(j.id());engine().wake();return j;
  }
  VantaJob waitFor(String id,String status,long limit) throws Exception {
    long deadline=SystemClock.elapsedRealtime()+limit;
    VantaJob j;
    do {j=engine().store.get(id);if(j.status().equals(status))return j;
      if(j.needsAction())fail(j.json.toString());Thread.sleep(100);
    }while(SystemClock.elapsedRealtime()<deadline);
    fail("Expected "+status+"; actual "+j.json);return j;
  }
  void output(String name,JSONObject data) throws Exception {
    try(OutputStream out=new FileOutputStream(new File(ctx().getExternalFilesDir(null),name))){out.write(data.toString(2).getBytes(StandardCharsets.UTF_8));}
  }
  void shot(String name) throws Exception {
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();Thread.sleep(200);
    Bitmap b=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(b);
    try(OutputStream out=new FileOutputStream(new File(ctx().getExternalFilesDir(null),name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();
  }
  @Test public void temporaryLimitRecoversInSameTaskAndPreservesCache() throws Exception {
    ProviderConfig p=provider();ModelRegistry r=engine().registry;
    r.refresh(p,Collections.singletonList(new ModelInfo("fixture/old","Previous cache","text","")));
    AtomicInteger calls=new AtomicInteger();
    engine().testTransport=new Transport(){public Net.Response request(String m,String u,Map<String,String>h,byte[]b,String t,int max,Net.Call c)throws Exception{
      assertEquals("GET",m);assertTrue(h.isEmpty());
      if(calls.incrementAndGet()==1)throw new Net.HttpError(429,"rate limit fixture",1000);
      return response("{\"data\":[{\"id\":\"fixture/new\"}],\"total\":999}");}};
    VantaJob j=start("qa/catalogue/429");VantaJob waiting=waitFor(j.id(),"WAITING_PROVIDER",15000);
    assertNotNull(r.find(p.id,"fixture/old"));assertTrue(waiting.json.optLong("catalogue_retry_at")>System.currentTimeMillis());
    assertEquals(1,calls.get());
    for(int i=0;i<20;i++)assertEquals(j.id(),engine().enqueue(ctx(),"catalogue","Refresh","qa/catalogue/429",new JSONObject().put("provider",p.toJson())).id());
    Thread.sleep(300);assertEquals(1,calls.get());
    waitFor(j.id(),"COMPLETED",40000);assertEquals(2,calls.get());
    assertNotNull(r.find(p.id,"fixture/new"));assertEquals("removed",r.find(p.id,"fixture/old").spec.optString("registry_status"));
    assertTrue(engine().store.document(j.id(),"result").optString("catalogue_warning").contains("999"));
    output("catalogue-recovery.json",engine().store.get(j.id()).json);
  }
  @Test public void cancellingCooldownNeverSubmitsAnotherRead() throws Exception {
    AtomicInteger calls=new AtomicInteger();engine().testTransport=new Transport(){public Net.Response request(String m,String u,Map<String,String>h,byte[]b,String t,int max,Net.Call c)throws Exception{calls.incrementAndGet();throw new Net.HttpError(429,"fixture",3600000);}};
    VantaJob j=start("qa/catalogue/cancel");waitFor(j.id(),"WAITING_PROVIDER",15000);engine().cancel(j.id());
    engine().wake();Thread.sleep(350);assertEquals("CANCELLED",engine().store.get(j.id()).status());assertEquals(1,calls.get());
  }
  @Test public void malformedReadCannotReplacePreviousCache() throws Exception {
    ProviderConfig p=provider();ModelRegistry r=engine().registry;
    r.refresh(p,Collections.singletonList(new ModelInfo("fixture/retained","Retained","text","")));
    engine().testTransport=new Transport(){public Net.Response request(String m,String u,Map<String,String>h,byte[]b,String t,int max,Net.Call c){return response("{\"data\":[{\"id\":\"should/not/save\"},null]}");}};
    VantaJob j=start("qa/catalogue/malformed");long deadline=SystemClock.elapsedRealtime()+15000;
    while(engine().store.get(j.id()).active()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);
    assertTrue(engine().store.get(j.id()).needsAction());assertNotNull(r.find(p.id,"fixture/retained"));assertNull(r.find(p.id,"should/not/save"));
  }
  @Test public void generationRequestCannotEnterCatalogueRecovery() throws Exception {
    VantaJob j=engine().store.create("chat","Paid fixture","qa/non-catalogue",new JSONObject());owned.add(j.id());
    assertFalse(FeatherlessCatalogue.recover(engine(),j,new Net.HttpError(429,"busy"),new Net.Call()));engine().cancel(j.id());
  }
  @Test public void catalogueRefreshPreservesSavedModelPreferences() throws Exception {
    ProviderConfig p=provider();ModelRegistry r=engine().registry;
    ModelInfo original=new ModelInfo("fixture/preferences","Preferences","text","");original.enabled=false;original.manuallyUncensored=true;
    r.refresh(p,Collections.singletonList(original));
    r.refresh(p,Collections.singletonList(new ModelInfo(original.id,"Updated","text","")));
    ModelInfo saved=r.find(p.id,original.id);assertFalse(saved.enabled);assertTrue(saved.manuallyUncensored);
  }
  @Test public void livePublicCatalogueCompletesAndFullNativeBrowserSearchesIt() throws Exception {
    engine().testTransport=null;ProviderConfig p=provider();
    long start=SystemClock.elapsedRealtime();
    VantaJob job=start("qa/catalogue/live");waitFor(job.id(),"COMPLETED",240000);
    long duration=SystemClock.elapsedRealtime()-start;
    JSONObject result=engine().store.document(job.id(),"result");int returned=result.getInt("model_count");assertTrue("Actual live provider catalogue expected",returned>10000);
    List<ModelInfo> models=engine().registry.models(p.id);
    long listed=models.stream().filter(m->!"removed".equals(m.spec.optString("registry_status"))).count();assertEquals(returned,listed);
    String expected="huihui-ai/Huihui-Qwen3.5-27B-abliterated";
    assertNotNull("Exact requested model in real catalogue",engine().registry.find(p.id,expected));
    output("live-catalogue.json",new JSONObject().put("source","https://api.featherless.ai/v1/models?status=active,pending_deploy,not_deployed").put("count",returned).put("elapsed_ms",duration).put("authenticated",false).put("job",engine().store.get(job.id()).json).put("result",result).put("max_heap",Runtime.getRuntime().maxMemory()));
    try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
      AtomicReference<Dialog> dialog=new AtomicReference<>();
      scenario.onActivity(a->{Map<String,List<ModelInfo>> catalogue=new HashMap<>();catalogue.put(p.id,models);
        dialog.set(new ModelPicker(a,ctx().getSharedPreferences("vanta_state",0),Collections.singletonList(p),catalogue,Collections.emptySet(),"all","catalogue-test",p,false,new ModelPicker.Listener(){public void select(ProviderConfig pp,ModelInfo mm){}public void auto(){}public void connect(ProviderConfig pp){}public void changed(ProviderConfig pp){}}).show());});
      AtomicInteger count=new AtomicInteger();long deadline=SystemClock.elapsedRealtime()+45000;
      do{Thread.sleep(150);scenario.onActivity(a->{ListView list=dialog.get().findViewById(android.R.id.content).findViewWithTag("model-list");if(list!=null)count.set(list.getCount()-list.getHeaderViewsCount()-list.getFooterViewsCount());});}while(count.get()!=models.size()&&SystemClock.elapsedRealtime()<deadline);
      assertEquals(models.size(),count.get());shot("live-full-catalogue");
      scenario.onActivity(a->{EditText search=dialog.get().findViewById(android.R.id.content).findViewWithTag("model-search");search.setText(expected);});
      deadline=SystemClock.elapsedRealtime()+30000;
      do{Thread.sleep(150);scenario.onActivity(a->{ListView list=dialog.get().findViewById(android.R.id.content).findViewWithTag("model-list");count.set(list.getCount()-list.getHeaderViewsCount()-list.getFooterViewsCount());});}while(count.get()!=1&&SystemClock.elapsedRealtime()<deadline);
      assertEquals(1,count.get());shot("live-exact-model");scenario.onActivity(a->dialog.get().dismiss());
    }
  }
}
