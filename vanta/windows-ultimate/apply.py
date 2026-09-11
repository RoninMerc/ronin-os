from pathlib import Path
import base64, hashlib, lzma, subprocess

root = Path(__file__).resolve().parent
encoded = ''.join((root / f'patch_{i:02d}.txt').read_text().strip() for i in range(5))
packed = base64.b64decode(encoded, validate=True)
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
