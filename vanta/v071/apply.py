from pathlib import Path
import base64,hashlib,lzma,subprocess
root=Path(__file__).resolve().parent
raw=base64.b64decode((root/'patch.txt').read_text().strip(),validate=True)
expected='f1503497ac96579c47acab01392da814a6dd44145a2cf654852f4d570ee43842'
actual=hashlib.sha256(raw).hexdigest()
if actual!=expected: raise SystemExit(f'0.7.1 patch checksum mismatch: {actual}')
patch=lzma.decompress(raw,memlimit=268435456)
subprocess.run(['patch','--batch','--forward','--fuzz=0','-p1','-d',str(root.parent/'personal')],input=patch,check=True)
print('Applied Vanta 0.7.1: scrollable model panel, Prompt UX improvements and picker latency reduction.')
