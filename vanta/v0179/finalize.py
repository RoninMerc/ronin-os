from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else 'source')
p=root/'app/src/androidTest/java/com/ronin/vanta/Planning178DeviceTest.java'
t=p.read_text();old='assertFalse(call.inferenceOptions.has("chat_template_kwargs"));return correct;'
assert t.count(old)==1
t=t.replace(old,'assertFalse(call.inferenceOptions.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"));assertTrue(call.generationPolicy.getBoolean("source_file"));return correct;');p.write_text(t)
p=root/'app/src/main/java/com/ronin/vanta/JobOperations.java';t=p.read_text()
needle='''    if (phase.equals("repair") && ForgeCodeDiagnostics.opaqueKapt(session.optString("log"))) {'''
assert t.count(needle)==1
t=t.replace(needle,'''    if (phase.equals("repair") && session.optBoolean("diagnostic_refresh_unactionable"))
      throw new Blocked("Compiler diagnosis: the saved diagnostic run still has no actionable source errors. "
          + "No repeated diagnostic build or AI repair was purchased. The original source and logs remain available.");
'''+needle)
old='''      if (ForgeCodeDiagnostics.errors(refreshed.log).isEmpty())
        throw new Blocked("Compiler diagnosis: the worker still supplied no actionable Kotlin file/line errors. "
            + "The original failure and diagnostic recompile are saved. No AI repair was purchased; check the worker diagnostics update.");'''
assert t.count(old)==1
t=t.replace(old,'''      if (ForgeCodeDiagnostics.errors(refreshed.log).isEmpty()) {
        session.put("diagnostic_refresh_unactionable", true);
        e.store.checkpoint(j, "build", session);
        throw new Blocked("Compiler diagnosis: the worker still supplied no actionable Kotlin file/line errors. "
            + "The original failure and diagnostic recompile are saved. No AI repair was purchased; check the worker diagnostics update.");
      }''');p.write_text(t)
p=root/'app/src/main/java/com/ronin/vanta/ForgeCodeDiagnostics.java';t=p.read_text();old=r')):(?:\\((\\d+)';new=r')):\\s*(?:\\((\\d+)'
assert t.count(old)==1,(old,t[:750]);p.write_text(t.replace(old,new))
p=root/'app/src/test/java/com/ronin/vanta/Source179RegressionTest.java';t=p.read_text();old='JSONObject p=project("app/src/main/java/qa/A.kt","package qa; class A {}");String first=ForgeDiagnosticRefresh.key(p,4);';new='JSONObject p=Pipeline090Test.project();String first=ForgeDiagnosticRefresh.key(p,4);'
assert t.count(old)==1;p.write_text(t.replace(old,new))
p=root/'app/src/androidTest/java/com/ronin/vanta/Vanta175UpgradeDeviceTest.java';t=p.read_text();assert t.count('assertEquals("0.17.8",')==1;p.write_text(t.replace('assertEquals("0.17.8",','assertEquals("0.17.9",'))
print('Finalised source recovery, compiler coordinates and the exact 0.17.9 upgrade-version assertion.')
