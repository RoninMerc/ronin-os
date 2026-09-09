from pathlib import Path
import base64, hashlib, lzma, subprocess
root=Path(__file__).resolve().parent
packed=base64.b64decode(''.join((root/f'update_{i:02d}.txt').read_text().strip() for i in range(2)),validate=True)
actual=hashlib.sha256(packed).hexdigest()
assert actual=='ae04b598eafe8573de481bbfdb299898b991cb3d679a0219c65bd6f6cd39b442',f'Patch checksum mismatch: {actual}'
patch=lzma.decompress(packed,memlimit=268435456)
assert len(patch)==64425
subprocess.run(['patch','--batch','--forward','--fuzz=0','-p1','-d',str(root.parent/'personal')],input=patch,check=True)
print('Applied Vanta 0.9.1: non-billable readiness, preflight XML/resource checks, bounded repairs, accurate recovery state and general regression tests.')
