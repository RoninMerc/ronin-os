from pathlib import Path, PurePosixPath
import base64,hashlib,zlib,json
here=Path(__file__).resolve().parent
raw=base64.b64decode(''.join((here/f'part{i:02d}.txt').read_text().strip() for i in range(5)),validate=True)
assert hashlib.sha256(raw).hexdigest()=='9ad394fac11487d62fd2cac5acbe8b697f1941d07376a5791024085f2b434296','Source transfer integrity failure'
records=json.loads(zlib.decompress(raw));assert len(records)==31
root=Path('ronin-vanta-windows')
for record in records:
 rel=PurePosixPath(record['path']);assert not rel.is_absolute() and '..' not in rel.parts
 p=root.joinpath(*rel.parts)
 before=p.read_text(encoding='utf-8') if p.exists() else None
 actual=hashlib.sha256(before.encode()).hexdigest() if before is not None else None
 assert actual==record['before'],f'Baseline mismatch: {rel}, {actual}'
 if 'text' in record:after=record['text']
 else:
  lines=before.splitlines(keepends=True)
  for start,end,text in reversed(record['changes']):lines[start:end]=[text]
  after=''.join(lines)
 assert hashlib.sha256(after.encode()).hexdigest()==record['after'],f'Applied content mismatch: {rel}'
 p.parent.mkdir(parents=True,exist_ok=True);p.write_text(after,encoding='utf-8',newline='\n')
 print(rel)
print('Applied 31 Windows source files with verified baseline and result hashes.')
