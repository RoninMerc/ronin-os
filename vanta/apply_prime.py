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
print('Applied Ronin Vanta Prime v1 source')
