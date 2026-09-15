from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'));j=root/'app/src/main/java/com/ronin/vanta'
def change(name,old,new,count=1):
 p=j/name;s=p.read_text();assert s.count(old)==count,(name,s.count(old),old[:90]);p.write_text(s.replace(old,new,count))
change('ProjectConversation.java','  static boolean discussion(JSONObject thread) {','''  /** An explicit new-project user turn starts a fresh source handoff in this conversation. */
  static int projectStart(JSONObject thread) {
    JSONArray messages=thread==null?null:thread.optJSONArray("messages");
    for(int i=messages==null?-1:messages.length()-1;i>=0;i--) {
      JSONObject m=messages.optJSONObject(i);
      if(m!=null&&"user".equals(m.optString("role"))&&newProject(m.optString("content")))return i;
    }
    return 0;
  }

  static boolean discussion(JSONObject thread) {''')
change('ProjectConversation.java','for (int i = messages == null ? -1 : messages.length() - 1, n = 0; i >= 0 && n < 80; i--, n++) {','for (int i = messages == null ? -1 : messages.length() - 1, n = 0, start=projectStart(thread); i >= start && n < 80; i--, n++) {')
change('ProjectConversation.java','''      if (m == null) continue;
      if (m.optString("content").matches''','''      if (m == null) continue;
      if("user".equals(m.optString("role")) && m.optJSONArray("attachments")!=null && m.optJSONArray("attachments").length()>0)return true;
      if (m.optString("content").matches''')
change('ProjectConversation.java','''    // The user's own choices win; model output cannot silently choose a target platform.''','''    int first=projectStart(thread);
    // The user's own choices win; model output cannot silently choose a target platform.''')
change('ProjectConversation.java','i >= Math.max(0, (messages == null ? 0 : messages.length()) - 80)','i >= Math.max(first, (messages == null ? 0 : messages.length()) - 80)')
change('ProjectConversation.java','for (int i = 0; i < messages.length(); i++) {','for (int i = projectStart(thread); i < messages.length(); i++) {')
change('ProjectConversation.java','for (int i = 0; messages != null && i < messages.length(); i++) {','for (int i = projectStart(thread); messages != null && i < messages.length(); i++) {')
change('ProjectConversation.java','''    if (s.matches(
        "[^\\\\s]+\\\\.(?:java''','''    if(s.matches("(?:[^\\\\s]+/)?(?:\\\\.gitignore|\\\\.editorconfig|gradlew|Makefile|Dockerfile|LICENSE)"))return s;
    if (s.matches(
        "[^\\\\s]+\\\\.(?:sh|bash|ps1|bat|cmd|java''')
change('ConversationProjects.java','''    Set<String> seen = new HashSet<>(), linked = new HashSet<>();''','''    int first=ProjectConversation.projectStart(thread);
    Set<String> seen = new HashSet<>(), linked = new HashSet<>();''')
