from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'));j=root/'app/src/main/java/com/ronin/vanta'
def edit(path,old,new):
 s=path.read_text();assert s.count(old)==1,(path,s.count(old),old[:90]);path.write_text(s.replace(old,new,1))
p=j/'MainActivity.java'
edit(p,'    String restored = prefs.getString("last_workspace", "chat");','''    // Only small navigation identifiers enter saved instance state. Source, credentials
    // and job results remain in their existing encrypted application stores.
    String restored=state==null ? prefs.getString("last_workspace","chat")
        : state.getString("vanta_workspace",prefs.getString("last_workspace","chat"));
    activityDetail=state==null ? prefs.getString("activity_detail","")
        : state.getString("vanta_activity_detail","");
    activityFilter=state==null ? prefs.getString("activity_filter","All")
        : state.getString("vanta_activity_filter","All");''')
edit(p,'    if (requested != null) hubActivity("All", requested);','''    // A launch notification is consumed once; rotation must not navigate back to an
    // old notification after the user has deliberately changed workspace or task.
    if (state==null && requested != null) hubActivity("All", requested);''')
edit(p,'''  protected void onSaveInstanceState(Bundle out) {
    saveDraft();
    super.onSaveInstanceState(out);
  }''','''  protected void onSaveInstanceState(Bundle out) {
    saveDraft();
    out.putString("vanta_workspace",mode);
    out.putString("vanta_activity_detail",activityDetail);
    out.putString("vanta_activity_filter",activityFilter);
    super.onSaveInstanceState(out);
  }''')
edit(p,'''  void hubActivity(String filter, String id) {
    activityFilter = filter;
    activityDetail = id;
    showMode("activity");
  }

  void hubSetDetail(String id) {
    activityDetail = id;
  }''','''  void hubActivity(String filter, String id) {
    hubSetActivityFilter(filter);
    hubSetDetail(id);
    showMode("activity");
  }

  void hubSetDetail(String id) {
    activityDetail = id==null ? "" : id;
    prefs.edit().putString("activity_detail",activityDetail).apply();
  }

  void hubSetActivityFilter(String filter) {
    activityFilter=filter==null || filter.isEmpty() ? "All" : filter;
    prefs.edit().putString("activity_filter",activityFilter).apply();
  }''')
p=j/'VantaHub.java'
edit(p,'''            typeFilter = String.valueOf(types.getSelectedItem());
            refresh();''','''            typeFilter = String.valueOf(types.getSelectedItem());
            host.hubSetActivityFilter(typeFilter);
            refresh();''')
# The build foundation legitimately adds one managed quality-gate source. Assert the
# entire expected source fingerprint rather than removing source-preservation checks.
p=root/'app/src/androidTest/java/com/ronin/vanta/AutomaticHandoverDeviceTest.java'
edit(p,'''      assertEquals(
          new JSONObject(saved).getJSONArray("files").length(),
          session.getJSONObject("project").getJSONArray("files").length());''','''      assertEquals(
          ProjectArchive.fingerprint(AndroidBuildFoundation.prepare(new JSONObject(saved)).project),
          ProjectArchive.fingerprint(session.getJSONObject("project")));''')
p=root/'app/src/androidTest/java/com/ronin/vanta/ForgeRecoveryDeviceTest.java'
edit(p,'    assertEquals(all.length() + 2, uploaded.getJSONArray("files").length());','''    assertEquals(AndroidBuildFoundation.prepare(source).project.getJSONArray("files").length()+2,
        uploaded.getJSONArray("files").length());
    assertTrue(AndroidBuildFoundation.files(uploaded).containsKey(AndroidBuildFoundation.GATE_PATH));
    assertTrue(AndroidBuildFoundation.files(uploaded).containsKey("app/src/main/res/mipmap/notes_icon.xml"));
    assertTrue(AndroidBuildFoundation.files(uploaded).containsKey("app/src/main/res/mipmap/notes_round.xml"));''')
p=root/'app/src/androidTest/java/com/ronin/vanta/FoundationDeviceTest.java';s=p.read_text().rstrip();assert s.endswith('}')
s=s[:-1]+'''
  @Test public void oldNotificationDoesNotOverrideNavigationAfterRecreation()throws Exception {
    VantaJob job=f.job("build",input());
    job.event("paused","BLOCKED",ProgressState.unknown("ACTION REQUIRED","Neutral saved job"));e.store.save(job);
    android.content.Intent launch=new android.content.Intent(h.ctx(),MainActivity.class).putExtra("vanta_job",job.id());
    try(ActivityScenario<MainActivity> activity=ActivityScenario.launch(launch)) {
      h.awaitText(activity,"Neutral saved job");
      activity.onActivity(host->host.showMode("chat"));activity.recreate();
      activity.onActivity(host->{
        try{java.lang.reflect.Field mode=MainActivity.class.getDeclaredField("mode");mode.setAccessible(true);assertEquals("chat",mode.get(host));}
        catch(ReflectiveOperationException error){throw new AssertionError(error);}
      });
    }
  }
  @Test public void clearingTaskDetailRestoresListInsteadOfOldTask()throws Exception {
    VantaJob job=f.job("build",input());
    job.event("paused","BLOCKED",ProgressState.unknown("ACTION REQUIRED","Neutral saved job"));e.store.save(job);
    try(ActivityScenario<MainActivity> activity=ActivityScenario.launch(MainActivity.class)) {
      activity.onActivity(host->host.hubActivity("build",job.id()));h.awaitText(activity,"Neutral saved job");
      activity.onActivity(host->host.hubActivity("build",""));activity.recreate();
      h.awaitText(activity,"Your work, its current state and saved results.");
      activity.onActivity(host->{assertEquals("",host.getSharedPreferences("vanta_state",0).getString("activity_detail","missing"));});
    }
  }
}
''';p.write_text(s)
print('Restored current job/filter on recreation, consumed notification navigation once and retained full source/quality-gate assertions.')
