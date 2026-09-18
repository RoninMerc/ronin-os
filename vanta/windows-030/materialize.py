from pathlib import Path
import base64, hashlib, zipfile, shutil

here = Path(__file__).resolve().parent
order = [*(f"source_{i:02d}.b64" for i in range(12)), "source_12a.b64", "source_12b.b64", "source_13.b64", "source_14.b64"]
payload = "".join((here / name).read_text(encoding="utf-8").strip() for name in order)
raw = base64.b64decode(payload, validate=True)
expected = "208e6f61603dbfbee95a4e84b52f2caf5c5d48c060ca006d0084f567cd92e9e4"
actual = hashlib.sha256(raw).hexdigest()
if actual != expected:
    raise SystemExit(f"Vanta 0.3 overlay checksum mismatch: {actual}")
zip_path = here / "overlay030.zip"
zip_path.write_bytes(raw)
root = Path("ronin-vanta-windows")
if not root.exists():
    raise SystemExit("Verified Windows baseline was not reconstructed")
with zipfile.ZipFile(zip_path) as z:
    z.extractall(root)
print(f"Vanta Windows 0.3 overlay verified and applied: {actual}")