change('ConversationProjects.java','for (int n = 0; messages != null && n < messages.length(); n++) {','for (int n = first; messages != null && n < messages.length(); n++) {')
change('ConversationProjects.java','i >= 0 && seen.size() < 32','i >= first && seen.size() < 32')
p=root/'app/src/test/java/com/ronin/vanta/ProjectContinuityTest.java';s=p.read_text().rstrip();assert s.endswith('}')
s=s[:-1]+r'''
  @Test public void aNewProjectBoundaryDoesNotReuseOlderSource()throws Exception {
    JSONObject thread=history("Build Android project A","### app/src/main/java/A.java\n```java\nclass A {}\n```\n","Start a new Windows app","We should use a Windows project.");
    assertEquals(2,ProjectConversation.projectStart(thread));
    assertNull(ProjectConversation.fragments(thread));
    String context=ProjectConversation.context(thread,16000);
    assertFalse(context.contains("class A"));assertTrue(context.contains("new Windows app"));
    assertEquals("Windows",ProjectConversation.platform("Build it",thread));
  }
  @Test public void unclearNewProjectDoesNotInheritOldAndroidPlatform()throws Exception {
    assertEquals("",ProjectConversation.platform("Build it",history("Create an Android app","Source planned","Start a separate app","What should it do?")));
  }
  @Test public void discussingNewProjectIsNotAToolBoundary()throws Exception {
    assertEquals(0,ProjectConversation.projectStart(history("Create an Android project","Current source","How would I start a new project?","Explanation")));
  }
  @Test public void laterAttachmentsRequireSourceReviewBeforeCompilation()throws Exception {
    JSONObject thread=history("Source saved","Ready","Use this layout");
    thread.getJSONArray("messages").getJSONObject(2).put("attachments",new JSONArray().put(new JSONObject().put("kind","image").put("name","layout.png")));
    assertTrue(ProjectConversation.changesAfter(thread,1));
  }
  @Test public void helperScriptAndBuildEntryFileNamesArePreserved()throws Exception {
    JSONObject p=ProjectConversation.fragments(history("Save project files","### .gitignore\n```text\nbuild/\n```\n### scripts/build.ps1\n```powershell\nWrite-Output 'build'\n```\n### gradlew\n```sh\n#!/bin/sh\nexit 0\n```\n"));
    JSONArray files=p.getJSONArray("files");
    assertEquals(".gitignore",files.getJSONObject(0).getString("path"));
    assertEquals("scripts/build.ps1",files.getJSONObject(1).getString("path"));
    assertEquals("gradlew",files.getJSONObject(2).getString("path"));
  }
}
''';p.write_text(s)
p=root/'app/src/androidTest/java/com/ronin/vanta/ProjectContinuityDeviceTest.java';s=p.read_text().rstrip();assert s.endswith('}')
s=s[:-1]+r'''
  @Test public void newProjectConversationBoundaryDoesNotFindOldLinkedBuild()throws Exception {
    sourceJob(source("original"));
    JSONObject thread=threads.get(h.threadId);
    threads.append(thread,"user","Create a new Android app for a different project");
    threads.append(thread,"assistant","The new application needs its own source.");
    JSONObject snapshot=ConversationProjects.capture(h.h.ctx(),threads.get(h.threadId),"Now build it",null);
    assertFalse(snapshot.has("project"));assertFalse(snapshot.has("build"));
  }
  @Test public void importedExportZipBuildsThroughTheSameModelFreeCompilerPath()throws Exception {
    File dir=new File(h.h.ctx().getCacheDir(),"shared");dir.mkdirs();
    File file=new File(dir,"continuity-source.zip");
    try(OutputStream out=new FileOutputStream(file)){out.write(asset("source.zip"));}
    Uri inputUri=androidx.core.content.FileProvider.getUriForFile(h.h.ctx(),h.h.ctx().getPackageName()+".files",file);
    JSONObject input=new JSONObject().put("kind","project").put("uri",inputUri.toString()).put("conversation",h.threadId);
    VantaJob imported=e.store.create("file_import","QA conversation ZIP import","",input);
    try {
      FileJobs.run(e,imported,input,new Net.Call());UnifiedThreads.sync(e,imported.id());
      assertEquals("COMPLETED",e.store.get(imported.id()).status());
      assertEquals(ProjectArchive.fingerprint(source("archive")),ProjectArchive.fingerprint(e.store.document(imported.id(),"result").getJSONObject("project")));
      e.vault.deleteSecret(h.p.id);Worker worker=new Worker();e.testTransport=worker;
      try(ActivityScenario<MainActivity> activity=ActivityScenario.launch(MainActivity.class)) {
        compose(activity,"Build this APK",null);confirm(activity);
        VantaJob built=last("build");assertEquals("COMPLETED",h.h.waitDone(built.id(),30000).status());
        assertEquals(1,worker.submissions);assertEquals("archive",worker.track);
        JSONObject result=e.store.document(built.id(),"result");assertTrue(result.getString("test_result").contains("14 passed"));
        shared.add(Uri.parse(result.getString("uri")));
      }
    } finally {file.delete();}
  }
}
''';p.write_text(s)
print('Project boundaries, attached-input review, script preservation and ZIP reimport/compiler tests applied.')
