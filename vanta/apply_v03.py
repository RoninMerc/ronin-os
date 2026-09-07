from pathlib import Path
import base64
import hashlib
import io
import zipfile

ROOT = Path(__file__).resolve().parent
EXPECTED = "992d177cd293b231ccbec707782e1de25b2874ac653767351ab514f630920daf"
PARTS = 5

encoded = "".join((ROOT / f"v03_payload_{i:02d}.txt").read_text().strip() for i in range(PARTS))
raw = base64.b64decode(encoded, validate=True)
actual = hashlib.sha256(raw).hexdigest()
if actual != EXPECTED:
    raise SystemExit(f"Vanta v0.3 payload checksum mismatch: {actual}")

allowed = {
    "app/src/main/java/com/ronin/vanta/MainActivity.java",
    "app/src/main/java/com/ronin/vanta/ApiClient.java",
    "app/src/main/java/com/ronin/vanta/ModelInfo.java",
    "app/src/main/java/com/ronin/vanta/ModelRanker.java",
    "app/src/main/java/com/ronin/vanta/ForgeClient.java",
    "app/src/main/AndroidManifest.xml",
    "app/build.gradle",
}

with zipfile.ZipFile(io.BytesIO(raw)) as z:
    names = set(z.namelist())
    if names != allowed:
        raise SystemExit(f"Unexpected Vanta v0.3 payload paths: {sorted(names ^ allowed)}")
    for name in sorted(names):
        p = Path(name)
        if p.is_absolute() or ".." in p.parts:
            raise SystemExit(f"Unsafe payload path: {name}")
        target = ROOT / p
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(z.read(name))

print("Applied Ronin Vanta v0.3: Forge + Venice media fixes")
