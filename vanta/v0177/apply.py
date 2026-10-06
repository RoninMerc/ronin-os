from pathlib import Path
import sys, shutil
root=Path(sys.argv[1]); here=Path(__file__).parent
base=root/'app/src/main/java/com/ronin/vanta'
def edit(path,old,new,count=1):
 p=root/path;t=p.read_text(encoding='utf-8')
 if t.count(old)!=count:raise RuntimeError(f'{path}: expected {count} anchors, got {t.count(old)}: {old[:100]}')
 p.write_text(t.replace(old,new),encoding='utf-8')
prefix='app/src/main/java/com/ronin/vanta/'
for name in ['ProviderStreamFailure.java','CapacityBackoff.java','ForgeCapacityRecovery.java']:
 shutil.copyfile(here/name,base/name)
edit(prefix+'GenerationProgress.java','  private Listener listener;','  private Listener listener;\n  private boolean outputObserved;')
edit(prefix+'GenerationProgress.java','events = reasoningCharacters = answerCharacters = 0;','events = reasoningCharacters = answerCharacters = 0; outputObserved = false;')
edit(prefix+'GenerationProgress.java','  synchronized void event() { events++; }','''  synchronized void event() { events++; }
  synchronized void observeEnvelope(JSONObject event) {
    if (ProviderStreamFailure.producedOutput(event)) outputObserved=true;
  }
  synchronized boolean observedOutput() { return outputObserved; }''')
edit(prefix+'GenerationProgress.java','''    if (text == null || text.isBlank()) return;''','''    if (text == null || text.isEmpty()) return;
    if ("answer".equals(kind) || "reasoning".equals(kind)) synchronized(this) { outputObserved=true; }
    if (text.isBlank()) return;''')
edit(prefix+'GenerationProgress.java',' .put("answer_characters", answerCharacters).put("output_stage", channel);',' .put("answer_characters", answerCharacters).put("output_stage", channel).put("output_observed",outputObserved);')
edit(prefix+'ChatProtocol.java','''      JSONObject event = new JSONObject(data);
      String type''','''      JSONObject event = new JSONObject(data);
      call.generation.observeEnvelope(event);
      String type''')
edit(prefix+'ChatProtocol.java','''        throw new IOException(
            "Provider stream failed: " + event.optString("error", event.optString("message", "")));''','''        throw ProviderStreamFailure.decode(event,call,text.length()>0);''')
edit(prefix+'Net.java','    private volatile long startedNanos, receivedBytes, lastByteNanos;','    private volatile long startedNanos, receivedBytes, lastByteNanos;\n    volatile long responseRetryAfterMs = -1;')
edit(prefix+'Net.java','      responseStatus = -1;','      responseStatus = -1; responseRetryAfterMs = -1;')
edit(prefix+'Net.java','      responseStatus = status;','      responseStatus = status;\n      responseRetryAfterMs = retryAfter(connection.getHeaderField("Retry-After"),System.currentTimeMillis());')
edit(prefix+'Net.java','      if (responseStatus >= 0) result.put("http_status", responseStatus);','      if (responseStatus >= 0) result.put("http_status", responseStatus);\n      if (responseRetryAfterMs >= 0) result.put("retry_after_ms", responseRetryAfterMs);')
edit(prefix+'ForgeRecovery.java','    TRANSIENT,','    TRANSIENT,\n    CAPACITY,')
edit(prefix+'ForgeRecovery.java','''    if (!inferenceOperation) return Kind.STOP;
    if (failure instanceof InferenceReadiness.Unavailable)''','''    if (!inferenceOperation) return Kind.STOP;
    if (failure instanceof ProviderStreamFailure)
      return ((ProviderStreamFailure)failure).capacityBeforeOutput() ? Kind.CAPACITY : Kind.STOP;
    if (failure instanceof InferenceReadiness.Unavailable)''')
edit(prefix+'ForgeRecoveryController.java','''    if (kind == ForgeRecovery.Kind.STOP) return false;
    live(e, j, call);''','''    if (kind == ForgeRecovery.Kind.STOP) return false;
    if (kind == ForgeRecovery.Kind.CAPACITY)
      return ForgeCapacityRecovery.recover(e,j,(ProviderStreamFailure)failure,call);
    live(e, j, call);''')
edit(prefix+'ForgeTransportRecovery.java','''          || t instanceof Net.HttpError''','''          || t instanceof Net.HttpError
          || t instanceof ProviderStreamFailure''')
edit(prefix+'JobOperations.java','''        ForgeRecoveryController.checking(e, j, in, p, m, inputChars, images, outputTokens, c);''','''        ForgeCapacityRecovery.beforeRequest(e,j,p,m,phase,c);
        ForgeRecoveryController.checking(e, j, in, p, m, inputChars, images, outputTokens, c);''')
edit(prefix+'JobEngine.java','''          if (failure instanceof Net.HttpError)
            diagnostic.put("http_status", ((Net.HttpError) failure).status);
          store.document(id, "diagnostics", diagnostic);''','''          if (failure instanceof Net.HttpError)
            diagnostic.put("http_status", ((Net.HttpError) failure).status);
          ProviderStreamFailure streamFailure=ProviderStreamFailure.find(failure);
          if (streamFailure!=null) diagnostic.put("provider_stream_error",streamFailure.diagnostic());
          store.document(id, "diagnostics", diagnostic);''')
edit(prefix+'JobEngine.java','''          boolean remote =
              store.document(j.id(), "remote") != null''','''          if (j.json.optLong("capacity_retry_at") > System.currentTimeMillis()
              && !j.json.optBoolean("request_started")) {
            j.event("capacity_wait_restored","WAITING_PROVIDER",ProgressState.transition(j.progress(),
                "WAITING FOR MODEL CAPACITY","Saved same-model capacity deadline retained. No new request was submitted during restart."));
            store.save(j);continue;
          }
          boolean remote =
              store.document(j.id(), "remote") != null''')
edit('app/build.gradle','versionCode 176','versionCode 177')
edit('app/build.gradle',"versionName '0.17.6'","versionName '0.17.7'")
# Use the corrected foreign-script upgrade fixture from 0.17.6 verification, not its contradictory legacy seed.
f=root/'app/src/androidTest/java/com/ronin/vanta/Vanta175UpgradeDeviceTest.java';t=f.read_text()
if 'project.remove(ManagedBuildFiles.RECEIPTS)' not in t:
 t=t.replace('    // A project-owned colliding script must remain intact after upgrade, not be overwritten.',
 '    project.remove(ManagedBuildFiles.RECEIPTS);\n    assertFalse(project.has(ManagedBuildFiles.RECEIPTS));\n    // A project-owned colliding script must remain intact after upgrade, not be overwritten.')
t=t.replace('assertEquals("0.17.6"','assertEquals("0.17.7"');f.write_text(t,encoding='utf-8')
shutil.copyfile(here/'Capacity177RegressionTest.java',root/'app/src/test/java/com/ronin/vanta/Capacity177RegressionTest.java')
shutil.copyfile(here/'Capacity177DeviceTest.java',root/'app/src/androidTest/java/com/ronin/vanta/Capacity177DeviceTest.java')
print('Applied 0.17.7: structured stream errors, semantic replay guard, durable approved same-model capacity waits and preserved source/checkpoint/model/call budgets.')
