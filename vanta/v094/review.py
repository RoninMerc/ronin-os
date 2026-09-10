from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal')); j=root/'app/src/main/java/com/ronin/vanta'
def edit(name,old,new):
 p=j/name;s=p.read_text();assert s.count(old)==1,(name,s.count(old));p.write_text(s.replace(old,new,1))
edit('VantaHub.java','Vanta will resume the unfinished step and may submit up to 2 additional code requests after connection failures. A lost request may also be charged. Existing provider approvals, source files and request/build limits remain unchanged. No images or videos are retried.', 'Vanta will resume the unfinished step. Connection recovery allows at most 2 automatic retries across this entire task; already-used retries do not reset. A lost request may also be charged. Existing provider approvals, source files and request/build limits remain unchanged. No images or videos are retried.')
# A provider-specific unknown finish reason is not sufficient evidence of completion.
edit('ChatProtocol.java','    if (finish.equals("length"))','    if(!finish.isEmpty() && !finish.equals("null") && !java.util.Arrays.asList("stop","length","content_filter","tool_calls","function_call").contains(finish))\n      throw new IOException("Provider returned an unrecognised completion reason. Partial output is preserved, not marked complete.");\n    if (finish.equals("length"))')
p=root/'app/src/test/java/com/ronin/vanta/TransportRecoveryTest.java';s=p.read_text().rstrip();assert s.endswith('}')
s=s[:-1]+'''
 @Test public void unrecognisedFinishReasonCannotMarkSourceComplete()throws Exception{
  String event="data: {\\"choices\\":[{\\"delta\\":{\\"content\\":\\"partial source\\"},\\"finish_reason\\":\\"unknown_status\\"}]}\\n\\n";
  try{ChatProtocol.events(new StringReader(event),new Net.Call(),t->{});fail();}
  catch(ChatProtocol.Incomplete error){assertEquals("partial source",error.partial);assertFalse(ForgeTransportRecovery.networkFailure(error));}
 }
}
''';p.write_text(s)
p=root/'app/src/androidTest/java/com/ronin/vanta/ConnectionRecoveryDeviceTest.java';s=p.read_text().rstrip();assert s.endswith('}')
s=s[:-1]+'''
 private android.view.accessibility.AccessibilityNodeInfo node(String text)throws Exception{
  long deadline=SystemClock.elapsedRealtime()+10000;
  do{
   android.view.accessibility.AccessibilityNodeInfo root=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
   if(root!=null)for(android.view.accessibility.AccessibilityNodeInfo value:root.findAccessibilityNodeInfosByText(text))
     if(text.equals(String.valueOf(value.getText()))&&value.isVisibleToUser())return value;
   Thread.sleep(100);
  }while(SystemClock.elapsedRealtime()<deadline);
  throw new AssertionError("Visible action not found: "+text);
 }
 private void clickNode(String text)throws Exception{
  android.view.accessibility.AccessibilityNodeInfo value=node(text);
  while(value!=null&&!value.isClickable())value=value.getParent();
  assertNotNull(value);assertTrue(value.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
 }
 @Test public void existingPausedTaskResumesThroughExplicitConnectionConsent()throws Exception{
  VantaJob job=interrupted(false);ForgeTransportRecovery.recover(e,job,new SocketException("Socket closed"),new Net.Call());
  AtomicInteger executed=new AtomicInteger();
  e.setHandlerForTests((engine,task,in,call)->{
   assertTrue(in.getJSONObject("recovery_policy").getBoolean("retry_interrupted"));
   assertEquals(2,engine.store.document(task.id(),"build").getInt("attempt"));
   executed.incrementAndGet();engine.completed(task,new JSONObject().put("text","Saved fixture resumed through explicit consent"));
  });
  try(ActivityScenario<MainActivity> activity=ActivityScenario.launch(MainActivity.class)){
   activity.onActivity(host->host.hubActivity("build",job.id()));
   h.awaitText(activity,"Recover connection & resume");
   activity.onActivity(host->{android.view.View root=host.findViewById(android.R.id.content);java.util.ArrayDeque<android.view.View> views=new java.util.ArrayDeque<>();views.add(root);
    while(!views.isEmpty()){android.view.View view=views.removeFirst();
     if(view instanceof android.widget.TextView && "Recover connection & resume".contentEquals(((android.widget.TextView)view).getText())){view.requestRectangleOnScreen(new android.graphics.Rect(0,0,view.getWidth(),view.getHeight()),true);break;}
     if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++)views.add(((android.view.ViewGroup)view).getChildAt(i));
    }
   });
   clickNode("Recover connection & resume");node("APPROVE & RESUME");h.shot("connection-recovery-consent");
   assertEquals(0,executed.get());assertFalse(e.store.document(job.id(),"input").getJSONObject("recovery_policy").optBoolean("retry_interrupted"));
   clickNode("APPROVE & RESUME");
   assertEquals("COMPLETED",h.waitDone(job.id(),15000).status());assertEquals(1,executed.get());assertEquals(17,e.store.document(job.id(),"recovery").getInt("calls"));
  }
 }
}
''';p.write_text(s)
print('Final review: explicit whole-task connection retry limits, valid terminal reasons and actual consent/resume UI regression.')
