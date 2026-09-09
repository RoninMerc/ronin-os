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
p=root/'app/src/main/java/com/ronin/vanta/AndroidProjectCheck.java'
s=p.read_text()
old='  public static JSONObject inspect(JSONObject project) throws Exception {\n    ProjectArchive.validate(project);'
new='''  public static JSONObject inspect(JSONObject project) throws Exception {
    return inspect(project,"release");
  }
  public static JSONObject inspect(JSONObject project,String variant) throws Exception {
    if(!variant.equals("debug")&&!variant.equals("release"))throw new IOException("Unsupported build variant for source validation");
    String sets="app/src/(main|"+variant+")";
    ProjectArchive.validate(project);'''
assert s.count(old)==1
s=s.replace(old,new)
assert s.count('"app/src/(main|release)/res/')==3
s=s.replace('"app/src/(main|release)/res/','sets+"/res/')
old='  private static Document parse(String text)throws Exception{'
assert s.count(old)==1
s=s.replace(old,old+'\n    if(text.startsWith("\\uFEFF"))text=text.substring(1);')
p.write_text(s)
p=root/'app/src/main/java/com/ronin/vanta/JobOperations.java'
s=p.read_text();old='JSONObject report=AndroidProjectCheck.inspect(project);';assert s.count(old)==1
s=s.replace(old,'JSONObject report=AndroidProjectCheck.inspect(project,session.optString("build_type","release"));');p.write_text(s)
p=root/'app/src/main/java/com/ronin/vanta/ForgeSession.java'
s=p.read_text();old='JSONObject preflight=AndroidProjectCheck.inspect(project);';assert s.count(old)==1
s=s.replace(old,'JSONObject preflight=AndroidProjectCheck.inspect(project,session.optString("build_type","debug"));');p.write_text(s)
p=root/'app/src/test/java/com/ronin/vanta/ForgeReliabilityTest.java'
s=p.read_text().rstrip();assert s.endswith('}')
s=s[:-1]+'''
  @Test public void xmlByteOrderMarkIsAccepted()throws Exception {
    JSONObject p=notes(true);JSONObject manifest=p.getJSONArray("files").getJSONObject(3);
    manifest.put("content","\\uFEFF"+manifest.getString("content"));
    assertTrue(AndroidProjectCheck.inspect(p).getBoolean("passed"));
  }
  @Test public void debugResourceSetMatchesDebugBuildOnly()throws Exception {
    JSONObject p=notes(true);JSONArray files=p.getJSONArray("files");
    for(int i=5;i<7;i++)files.getJSONObject(i).put("path",files.getJSONObject(i).getString("path").replace("/main/","/debug/"));
    assertTrue(AndroidProjectCheck.inspect(p,"debug").getBoolean("passed"));
    assertFalse(AndroidProjectCheck.inspect(p,"release").getBoolean("passed"));
  }
  @Test public void releaseResourcesCannotSatisfyADebugBuild()throws Exception {
    JSONObject p=notes(true);JSONArray files=p.getJSONArray("files");
    for(int i=5;i<7;i++)files.getJSONObject(i).put("path",files.getJSONObject(i).getString("path").replace("/main/","/release/"));
    assertTrue(AndroidProjectCheck.inspect(p,"release").getBoolean("passed"));
    assertFalse(AndroidProjectCheck.inspect(p,"debug").getBoolean("passed"));
  }
}
'''
p.write_text(s)
p=root/'app/src/main/java/com/ronin/vanta/InferenceReadiness.java'
s=p.read_text();old='return s.contains("not available for inference") || s.contains("not ready for inference")'
assert s.count(old)==1
s=s.replace(old,old+'\n        || s.contains("model is unavailable for inference")');p.write_text(s)
p=root/'app/src/test/java/com/ronin/vanta/ForgeReliabilityTest.java'
s=p.read_text().rstrip();assert s.endswith('}')
s=s[:-1]+'''
  @Test public void modelNotFoundCodeSurvivesHumanReadableFormatting(){
    String formatted=ProviderErrors.format(404,"{\\"error\\":{\\"code\\":\\"model_not_found\\",\\"message\\":\\"The selected model does not exist.\\"}}");
    assertTrue(InferenceReadiness.rejected(404,formatted));
  }
}
'''
p.write_text(s)
print('Client compiler fixes, provider-error regression and variant-correct XML validation applied.')
