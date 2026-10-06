from pathlib import Path
import base64, hashlib, io, zipfile

ROOT = Path(__file__).resolve().parent
EXPECTED = '4449994167ace8b76bf934175619e3081d122f33391f23f9ddd46249d49cba37'
PARTS = 6
encoded = ''.join((ROOT / f'prime9_{i:02d}.txt').read_text().strip() for i in range(PARTS))
raw = base64.b64decode(encoded, validate=True)
actual = hashlib.sha256(raw).hexdigest()
if actual != EXPECTED:
    raise SystemExit(f'Prime payload checksum mismatch: {actual}')
allowed = {
    'app/build.gradle','app/src/main/AndroidManifest.xml',
    'app/src/main/java/com/ronin/vanta/ApiClient.java','app/src/main/java/com/ronin/vanta/ForgeClient.java',
    'app/src/main/java/com/ronin/vanta/MainActivity.java','app/src/main/java/com/ronin/vanta/ModelInfo.java',
    'app/src/main/java/com/ronin/vanta/ModelRanker.java','app/src/main/java/com/ronin/vanta/ProviderConfig.java',
    'app/src/main/java/com/ronin/vanta/SecureVault.java','app/src/main/java/com/ronin/vanta/VoiceCatalog.java',
    'app/src/main/res/drawable/ic_launcher.xml','app/src/main/res/values/strings.xml','app/src/main/res/values/styles.xml',
    'app/src/main/res/xml/data_extraction_rules.xml','build.gradle','gradle.properties','settings.gradle'
}
with zipfile.ZipFile(io.BytesIO(raw)) as z:
    names = set(z.namelist())
    if names != allowed:
        raise SystemExit('Unexpected Prime payload paths: ' + str(sorted(names ^ allowed)))
    for name in sorted(names):
        p = Path(name)
        if p.is_absolute() or '..' in p.parts:
            raise SystemExit('Unsafe payload path: ' + name)
        target = ROOT / p
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(z.read(name))

# Deterministic Prime quality corrections discovered by the API-36 QA gate.
def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected one match, found {count}')
    return text.replace(old, new, 1)

main_path = ROOT / 'app/src/main/java/com/ronin/vanta/MainActivity.java'
main = main_path.read_text()
main = replace_once(
    main,
    'items[i]=(i+1)+". ["+ModelRanker.tier(m,wantedType)+"] "+m.displayName()+"\n"+ModelRanker.capabilitySummary(m);'.replace('\\n', '\n'),
    'items[i]=(i+1)+". ["+ModelRanker.tier(m,wantedType)+"] "+m.displayName()+"\\n"+ModelRanker.capabilitySummary(m);',
    'model picker capability line'
)
main = replace_once(
    main,
    '"Vanta is designed to improve as providers and models change. Live discovery keeps the catalogue moving; capability metadata drives ranking; manual selection always remains yours."',
    '"Vanta continuously adapts as providers and models change. Connected catalogues refresh automatically every 12 hours; live capability metadata drives ranking; manual selection always remains yours."',
    'adaptive home copy'
)
main = replace_once(
    main,
    '    private void refreshRankingMetadataIfNeeded() {\n        if (prefs.getBoolean("ranking_metadata_prime_v1", false)) return;\n        io.submit(() -> {\n            boolean refreshedAny = false;',
    '    private void refreshRankingMetadataIfNeeded() {\n        long now = System.currentTimeMillis();\n        long last = prefs.getLong("model_catalog_last_refresh", 0L);\n        if (now - last < 12L * 60L * 60L * 1000L) return;\n        io.submit(() -> {\n            boolean refreshedAny = false;',
    'adaptive catalog schedule'
)
main = replace_once(
    main,
    'prefs.edit().putBoolean("ranking_metadata_prime_v1", true).apply();',
    'prefs.edit().putLong("model_catalog_last_refresh", System.currentTimeMillis()).putBoolean("ranking_metadata_prime_v1", true).apply();',
    'adaptive catalog timestamp'
)
main = replace_once(
    main,
    'modelCache.put(p.id, fresh); saveModelCache(p.id);\n                runOnUiThread(() -> { setBusy(false); toast("Synced " + fresh.size() + " models from " + p.name);',
    'modelCache.put(p.id, fresh); saveModelCache(p.id); prefs.edit().putLong("model_catalog_last_refresh", System.currentTimeMillis()).apply();\n                runOnUiThread(() -> { setBusy(false); toast("Synced " + fresh.size() + " models from " + p.name);',
    'manual sync timestamp'
)
main_path.write_text(main)

gradle_props = ROOT / 'gradle.properties'
gp = gradle_props.read_text()
gp = replace_once(gp, 'android.useAndroidX=false', 'android.useAndroidX=true', 'AndroidX current default')
gradle_props.write_text(gp)

print('Applied Ronin Vanta Prime v1 source + deterministic QA corrections')
