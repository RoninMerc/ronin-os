from pathlib import Path
import base64, hashlib, json, zlib
here=Path(__file__).resolve().parent
text=''.join((here/f'finish_{i:02d}.txt').read_text().strip() for i in range(2))
# Repair one transport-only transcription error; the full digest still must match.
text=text.replace('Ho2c3ZGdm1C37','Ho2cZGdm1C37')
raw=base64.b64decode(text,validate=True)
assert hashlib.sha256(raw).hexdigest()=='5fe9c25263763600ba723537fffd5c343ee2f60bfd62a319ad89ba616170a0c7','Desktop patch transport checksum mismatch'
root=Path('ronin-vanta-windows')
for op in json.loads(zlib.decompress(raw)):
 p=root/op['path']; old=p.read_text(encoding='utf-8')
 assert hashlib.sha256(old.encode()).hexdigest()==op['before'], 'Unexpected baseline: '+op['path']
 lines=old.splitlines(keepends=True)
 for start,end,replacement in reversed(op['changes']): lines[start:end]=[replacement]
 new=''.join(lines)
 assert hashlib.sha256(new.encode()).hexdigest()==op['after'], 'Unexpected patched source: '+op['path']
 p.write_text(new,encoding='utf-8',newline='\n')
 print('Updated',op['path'])
