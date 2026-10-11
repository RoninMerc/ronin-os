from pathlib import Path
import shutil,sys,re

root=Path(sys.argv[1] if len(sys.argv)>1 else 'source')
here=Path(__file__).parent
java=root/'app/src/main/java/com/ronin/vanta'
tests=root/'app/src/test/java/com/ronin/vanta'

def edit(path,old,new,count=1):
    p=root/path
    text=p.read_text()
    actual=text.count(old)
    if actual!=count:
        raise RuntimeError(f'{path}: expected {count} matches, got {actual}: {old[:120]}')
    p.write_text(text.replace(old,new))

shutil.copyfile(here/'AgenticRepair.java', java/'AgenticRepair.java')
shutil.copyfile(here/'Agentic180RegressionTest.java', tests/'Agentic180RegressionTest.java')

# 1) Existing/new app scaffold remains unchanged; compiler repair switches to the new targeted agent.
edit(
 'app/src/main/java/com/ronin/vanta/JobOperations.java',
 '        ForgeAuthor.author(\n            prefix,',
 '        AgenticRepair.author(\n            prefix,'
)

# 2) Build attempts become a progress-driven ceiling, not a six-round hard stop.
edit(
 'app/src/main/java/com/ronin/vanta/ForgeClient.java',
 '  public static final int MAX_ATTEMPTS = 6;',
 '  public static final int MAX_ATTEMPTS = 24;'
)
edit(
 'app/src/main/java/com/ronin/vanta/JobOperations.java',
 '          "The six-attempt build budget is exhausted. Source and compiler diagnostics are retained."\n              + " Review the project before starting another build.");',
 '          "The " + max + "-attempt build safety ceiling is exhausted. Source, repair ledger and compiler diagnostics are retained."\n              + " Review the no-progress evidence before deliberately continuing.");'
)

# 3) Higher ceilings for an actually autonomous agent. Used counters are never reset.
edit(
 'app/src/main/java/com/ronin/vanta/ForgeRecovery.java',
 '  public static final int DEFAULT_CALLS = 120, DEFAULT_SWITCHES = 4, MAX_ACTIONS = 8;',
 '  public static final int DEFAULT_CALLS = 300, DEFAULT_SWITCHES = 8, MAX_ACTIONS = 24;'
)
edit(
 'app/src/main/java/com/ronin/vanta/ForgeRecovery.java',
 '      return "Automatic recovery reached its eight-action limit. Saved files and diagnostics are"\n          + " intact.";',
 '      return "Automatic recovery reached its " + MAX_ACTIONS + "-action safety ceiling. Saved files and diagnostics are intact.";'
)

# 4) Upgrade legacy per-task ceilings on resume, but retain every consumed call/switch/action.
insert_after='''  static JSONObject input(JobEngine e, VantaJob j, JSONObject fallback) throws Exception {
    JSONObject saved = e.store.document(j.id(), "input");
    return saved == null ? fallback : saved;
  }
'''
migration='''

  static void migrateAgenticCeilings(JobEngine e, VantaJob j, JSONObject fallback) throws Exception {
    if (!forge(j)) return;
    JSONObject in = input(e, j, fallback);
    if (in == null || !ForgeRecovery.enabled(in)) return;
    JSONObject policy = in.optJSONObject("recovery_policy");
    if (policy == null) return;
    boolean changed = false;
    if (policy.optInt("max_calls", ForgeRecovery.DEFAULT_CALLS) <= 120) {
      policy.put("max_calls", ForgeRecovery.DEFAULT_CALLS);
      changed = true;
    }
    if (policy.optInt("max_switches", ForgeRecovery.DEFAULT_SWITCHES) <= 4) {
      policy.put("max_switches", ForgeRecovery.DEFAULT_SWITCHES);
      changed = true;
    }
    if (!changed) return;
    JSONObject state = state(e, j);
    summary(j, policy, state);
    Map<String, JSONObject> writes = new LinkedHashMap<>();
    writes.put("input", in);
    writes.put("recovery", state);
    e.store.recoveryCheckpoint(j, writes, Collections.emptyList());
    e.event(
        j,
        "agentic_limits_migrated",
        "RUNNING",
        "Upgraded this task's automation ceilings for compiler-led repair. Existing request, model-switch and action counters were preserved.");
  }
'''
p=root/'app/src/main/java/com/ronin/vanta/ForgeRecoveryController.java'
text=p.read_text()
if text.count(insert_after)!=1: raise RuntimeError('ForgeRecoveryController input anchor mismatch')
p.write_text(text.replace(insert_after,insert_after+migration))

