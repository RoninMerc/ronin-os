from pathlib import Path
import base64, hashlib, lzma, subprocess

encoded=Path('vanta/v017/patch.b64').read_text(encoding='utf-8').strip()
packed=base64.b64decode(encoded, validate=True)
if hashlib.sha256(packed).hexdigest()!='b9e90a2e5658fdd30d6321bec76c9ff956f29137afe855a5e5deab2e9290d670':
    raise SystemExit('Android 0.17 packed patch checksum mismatch')
patch=lzma.decompress(packed, memlimit=128*1024*1024)
if hashlib.sha256(patch).hexdigest()!='2fd644c38b8310d69aa2bb1439e21fe4936c946278f29d2acd98352e30d587f3':
    raise SystemExit('Android 0.17 patch checksum mismatch')
subprocess.run(['patch','--batch','--fuzz=0','-p1','-d','vanta/personal'], input=patch, check=True)
print(f'Applied Android 0.17 direct Forge patch ({len(patch)} bytes).')
