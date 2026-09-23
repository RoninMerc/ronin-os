from pathlib import Path
import base64, hashlib, lzma, subprocess

encoded = Path('vanta/v017/patch.b64').read_text(encoding='utf-8').strip()
packed = base64.b64decode(encoded, validate=True)
packed_expected = '144eca0773c1539584fa6dcbf9851f7ef2b07f7b5b97ce90c2b10dccc63f580c'
if hashlib.sha256(packed).hexdigest() != packed_expected:
    raise SystemExit('Android 0.17 packed patch checksum mismatch')
patch = lzma.decompress(packed, memlimit=128 * 1024 * 1024)
patch_expected = '648f0932c83534f941dd9bd75e6c30ee26188f3f81fd2cab7473c209b436f53d'
if hashlib.sha256(patch).hexdigest() != patch_expected:
    raise SystemExit('Android 0.17 patch checksum mismatch')
subprocess.run(['patch','--batch','--fuzz=0','-p2','-d','vanta/personal'], input=patch, check=True)
print(f'Applied Android 0.17 Conversation Operations patch ({len(patch)} bytes).')
