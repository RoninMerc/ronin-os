from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'));j=root/'app/src/main/java/com/ronin/vanta'
p=j/'ForgeRecoveryController.java';s=p.read_text();old='''    JSONObject state = state(e, j),
        selection =
            JobOperations.selection(
                ProviderConfig.fromJson(in.getJSONObject("provider")),
                ModelInfo.fromJson(in.getJSONObject("model")));
    state.put("active", selection);''';assert s.count(old)==1
s=s.replace(old,'''    JSONObject state=state(e,j),selection=null;
    // Prompt generation and coding can have different models. Attribute a compiler
    // failure to the saved build target, never automatically to the prompt generator.
    for(JSONObject candidate:new JSONObject[]{state.optJSONObject("active"),e.store.document(j.id(),"target"),in.optJSONObject("target"),in}) {
      if(candidate==null)continue;
      JSONObject provider=candidate.optJSONObject("provider"),model=candidate.optJSONObject("model");
      if(provider!=null&&model!=null&&session.optString("provider").equals(provider.optString("id"))&&session.optString("model").equals(model.optString("id"))) {
        selection=candidate;break;
      }
    }
    if(selection==null)for(ProviderConfig provider:e.registry.providers()) {
      if(!provider.id.equals(session.optString("provider")))continue;
      ModelInfo model=e.registry.find(provider.id,session.optString("model"));
      if(model!=null){selection=JobOperations.selection(provider,model);break;}
    }
    if(selection==null)throw new JobOperations.Blocked("The failed build's coding-model configuration is unavailable. Project and compiler errors are saved; review the build model before resuming.");
    state.put("active", selection);''');p.write_text(s)
p=root/'app/src/androidTest/java/com/ronin/vanta/AutomaticHandoverDeviceTest.java';s=p.read_text().rstrip();assert s.endswith('}')
s=s[:-1]+'''
  @Test public void compilerFailureIsAttributedToCoderNotSeparatePromptGenerator() throws Exception {
    ProviderConfig generator=h.seed("venice");ModelInfo coder=e.registry.find(p.id,"qa-model");
    JSONObject session=f.session(f.project(),p,3);
    JSONObject in=h.input(generator).put("target",JobOperations.selection(p,coder)).put("session",session).put("recovery_policy",policy(generator,p));
    VantaJob job=f.job("forge_source",in);
    e.store.document(job.id(),"target",JobOperations.selection(p,coder));
    assertFalse(ForgeRecoveryController.buildFailed(e,job,session,"error: unresolved formatter method"));
    JSONObject state=e.store.document(job.id(),"recovery");
    assertEquals(p.id,state.getJSONObject("active").getJSONObject("provider").getString("id"));
    assertEquals(coder.id,state.getJSONObject("active").getJSONObject("model").getString("id"));
    assertEquals(1,state.getJSONObject("build_failures").getInt(p.id+"/"+coder.id));
    assertEquals(generator.id,e.store.document(job.id(),"input").getJSONObject("provider").getString("id"));
  }
}
''';p.write_text(s)
print('Compiler failures retain their actual coder identity when Prompt Architect uses a different generator.')
