from pathlib import Path
import base64, hashlib, lzma, subprocess

encoded = Path('vanta/v016/patch.b64').read_text(encoding='utf-8').strip()
packed = base64.b64decode(encoded, validate=True)
packed_expected = '8ffebd25bbda353013695e62081828f88f85989896b7a4f7bd35325d87dbb1d3'
if hashlib.sha256(packed).hexdigest() != packed_expected:
    raise SystemExit('Android 0.16 packed patch checksum mismatch')
patch = lzma.decompress(packed, memlimit=128 * 1024 * 1024)
patch_expected = 'b4732aacf1b724c4ebecc850eb7d2b4bb656678a9f56ebbd44bc14bce61249ff'
if hashlib.sha256(patch).hexdigest() != patch_expected:
    raise SystemExit('Android 0.16 patch checksum mismatch')
subprocess.run(['patch','--batch','--fuzz=0','-p1','-d','vanta/personal'], input=patch, check=True)
print(f'Applied Android 0.16 conversation execution patch ({len(patch)} bytes).')
