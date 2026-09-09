from pathlib import Path
import os,subprocess
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'));j=root/'app/src/main/java/com/ronin/vanta'
formatter=Path(os.environ.get('VANTA_FORMATTER','/tmp/format.jar'))
subprocess.run(['java','-jar',str(formatter),'--replace']+[str(p) for p in (root/'app/src').rglob('*.java')],check=True)
def edit(path,old,new):
 p=root/path;s=p.read_text();assert s.count(old)==1,(path,s.count(old),old[:75]);p.write_text(s.replace(old,new,1))
def java(name,old,new):edit('app/src/main/java/com/ronin/vanta/'+name,old,new)
java('JobOperations.java','''      e.savedResponse(j, phase, new JSONObject().put("text", answer));''','''      j.json.put("forge_inference",false);
      e.savedResponse(j, phase, new JSONObject().put("text", answer));''')
java('JobOperations.java','''      analysis = ForgeClient.extractObject(a);''','''      try { analysis = ForgeClient.extractObject(a); }
      catch(org.json.JSONException|IOException malformed) {
        if(forge && ForgeRecovery.enabled(in))throw new ForgeRecovery.InvalidOutput(
            "The completed requirements analysis was not valid structured data. Saved source is preserved.",
            Collections.singletonList("analysis"),malformed);
        throw malformed;
      }''')
java('JobOperations.java','''    try {
      if (session.optString("sha").isEmpty()) {''','''    j.json.put("forge_inference",false);
    e.store.save(j);
    try {
      if (session.optString("sha").isEmpty()) {''')
java('ForgeRecoveryController.java','''+ ((delay + 999) / 1000)''','''+ (delay/1000 + (delay%1000==0?0:1))''')
java('ForgeRecoveryController.java','''    e.store.checkpoint(j, "recovery", state);
  }

  static void summary''','''    e.store.recoveryCheckpoint(j,Collections.singletonMap("recovery",state),Collections.emptyList());
  }

  static void summary''')
java('ForgeRecoveryController.java','''    e.store.checkpoint(j, "recovery", state);
  }

  /**
   * Returns true''','''    e.store.recoveryCheckpoint(j,Collections.singletonMap("recovery",state),Collections.emptyList());
  }

  /**
   * Returns true''')
java('ForgeRecoveryController.java','''        e.store.checkpoint(j, "recovery", state);
        return true;''','''        e.store.recoveryCheckpoint(j,Collections.singletonMap("recovery",state),Collections.emptyList());
        return true;''')
# Android must be explicitly brought to the foreground after the Home key, not just lifecycle-moved.
test='app/src/androidTest/java/com/ronin/vanta/AutomaticHandoverDeviceTest.java'
edit(test,'''      a.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
      a.recreate();
      assertEquals(1, e.store.document(j.id(), "recovery").getInt("switches"));
      assertEquals(2, paid.get());''','''      a.close();
      try(ActivityScenario<MainActivity> reopened=ActivityScenario.launch(MainActivity.class)) {
        reopened.recreate();
        assertEquals(1,e.store.document(j.id(),"recovery").getInt("switches"));
        assertEquals(2,paid.get());
        reopened.onActivity(host->host.hubActivity("build",j.id()));
        h.awaitText(reopened,"1 / 4 model changes");
      }''')
# Configurable expected attempt only affects the neutral worker fixture; production attempts unchanged.
edit('app/src/androidTest/java/com/ronin/vanta/PipelineDeviceTest.java','''    int puts, downloads, reads;''','''    int puts, downloads, reads;
    int expectedAttempt=4;''')
