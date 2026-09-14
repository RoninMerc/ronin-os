from pathlib import Path
import base64,hashlib,json,zlib,os,runpy
root=Path(__file__).resolve().parent
project=Path(os.environ.get('VANTA_PROJECT',str(root.parent/'personal')))
raw=base64.b64decode(''.join((root/f'part{i:02d}.txt').read_text().strip() for i in range(2)),validate=True)
assert hashlib.sha256(raw).hexdigest()=='71d39a5a1321fd8e8ccabcc87f4846f67ea7a32330edf0aa871157286a3b626e','Pending source checksum mismatch'
data=zlib.decompress(raw)
assert hashlib.sha256(data).hexdigest()=='e809fb2135b9ad13564450d7c9874804cfbd3ea040f6e1b96ec313159a30c934','Decoded source checksum mismatch'
for change in json.loads(data):
 relative=Path(change['path'])
 assert not relative.is_absolute() and '..' not in relative.parts
 target=project/relative
 original=target.read_bytes() if target.exists() else None
 assert (hashlib.sha256(original).hexdigest() if original is not None else None)==change['before'],f'Baseline mismatch: {relative}'
 if 'text' in change: content=change['text']
 else:
  lines=original.decode().splitlines(keepends=True)
  for start,end,replacement in reversed(change['changes']):lines[start:end]=[replacement]
  content=''.join(lines)
 assert hashlib.sha256(content.encode()).hexdigest()==change['after'],f'Result mismatch: {relative}'
 target.parent.mkdir(parents=True,exist_ok=True);target.write_text(content)
 print('Applied',relative)
for name in ['review.py','polish.py']:
 if (root/name).exists():runpy.run_path(str(root/name),run_name='__main__')
