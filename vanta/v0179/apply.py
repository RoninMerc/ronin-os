from pathlib import Path
import sys, shutil
root=Path(sys.argv[1]); here=Path(__file__).parent

def edit(rel, old, new, count=1):
    p=root/rel;t=p.read_text(encoding='utf-8')
    if t.count(old)!=count: raise RuntimeError(f'{rel}: expected {count} anchor, found {t.count(old)}: {old[:70]}')
    p.write_text(t.replace(old,new),encoding='utf-8')
base='app/src/main/java/com/ronin/vanta/'
edit(base+'ForgePlanState.java','178-direct-planning-v1','179-direct-source-v1')
edit(base+'ForgeRequestPolicy.java','  static JSONObject options(','''  static boolean sourceFile(String phase) {
    return phase != null && phase.matches(".+_file_[0-9]+_part_[0-3](?:_expanded_1)?");
  }
  static boolean directOutput(String phase) { return planning(phase) || sourceFile(phase); }
  static JSONObject options(''')
edit(base+'ForgeRequestPolicy.java','ProviderConfig.FEATHERLESS.equals(p.kind) && planning(phase)','ProviderConfig.FEATHERLESS.equals(p.kind) && directOutput(phase)',2)
edit(base+'ForgeRequestPolicy.java','.put("structured_plan", planning(phase));','''.put("structured_plan", planning(phase)).put("source_file", sourceFile(phase))
        .put("request_kind", planning(phase) ? "structured_plan" : sourceFile(phase) ? "source_file" : "other");''')
edit(base+'ForgeRequestPolicy.java','    return out;\n  }\n  static void apply','''    if (sourceFile(phase)) out.put("reasoning_only_character_limit", GenerationWatchdog.SOURCE_REASONING_CHARS)
        .put("reasoning_only_time_limit_ms", GenerationWatchdog.SOURCE_REASONING_MS)
        .put("empty_answer_expansion", false);
    return out;
  }
  static void apply''')
edit(base+'JobOperations.java','new GenerationWatchdog(c, ForgeRequestPolicy.planning(phase))','new GenerationWatchdog(c, phase)')
# A confirmed output-token limit with no file text is not useful progress and must not buy a larger repeated call.
p=root/(base+'ForgeOutputRecovery.java');t=p.read_text();a=t.index('    try {\n      return writer.write(phase, system, user, 8000);');b=t.index('\n  private static ForgeRecovery.InvalidOutput noPlanningAnswer',a)
t=t[:a]+'''    JSONObject previous = docs.get(phase + "_output_limit");
    if (previous != null && previous.optBoolean("confirmed") && previous.optString("partial").isBlank())
      throw new SourceOutputBudget(null);
    try {
      return writer.write(phase, system, user, 8000);
    } catch (ChatProtocol.Incomplete limit) {
      if (!limit.confirmedOutputLimit || limit.partial != null && !limit.partial.isBlank()) throw limit;
      docs.put(phase + "_output_limit", new JSONObject().put("confirmed", true).put("partial", "")
          .put("next_action", "inspect_or_select_model").put("policy_revision", ForgePlanState.REVISION));
      throw new SourceOutputBudget(limit);
    }
  }

  static final class SourceOutputBudget extends java.io.IOException {
    SourceOutputBudget(Throwable cause) {
      super("Source output budget: the provider confirmed its completion limit without source text. "
          + "No larger same-model source request was submitted. Validated files and continuation chunks are retained; "
          + "inspect diagnostics or explicitly choose a compatible coding model.", cause);
    }
  }
''' +t[b:];p.write_text(t,encoding='utf-8')
# Distinguish active-but-unproductive file output from a dead connection and total deadline.
edit(base+'Errors.java','    if (s.startsWith("generation watchdog:"))','''    if (s.startsWith("source output budget:"))
      return "No source text returned within the file-output allowance. Completed files are retained; open Technical details.";
    if (s.startsWith("generation watchdog: request exceeded its total time limit"))
      return "Generation reached its total time limit — not necessarily an idle connection. Checkpoint retained; open Technical details.";
    if (s.startsWith("generation watchdog:"))''')
