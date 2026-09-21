from pathlib import Path
import base64, hashlib, lzma, subprocess

root=Path('vanta/v0141')
parts=[root/f'patch_{i:02d}.b64' for i in range(4)]
encoded=''.join(p.read_text(encoding='utf-8').strip() for p in parts)
packed=base64.b64decode(encoded, validate=True)
expected='022c06c7e9a31b43b8235fe269f23f9d7a0907cda984b72b6256b72e678b5ae0'
actual=hashlib.sha256(packed).hexdigest()
if actual != expected:
    raise SystemExit(f'Android 0.14.1 packed patch checksum mismatch: {actual}')
patch=lzma.decompress(packed, memlimit=128*1024*1024)
raw_expected='78f6d3a8c1e1ca7b1044da5016fdcb60795214ba05d1c48ae211f030e1ac699f'
raw_actual=hashlib.sha256(patch).hexdigest()
if raw_actual != raw_expected:
    raise SystemExit(f'Android 0.14.1 raw patch checksum mismatch: {raw_actual}')
subprocess.run(['patch','--batch','--fuzz=0','-p4','-d','vanta/personal'], input=patch, check=True)
print('Applied Android 0.14.1 native tool dispatch and artifact completion gate.')
