from pathlib import Path
import base64,hashlib,json,zlib,os
root=Path(__file__).resolve().parent
project=Path(os.environ.get('VANTA_PROJECT',str(root.parent/'personal')))
raw=base64.b64decode(''.join((root/f'review-{i:02d}.txt').read_text().strip() for i in range(3)),validate=True)
assert hashlib.sha256(raw).hexdigest()=='c4031b5a3757d2a9e54d42e9e1febf2aeb2faa80a209b7371dbe8903fcb71b5f','Review checksum mismatch'
decoder=zlib.decompressobj();data=decoder.decompress(raw,1000000)
assert decoder.eof and not decoder.unconsumed_tail and not decoder.unused_data
assert hashlib.sha256(data).hexdigest()=='6550865b96fac44f5fa84ab6348e461e012eccfddc281dadb0085550b805e2d9','Decoded review checksum mismatch'
for change in json.loads(data):
 relative=Path(change['path'])
 assert not relative.is_absolute() and '..' not in relative.parts
 target=project/relative
 original=target.read_bytes() if target.exists() else None
 assert (hashlib.sha256(original).hexdigest() if original is not None else None)==change['before'],f'Review baseline mismatch: {relative}'
 if 'text' in change:content=change['text']
 else:
  lines=original.decode().splitlines(keepends=True)
  for start,end,replacement in reversed(change['changes']):lines[start:end]=[replacement]
  content=''.join(lines)
 assert hashlib.sha256(content.encode()).hexdigest()==change['after'],f'Review result mismatch: {relative}'
 target.parent.mkdir(parents=True,exist_ok=True);target.write_text(content)
 print('Reviewed',relative)
