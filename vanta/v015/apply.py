from pathlib import Path
import base64, hashlib, lzma, subprocess

encoded = Path('vanta/v015/patch.b64').read_text(encoding='utf-8').strip()
packed = base64.b64decode(encoded, validate=True)
packed_expected = 'ebb3182fd6175745116bb0afddab93c191566eee7f1380adcc9ba2791bfeed17'
if hashlib.sha256(packed).hexdigest() != packed_expected:
    raise SystemExit('Android 0.15 packed patch checksum mismatch')
patch = lzma.decompress(packed, memlimit=128 * 1024 * 1024)
patch_expected = '578db5db830128297aa3dd1c79a59accdb399cb014c75d5e824699ea0e96e251'
if hashlib.sha256(patch).hexdigest() != patch_expected:
    raise SystemExit('Android 0.15 patch checksum mismatch')
subprocess.run(['patch','--batch','--fuzz=0','-p1','-d','vanta/personal'], input=patch, check=True)
print(f'Applied Android 0.15 Forge integrity patch ({len(patch)} bytes).')
