from pathlib import Path
import base64, hashlib, lzma, subprocess

root = Path(__file__).resolve().parent
compressed = base64.b64decode((root / "patch.txt").read_text().strip(), validate=True)
expected = "0d28fb42e05fd0173aa2bdd586245ffb3077d05361a014638ab249a03610d8a3"
actual = hashlib.sha256(compressed).hexdigest()
if actual != expected:
    raise SystemExit(f"Vanta 0.8 patch checksum mismatch: {actual}")
patch = lzma.decompress(compressed, memlimit=268435456)
subprocess.run(
    ["patch", "--batch", "--forward", "--fuzz=0", "-p1", "-d", str(root.parent / "personal")],
    input=patch,
    check=True,
)
print("Applied Ronin Vanta 0.8: simple Forge and low-refusal coding routing.")
