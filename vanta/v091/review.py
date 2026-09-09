from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'))
p=root/'app/src/main/java/com/ronin/vanta/VantaHub.java'
s=p.read_text()
old='            JSONObject validation=engine.store.document(job.id(),"source_validation");'
assert s.count(old)==1
s=s.replace(old,old+'\n            final String validationText=validation==null?"":Errors.redact(validation.toString(2));')
old='"Source validation (not a build result)\\n"+validation.toString(2)'
assert s.count(old)==1
s=s.replace(old,'"Source validation (not a build result)\\n"+validationText')
p.write_text(s)
p=root/'app/src/test/java/com/ronin/vanta/ForgeReliabilityTest.java'
s=p.read_text()
for name in ['coldRejected','loadingRejected','offlineRejected','warmSnapshotAllowsRequest']:
 old='void '+name+'(){'
 assert s.count(old)==1,name
 s=s.replace(old,'void '+name+'()throws Exception{')
p.write_text(s)
p=root/'app/src/main/java/com/ronin/vanta/ProviderErrors.java'
s=p.read_text()
old='unavailable ? "Model is unavailable for inference." :'
assert s.count(old)==1
s=s.replace(old,'unavailable ? (status==404 ? "Model or endpoint not found. Model is unavailable for inference." : "Model is unavailable for inference.") :')
p.write_text(s)
p=root/'app/src/main/java/com/ronin/vanta/Errors.java'
s=p.read_text()
old='    if (s.contains("not available for inference") || s.contains("not ready for inference")\n        || s.contains("model is unavailable for inference") || s.contains("model_not_found"))'
assert s.count(old)==1
s=s.replace(old,'    if (!s.contains("content policy") && !s.contains("content_policy")\n        && (s.contains("not available for inference") || s.contains("not ready for inference")\n        || s.contains("model is unavailable for inference")))')
p.write_text(s)
p=root/'app/src/test/java/com/ronin/vanta/ForgeReliabilityTest.java'
s=p.read_text().rstrip();assert s.endswith('}')
s=s[:-1]+'''\n  @Test public void policySummaryIsNeverRelabelledAsAvailability(){
    String policy="Provider content policy: model not available for inference for this content";
    assertEquals(policy,Errors.summary(policy));
  }
}
'''
p.write_text(s)
print('Compiler fixes and generic 404 regression corrected; provider policy classification preserved.')
