from pathlib import Path
import base64, hashlib, lzma, subprocess
root=Path(__file__).resolve().parent
raw=base64.b64decode(''.join((root/f'release_081_{i}.txt').read_text().strip() for i in range(3)),validate=True)
assert hashlib.sha256(raw).hexdigest()=='c675d13a257827692f18d6ba9ae17ee1e0d3ee996b305534b377765631c865b6','Release patch checksum mismatch'
patch=lzma.decompress(raw,memlimit=268435456)
assert len(patch)==92231
subprocess.run(['patch','--batch','--forward','--fuzz=0','-p1','-d',str(root.parent/'personal')],input=patch,check=True)
print('Applied 0.8.1: simple Forge, automatic compilation handoff, metadata-based routing, complete catalogue navigation, real scrolling, dictation and regression tests.')
