from pathlib import Path
import sys,shutil
root=Path(sys.argv[1] if len(sys.argv)>1 else 'source'); here=Path(__file__).parent
java=root/'app/src/main/java/com/ronin/vanta'
def edit(name,old,new,count=1):
 p=java/name;t=p.read_text()
 if t.count(old)!=count:raise RuntimeError(f'{name}: expected {count} matches, got {t.count(old)}: {old[:100]}')
 p.write_text(t.replace(old,new))
def test_edit(name,old,new,count=1):
 p=root/'app/src/test/java/com/ronin/vanta'/name;t=p.read_text()
 if t.count(old)!=count:raise RuntimeError(f'{name}: test anchor mismatch {t.count(old)}')
 p.write_text(t.replace(old,new))
shutil.copyfile(here/'ForgeCodeDiagnostics.java',java/'ForgeCodeDiagnostics.java')
shutil.copyfile(here/'ForgeDiagnosticRefresh.java',java/'ForgeDiagnosticRefresh.java')
shutil.copyfile(here/'Source179RegressionTest.java',root/'app/src/test/java/com/ronin/vanta/Source179RegressionTest.java')
shutil.copyfile(here/'Source179DeviceTest.java',root/'app/src/androidTest/java/com/ronin/vanta/Source179DeviceTest.java')
edit('ForgePlanState.java','178-direct-planning-v1','179-direct-source-v1')
edit('ForgeRequestPolicy.java','  static JSONObject options(','''  static boolean sourceFile(String phase) {
    return phase != null && phase.matches(".+_file_[0-9]+_part_[0-3](?:_expanded_1)?");
  }
  static JSONObject options(''')
edit('ForgeRequestPolicy.java','ProviderConfig.FEATHERLESS.equals(p.kind) && planning(phase)','ProviderConfig.FEATHERLESS.equals(p.kind) && (planning(phase) || sourceFile(phase))',2)
edit('ForgeRequestPolicy.java','.put("structured_plan", planning(phase));','.put("structured_plan", planning(phase)).put("source_file", sourceFile(phase));')
edit('ForgeRequestPolicy.java','''    return out;
  }
  static void apply''','''    if (sourceFile(phase)) out.put("reasoning_only_character_limit", GenerationWatchdog.SOURCE_REASONING_CHARS)
        .put("reasoning_only_time_limit_ms", GenerationWatchdog.SOURCE_REASONING_MS)
        .put("overall_time_limit_ms", GenerationWatchdog.SOURCE_TOTAL_MS);
    return out;
  }
  static void apply''')
edit('GenerationWatchdog.java','  static final long IDLE_MS','  static final class SourceExhausted extends Expired { SourceExhausted(String message) { super(message); } }\n  static final long SOURCE_REASONING_MS = 6 * 60_000L, SOURCE_REASONING_CHARS = 48000, SOURCE_TOTAL_MS = 30 * 60_000L;\n  static final long IDLE_MS')
edit('GenerationWatchdog.java','private final boolean planning;','private final boolean planning, sourceFile;')
edit('GenerationWatchdog.java','private boolean closed, planningExceeded;','private boolean closed, planningExceeded, sourceExceeded;')
edit('GenerationWatchdog.java','  GenerationWatchdog(Net.Call call) { this(call, false); }','''  GenerationWatchdog(Net.Call call) { this(call, false); }
  GenerationWatchdog(Net.Call call, String phase) {
    this(call, System::nanoTime, IDLE_MS, ForgeRequestPolicy.sourceFile(phase) ? SOURCE_TOTAL_MS : TOTAL_MS,
        ForgeRequestPolicy.planning(phase), ForgeRequestPolicy.sourceFile(phase));
  }''')
edit('GenerationWatchdog.java','''  GenerationWatchdog(Net.Call call, LongSupplier clock, long idleMs, long totalMs, boolean planning) {
    if (idleMs''','''  GenerationWatchdog(Net.Call call, LongSupplier clock, long idleMs, long totalMs, boolean planning) {
    this(call, clock, idleMs, totalMs, planning, false);
  }
  GenerationWatchdog(Net.Call call, LongSupplier clock, long idleMs, long totalMs, boolean planning, boolean sourceFile) {
    if (idleMs''')
