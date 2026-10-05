from pathlib import Path
import base64,gzip,hashlib,json,sys
root=Path(sys.argv[1]).resolve()
parts=Path(__file__).parent
payload=''.join((parts/f'patch-{i}.b64').read_text().strip() for i in range(4))
assert hashlib.sha256(payload.encode()).hexdigest()=='1674c73308b4e20c04f2ba9986c1c0e66e33fc9806f0c1baaf170501f761a083','Patch transport hash mismatch'
records=json.loads(gzip.decompress(base64.b64decode(payload,validate=True)))
for r in records:
    p=(root/r['path']).resolve()
    assert p.is_relative_to(root),'Unsafe patch path'
    old=p.read_bytes() if p.exists() else b''
    assert hashlib.sha256(old).hexdigest()==r['before'],'Baseline mismatch: '+r['path']
    lines=old.decode('utf-8').splitlines(keepends=True)
    for start,end,new in reversed(r['ops']):lines[start:end]=new.splitlines(keepends=True)
    updated=''.join(lines).encode('utf-8')
    assert hashlib.sha256(updated).hexdigest()==r['after'],'Patch result mismatch: '+r['path']
    p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(updated)
    print('PATCHED',r['path'])
print('Applied',len(records),'hash-verified source changes; retained all other baseline files.')
