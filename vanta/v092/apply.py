from pathlib import Path
import base64,hashlib,lzma,subprocess,runpy
root=Path(__file__).resolve().parent
raw=base64.b64decode(''.join((root/f'patch_{i}.txt').read_text().strip() for i in range(2)),validate=True)
assert hashlib.sha256(raw).hexdigest()=='7dc1413ae2d4ce624d92217bb574083d524072fae83034ed712f0376fa474d1e','Recovery patch checksum mismatch'
patch=lzma.decompress(raw,memlimit=268435456)
assert len(patch)==82755,'Recovery source length mismatch'
project=root.parent/'personal'
subprocess.run(['patch','--batch','--fuzz=0','-p1','-d',str(project)],input=patch,check=True)
subprocess.run(['java','-jar','/tmp/format.jar','--replace']+[str(p) for p in (project/'app/src').rglob('*.java')],check=True)
runpy.run_path(str(root/'attribution.py'),run_name='__main__')
runpy.run_path(str(root/'compatibility.py'),run_name='__main__')
runpy.run_path(str(root/'diagnostics.py'),run_name='__main__')
print('Applied Vanta 0.9.2 Forge recovery, provider consent, budgets and neutral regressions.')
