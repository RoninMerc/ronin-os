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
