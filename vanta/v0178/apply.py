from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]);here=Path(__file__).parent
main=root/'app/src/main/java/com/ronin/vanta'

def edit(path,old,new,count=1):
    p=root/path;text=p.read_text(encoding='utf-8')
    if text.count(old)!=count:raise RuntimeError(f'{path}: expected {count} matches, got {text.count(old)}: {old[:100]}')
    p.write_text(text.replace(old,new),encoding='utf-8')
def core(name,old,new,count=1):edit('app/src/main/java/com/ronin/vanta/'+name,old,new,count)

for name in ['ForgePlanState.java','ForgeRequestPolicy.java','GenerationWatchdog.java']:
    shutil.copyfile(here/name,main/name)

core('JobStore.java','  public synchronized void remove(String id) throws Exception {','''  /** A user-authorised inactive task retry: archive and invalidate only scoped raw records,
   * then queue atomically. Background recovery still rejects cancelled jobs. */
  public synchronized void retryCheckpoint(
      VantaJob job, Map<String, JSONObject> writes, List<String> deletes) throws Exception {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      VantaJob current = get(job.id());
      if (current == null || current.active())
        throw new IOException("The task is already active or no longer exists. Nothing was reset.");
      if (!"QUEUED".equals(job.status())) throw new IOException("A retry must queue the retained task.");
      for (Map.Entry<String, JSONObject> entry : writes.entrySet())
        document(job.id(), entry.getKey(), entry.getValue());
      for (String name : deletes) deleteDocument(job.id(), name);
      save(job);
      db.setTransactionSuccessful();
    } finally { db.endTransaction(); }
  }

  public synchronized void remove(String id) throws Exception {''')

# Migration is triggered only by an explicit retry or actual authoring; installing never purchases a request.
core('JobEngine.java','''    // Drop an invalid last raw response on an explicit retry; validated files/chunks remain.
    if (j.status().equals("FAILED")) {''','''    JSONObject retryInput = store.document(id, "input");
    boolean forgeRetry = ForgeRecoveryController.forge(j);
    ForgePlanState.Change planning = new ForgePlanState.Change();
    if (forgeRetry && store.document(id, "signed_apk") == null && retryInput != null
        && (retryInput.optJSONObject("provider") != null || retryInput.optJSONObject("target") != null)) {
      JSONObject build = store.document(id, "build");
      String prefix = ForgePlanState.prefix(j.json, build);
      planning = ForgePlanState.prepare(name -> store.document(id, name), prefix,
          ForgePlanState.selection(retryInput, build), j.json.optBoolean("planning_renew_authorized"));
      if (planning.archived > 0) {
        j.json.put("planning_records_archived", planning.archived);
        j.json.remove("files_completed"); j.json.remove("files_planned");
        j.json.remove("stage_progress"); j.json.remove("last_progress");
      }
    }
    j.json.remove("planning_renew_authorized");
    // Forge invalid raw responses are archived by the scoped migration, not blindly deleted.
    if (!forgeRetry && j.status().equals("FAILED")) {''')
core('JobEngine.java','''    JSONObject retryInput = store.document(id, "input");
    if (!ForgeRecovery.enabled(retryInput)) {''','''    if (!forgeRetry && !ForgeRecovery.enabled(retryInput)) {''')
core('JobEngine.java','''    store.save(j);
    schedule(1000);
    signal(id);''','''    if (forgeRetry) store.retryCheckpoint(j, planning.writes, planning.deletes);
    else store.save(j);
    schedule(1000);
    signal(id);''')
# Do not misrepresent a planning-only stop as a broken or idle internet stream.
core('JobEngine.java','''          if (remote
              && (failure instanceof java.net.SocketException''','''          if (remote && !(failure instanceof GenerationWatchdog.Expired)
              && (failure instanceof java.net.SocketException''')
core('JobEngine.java','''          String message =
              call.isCancelled()''','''          if (failure instanceof GenerationWatchdog.PlanningExhausted) {
            diagnostic.put("category", "PLANNING_OUTPUT_BUDGET");
            store.document(id, "diagnostics", diagnostic);
            job.json.put("action_kind", "PLANNING_OUTPUT_BUDGET");
          }
          String message =
              call.isCancelled()''')

# Retain the original source and all valid continuation chunks on a manual model choice.
p=main/'VantaHub.java';text=p.read_text(encoding='utf-8')
a=text.index('                        for (String phase :',text.index('  private void changeModelAndResume'))
b=text.index('                        if (build != null) {\n                          build.remove("repair_model");',a)
text=text[:a]+'''                        // JobEngine.retry archives the failed unvalidated response family in
                        // the same transaction that queues this explicit model selection.
                        j.json.put("planning_renew_authorized", true);
'''+text[b:]
text=text.replace('" retained. Only unfinished AI repair phases will be cleared. The next"','" retained. Invalid planning responses will be archived, not reused. The next"')
p.write_text(text,encoding='utf-8')

