from pathlib import Path
import base64,hashlib,json,zlib,os,subprocess
root=Path(__file__).resolve().parent
project=Path(os.environ.get('VANTA_PROJECT',str(root.parent/'personal')))
subprocess.run(['java','-jar','/tmp/format.jar','--replace']+[str(p) for p in (project/'app/src').rglob('*.java')],check=True)
raw=base64.b64decode(''.join((root/f'choice_{i:02d}.txt').read_text().strip() for i in range(2)),validate=True)
assert hashlib.sha256(raw).hexdigest()=='4ada9eb1e6ba90e589e1b7d3d0873644c8ce9c9aef1a857cc6b32172117c1244','Task review transport checksum mismatch'
data=zlib.decompress(raw)
assert len(data)==94700
assert hashlib.sha256(data).hexdigest()=='53b85d24ca3ced4bf8e669a25292eb559da8bb72a5e03520ca427a2cc9dcb27d','Task review source checksum mismatch'
for change in json.loads(data):
 relative=Path(change['path']);assert not relative.is_absolute() and '..' not in relative.parts
 target=project/relative;original=target.read_bytes() if target.exists() else None
 assert (hashlib.sha256(original).hexdigest() if original is not None else None)==change['before'],f'Task review baseline mismatch: {relative}'
 if 'text' in change:content=change['text']
 else:
  lines=original.decode().splitlines(keepends=True)
  for start,end,replacement in reversed(change['changes']):lines[start:end]=[replacement]
  content=''.join(lines)
 assert hashlib.sha256(content.encode()).hexdigest()==change['after'],f'Task review result mismatch: {relative}'
 target.parent.mkdir(parents=True,exist_ok=True);target.write_text(content)
 print('Applied',relative)
# Read the real previous font setting rather than dereferencing a shell-drain helper returning void.
p=project/'app/src/androidTest/java/com/ronin/vanta/TaskChoiceDeviceTest.java'
s=p.read_text();old='String scale = MasterDeviceTest.shell("settings get system font_scale").trim();'
assert s.count(old)==1
s=s.replace(old,'String scale = Float.toString(android.provider.Settings.System.getFloat(h.ctx().getContentResolver(), android.provider.Settings.System.FONT_SCALE, 1f));');p.write_text(s)
print('One conversation, task-aware review, verified low-refusal choices and explicit provider approval integrated.')