edit('GenerationWatchdog.java','this.call = call; this.clock = clock; this.planning = planning;','this.call = call; this.clock = clock; this.planning = planning; this.sourceFile = sourceFile;')
edit('GenerationWatchdog.java','''    if (!expired.isEmpty()) call.abortTimedOutRequest''','''    else if (sourceFile && answerChars == 0 && reasoningChars > 0
        && (reasoningChars >= SOURCE_REASONING_CHARS || now - start >= TimeUnit.MILLISECONDS.toNanos(SOURCE_REASONING_MS))) {
      sourceExceeded = true;
      expired = "Source output budget: the model is actively reasoning but has returned no source-file answer. "
          + "The direct-output request was not honoured within the source allowance. This is not an idle connection; no larger same-model retry was bought.";
    }
    if (!expired.isEmpty()) call.abortTimedOutRequest''')
edit('GenerationWatchdog.java','planningExceeded ? new PlanningExhausted(text) : new Expired(text)','planningExceeded ? new PlanningExhausted(text) : sourceExceeded ? new SourceExhausted(text) : new Expired(text)')
edit('JobOperations.java','new GenerationWatchdog(c, ForgeRequestPolicy.planning(phase))','new GenerationWatchdog(c, phase)')
edit('JobEngine.java','''          if (failure instanceof GenerationWatchdog.PlanningExhausted) {''','''          if (failure instanceof GenerationWatchdog.SourceExhausted || failure instanceof ForgeOutputRecovery.SourceAnswerMissing) {
            diagnostic.put("category", "SOURCE_OUTPUT_BUDGET");
            store.document(id, "diagnostics", diagnostic);
            job.json.put("action_kind", "SOURCE_OUTPUT_BUDGET");
          }
          if (failure instanceof GenerationWatchdog.PlanningExhausted) {''')
edit('ForgeOutputRecovery.java','  private ForgeOutputRecovery() {}','''  private ForgeOutputRecovery() {}
  static final class SourceAnswerMissing extends java.io.IOException {
    SourceAnswerMissing(Throwable cause) {
      super("Source output budget: the provider confirmed its output limit without source text. "
          + "A larger same-model retry was not purchased. Completed files and validated continuation chunks remain saved; "
          + "inspect the request policy or explicitly select a compatible coding model.", cause);
    }
  }''')
edit('ForgeOutputRecovery.java','''      docs.put(phase + "_output_limit", new JSONObject().put("confirmed", true).put("partial", ""));
      try {''','''      docs.put(phase + "_output_limit", new JSONObject().put("confirmed", true).put("partial", ""));
      if (ForgeRequestPolicy.sourceFile(phase)) throw new SourceAnswerMissing(first);
      try {''')
edit('Errors.java','''    if (s.startsWith("generation watchdog:"))
      return "Generation stalled — checkpoint retained. Open Technical details before retrying.";''','''    if (s.startsWith("source output budget:"))
      return "No source-file answer returned — not an internet stall. Saved work retained; open Technical details.";
    if (s.startsWith("generation watchdog:") && s.contains("total time limit"))
      return "Generation reached its total time allowance — partial output retained. Open Technical details.";
    if (s.startsWith("generation watchdog:"))
      return "Generation stopped supplying meaningful output — checkpoint retained. Open Technical details.";''')
# Use bounded diagnostic facts, not repeated stack traces. Dependency lookups follow declarations, not filenames.
edit('ForgeAuthor.java','''              + index(existing)
              + (repair''','''              + index(existing)
              + "\\n" + ForgeCodeDiagnostics.inventory(existing, Math.min(7000, inputChars / 8))
              + (repair''')
edit('ForgeAuthor.java','+ bounded(diagnostics(log), part))','+ ForgeCodeDiagnostics.compact(log, path, part))')
edit('ForgeAuthor.java','''    if (needs != null) for (int i = 0; i < needs.length(); i++) preferred.add(needs.getString(i));''','''    if (needs != null) for (int i = 0; i < needs.length(); i++) preferred.add(needs.getString(i));
    preferred.addAll(ForgeCodeDiagnostics.related(project, target));''')