core('JobOperations.java','''      c.inferenceOptions = forge ? ForgeRequestPolicy.options(p, m, phase) : null;
      // Metadata validation has separate transport limits; the generation timer begins here.
      watchdog = forge ? new GenerationWatchdog(c) : null;''','''      c.inferenceOptions = forge ? ForgeRequestPolicy.options(p, m, phase) : null;
      c.generationPolicy = forge ? ForgeRequestPolicy.diagnostics(p, m, phase) : null;
      // Metadata validation has separate transport limits; the generation timer begins here.
      watchdog = forge ? new GenerationWatchdog(c, ForgeRequestPolicy.planning(phase)) : null;''')
core('JobOperations.java','''    j.json.put("source_prefix", prefix).put("source_pass", log == null ? "source" : "repair");''','''    j.json.put("source_prefix", prefix).put("source_pass", log == null ? "source" : "repair");
    ForgePlanState.Change planningScope = ForgePlanState.prepare(
        name -> e.store.document(j.id(), name), prefix, selection(p, m), false);
    if (planningScope.changed()) {
      call.check();
      e.store.recoveryCheckpoint(j, planningScope.writes, planningScope.deletes);
    }
    if (e.store.document(j.id(), prefix + "_plan") == null
        && e.store.document(j.id(), prefix + "_ready") == null) {
      j.json.remove("files_completed"); j.json.remove("files_planned");
      j.json.remove("stage_progress");
      e.event(j, "planning_started", log == null ? "PLANNING SOURCE" : "PLANNING REPAIR",
          "Preparing a compact file plan with the selected coding model. Saved project files are retained; a previous file count is not this plan's progress.");
    }''')
core('Net.java','''      try { result.put("generation", generation.diagnostics()); }''','''      if (generationPolicy != null) result.put("request_policy", new org.json.JSONObject(generationPolicy.toString()));
      try { result.put("generation", generation.diagnostics()); }''')
core('Net.java','''    Transport transport;
    org.json.JSONObject''','''    Transport transport;
    org.json.JSONObject generationPolicy; // Safe policy/limit diagnostics, no prompts or credentials.
    org.json.JSONObject''')

core('ForgeOutputRecovery.java','''      if (previous != null) {
        if (!docs.automaticRecovery())''','''      if (previous != null) {
        if (ForgeRequestPolicy.planning(phase) && previous.optBoolean("confirmed")
            && previous.optString("partial").isBlank())
          throw noPlanningAnswer(phase, null);
        if (!docs.automaticRecovery())''')
core('ForgeOutputRecovery.java','''        if (!docs.automaticRecovery()) throw limit;
      }
    }
    throw new ForgeRecovery.InvalidOutput(''','''        if (ForgeRequestPolicy.planning(phase) && (limit.partial == null || limit.partial.isBlank()))
          throw noPlanningAnswer(phase, limit);
        if (!docs.automaticRecovery()) throw limit;
      }
    }
    throw new ForgeRecovery.InvalidOutput(''')
core('ForgeOutputRecovery.java','''  public static String plan(''','''  private static ForgeRecovery.InvalidOutput noPlanningAnswer(String phase, Throwable cause) {
    return new ForgeRecovery.InvalidOutput(
        "Planning output budget: the provider confirmed its completion limit without a usable plan answer. "
            + "Larger same-model planning retries were not purchased. Source and requirements are retained; "
            + "inspect diagnostics or explicitly choose a compatible coding model.", records(phase), cause);
  }

  public static String plan(''')
core('ForgeAuthor.java','''              + " callers. Keep individual files focused and small enough for a complete response. "''','''              + " callers. Keep individual files focused and small enough for a complete response. "
              + "Return the plan directly: architecture under 80 words, each purpose under 24 words, "
              + "and needs limited to direct dependencies. Do not repeat requirements or include code. "''')
core('ForgeRecoveryController.java','''      stop(e, j, policy, state, limit);''','''      stop(e, j, policy, state, Errors.summary(failure.getMessage()) + "\\n" + limit);''')
core('Errors.java','''    if (s.startsWith("generation watchdog:"))''','''    if (s.startsWith("planning output budget:"))
      return "No usable repair plan returned within the planning allowance. Saved source is retained; open Technical details.";
    if (s.startsWith("generation watchdog:"))''')

edit('app/build.gradle','versionCode 177','versionCode 178')
edit('app/build.gradle',"versionName '0.17.7'","versionName '0.17.8'")
p=root/'app/src/androidTest/java/com/ronin/vanta/Vanta175UpgradeDeviceTest.java'
p.write_text(p.read_text(encoding='utf-8').replace('assertEquals("0.17.7",','assertEquals("0.17.8",'),encoding='utf-8')
for name,target in [('Planning178RegressionTest.java','app/src/test/java/com/ronin/vanta'),('Planning178DeviceTest.java','app/src/androidTest/java/com/ronin/vanta')]:
    shutil.copyfile(here/name,root/target/name)
print('Applied Android 0.17.8 scoped planning reset, compact request policy, reasoning-only bounds and retained original source/signing identity.')
