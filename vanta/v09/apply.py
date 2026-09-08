from pathlib import Path
import base64,hashlib,lzma,subprocess
root=Path(__file__).resolve().parent
packed=base64.b64decode(''.join((root/f'patch{i:02d}.txt').read_text().strip() for i in range(4)),validate=True)
actual=hashlib.sha256(packed).hexdigest()
if actual!='4cd5ed8a9dd061ef01c4bfb3cc2cc98add937bc2a00efdb4bbb70b101af2004d': raise SystemExit('Source integrity failed: '+actual)
patch=lzma.decompress(packed,memlimit=268435456)
if len(patch)!=196286: raise SystemExit('Unexpected patch size')
subprocess.run(['patch','--batch','--forward','--fuzz=0','-p1','-d',str(root.parent/'personal')],input=patch,check=True)
print('Applied Vanta 0.9.0: checkpointed file authoring, build recovery, media validation and regression coverage.')
