from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'))
p=root/'app/src/androidTest/java/com/ronin/vanta/FieldAcceptanceDeviceTest.java'
s=p.read_text()
old='''          files.put(entry.getName(), Net.read(z, 16000000, new Net.Call()));'''
assert s.count(old)==1
s=s.replace(old,'''          {
            // Net.read owns and closes its input. A ZIP entry does not own the archive stream.
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            byte[] buffer=new byte[16384];int n;
            while((n=z.read(buffer))!=-1){
              if(bytes.size()+n>16000000)throw new IOException("DEX inspection limit exceeded");
              bytes.write(buffer,0,n);
            }
            files.put(entry.getName(),bytes.toByteArray());
          }''')
s=s.rstrip();assert s.endswith('}')
s=s[:-1]+'''
  @Test public void realSavedSourceBuildButtonCompletesWithoutSelectedModel()throws Exception {
    Context ctx=f.h.ctx();SharedPreferences prefs=ctx.getSharedPreferences("vanta_state",0);
    String priorRepo=prefs.getString("forge_repo",null),priorBranch=prefs.getString("forge_branch",null);
    String priorProject=e.vault.getSecret("forge_project");boolean priorRelease=prefs.getBoolean("forge_release",true);
    WorkspaceState workspace=new WorkspaceState(ctx);JSONObject priorCheckpoint=workspace.checkpoint();
    VantaJob prior=e.latestForScope("forge-build");String priorId=prior==null?"":prior.id();
    Worker worker=new Worker();e.testTransport=worker;String jobId=null;
    prefs.edit().putString("forge_repo","Fixture/PrivateBuild").putString("forge_branch","vanta-forge-worker").commit();
    workspace.clearCheckpoint();
    try {
      JSONObject exact=compiledProject();
      try(ActivityScenario<MainActivity> activity=ActivityScenario.launch(MainActivity.class)) {
        activity.onActivity(host->host.hubOpenProject(exact));
        f.h.awaitText(activity,"Build Android APK");
        activity.onActivity(host->{
          try {
            for(String name:new String[]{"provider","model"}){
              java.lang.reflect.Field member=MainActivity.class.getDeclaredField(name);member.setAccessible(true);member.set(host,null);
            }
            View build=host.findViewById(android.R.id.content).findViewWithTag("forge-build-apk");
            assertNotNull(build);assertTrue(build.isEnabled());assertTrue(build.performClick());
          }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
        });
        android.app.UiAutomation ui=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getUiAutomation();
        long until=android.os.SystemClock.elapsedRealtime()+10000;boolean clicked=false;
        do {
          android.view.accessibility.AccessibilityNodeInfo tree=ui.getRootInActiveWindow();
          if(tree!=null)for(android.view.accessibility.AccessibilityNodeInfo node:tree.findAccessibilityNodeInfosByText("BUILD APK")) {
            if("BUILD APK".equals(String.valueOf(node.getText()))&&node.isClickable()&&node.isEnabled()){
              clicked=node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);break;
            }
          }
          if(!clicked)Thread.sleep(80);
        }while(!clicked&&android.os.SystemClock.elapsedRealtime()<until);
        assertTrue("The actual user-facing Build APK confirmation was clicked",clicked);
        until=android.os.SystemClock.elapsedRealtime()+15000;VantaJob created;
        do {created=e.latestForScope("forge-build");if(created!=null&&!created.id().equals(priorId))break;Thread.sleep(80);}
        while(android.os.SystemClock.elapsedRealtime()<until);
        assertNotNull("The UI creates a durable build",created);assertNotEquals(priorId,created.id());jobId=created.id();
        VantaJob complete=f.h.waitDone(jobId,25000);
        assertEquals(complete.json.toString(),"COMPLETED",complete.status());assertEquals(1,worker.puts);
        assertTrue(e.store.document(jobId,"input").getBoolean("compile_only"));
        JSONObject result=e.store.document(jobId,"result");f.shared.add(Uri.parse(result.getString("uri")));
        assertEquals("com.ronin.fieldreport",result.getString("package"));
        assertTrue(result.getString("test_result").contains("14 passed"));
        assertEquals(ProjectArchive.fingerprint(exact),result.getJSONObject("build_receipt").getString("source_sha256"));
        final String current=jobId;activity.onActivity(host->host.hubActivity("build",current));
        f.h.awaitText(activity,"Artifact receipt");f.h.shot("field-ui-build-complete");
      }
    } finally {
      if(jobId!=null){VantaJob task=e.store.get(jobId);if(task!=null&&task.active())e.cancel(jobId);new JobFiles(ctx).remove(jobId);e.store.remove(jobId);}
      SharedPreferences.Editor restore=prefs.edit().putBoolean("forge_release",priorRelease);
      if(priorRepo==null)restore.remove("forge_repo");else restore.putString("forge_repo",priorRepo);
      if(priorBranch==null)restore.remove("forge_branch");else restore.putString("forge_branch",priorBranch);restore.commit();
      if(priorProject==null)e.vault.deleteSecret("forge_project");else e.vault.putSecret("forge_project",priorProject);
      if(priorCheckpoint==null)workspace.clearCheckpoint();else workspace.checkpoint(priorCheckpoint);
    }
  }
}
'''
p.write_text(s)
print('Fixed archive-reader ownership in acceptance tests; actual saved-source Build APK UI is exercised without a selected model.')
