from pathlib import Path
import base64, hashlib, lzma, subprocess

root = Path('ronin-vanta-windows')
payload = Path(__file__).with_name('ops.b64').read_text(encoding='utf-8').strip()
packed = base64.b64decode(payload, validate=True)
packed_expected = '53dfd73152c11e8323e369a77e85fc051f676f489517d855206733f0c48ee138'
packed_actual = hashlib.sha256(packed).hexdigest()
if packed_actual != packed_expected:
    raise SystemExit(f'Windows 0.2.1 packed patch checksum mismatch: {packed_actual}')
patch = lzma.decompress(packed, memlimit=512 * 1024 * 1024)
patch_expected = 'dd1d7a88909086357c4ef88f37ba28ba2e0b75cab6fb4b581d0d1cfaf4be3abf'
patch_actual = hashlib.sha256(patch).hexdigest()
if patch_actual != patch_expected:
    raise SystemExit(f'Windows 0.2.1 Git patch checksum mismatch: {patch_actual}')
subprocess.run(['git', 'apply', '--check', f'--directory={root}'], input=patch, check=True)
subprocess.run(['git', 'apply', f'--directory={root}'], input=patch, check=True)
print(f'Applied exact Windows 0.2.1 Vanta Orchestrator Git patch ({len(patch)} bytes).')