edit('app/src/androidTest/java/com/ronin/vanta/PipelineDeviceTest.java','''        assertEquals(4, new JSONObject(uploaded).getInt("attempt"));''','''        assertEquals(expectedAttempt, new JSONObject(uploaded).getInt("attempt"));''')
p=root/test;s=p.read_text().rstrip();assert s.endswith('}')
s=s[:-1]+'''
  @Test public void forgeSourceHandoverKeepsCompletedAnalysisThenBuilds() throws Exception {
    Wire wire=new Wire(){
      @Override public Net.Response request(String method,String url,Map<String,String> headers,byte[] body,String contentType,int limit,Net.Call call)throws Exception {
        if(url.contains("/contents/.github/workflows/vanta-forge-worker.yml"))return f.json(new JSONObject().put("name","vanta-forge-worker.yml"));
        return super.request(method,url,headers,body,contentType,limit,call);
      }
    };
    wire.worker.expectedAttempt=1;e.testTransport=wire;
    JSONObject in=h.input(p).put("target",JobOperations.selection(p,e.registry.find(p.id,"qa-model")))
      .put("project",f.project()).put("source_protocol","files-v1").put("auto_build",true)
      .put("platform","Android").put("worker_repo","Fixture/PrivateBuild").put("worker_branch","vanta-forge-worker")
      .put("recovery_policy",policy(p));
    AtomicInteger analysis=new AtomicInteger(),synthesis=new AtomicInteger(),calls=new AtomicInteger();
    try(ActivityScenario<MainActivity> a=ActivityScenario.launch(MainActivity.class)){
      VantaJob job=h.enqueue(a,"forge_source",in,(provider,key,model,history,system,max,np,web,call,stream)->{
        calls.incrementAndGet();
        if(system.startsWith(PromptStrategy.analysisSystem())) {analysis.incrementAndGet();return "{\\"objective\\":\\"Preserve the existing report app and improve its formatter\\",\\"question\\":\\"\\",\\"output_mode\\":\\"code\\"}";}
        if(system.startsWith("You are Vanta Prompt Architect. Produce")){synthesis.incrementAndGet();return "Preserve the report app. Improve ReportFormatter and compile the project.";}
        if(model.equals("qa-model"))throw new Net.HttpError(400,"The model is not available for inference");
        assertEquals("fixture-alt",model);
        if(system.contains("Do not include file content"))return "{\\"name\\":\\"Neutral report app\\",\\"architecture\\":\\"Preserve interfaces\\",\\"files\\":[{\\"path\\":\\"app/src/main/java/com/ronin/forge/verification/ReportFormatter.java\\",\\"purpose\\":\\"Implement formatting\\"}]}";
        return f.project().getJSONArray("files").getJSONObject(5).getString("content");
      });
      h.waitDone(job.id(),30000);f.assertReady(job);
      assertEquals(1,analysis.get());assertEquals(1,synthesis.get());assertEquals(5,calls.get());
      assertEquals(1,wire.worker.puts);assertEquals(1,e.store.document(job.id(),"build").getInt("attempt"));
      assertEquals("fixture-alt",e.store.document(job.id(),"target").getJSONObject("model").getString("id"));
      assertFalse(e.store.get(job.id()).json.optBoolean("forge_inference"));
    }
  }

  @Test public void malformedCompletedAnalysisGetsNewModelWithoutLosingProject() throws Exception {
    e.testTransport=new Wire();JSONObject in=h.input(p).put("project",f.project()).put("platform","Android")
        .put("recovery_policy",policy(p)).put("target",JobOperations.selection(p,e.registry.find(p.id,"qa-model")));
    AtomicInteger calls=new AtomicInteger();
    try(ActivityScenario<MainActivity> a=ActivityScenario.launch(MainActivity.class)){
      VantaJob job=h.enqueue(a,"forge_source",in,(provider,key,model,history,system,max,np,web,call,stream)->{
        calls.incrementAndGet();
        if(model.equals("qa-model"))return "Requirements drafted, but not returned as JSON.";
        if(system.startsWith(PromptStrategy.analysisSystem()))return "{\\"output_mode\\":\\"code\\",\\"question\\":\\"\\"}";
        if(system.startsWith("You are Vanta Prompt Architect. Produce"))return "Preserve the current report application and return complete source.";
        return f.project().toString();
      });
      assertEquals("COMPLETED",h.waitDone(job.id(),30000).status());
      assertEquals(4,calls.get());assertNotNull(e.store.document(job.id(),"recovery_invalid_1").getJSONObject("analysis"));
      assertEquals(f.project().getJSONArray("files").length(),e.store.document(job.id(),"result").getJSONObject("project").getJSONArray("files").length());
      assertTrue(e.store.document(job.id(),"result").getString("build_result").startsWith("Not run"));
    }
  }

  @Test public void workerCapacityErrorAfterSuccessfulInferenceDoesNotSwitchModel() throws Exception {
    Wire wire=new Wire(){
      @Override public Net.Response request(String method,String url,Map<String,String> headers,byte[] body,String ct,int limit,Net.Call call)throws Exception {
        if(url.startsWith("https://api.github.com/")&&url.endsWith("PrivateBuild"))throw new Net.HttpError(503,"Worker unavailable");
        return super.request(method,url,headers,body,ct,limit,call);
      }
    };e.testTransport=wire;
    AtomicInteger paid=new AtomicInteger();
    JSONObject in=input(4);in.put("model",alt.toJson());in.getJSONObject("session").put("model",alt.id);
    try(ActivityScenario<MainActivity> a=ActivityScenario.launch(MainActivity.class)){
      VantaJob job=h.enqueue(a,"build",in,repair(paid,null));
      assertEquals("BLOCKED",h.waitDone(job.id(),12000).status());assertEquals(2,paid.get());
      assertFalse(e.store.get(job.id()).json.optBoolean("forge_inference"));
      assertEquals(0,e.store.document(job.id(),"recovery").optInt("switches"));assertEquals(0,wire.worker.puts);
    }
  }
}
''';p.write_text(s)
print('Reviewed recovery checkpoint cancellation, inference/worker boundary, structured analysis and full Forge-source handover.')
