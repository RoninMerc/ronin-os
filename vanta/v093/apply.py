from pathlib import Path
import base64,hashlib,lzma,os,subprocess,runpy
root=Path(__file__).resolve().parent
raw=base64.b64decode(''.join((root/f'patch_{n}.txt').read_text().strip() for n in range(6)),validate=True)
assert hashlib.sha256(raw).hexdigest()=='f9a5542a8f8793ea0b234b3f30bb538a40693c55639c123b505db221b33dd6c8','Vanta 0.9.3 patch checksum mismatch'
patch=lzma.decompress(raw,memlimit=268435456)
assert len(patch)==203499,'Unexpected Vanta source length'
project=Path(os.environ.get('VANTA_PROJECT',str(root.parent/'personal')))
subprocess.run(['patch','--batch','fuzz=0','-p1','-d',str(project)],input=patch,check=True)
os.environ['VANTA_PROJECT']=str(project)
for script in ['review.py','runtime_followup.py','checkpoint_review.py','ui_review.py']:
    runpy.run_path(str(root/script),run_name='__main__')
subprocess.run(['java','-jar','/tmp/format.jar','--replace']+[str(p) for p in (project/'app/src').rglob('*.java')],check=True)
runpy.run_path(str(root/'job_publication.py'),run_name='__main__')
runpy.run_path(str(root/'scheduler_ownership.py'),run_name='__main__')
runpy.run_path(str(root/'network_recovery.py'),run_name='__main__')
runpy.run_path(str(root/'config_safety.py'),run_name='__main__')
test=project/'app/src/androidTest/java/com/ronin/vanta/BackgroundDeviceTest.java'
s=test.read_text();old='      reschedule(job.id());\n      waitState(job.id(), "WAITING_PROVIDER");'
assert s.count(old)==1
s=s.replace(old,'      // Connectivity restoration itself must resume the queued request; no manual wake.\n      waitState(job.id(), "WAITING_PROVIDER");',1)
test.write_text(s)
runpy.run_path(str(root/'final_evidence.py'),run_name='__main__')
print('Applied Vanta 0.9.3: build foundation, preserved custom configuration, diagnostic-aware repair, typed output recovery, atomic startup, foreground ownership, connectivity resumption and real compiler regressions.')