edit(base+'JobEngine.java','          String message =\n              call.isCancelled()', '''          if (failure instanceof GenerationWatchdog.SourceExhausted
              || failure instanceof ForgeOutputRecovery.SourceOutputBudget) {
            diagnostic.put("category", "SOURCE_OUTPUT_BUDGET");
            store.document(id, "diagnostics", diagnostic);
            job.json.put("action_kind", "SOURCE_OUTPUT_BUDGET");
          } else if (failure instanceof GenerationWatchdog.TotalLimit) {
            diagnostic.put("category", "GENERATION_TOTAL_LIMIT");
            store.document(id, "diagnostics", diagnostic);
            job.json.put("action_kind", "GENERATION_TOTAL_LIMIT");
          }
          String message =
              call.isCancelled()''')
# Update old assertions only where the operation's intended policy changed. Retain all other checks.
edit('app/src/test/java/com/ronin/vanta/Planning178RegressionTest.java','normalChatAnalysisAndFileRequestsKeepTheirOriginalPolicy','normalChatAndAnalysisRequestsKeepTheirOriginalPolicy')
edit('app/src/test/java/com/ronin/vanta/Planning178RegressionTest.java','"chat","analysis","analysis_compact_1","author_file_0_part_0",PREFIX+"_file_1_part_0_expanded_1"','"chat","analysis","analysis_compact_1"')
edit('app/src/androidTest/java/com/ronin/vanta/Planning178DeviceTest.java','assertFalse(call.inferenceOptions.has("chat_template_kwargs"));return correct;','assertFalse(call.inferenceOptions.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"));return correct;')
edit('app/build.gradle','versionCode 178','versionCode 179');edit('app/build.gradle',"versionName '0.17.8'","versionName '0.17.9'")
edit('app/src/androidTest/java/com/ronin/vanta/Vanta175UpgradeDeviceTest.java','"0.17.8"','"0.17.9"')
for filename, dest in [('GenerationWatchdog.java',base),('Source179RegressionTest.java','app/src/test/java/com/ronin/vanta/'),('Source179DeviceTest.java','app/src/androidTest/java/com/ronin/vanta/')]:
    shutil.copyfile(here/filename,root/(dest+filename))
# Revise the two old expansion assertions to the new no-empty-replay contract, retaining all tests.
p=root/'app/src/test/java/com/ronin/vanta/OutputRecoveryTest.java';t=p.read_text()
a=t.index('  @Test\n  public void sourceReasoningOnlyLimitUsesOneBoundedExpansion');b=t.index('  @Test\n  public void invalidRepairCannotReuse',a)
t=t[:a]+"""  @Test
  public void sourceReasoningOnlyLimitDoesNotPurchaseAnEmptyExpansion() throws Exception {
    AtomicInteger n = new AtomicInteger(); Docs d = new Docs();
    try {
      ForgeAuthor.writeFile("f", "system", "file", (id,s,u,t) -> {
        n.incrementAndGet(); throw new ChatProtocol.Incomplete("Output limit reached", "", true);
      }, d); fail();
    } catch (ForgeOutputRecovery.SourceOutputBudget expected) {
      assertEquals(1, n.get()); assertNotNull(d.get("f_part_0_output_limit"));
      assertNull(d.get("f_part_0_expanded_1_output_limit"));
    }
  }
  @Test
  public void exhaustedReasoningOnlyFileStopsWithoutAnotherRequestOnResume() throws Exception {
    Docs d = new Docs(); AtomicInteger n = new AtomicInteger();
    ForgeAuthor.Writer writer = (id,s,u,t) -> { n.incrementAndGet(); throw new ChatProtocol.Incomplete("Output limit reached", "", true); };
    for (int attempt = 0; attempt < 2; attempt++) {
      try { ForgeAuthor.writeFile("f", "s", "u", writer, d); fail(); }
      catch (ForgeOutputRecovery.SourceOutputBudget expected) {
        assertEquals(ForgeRecovery.Kind.STOP, ForgeRecovery.classify(expected, true, false));
      }
    }
    assertEquals(1, n.get());
  }

"""+t[b:];p.write_text(t,encoding='utf-8')
print('Installed 0.17.9 file-output policy, checkpoint migration, watchdog classifications and tests.')
