from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'))
p=root/'app/src/androidTest/java/com/ronin/vanta/PipelineDeviceTest.java';s=p.read_text();old='    assertEquals("COMPLETED", e.store.get(j.id()).status());';assert s.count(old)==1
s=s.replace(old,'''    assertEquals("Saved task: "+e.store.get(j.id()).json+"\\nDiagnostic: "+e.store.document(j.id(),"diagnostics")+"\\nRecovery: "+e.store.document(j.id(),"recovery"),"COMPLETED",e.store.get(j.id()).status());''');p.write_text(s)
p=root/'app/src/androidTest/java/com/ronin/vanta/AutomaticHandoverDeviceTest.java';s=p.read_text();old='    f.cleanup();';assert s.count(old)==1
s=s.replace(old,'''    // Isolated fixture records only, captured before the test cleanup deletes them.
    for(VantaJob task:e.store.list())if(task.title().startsWith("QA ")&&!task.status().equals("COMPLETED")) {
      JSONObject evidence=new JSONObject().put("task",task.json).put("diagnostics",e.store.document(task.id(),"diagnostics")).put("recovery",e.store.document(task.id(),"recovery"));
      JSONObject build=e.store.document(task.id(),"build");
      if(build!=null)evidence.put("build_phase",build.optString("phase")).put("build_attempt",build.optInt("attempt"));
      try(OutputStream out=new FileOutputStream(new File(h.ctx().getExternalFilesDir(null),"recovery-fixture-"+task.id()+".json"))) {out.write(evidence.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    }
    f.cleanup();''')
s=s.rstrip();assert s.endswith('}')
s=s[:-1]+'''
  @Test public void repeatedInvalidPlanHandoversFinishWithoutInterJobStateLeak() throws Exception {
    for(int iteration=0;iteration<12;iteration++)twoMalformedPlansAreArchivedThenAlternativeCompletes();
  }
}
''';p.write_text(s)
print('Native fixture failures retain exact diagnostics; repeated handovers exercise inter-job lifetime boundaries.')
