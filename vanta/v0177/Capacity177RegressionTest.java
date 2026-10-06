package com.ronin.vanta;

import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import org.json.*;
import org.junit.Test;

/** Production decoder and pure recovery policy. No external API call or provider credential. */
public class Capacity177RegressionTest {
  String frame(String value){return "data: "+value+"\n\n";}
  String rejected(){return frame("{\"error\":{\"code\":\"capacity_exhausted\",\"message\":\"MiniMaxAI/MiniMax-M2.1 is temporarily at capacity. Please try again shortly.\",\"type\":\"server_error\",\"param\":null}}");}
  String delta(String json){return frame("{\"choices\":[{\"index\":0,\"delta\":"+json+",\"finish_reason\":null}]}");}
  String finished(){return frame("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}");}
  Exception failure(String s,Net.Call c)throws Exception{
    try{ApiClient.readEvents(new StringReader(s),c,x->{});fail("Expected error, never complete source");return null;}catch(IOException e){return e;}
  }
  ProviderStreamFailure typed(String s)throws Exception{
    ProviderStreamFailure result=ProviderStreamFailure.find(failure(s,new Net.Call()));assertNotNull(result);return result;
  }
  JSONObject policy()throws Exception{return ForgeRecovery.policy(true,120,4,new JSONArray());}
  JSONObject state()throws Exception{return new JSONObject().put("calls",63).put("actions",4).put("switches",4);}
  CapacityBackoff.Decision plan(JSONObject p,JSONObject s,JSONObject ledger,String scope,long when,long advertised)throws Exception{
    return CapacityBackoff.plan(p,s,ledger,scope,when,advertised,0);
  }
  @Test public void exactReportedEnvelopeIsTypedCapacityBeforeOutput()throws Exception{
    Net.Call c=new Net.Call();ProviderStreamFailure e=(ProviderStreamFailure)failure(rejected(),c);
    assertEquals("capacity_exhausted",e.code);assertTrue(e.capacityBeforeOutput());
    assertEquals(ForgeRecovery.Kind.CAPACITY,ForgeRecovery.classify(e,true,false));
    assertEquals(1,c.diagnostics().getJSONObject("generation").getLong("stream_events"));
    assertEquals(0,c.diagnostics().getJSONObject("generation").getLong("answer_characters"));
    assertEquals(0,c.diagnostics().getJSONObject("generation").getLong("reasoning_characters"));
  }
  @Test public void answerThenCapacityIsNotAutomaticallyReplayable()throws Exception{
    Exception e=failure(delta("{\"content\":\"partial source\"}")+rejected(),new Net.Call());
    assertTrue(e instanceof ChatProtocol.Incomplete);assertEquals("partial source",((ChatProtocol.Incomplete)e).partial);
    assertTrue(ProviderStreamFailure.find(e).outputObserved);assertEquals(ForgeRecovery.Kind.STOP,ForgeRecovery.classify(e,true,false));
  }
  @Test public void reasoningThenCapacityCannotReplay()throws Exception{
    ProviderStreamFailure e=typed(delta("{\"reasoning_content\":\"private fixture\"}")+rejected());assertTrue(e.outputObserved);assertFalse(e.capacityBeforeOutput());
  }
  @Test public void reasoningAliasThenCapacityCannotReplay()throws Exception{
    assertFalse(typed(delta("{\"reasoning\":\"private fixture\"}")+rejected()).capacityBeforeOutput());
  }
  @Test public void whitespaceReasoningDoesNotImplyNoOutput()throws Exception{
    assertTrue(typed(delta("{\"reasoning_content\":\"  \\n \\".replace(" \\"," \") +"\"}")+rejected()).outputObserved);
  }
  @Test public void whitespaceAnswerCannotReplay()throws Exception{
    assertTrue(typed(delta(new JSONObject().put("content","  \n ").toString())+rejected()).outputObserved);
  }
  @Test public void roleOnlyStillAllowsCapacityRejection()throws Exception{
    assertTrue(typed(delta("{\"role\":\"assistant\"}")+rejected()).capacityBeforeOutput());
  }
  @Test public void nullOutputIsNotGeneratedText()throws Exception{
    assertTrue(typed(delta("{\"content\":null,\"reasoning_content\":null}")+rejected()).capacityBeforeOutput());
  }
  @Test public void heartbeatDoesNotCountAsOutput()throws Exception{
    assertTrue(typed(": keep-alive\n\n"+rejected()).capacityBeforeOutput());
  }
  @Test public void toolOutputPreventsReplay()throws Exception{
    assertTrue(typed(delta("{\"tool_calls\":[{\"index\":0,\"id\":\"call1\",\"function\":{\"name\":\"build\",\"arguments\":\"{\"}}]}")+rejected()).outputObserved);
  }
  @Test public void anotherChoiceOutputPreventsReplay()throws Exception{
    assertTrue(typed(frame("{\"choices\":[{\"index\":1,\"delta\":{\"content\":\"another answer\"}}]}")+rejected()).outputObserved);
  }
  @Test public void sameErrorFrameWithOutputCannotReplay()throws Exception{
    JSONObject event=new JSONObject().put("error",new JSONObject().put("code","capacity_exhausted").put("type","server_error")).put("choices",new JSONArray().put(new JSONObject().put("delta",new JSONObject().put("content","partial"))));
    assertTrue(typed(frame(event.toString())).outputObserved);
  }
  @Test public void usageWithCompletionTokensPreventsReplay()throws Exception{
    assertTrue(typed(frame("{\"usage\":{\"completion_tokens\":1},\"choices\":[]}")+rejected()).outputObserved);
  }
  @Test public void promptUsageDoesNotInventGeneratedOutput()throws Exception{
    assertFalse(typed(frame("{\"usage\":{\"prompt_tokens\":2000,\"completion_tokens\":0},\"choices\":[]}")+rejected()).outputObserved);
  }
  @Test public void reasoningTokenUsagePreventsReplay()throws Exception{
    assertTrue(typed(frame("{\"usage\":{\"completion_tokens_details\":{\"reasoning_tokens\":2}},\"choices\":[]}")+rejected()).outputObserved);
  }
  @Test public void anthropicThinkingIsObservedButNotExposed()throws Exception{
    ProviderStreamFailure e=typed(frame("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"private fixture\"}}")+rejected());
    assertTrue(e.outputObserved);assertFalse(e.diagnostic().toString().contains("private fixture"));
  }
  @Test public void outputItemsAreNotIgnored()throws Exception{
    assertTrue(typed(frame("{\"type\":\"response.failed\",\"response\":{\"output\":[{\"type\":\"function_call\"}],\"error\":{\"code\":\"capacity_exhausted\"}}}")).outputObserved);
  }
  @Test public void unknownErrorCodeIsNotCapacity()throws Exception{
    assertEquals(ForgeRecovery.Kind.STOP,ForgeRecovery.classify(typed(frame("{\"error\":{\"code\":\"server_error\",\"message\":\"at capacity\"}}")),true,false));
  }
  @Test public void billingTypeNeverBecomesCapacityRetry()throws Exception{
    assertFalse(typed(frame("{\"error\":{\"code\":\"capacity_exhausted\",\"type\":\"insufficient_quota\"}}")).capacityBeforeOutput());
  }
  @Test public void refusalMessageCannotTriggerCapacityRetry()throws Exception{
    assertEquals(ForgeRecovery.Kind.STOP,ForgeRecovery.classify(typed(frame("{\"error\":{\"code\":\"capacity_exhausted\",\"type\":\"server_error\",\"message\":\"content_policy rejection\"}}")),true,false));
  }
  @Test public void plainIOExceptionWithCapacityTextStillStops()throws Exception{
    assertEquals(ForgeRecovery.Kind.STOP,ForgeRecovery.classify(new IOException("capacity_exhausted"),true,false));
  }
  @Test public void modelAnswerQuotingCapacityIsNotAnError()throws Exception{
    assertEquals("capacity_exhausted",ApiClient.readEvents(new StringReader(delta("{\"content\":\"capacity_exhausted\"}")+finished()),new Net.Call(),x->{}));
  }
  @Test public void missingTerminalStillCannotReplay()throws Exception{
    assertTrue(failure(delta("{\"role\":\"assistant\"}"),new Net.Call()) instanceof EOFException);
  }
  @Test public void callerCancellationWinsOverCapacity()throws Exception{
    assertEquals(ForgeRecovery.Kind.STOP,ForgeRecovery.classify(typed(rejected()),true,true));
  }
  @Test public void workerOperationCannotUseInferenceCapacityRecovery()throws Exception{
    assertEquals(ForgeRecovery.Kind.STOP,ForgeRecovery.classify(typed(rejected()),false,false));
  }
  @Test public void transportRecoveryDoesNotReclassifyCapacity()throws Exception{
    assertFalse(ForgeTransportRecovery.networkFailure(typed(rejected())));
  }
  @Test public void responseRetryAfterHeaderIsRespected()throws Exception{
    Net.Call c=new Net.Call();c.responseRetryAfterMs=90000;
    assertEquals(90000,((ProviderStreamFailure)failure(rejected(),c)).retryAfterMs);
  }
  @Test public void explicitStreamRetryAfterCannotShortenHeader()throws Exception{
    Net.Call c=new Net.Call();c.responseRetryAfterMs=90000;
    assertEquals(90000,((ProviderStreamFailure)failure(frame("{\"error\":{\"code\":\"capacity_exhausted\",\"retry_after\":20}}"),c)).retryAfterMs);
  }
  @Test public void allAutomaticSwitchSlotsUsedStillAllowsSameModelWait()throws Exception{
    JSONObject s=state();CapacityBackoff.Decision d=plan(policy(),s,null,"scope",1000,-1);
    assertTrue(d.retry);assertEquals(31000,d.due);assertEquals(4,s.getInt("switches"));assertEquals(63,s.getInt("calls"));
  }
  @Test public void threeRetriesBackOffAndFourthStops()throws Exception{
    JSONObject ledger=null,s=state();long now=1000;
    for(int i=0;i<3;i++){
      CapacityBackoff.Decision d=plan(policy(),s,ledger,"scope",now,-1);assertTrue(d.retry);assertEquals(30000L*(1<<i),d.delay);
      s.put("actions",s.getInt("actions")+1);ledger=d.ledger;now=d.due;
    }
    assertFalse(plan(policy(),s,ledger,"scope",now,-1).retry);assertEquals(7,s.getInt("actions"));
  }
  @Test public void ledgerRoundTripDoesNotResetRetryAllowance()throws Exception{
    CapacityBackoff.Decision a=plan(policy(),state(),null,"scope",1000,-1);
    CapacityBackoff.Decision b=plan(policy(),state(),new JSONObject(a.ledger.toString()),"scope",a.due,-1);
    assertEquals(2,b.count);assertEquals(60000,b.delay);
  }
  @Test public void anotherModelHasItsOwnCooldownScope()throws Exception{
    CapacityBackoff.Decision a=plan(policy(),state(),null,"model-a/phase",1000,-1);
    CapacityBackoff.Decision b=plan(policy(),state(),a.ledger,"model-b/phase",1000,-1);assertEquals(1,b.count);
  }
  @Test public void actionLimitIsNotIncreased()throws Exception{
    JSONObject s=state().put("actions",8);assertFalse(plan(policy(),s,null,"scope",1000,-1).retry);assertEquals(8,s.getInt("actions"));
  }
  @Test public void callLimitIsNotReset()throws Exception{
    JSONObject s=state().put("calls",120);assertFalse(plan(policy(),s,null,"scope",1000,-1).retry);assertEquals(120,s.getInt("calls"));
  }
  @Test public void disabledRecoveryDoesNotScheduleAnything()throws Exception{
    assertFalse(plan(policy().put("enabled",false),state(),null,"scope",1000,-1).retry);
  }
  @Test public void excessiveRetryAfterStopsRatherThanShorteningIt()throws Exception{
    assertFalse(plan(policy(),state(),null,"scope",1000,700000).retry);
  }
  @Test public void serverDelayOverridesShorterBackoff()throws Exception{
    assertEquals(90000,plan(policy(),state(),null,"scope",1000,90000).delay);
  }
  @Test public void priorLedgerIsNotMutatedBeforeCommit()throws Exception{
    JSONObject ledger=new JSONObject();plan(policy(),state(),ledger,"scope",1000,-1);assertEquals(0,ledger.length());
  }
  @Test public void generationResetSeparatesIndependentRequests()throws Exception{
    Net.Call c=new Net.Call();c.generation.text("reasoning","first request");assertTrue(c.generation.observedOutput());
    c.generation.start((channel,n)->{});assertFalse(c.generation.observedOutput());
  }
}
