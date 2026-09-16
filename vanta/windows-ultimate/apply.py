from pathlib import Path
import base64, hashlib, lzma, subprocess

root = Path(__file__).resolve().parent
# Each stored patch part is an independently Base64-padded fragment. Decode the
# fragments independently, then concatenate the original binary bytes. Never
# strip/rewrite padding across fragment boundaries: doing that changes bytes.
parts = [(root / f'patch_{i:02d}.txt').read_text().strip() for i in range(5)]
packed = b''.join(base64.b64decode(part, validate=True) for part in parts)
expected = 'f97a4d264af650f24ae574c1becdc308d0ca8ab607830850599bb882840ca4fa'
actual = hashlib.sha256(packed).hexdigest()
if actual != expected:
    raise SystemExit(f'Windows source patch checksum mismatch: {actual}')
patch = lzma.decompress(packed, memlimit=268435456)
if len(patch) != 396300:
    raise SystemExit(f'Unexpected patch length: {len(patch)}')
project = Path('ronin-vanta-windows')
subprocess.run(['git', 'apply', '--check', f'--directory={project}'], input=patch, check=True)
subprocess.run(['git', 'apply', f'--directory={project}'], input=patch, check=True)
print('Applied Ronin Vanta Windows 0.2 overhaul.')
