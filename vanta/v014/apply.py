from pathlib import Path
import base64, hashlib, lzma, subprocess

subprocess.run(['python3','vanta/v013/apply.py'], check=True)
encoded=Path('vanta/v014/patch.b64').read_text(encoding='utf-8').strip()
packed=base64.b64decode(encoded, validate=True)
expected='3b76cfd3a7eed30271cd45e1c20b06c508dbd4acd76658ffc02c44591f9edb0e'
actual=hashlib.sha256(packed).hexdigest()
if actual != expected:
    raise SystemExit(f'Android 0.14 patch checksum mismatch: {actual}')
patch=lzma.decompress(packed, memlimit=128*1024*1024)
subprocess.run(['patch','--batch','--fuzz=0','-p1','-d','vanta/personal'], input=patch, check=True)
print('Applied Android 0.14 Agentic Forge Build Center upgrade.')
