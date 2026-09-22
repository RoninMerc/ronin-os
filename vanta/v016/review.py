from pathlib import Path
root=Path('vanta/personal')

def repl(rel, old, new, label, count=1):
    p=root/rel
    s=p.read_text(encoding='utf-8')
    n=s.count(old)
    if n!=count:
        raise SystemExit(f'{label}: expected {count}, found {n}')
    p.write_text(s.replace(old,new,count),encoding='utf-8')

repl('app/src/main/java/com/ronin/vanta/MainActivity.java',
'''    String scope = draftKey();
    boolean vision =''',
'''    boolean sourceArchive = req == ATTACH && isSourceArchive(uri);
    String scope = draftKey();
    boolean vision =''',
'detect source ZIP from normal attachment picker')

repl('app/src/main/java/com/ronin/vanta/MainActivity.java',
'''                .put("uri", destination)
                .put("kind", req == IMPORT ? "project" : "attachment")
                .put("scope", scope)''',
'''                .put("uri", destination)
                .put("kind", (req == IMPORT || sourceArchive) ? "project" : "attachment")
                .put("scope", scope)''',
'route source ZIP to project importer')

repl('app/src/main/java/com/ronin/vanta/MainActivity.java',
'''        if (req == IMPORT) {
          String destinationThread = prefs.getString("pending_project_import_thread", "");
          prefs.edit().remove("pending_project_import_thread").apply();
          if (!destinationThread.isEmpty())
            in.put("conversation", destinationThread)
                .put("request", "Import this source project into the conversation.")
                .put("unified", true);
        }
        startDurable(
            "file_import",
            req == IMPORT ? "Import source project" : "Prepare attachment",''',
'''        if (req == IMPORT) {
          String destinationThread = prefs.getString("pending_project_import_thread", "");
          prefs.edit().remove("pending_project_import_thread").apply();
          if (!destinationThread.isEmpty())
            in.put("conversation", destinationThread)
                .put("request", "Import this source project into the conversation.")
                .put("unified", true);
        } else if (sourceArchive && mode.equals("chat") && thread != null) {
          in.put("conversation", thread.optString("id"))
              .put("request", "Use this source ZIP as the current project context.")
              .put("unified", true);
        }
        startDurable(
            "file_import",
            (req == IMPORT || sourceArchive) ? "Import source project" : "Prepare attachment",''',
'link normal ZIP import to active conversation')

repl('app/src/main/java/com/ronin/vanta/MainActivity.java',
'''  private void watch(EditText field, Runnable action) {''',
'''  private boolean isSourceArchive(Uri uri) {
    try {
      String mime = getContentResolver().getType(uri);
      if ("application/zip".equalsIgnoreCase(mime)
          || "application/x-zip-compressed".equalsIgnoreCase(mime)
          || "application/x-zip".equalsIgnoreCase(mime)) return true;
      try (android.database.Cursor cursor =
          getContentResolver()
              .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
        if (cursor != null && cursor.moveToFirst()) {
          String name = cursor.getString(0);
          return name != null && name.toLowerCase(Locale.ROOT).endsWith(".zip");
        }
      }
    } catch (Exception ignored) {
    }
    return false;
  }

  private void watch(EditText field, Runnable action) {''',
'ZIP detection helper')

repl('app/src/main/java/com/ronin/vanta/MainActivity.java',
'''    addToolTile(inputs, "file", "File", () -> pickAttachment(false));''',
'''    addToolTile(inputs, "file", "File / ZIP", () -> pickAttachment(false));''',
'label normal picker for ZIP support')

repl('app/src/main/java/com/ronin/vanta/MainActivity.java',
'''            "text/*",
            "application/json",
            "application/xml",''',
'''            "text/*",
            "application/json",
            "application/zip",
            "application/x-zip-compressed",
            "application/x-zip",
            "application/xml",''',
'allow ZIP MIME types in normal picker')

test_insert='''
  @Test
  public void normalFilePickerZipImportsProjectAndPreservesPrompt() throws Exception {
    File dir = new File(h.h.ctx().getCacheDir(), "shared");
    dir.mkdirs();
    File file = new File(dir, "normal-picker-source.zip");
    try (OutputStream out = new FileOutputStream(file)) {
      out.write(asset("source.zip"));
    }
    Uri inputUri =
        androidx.core.content.FileProvider.getUriForFile(
            h.h.ctx(), h.h.ctx().getPackageName() + ".files", file);
    try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
      activity.onActivity(
          host -> {
            try {
              ((EditText) TaskChoiceDeviceTest.field(host, "prompt")).setText("Build this APK");
              Intent data =
                  new Intent()
                      .setData(inputUri)
                      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
              host.onActivityResult(7004, Activity.RESULT_OK, data);
            } catch (Exception failure) {
              throw new AssertionError(failure);
            }
          });
      long until = SystemClock.elapsedRealtime() + 20000;
      VantaJob imported = null;
      while (SystemClock.elapsedRealtime() < until) {
        try {
          imported = last("file_import");
          if (imported != null && !imported.active()) break;
        } catch (Exception ignored) {
        }
        Thread.sleep(80);
      }
      assertNotNull("Normal File/ZIP picker creates a durable import", imported);
      assertEquals("COMPLETED", e.store.get(imported.id()).status());
      assertEquals(
          ProjectArchive.fingerprint(source("archive")),
          ProjectArchive.fingerprint(
              e.store.document(imported.id(), "result").getJSONObject("project")));
      AtomicReference<String> draft = new AtomicReference<>();
      activity.onActivity(
          host -> {
            try {
              draft.set(((EditText) TaskChoiceDeviceTest.field(host, "prompt")).getText().toString());
            } catch (Exception failure) {
              throw new AssertionError(failure);
            }
          });
      assertEquals("Build this APK", draft.get());
    } finally {
      file.delete();
    }
  }

'''
p=root/'app/src/androidTest/java/com/ronin/vanta/ProjectContinuityDeviceTest.java'
s=p.read_text(encoding='utf-8')
marker='''  @Test
  public void importedExportZipBuildsThroughTheSameModelFreeCompilerPath() throws Exception {'''
if s.count(marker)!=1:
    raise SystemExit('normal ZIP picker regression insertion point missing')
p.write_text(s.replace(marker,test_insert+marker,1),encoding='utf-8')

print('Applied Vanta 0.16 normal ZIP attachment/import review.')
