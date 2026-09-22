from pathlib import Path
import base64, hashlib, lzma, subprocess

encoded=Path('vanta/v016/patch.b64').read_text(encoding='utf-8').strip()
packed=base64.b64decode(encoded, validate=True)
if hashlib.sha256(packed).hexdigest()!='8f40d28d823e12cbe20b73ced788da6cefc01472b22a8026a3d44449f3257232':
    raise SystemExit('Android 0.16 packed patch checksum mismatch')
patch=lzma.decompress(packed, memlimit=128*1024*1024)
if hashlib.sha256(patch).hexdigest()!='f6a2999cc27dec07a40aafd3dbeca396ffa2b9dc63680b1d6da1152e61d7a76d':
    raise SystemExit('Android 0.16 patch checksum mismatch')
subprocess.run(['patch','--batch','--fuzz=0','-p1','-d','vanta/personal'], input=patch, check=True)
print(f'Applied Android 0.16 output-limit resilience patch ({len(patch)} bytes).')
