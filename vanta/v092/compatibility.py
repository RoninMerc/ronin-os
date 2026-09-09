from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'));j=root/'app/src/main/java/com/ronin/vanta'
p=j/'JobOperations.java';s=p.read_text();old='    int inputChars =';assert s.count(old)==1
s=s.replace(old,'''    // A resumed Forge source workflow still owns its original visual inputs, even when
    // the failed review call itself had no attachments. Never route it to a text-only model.
    if(forge && j.type().equals("forge_source") && e.store.document(j.id(),"build")==null) {
      JSONArray original=in.optJSONArray("attachments");
      images=images || (original!=null && RequestMessages.hasImages(history("",original)));
    }
'''+old);p.write_text(s)
p=root/'app/src/androidTest/java/com/ronin/vanta/AutomaticHandoverDeviceTest.java';s=p.read_text().rstrip();assert s.endswith('}')
s=s[:-1]+'''
  @Test public void resumedReviewKeepsOriginalVisionRequirement() throws Exception {
    ModelInfo primary=e.registry.find(p.id,"qa-model");primary.supportsVision=true;e.registry.refresh(p,Arrays.asList(primary,alt));
    JSONObject in=h.input(p).put("depth","Deep").put("project",f.project())
      .put("target",JobOperations.selection(p,primary)).put("recovery_policy",policy(p))
      .put("attachments",new JSONArray().put(new JSONObject().put("kind","image").put("name","layout.png")));
    VantaJob job=f.job("forge_source",in);
    e.store.document(job.id(),"analysis",new JSONObject().put("text","{\\"output_mode\\":\\"code\\",\\"question\\":\\"\\"}"));
    e.store.document(job.id(),"optimized",new JSONObject().put("text","Preserve the report application's visual layout."));
    AtomicInteger paid=new AtomicInteger();
    e.overrideChat(job.id(),(provider,key,model,history,system,max,np,web,call,stream)->{
      paid.incrementAndGet();assertTrue(system.startsWith("Review the proposed prompt"));
      assertFalse(RequestMessages.hasImages(history));throw new Net.HttpError(400,"model not available for inference");
    });
    Net.Call call=new Net.Call();call.transport=new Wire();
    try{JobOperations.run(e,job,in,call);fail();}catch(Net.HttpError unavailable){assertTrue(ForgeRecoveryController.recover(e,job,unavailable,call));}
    assertEquals(1,paid.get());assertEquals("BLOCKED",e.store.get(job.id()).status());
    assertTrue(e.store.document(job.id(),"recovery").getBoolean("images"));assertEquals(0,e.store.document(job.id(),"recovery").optInt("switches"));
    assertNotNull(e.store.document(job.id(),"analysis"));assertNotNull(e.store.document(job.id(),"optimized"));
  }
}
''';p.write_text(s)
print('Original visual-input requirements survive a review-stage model handover.')
p=j/'JobEngine.java';s=p.read_text();old='  public synchronized void schedulePending() {';assert s.count(old)==1
s=s.replace(old,'''  /** Short continuation of active user-initiated work, not an exact JobScheduler deadline. */
  synchronized long foregroundContinuationDelay() {
    if(!VantaWorkService.foreground || !online())return -1;
    try {
      long now=System.currentTimeMillis(), next=Long.MAX_VALUE;
      for(VantaJob job:store.active())
        if(job.active() && !calls.containsKey(job.id()) && !job.json.optBoolean("request_started"))
          next=Math.min(next,job.json.optLong("next_run",0));
      if(next==Long.MAX_VALUE || next>now+10000)return -1;
      return Math.max(0,next-now);
    }catch(Exception error){recoveryError=Errors.summary(error.getMessage());return -1;}
  }

'''+old);p.write_text(s)
p=j/'VantaWorkService.java';s=p.read_text();old='''          if (engine.executing() == 0) {
            if (stopSelfResult(latestStartId)) {''';assert s.count(old)==1
s=s.replace(old,'''          if (engine.executing() == 0) {
            long delay=engine.foregroundContinuationDelay();
            if(delay>=0) {
              if(delay==0)engine.wake(true,null);
              // A finite, already-active workflow owns its short transition. Longer waits
              // still release this service and use persisted Android scheduling.
              handler.postDelayed(this,Math.max(100,Math.min(1000,delay)));
              return;
            }
            if (stopSelfResult(latestStartId)) {''');p.write_text(s)
p=root/'app/src/androidTest/java/com/ronin/vanta/AutomaticHandoverDeviceTest.java';s=p.read_text().rstrip();assert s.endswith('}')
s=s[:-1]+'''
  @Test public void foregroundContinuationDoesNotDependOnImmediateAndroidSchedulerDispatch() throws Exception {
    AtomicInteger phases=new AtomicInteger();
    e.setHandlerForTests((engine,job,in,call)->{
      call.check();
      int phase=phases.incrementAndGet();
      engine.store.document(job.id(),"phase-"+phase,new JSONObject().put("saved",true));
      if(phase<3)throw new JobEngine.Deferred(false,1200,"Saved phase; preparing the next bounded step");
      engine.completed(job,new JSONObject().put("text","Three phases persisted without relying on an immediate system job"));
    });
    try(ActivityScenario<MainActivity> a=ActivityScenario.launch(MainActivity.class)) {
      VantaJob job=h.enqueue(a,"build",new JSONObject(),null);
      android.app.job.JobScheduler scheduler=(android.app.job.JobScheduler)h.ctx().getSystemService(Context.JOB_SCHEDULER_SERVICE);
      long deadline=SystemClock.elapsedRealtime()+12000;
      VantaJob current;
      do {
        // Deliberately withhold the fallback system job. The actual foreground service
        // must keep the short workflow moving, not wait for an OS minimum-latency job.
        scheduler.cancel(707001);
        Thread.sleep(40);
        current=e.store.get(job.id());
      }while(current.active()&&SystemClock.elapsedRealtime()<deadline);
      assertEquals(current.json.toString(),"COMPLETED",current.status());assertEquals(3,phases.get());
      for(int phase=1;phase<=3;phase++)assertTrue(e.store.document(job.id(),"phase-"+phase).getBoolean("saved"));
    }
  }
}
''';p.write_text(s)
print('Short foreground continuations no longer depend on an immediate Android scheduler dispatch.')