edit(
 'app/src/main/java/com/ronin/vanta/JobOperations.java',
 '''    JSONObject session = e.store.document(j.id(), "build");
    if (session == null) {
      session = in.getJSONObject("session");
      e.store.document(j.id(), "build", session);
    }
''',
 '''    JSONObject session = e.store.document(j.id(), "build");
    if (session == null) {
      session = in.getJSONObject("session");
      e.store.document(j.id(), "build", session);
    }
    ForgeRecoveryController.migrateAgenticCeilings(e, j, in);
'''
)

# 5) Exact same source + exact same meaningful compiler failure twice is a real circuit breaker.
needle='''    String fingerprint = current.getString("fingerprint");
    JSONObject stagnation = state.optJSONObject("stagnation");
'''
replacement='''    String fingerprint = current.getString("fingerprint");
    String sourceFingerprint = ProjectArchive.fingerprint(session.getJSONObject("project"));
    String sourceErrorKey = sourceFingerprint + "/" + fingerprint;
    int exactRepeat = sourceErrorKey.equals(state.optString("last_source_error_key"))
        ? state.optInt("source_error_repeat", 0) + 1
        : 1;
    state.put("last_source_error_key", sourceErrorKey).put("source_error_repeat", exactRepeat);
    if (exactRepeat >= 2) {
      Map<String, JSONObject> writes = new LinkedHashMap<>();
      writes.put("recovery", state);
      e.store.recoveryCheckpoint(j, writes, Collections.emptyList());
      throw new JobOperations.Blocked(
          "No-progress circuit breaker: the exact same saved source produced the same meaningful compiler failure twice. "
              + "No additional AI repair was purchased. Source and diagnostics remain available.");
    }
    JSONObject stagnation = state.optJSONObject("stagnation");
'''
p=root/'app/src/main/java/com/ronin/vanta/ForgeRecoveryController.java'
text=p.read_text()
if text.count(needle)!=1: raise RuntimeError('build failure fingerprint anchor mismatch')
p.write_text(text.replace(needle,replacement))

# 6) Existing tests keep enforcing a hard action ceiling; update only the expected ceiling value.
edit(
 'app/src/test/java/com/ronin/vanta/Capacity177RegressionTest.java',
 '  @Test public void actionLimitIsNotIncreased()throws Exception{\n    JSONObject s=state().put("actions",8);assertFalse(plan(policy(),s,null,"scope",1000,-1).retry);assertEquals(8,s.getInt("actions"));\n  }',
 '  @Test public void actionLimitIsNotIncreased()throws Exception{\n    JSONObject s=state().put("actions",ForgeRecovery.MAX_ACTIONS);assertFalse(plan(policy(),s,null,"scope",1000,-1).retry);assertEquals(ForgeRecovery.MAX_ACTIONS,s.getInt("actions"));\n  }'
)
edit(
 'app/src/test/java/com/ronin/vanta/AutomaticForgeRecoveryTest.java',
 '    assertNotNull(ForgeRecovery.limit(policy(), state().put("actions", 8)));',
 '    assertNotNull(ForgeRecovery.limit(policy(), state().put("actions", ForgeRecovery.MAX_ACTIONS)));'
)

# 7) Version.
p=root/'app/build.gradle'
text=p.read_text()
if text.count('versionCode 179')!=1 or text.count("versionName '0.17.9'")!=1:
    raise RuntimeError('version anchors mismatch')
p.write_text(text.replace('versionCode 179','versionCode 180').replace("versionName '0.17.9'","versionName '0.18.0'"))

print('Applied Vanta 0.18.0 compiler-led agentic Forge repair engine.')
