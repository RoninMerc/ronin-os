from pathlib import Path
import base64,hashlib,lzma,subprocess
root=Path(__file__).resolve().parent
raw=base64.b64decode(''.join((root/f'patch_{i}.txt').read_text().strip() for i in range(3)),validate=True)
assert hashlib.sha256(raw).hexdigest()=='ee228a447ba04673a38f4a9d6bc40f53fab43ca37b83b97d220321966ef6a728','Continuity source checksum mismatch'
patch=lzma.decompress(raw,memlimit=268435456)
assert len(patch)==128812,'Source patch length mismatch'
subprocess.run(['patch','--batch','--forward','--fuzz=0','-p1','-d',str(root.parent/'personal')],input=patch,check=True)
print('Applied conversation-scoped source capture, compiler/ZIP tools, persistent task routing and actual acceptance tests.')