p=java/'ForgeAuthor.java';t=p.read_text();a=t.index('  static String diagnostics(String log) {');b=t.index('\n  static String bounded(',a)
t=t[:a]+'''  static String diagnostics(String log) {
    return ForgeCodeDiagnostics.compact(log, null, 18000);
  }
'''+t[b:];p.write_text(t)
edit('ForgeAuthor.java','''"Write the COMPLETE contents of exactly one source file. Return raw file text ONLY,"''','''"Write the COMPLETE contents of exactly one source file. Return the implementation directly, not an analysis of the task. Return raw file text ONLY,"''')
# Before an opaque saved kapt failure is sent to a model, obtain a bounded same-source diagnostic recompile.
edit('JobOperations.java','''    if (phase.equals("repair")) {
      // Never silently forward''','''    if (phase.equals("repair") && ForgeCodeDiagnostics.opaqueKapt(session.optString("log"))) {
      ForgeClient.BuildResult refreshed = ForgeDiagnosticRefresh.obtain(e, j, session, project, token, c);
      if (refreshed.success) {
        JSONObject r = BuildDiagnostics.inspect(refreshed.log);
        String issue = BuildDiagnostics.publicationIssue(r);
        if (!issue.isEmpty()) throw new Blocked(issue);
        ArtifactSigner.Result signed = ArtifactSigner.sign(e.context, refreshed.apk, c);
        c.check(); new JobFiles(e.context).save(j.id(), signed.apk);
        session.put("log", refreshed.log).put("build_report", r).put("run_id", refreshed.runId)
            .put("package_name", signed.packageName).put("certificate_sha256", signed.certificateSha256).put("phase", "publish");
        e.store.checkpoint(j, "build", session);
        e.savedResponse(j, "signed_apk", new JSONObject().put("package", signed.packageName).put("certificate", signed.certificateSha256));
        publishApk(e, j, session, c); return;
      }
      session.put("log_before_diagnostic_refresh", session.optString("log"));
      session.put("log", refreshed.log).put("diagnostic_run_id", refreshed.runId);
      e.store.checkpoint(j, "build", session);
      if (ForgeCodeDiagnostics.errors(refreshed.log).isEmpty())
        throw new Blocked("Compiler diagnosis: the worker still supplied no actionable Kotlin file/line errors. "
            + "The original failure and diagnostic recompile are saved. No AI repair was purchased; check the worker diagnostics update.");
      e.event(j, "compiler_diagnostics_ready", "FIXING", "Recovered Kotlin file/line diagnostics from the unchanged source. "
          + "The normal build remains failed; diagnostic-only task exclusions will never be used for APK publication.");
    }
    if (phase.equals("repair")) {
      // Never silently forward''')
# The independent diagnostic pending state must resume after process termination without purchasing a model response.
edit('JobEngine.java','''|| (build != null
                      && Arrays.asList("poll", "upload").contains(build.optString("phase")));''','''|| (build != null
                      && (Arrays.asList("poll", "upload").contains(build.optString("phase"))
                          || build.optBoolean("diagnostic_refresh_pending")));''')
# Existing tests whose contract explicitly changed now assert source request direct mode separately.
test_edit('Planning178RegressionTest.java','"analysis_compact_1","author_file_0_part_0",PREFIX+"_file_1_part_0_expanded_1"','"analysis_compact_1"')
p=root/'app/build.gradle';t=p.read_text().replace('versionCode 178','versionCode 179').replace("versionName '0.17.8'","versionName '0.17.9'");p.write_text(t)
# Exclude nested declarations from top-level fully qualified inventory; nested imports match their owner.
p=java/'ForgeCodeDiagnostics.java';t=p.read_text();t=t.replace('(?m)^\\\\s*(?:(?:public|private','(?m)^(?:(?:public|private');p.write_text(t)
print('Applied Android 0.17.9 source repair and compiler diagnostics. Original source, signing aliases and budgets preserved.')
