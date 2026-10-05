package com.ronin.vanta;

import static org.junit.Assert.*;
import java.io.*;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.*;
import org.junit.Test;

public class Generation176RegressionTest {
  private static String frame(JSONObject delta) throws Exception {
    return "data: " + new JSONObject().put("choices", new JSONArray().put(new JSONObject()
        .put("index",0).put("delta",delta).put("finish_reason",JSONObject.NULL))) + "\n\n";
  }
  private static String reason(String key,String text)throws Exception { return frame(new JSONObject().put(key,text)); }
  private static String answer(String text)throws Exception { return frame(new JSONObject().put("content",text)); }
  private static String stop() { return "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"; }
  private static JSONObject stats(Net.Call c)throws Exception { return c.diagnostics().getJSONObject("generation"); }

  private static final class TimedReader extends Reader {
    final List<String> frames;final AtomicLong clock;final GenerationWatchdog watchdog;final long step;
    int index,position;
    TimedReader(List<String> frames,AtomicLong clock,GenerationWatchdog watchdog,long step){this.frames=frames;this.clock=clock;this.watchdog=watchdog;this.step=step;}
    @Override public int read(char[] b,int off,int len) {
      if(index>=frames.size())return -1;
      if(position==0){clock.addAndGet(step);watchdog.checkNow();}
      String value=frames.get(index);int n=Math.min(len,value.length()-position);
      value.getChars(position,position+n,b,off);position+=n;
      if(position==value.length()){index++;position=0;}return n;
    }
    @Override public void close(){}
  }
  private void prolongedReasoning(String key)throws Exception{
    AtomicLong now=new AtomicLong();Net.Call c=new Net.Call();List<String> callbacks=new ArrayList<>();
    try(GenerationWatchdog w=new GenerationWatchdog(c,now::get,100,2000)){
      List<String> parts=new ArrayList<>();for(int i=0;i<9;i++)parts.add(reason(key,"private model reasoning fixture "));
      parts.add(answer("{\"files\":[]}"));parts.add(stop());
      String result=ApiClient.readEvents(new TimedReader(parts,now,w,45_000_000),c,callbacks::add);
      assertEquals("{\"files\":[]}",result);assertFalse(w.expired());assertTrue(now.get()>100_000_000);
      assertEquals(9L*"private model reasoning fixture ".length(),stats(c).getLong("reasoning_characters"));
      assertTrue(callbacks.stream().noneMatch(x->x.contains("private model reasoning")));
      assertFalse(stats(c).toString().contains("private model reasoning"));
    }
  }
  @Test public void reasoningContentSurvivesOriginalIdleBoundary()throws Exception{prolongedReasoning("reasoning_content");}
  @Test public void reasoningAliasSurvivesOriginalIdleBoundary()throws Exception{prolongedReasoning("reasoning");}
  @Test public void aliasesDoNotDoubleCountSameEvent()throws Exception{
    Net.Call c=new Net.Call();ApiClient.readEvents(new StringReader(frame(new JSONObject().put("reasoning","abc").put("reasoning_content","abc"))+answer("OK")+stop()),c,t->{});
    assertEquals(3,stats(c).getLong("reasoning_characters"));
  }
  @Test public void reasoningOnlyIsNeverCompletedSource()throws Exception{
    Net.Call c=new Net.Call();try{ApiClient.readEvents(new StringReader(reason("reasoning_content","fixture")+stop()),c,t->fail());fail();}
    catch(IOException e){assertTrue(e.getMessage().contains("no answer"));}
  }
  @Test public void reasoningWithoutTerminalEventIsIncomplete()throws Exception{
    Net.Call c=new Net.Call();try{ApiClient.readEvents(new StringReader(reason("reasoning","fixture")),c,t->fail());fail();}
    catch(EOFException expected){}assertEquals(0,stats(c).getLong("answer_characters"));
  }
  @Test public void placeholdersCannotHideRawAnswerProgress()throws Exception{
    Net.Call c=new Net.Call();AtomicLong now=new AtomicLong();List<String> values=new ArrayList<>();
    try(GenerationWatchdog w=new GenerationWatchdog(c,now::get,100,2000)){
      List<String> f=new ArrayList<>();f.add(answer("{\"description\":\"build_apk "));
      for(int i=0;i<9;i++)f.add(answer("another valid description fragment "));
      f.add(answer("\"}"));f.add(stop());
      String result=ApiClient.readEvents(new TimedReader(f,now,w,45_000_000),c,values::add);
      assertTrue(result.contains("description"));assertFalse(w.expired());
      assertEquals(result.length(),stats(c).getLong("answer_characters"));
      assertTrue(values.stream().allMatch(x->x.equals("Routing this request through Vanta tools…")));
    }
  }
  @Test public void transportBytesAloneDoNotResetSemanticIdle()throws Exception{
    Net.Call c=new Net.Call();AtomicLong now=new AtomicLong();
    try(GenerationWatchdog w=new GenerationWatchdog(c,now::get,100,2000)){
      c.observe(new ByteArrayInputStream(new byte[1000])).read(new byte[1000]);
      now.set(101_000_000);w.checkNow();assertTrue(w.expired());assertFalse(c.isCancelled());
    }
  }
  @Test public void commentPingsDoNotResetSemanticIdle()throws Exception{
    Net.Call c=new Net.Call();AtomicLong now=new AtomicLong();
    try(GenerationWatchdog w=new GenerationWatchdog(c,now::get,100,2000)){
      try{ApiClient.readEvents(new TimedReader(Arrays.asList(": ping\n\n",": ping\n\n",": ping\n\n"),now,w,45_000_000),c,t->fail());fail();}
      catch(SocketTimeoutException expected){assertTrue(w.expired());}assertEquals(0,stats(c).getLong("answer_characters"));
    }
  }
  @Test public void roleUsageAndUnknownFramesDoNotResetIdle()throws Exception{
    Net.Call c=new Net.Call();AtomicLong now=new AtomicLong();
    try(GenerationWatchdog w=new GenerationWatchdog(c,now::get,100,2000)){
      List<String> f=Arrays.asList(frame(new JSONObject().put("role","assistant")),"data: {\"usage\":{\"total_tokens\":300},\"choices\":[]}\n\n","data: {\"type\":\"custom.ping\",\"text\":\"ignore me\"}\n\n");
      try{ApiClient.readEvents(new TimedReader(f,now,w,45_000_000),c,t->fail());fail();}catch(SocketTimeoutException e){assertTrue(w.expired());}
    }
  }
  @Test public void whitespaceIsNotMeaningfulReasoning()throws Exception{
    Net.Call c=new Net.Call();AtomicLong now=new AtomicLong();
    try(GenerationWatchdog w=new GenerationWatchdog(c,now::get,100,2000)){
      now.set(95_000_000);c.generation.text("reasoning"," \r\n\t");now.set(101_000_000);w.checkNow();assertTrue(w.expired());
    }
  }
  @Test public void whitespaceIsNotMeaningfulAnswer()throws Exception{
    Net.Call c=new Net.Call();AtomicLong now=new AtomicLong();
    try(GenerationWatchdog w=new GenerationWatchdog(c,now::get,100,2000)){
      now.set(95_000_000);c.generation.text("answer"," \r\n\t");now.set(101_000_000);w.checkNow();assertTrue(w.expired());
    }
  }
  @Test public void repeatedTransportMetadataIsNotReasoningText()throws Exception{
    Net.Call c=new Net.Call();String stream=frame(new JSONObject().put("reasoning",new JSONObject().put("status","working")))+answer("OK")+stop();
    assertEquals("OK",ApiClient.readEvents(new StringReader(stream),c,t->{}));assertEquals(0,stats(c).getLong("reasoning_characters"));
  }
  @Test public void nullReasoningDoesNotBecomeLiteralNullProgress()throws Exception{
    Net.Call c=new Net.Call();ApiClient.readEvents(new StringReader(frame(new JSONObject().put("reasoning_content",JSONObject.NULL))+answer("OK")+stop()),c,t->{});
    assertEquals(0,stats(c).getLong("reasoning_characters"));
  }
  @Test public void totalDeadlineStillStopsContinuousReasoning()throws Exception{
    Net.Call c=new Net.Call();AtomicLong now=new AtomicLong();
    try(GenerationWatchdog w=new GenerationWatchdog(c,now::get,100,250)){
      now.set(90_000_000);c.generation.text("reasoning","first");now.set(180_000_000);c.generation.text("reasoning","second");
      now.set(251_000_000);c.generation.text("reasoning","third");w.checkNow();assertTrue(w.expired());assertTrue(w.failure(new IOException()).getMessage().contains("total"));
    }
  }
  @Test public void explicitCancelRemainsUserCancellation()throws Exception{
    Net.Call c=new Net.Call();AtomicLong now=new AtomicLong();
    try(GenerationWatchdog w=new GenerationWatchdog(c,now::get,100,250)){c.cancel();now.set(500_000_000);w.checkNow();assertFalse(w.expired());assertTrue(c.isCancelled());}
  }
  @Test public void closedWatchdogCannotAbortLaterWork()throws Exception{
    Net.Call c=new Net.Call();AtomicLong now=new AtomicLong();GenerationWatchdog w=new GenerationWatchdog(c,now::get,100,250);
    w.close();now.set(500_000_000);w.checkNow();assertFalse(w.expired());c.check();
  }
  @Test public void oldWatchdogDetachDoesNotDetachNewObserver()throws Exception{
    Net.Call c=new Net.Call();AtomicLong now=new AtomicLong();GenerationWatchdog first=new GenerationWatchdog(c,now::get,100,1000);first.close();
    try(GenerationWatchdog second=new GenerationWatchdog(c,now::get,100,1000)){
      first.close();now.set(90_000_000);c.generation.text("reasoning","more");now.set(150_000_000);second.checkNow();assertFalse(second.expired());
    }
  }
  @Test public void anthropicThinkingCountsButNeverBecomesAnswer()throws Exception{
    Net.Call c=new Net.Call();String s="data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hidden fixture\"}}\n\n";
    s+="data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"OK\"}}\n\ndata: {\"type\":\"message_stop\"}\n\n";
    assertEquals("OK",ApiClient.readEvents(new StringReader(s),c,t->assertEquals("OK",t)));assertEquals(14,stats(c).getLong("reasoning_characters"));
  }
  @Test public void responsesReasoningCountsWithoutLeaking()throws Exception{
    Net.Call c=new Net.Call();String s="data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"hidden fixture\"}\n\n";
    s+="data: {\"type\":\"response.output_text.delta\",\"delta\":\"OK\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n";
    assertEquals("OK",ApiClient.readEvents(new StringReader(s),c,t->assertEquals("OK",t)));assertEquals(14,stats(c).getLong("reasoning_characters"));
  }
  @Test public void anotherChoiceCannotExtendTheSelectedAnswerDeadline()throws Exception{
    Net.Call c=new Net.Call();String s="data: {\"choices\":[{\"index\":1,\"delta\":{\"reasoning_content\":\"not selected\"},\"finish_reason\":null}]}\n\n";
    assertEquals("OK",ApiClient.readEvents(new StringReader(s+answer("OK")+stop()),c,t->{}));assertEquals(0,stats(c).getLong("reasoning_characters"));
  }
  @Test public void partialSourceSurvivesStreamErrorWithoutReasoning()throws Exception{
    Net.Call c=new Net.Call();String s=reason("reasoning_content","hidden fixture")+answer("{\"files\":[");
    try{ApiClient.readEvents(new StringReader(s),c,t->{});fail();}catch(ChatProtocol.Incomplete expected){assertEquals("{\"files\":[",expected.partial);assertFalse(expected.confirmedOutputLimit);}
  }
  @Test public void confirmedOutputLimitRemainsIncompleteNotSuccess()throws Exception{
    Net.Call c=new Net.Call();String s=reason("reasoning_content","hidden fixture")+answer("partial")+"data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"length\"}]}\n\n";
    try{ApiClient.readEvents(new StringReader(s),c,t->{});fail();}catch(ChatProtocol.Incomplete expected){assertTrue(expected.confirmedOutputLimit);assertEquals("partial",expected.partial);}
  }
  @Test public void semanticDiagnosticRecordsChannelsWithoutRawContent()throws Exception{
    AtomicLong clock=new AtomicLong();GenerationProgress g=new GenerationProgress(clock::get);g.start((k,n)->{});
    g.event();g.text("reasoning","hidden fixture");clock.set(5_000_000);g.event();g.text("answer","OK");clock.set(8_000_000);
    JSONObject d=g.diagnostics();assertEquals(14,d.getLong("reasoning_characters"));assertEquals(2,d.getLong("answer_characters"));assertEquals(2,d.getLong("stream_events"));assertEquals(3,d.getLong("idle_since_meaningful_output_ms"));assertEquals("RECEIVING_ANSWER",d.getString("output_stage"));assertFalse(d.toString().contains("hidden fixture"));
  }
  @Test public void watchdogFailureStillCannotAutomaticallyPurchaseAnotherRequest()throws Exception{
    AtomicLong now=new AtomicLong();Net.Call c=new Net.Call();
    try(GenerationWatchdog w=new GenerationWatchdog(c,now::get,100,1000)){
      now.set(101_000_000);w.checkNow();Exception e=w.failure(new IOException("socket closed"));
      assertFalse(ForgeTransportRecovery.networkFailure(e));assertEquals(ForgeRecovery.Kind.STOP,ForgeRecovery.classify(e,true,false));
    }
  }
}
