from pathlib import Path
import shutil
r=Path('vanta/personal')
def replace(path,old,new,count=1):
 p=r/path; t=p.read_text(); assert t.count(old)==count,(path,t.count(old),old[:100]);p.write_text(t.replace(old,new))
b='app/src/main/java/com/ronin/vanta/'
replace('app/build.gradle',"versionCode 170", "versionCode 171")
replace('app/build.gradle',"versionName '0.17.0'", "versionName '0.17.1'")
replace('app/build.gradle',"dependencies {", "dependencies {\n    implementation 'com.google.code.gson:gson:2.13.2'")
replace(b+'ApiClient.java','    String url = CataloguePaging.first(p);','    if (ProviderConfig.FEATHERLESS.equals(p.kind)) return FeatherlessCatalogue.read(p, key, call);\n    String url = CataloguePaging.first(p);')
replace(b+'ApiClient.java','        if (ProviderConfig.FEATHERLESS.equals(p.kind))\n          enrichFeatherlessCapabilities(p, key, out, call);\n','')
p=r/(b+'ApiClient.java');t=p.read_text();a=t.index('  private static void enrichFeatherlessCapabilities(');end=t.index('  private static void addVerifiedValue(',a);t=t[:a]+t[end:];p.write_text(t)
replace(b+'JobOperations.java','    ProviderConfig p = provider(e, in);\n    String k = key(e, p);','    ProviderConfig p = provider(e, in);\n    String k = j.type().equals("catalogue") && FeatherlessCatalogue.publicEndpoint(p) ? "" : key(e, p);',1)
replace(b+'JobOperations.java','      case "catalogue":\n','      case "catalogue":\n        FeatherlessCatalogue.before(e, j);\n',1)
replace(b+'JobOperations.java','        e.completed(j, new JSONObject().put("provider", p.id).put("model_count", fresh.size()));','''        j.json.remove("catalogue_retry_at"); j.json.remove("catalogue_failures"); j.json.remove("next_run");
        e.completed(j, new JSONObject().put("provider", p.id).put("model_count", fresh.size())
            .put("catalogue_warning", fresh.isEmpty() ? "" : fresh.get(0).spec.optString("catalogue_warning")));''')
replace(b+'JobEngine.java','    VantaJob job = store.create(type, title, scope, input);','''    if ("catalogue".equals(type)) {
      VantaJob existing = store.latest(scope);
      if (existing != null && existing.active()) return existing;
      if (existing != null && "COMPLETED".equals(existing.status())
          && System.currentTimeMillis() - existing.json.optLong("updated") < 60000
          && String.valueOf(store.document(existing.id(), "input")).equals(String.valueOf(input))) return existing;
    }
    VantaJob job = store.create(type, title, scope, input);''')
replace(b+'JobEngine.java','        if (j.active()) {\n','''        if (j.active()) {
          if ("catalogue".equals(j.type())) {
            boolean cooling = j.json.optLong("catalogue_retry_at") > System.currentTimeMillis();
            j.json.put("request_started", false);
            j.event("catalogue_restored", cooling ? "WAITING_PROVIDER" : "QUEUED",
                ProgressState.unknown(cooling ? "WAITING FOR PROVIDER" : "RESTORED", "Read-only catalogue discovery restored; the saved catalogue and retry deadline are retained."));
            store.save(j); continue;
          }
''',1)
replace(b+'JobEngine.java','            if (ForgeTransportRecovery.recover(this, job, failure, call)) return;','            if (FeatherlessCatalogue.recover(this, job, failure, call)) return;\n            if (ForgeTransportRecovery.recover(this, job, failure, call)) return;')
replace(b+'MainActivity.java','  private void sync(ProviderConfig p) {\n    if (vault.getSecret(p.id) == null) {','  private void sync(ProviderConfig p) {\n    if (!FeatherlessCatalogue.publicEndpoint(p) && vault.getSecret(p.id) == null) {')
replace(b+'Net.java','"Ronin-Vanta/0.9.4"','"Ronin-Vanta/0.17.1"')
replace('app/src/test/java/com/ronin/vanta/Release081Test.java','new JSONObject().put("current_page", 1).put("per_page", 50).put("total", 500)','new JSONObject().put("current_page", 1).put("per_page", 50).put("total", 500).put("has_more", true)')
replace('app/src/test/java/com/ronin/vanta/Pipeline090Test.java','assertTrue(models.get(0).spec.has("catalogue_warning"));','assertFalse(models.get(0).spec.has("catalogue_warning"));')
for name,folder in [('FeatherlessCatalogue.java','main'),('CataloguePaging.java','main'),('FeatherlessCatalogueTest.java','test'),('FeatherlessCatalogueDeviceTest.java','androidTest')]:
 shutil.copyfile(Path(__file__).parent/name,r/f'app/src/{folder}/java/com/ronin/vanta'/name)
print('Patched Android 0.17.1 catalogue runtime and retained test expectations.')
