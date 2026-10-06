package com.ronin.vanta;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Actual Android persistence/controller integration. Only synthetic source and decoded fixture events. */
@RunWith(AndroidJUnit4.class)
public class Capacity177DeviceTest {
  final PipelineDeviceTest f=new PipelineDeviceTest();
  JobEngine e;ProviderConfig p;ModelInfo m;
  @Before public void prepare()throws Exception{
    f.prepare();e=f.e;p=f.h.seed("featherless");m=e.registry.find(p.id,"qa-model");assertNotNull(m);
  }
  @After public void clean()throws Exception{f.cleanup();}
  VantaJob seed()throws Exception{
    JSONObject input=f.h.input(p).put("recovery_policy",ForgeRecovery.policy(true,120,4,new JSONArray().put(p.toJson())));
    VantaJob j=f.job("build",input);
    j.json.put("forge_inference",true).put("request_started",true).put("phase","repair_files_4_plan_response");e.store.save(j);
    e.store.document(j.id(),"build",f.session(f.project(),p,4).put("phase","repair"));
    e.store.document(j.id(),"repair_files_4_file_0",new JSONObject().put("path","complete.java").put("content","completed source retained"));
    e.store.document(j.id(),"recovery",new JSONObject().put("calls",63).put("switches",4).put("actions",4)
        .put("active",JobOperations.selection(p,m)).put("visited",new JSONArray().put(p.id+"/"+m.id)).put("cooldowns",new JSONObject()));
    return j;
  }
  ProviderStreamFailure rejection(Net.Call call)throws Exception{
    try{ApiClient.readEvents(new StringReader("data: {\"error\":{\"code\":\"capacity_exhausted\",\"type\":\"server_error\",\"message\":\"Fixture model is temporarily at capacity\"}}\n\n"),call,s->fail());fail();return null;}
    catch(ProviderStreamFailure x){return x;}
  }
  @Test public void exhaustedSwitchAllowanceDoesNotBlockSavedSameModelWait()throws Exception{
    VantaJob j=seed();String input=e.store.document(j.id(),"input").toString();String build=e.store.document(j.id(),"build").toString();
    Net.Call call=new Net.Call();long now=System.currentTimeMillis();assertTrue(ForgeRecoveryController.recover(e,j,rejection(call),call));
    VantaJob waiting=e.store.get(j.id());assertEquals("WAITING_PROVIDER",waiting.status());assertTrue(waiting.json.getLong("next_run")>=now+30000);
    assertFalse(waiting.json.getBoolean("request_started"));assertEquals(input,e.store.document(j.id(),"input").toString());assertEquals(build,e.store.document(j.id(),"build").toString());
    JSONObject state=e.store.document(j.id(),"recovery");assertEquals(63,state.getInt("calls"));assertEquals(4,state.getInt("switches"));assertEquals(5,state.getInt("actions"));
    assertEquals("completed source retained",e.store.document(j.id(),"repair_files_4_file_0").getString("content"));
  }
  @Test public void earlyManualRetryCannotSkipSavedSameModelDeadline()throws Exception{
    VantaJob j=seed();Net.Call call=new Net.Call();assertTrue(ForgeRecoveryController.recover(e,j,rejection(call),call));
    try{ForgeCapacityRecovery.beforeRequest(e,e.store.get(j.id()),p,m,"repair_files_4_plan_response",new Net.Call());fail();}
    catch(JobEngine.Deferred wait){assertFalse(wait.network);assertTrue(wait.delay>20000);}
    assertEquals(63,e.store.document(j.id(),"recovery").getInt("calls"));
  }
  @Test public void repeatingRejectionsStopAfterThreeWaitsWithoutRegeneratingSource()throws Exception{
    VantaJob j=seed();String build=e.store.document(j.id(),"build").toString();
    for(int n=0;n<4;n++){
      j=e.store.get(j.id());j.json.put("forge_inference",true).put("request_started",true);e.store.save(j);
      Net.Call c=new Net.Call();assertTrue(ForgeRecoveryController.recover(e,j,rejection(c),c));
      assertEquals(n<3?"WAITING_PROVIDER":"ACTION_REQUIRED",e.store.get(j.id()).status());
    }
    assertEquals(7,e.store.document(j.id(),"recovery").getInt("actions"));assertEquals(4,e.store.document(j.id(),"recovery").getInt("switches"));
    assertEquals(63,e.store.document(j.id(),"recovery").getInt("calls"));assertEquals(build,e.store.document(j.id(),"build").toString());
    assertFalse(e.store.get(j.id()).json.has("next_run"));assertEquals("MODEL_CAPACITY",e.store.get(j.id()).json.getString("action_kind"));
  }
  @Test public void disabledApprovalAndUnapprovedEndpointNeverScheduleARequest()throws Exception{
    VantaJob j=seed();JSONObject in=e.store.document(j.id(),"input");in.getJSONObject("recovery_policy").put("enabled",false);e.store.document(j.id(),"input",in);
    Net.Call call=new Net.Call();assertFalse(ForgeRecoveryController.recover(e,j,rejection(call),call));assertNull(e.store.document(j.id(),"capacity_recovery"));
    in.getJSONObject("recovery_policy").put("enabled",true).put("approved",new JSONArray());e.store.document(j.id(),"input",in);
    call=new Net.Call();assertFalse(ForgeRecoveryController.recover(e,j,rejection(call),call));assertNull(e.store.document(j.id(),"capacity_recovery"));
  }
  @Test public void cancellationPreventsCapacityRetryMutation()throws Exception{
    VantaJob j=seed();Net.Call c=new Net.Call();ProviderStreamFailure error=rejection(c);c.cancel();
    assertFalse(ForgeRecoveryController.recover(e,j,error,c));assertNull(e.store.document(j.id(),"capacity_recovery"));assertEquals(4,e.store.document(j.id(),"recovery").getInt("actions"));
  }
  @Test public void manualModelChangeRemainsAllowedWithoutResettingAutomaticCounters()throws Exception{
    VantaJob j=seed();Net.Call c=new Net.Call();assertTrue(ForgeRecoveryController.recover(e,j,rejection(c),c));
    ModelInfo next=new ModelInfo("qa-alternative","Alternative fixture","text","");next.contextTokens=131072;next.codeCapable=true;
    JSONObject selection=JobOperations.selection(p,next),input=e.store.document(j.id(),"input");
    input.put("provider",selection.getJSONObject("provider")).put("model",selection.getJSONObject("model"));
    ForgeRecoveryController.manual(e,j,input,selection);
    assertEquals(4,e.store.document(j.id(),"recovery").getInt("switches"));assertEquals(63,e.store.document(j.id(),"recovery").getInt("calls"));
    ForgeCapacityRecovery.beforeRequest(e,j,p,next,"repair_files_4_plan_response",new Net.Call());
    assertEquals("completed source retained",e.store.document(j.id(),"repair_files_4_file_0").getString("content"));
  }
}
