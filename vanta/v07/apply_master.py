from pathlib import Path
import base64, hashlib, lzma, subprocess
root=Path(__file__).resolve().parent
raw=base64.b64decode(''.join((root/f'patch_{i:02d}.txt').read_text().strip() for i in range(7)),validate=True)
expected='8076e11f8640982bae6d378b0f9ecfd7f55e52ebc92a7cebafc66b5e2496b5cb'
actual=hashlib.sha256(raw).hexdigest()
if actual!=expected: raise SystemExit(f'Source patch checksum mismatch: {actual}')
patch=lzma.decompress(raw,memlimit=268435456)
if len(patch)!=299095: raise SystemExit('Unexpected source patch length')
subprocess.run(['patch','--batch','--forward','--fuzz=0','-p1','-d',str(root.parent/'personal')],input=patch,check=True)
print('Applied real Vanta 0.7 source: registry, Prompt Architect, durable jobs, progress, UI and regressions.')
