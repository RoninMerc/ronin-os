from pathlib import Path
root=Path('vanta/personal')

def repl(rel, old, new, label, count=1):
    p=root/rel
    s=p.read_text(encoding='utf-8')
    n=s.count(old)
    if n!=count:
        raise SystemExit(f'{label}: expected {count}, found {n}')
    p.write_text(s.replace(old,new,count),encoding='utf-8')

repl('app/src/main/java/com/ronin/vanta/JobEngine.java',
'''      throw new IOException(
          "Artifact-required task cannot be marked complete until a compiled, retrieved, signed and"''',
'''      throw new JobOperations.Blocked(
          "Artifact-required task cannot be marked complete until a compiled, retrieved, signed and"''',
'completion guard blocked')

for rel, helper, missing in [
('app/src/androidTest/java/com/ronin/vanta/PipelineDeviceTest.java','json','if (url.contains("/contents/")) throw new Net.HttpError(404, "Not found");'),
('app/src/androidTest/java/com/ronin/vanta/FieldAcceptanceDeviceTest.java','f.json','if (url.contains("/contents/")) throw new Net.HttpError(404, "No earlier submission");'),
('app/src/androidTest/java/com/ronin/vanta/ProjectContinuityDeviceTest.java','json','if (url.contains("/contents/")) throw new Net.HttpError(404, "No earlier request");'),
('app/src/androidTest/java/com/ronin/vanta/FreshCompilerReplayTest.java','f.json','if (url.contains("/contents/")) throw new Net.HttpError(404, "No earlier submission");'),
]:
    repl(rel, missing,
         f'''if (url.contains("/contents/.github/workflows/vanta-forge-worker.yml"))
        return {helper}(new JSONObject().put("name", "vanta-forge-worker.yml"));
      {missing}''',
         rel+' workflow fixture')

repl('app/src/androidTest/java/com/ronin/vanta/ProjectContinuityDeviceTest.java',
'''      assertEquals("COMPLETED", h.h.waitDone(job.id(), 15000).status());
      assertEquals(h.threadId, captured.get().getString("conversation"));''',
'''      assertEquals("BLOCKED", h.h.waitDone(job.id(), 15000).status());
      assertTrue(e.store.get(job.id()).json.optString("error").contains("Artifact-required task"));
      assertEquals(h.threadId, captured.get().getString("conversation"));''',
'followup dispatch blocked')

repl('app/src/androidTest/java/com/ronin/vanta/TaskChoiceDeviceTest.java',
'''      assertEquals("COMPLETED", h.waitDone(job.id(), 15000).status());
      assertEquals(threadId, e.store.document(job.id(), "input").getString("conversation"));''',
'''      assertEquals("BLOCKED", h.waitDone(job.id(), 15000).status());
      assertTrue(e.store.get(job.id()).json.optString("error").contains("Artifact-required task"));
      assertEquals(threadId, e.store.document(job.id(), "input").getString("conversation"));''',
'forge dispatch blocked')

repl('app/src/androidTest/java/com/ronin/vanta/ProjectContinuityDeviceTest.java',
'''      assertEquals("COMPLETED", h.h.waitDone(exported.id(), 15000).status());
      JSONObject result = e.store.document(exported.id(), "result");
      assertEquals("application/zip", result.getString("mime"));''',
'''      assertEquals("BLOCKED", h.h.waitDone(exported.id(), 15000).status());
      JSONObject result = e.store.document(exported.id(), "archive_ready");
      assertEquals("application/zip", result.getString("mime"));''',
'zip fallback blocked')

repl('app/src/androidTest/java/com/ronin/vanta/FieldAcceptanceDeviceTest.java',
'        f.h.awaitText(activity, "Build Android APK");',
'        f.h.awaitText(activity, "Build APK — compile, test, sign & verify");',
'build label')

synthetic='''          JSONObject receipt = new JSONObject().put("fixture", true);
          engine.store.document(job.id(), "build_receipt", receipt);
          engine.store.document(
              job.id(),
              "build",
              new JSONObject().put("phase", "done").put("apk_uri", "content://fixture/scheduler.apk"));
          engine.completed(
              job,
              new JSONObject()
                  .put("build_receipt", receipt)
                  .put("uri", "content://fixture/scheduler.apk")
                  .put("mime", "application/vnd.android.package-archive")
                  .put("build_result", "Passed — compiled APK, signature verified")
                  .put("text", "Three phases persisted without relying on an immediate system job"));'''
repl('app/src/androidTest/java/com/ronin/vanta/AutomaticHandoverDeviceTest.java',
'''          engine.completed(
              job,
              new JSONObject()
                  .put(
                      "text", "Three phases persisted without relying on an immediate system job"));''',
synthetic,
'scheduler synthetic receipt')

synthetic2='''          JSONObject receipt = new JSONObject().put("fixture", true);
          engine.store.document(task.id(), "build_receipt", receipt);
          JSONObject build = engine.store.document(task.id(), "build");
          build.put("phase", "done").put("apk_uri", "content://fixture/recovered.apk");
          engine.store.document(task.id(), "build", build);
          engine.completed(
              task,
              new JSONObject()
                  .put("build_receipt", receipt)
                  .put("uri", "content://fixture/recovered.apk")
                  .put("mime", "application/vnd.android.package-archive")
                  .put("build_result", "Passed — compiled APK, signature verified")
                  .put("text", "Saved fixture resumed through explicit consent"));'''
repl('app/src/androidTest/java/com/ronin/vanta/ConnectionRecoveryDeviceTest.java',
'''          engine.completed(
              task, new JSONObject().put("text", "Saved fixture resumed through explicit consent"));''',
synthetic2,
'connection synthetic receipt')

print('Applied Vanta 0.15 QA compatibility review.')
