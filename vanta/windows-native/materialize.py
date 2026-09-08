from pathlib import Path, PurePosixPath
import base64, hashlib, json, lzma
base=Path(__file__).resolve().parent
raw=base64.b64decode(''.join((base/f'source_{n:02d}.txt').read_text().strip() for n in range(12)),validate=True)
assert hashlib.sha256(raw).hexdigest()=='ef068fb54687055288624a05bab511964417fe04c559f22c3d3ca2141ce716b6','Source checksum mismatch'
text=lzma.decompress(raw,memlimit=268435456)
assert len(text)==345181,'Unexpected source length'
files=json.loads(text);assert len(files)==34
root=base.parents[1]/'ronin-vanta-windows';root.mkdir(parents=True,exist_ok=True)
for name,value in files.items():
    path=PurePosixPath(name)
    if path.is_absolute() or '..' in path.parts or '\\' in name or ':' in name:raise ValueError('Unsafe source path')
    out=root.joinpath(*path.parts);out.parent.mkdir(parents=True,exist_ok=True)
    out.write_bytes(base64.b64decode(value,validate=True) if name.endswith('.ico') else value.encode('utf-8'))
print('Materialized 34 dedicated native Windows files; Android source untouched.')
