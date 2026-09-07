"""Materialize the checksum-pinned 0.5 source. No credentials or binaries included."""
from pathlib import Path, PurePosixPath
import base64, hashlib, io, lzma, tarfile
root = Path(__file__).resolve().parent
encoded = ''.join((root / f'source{i:02d}.b64').read_text().strip() for i in range(8))
raw = base64.b64decode(encoded, validate=True)
expected = 'ebcf181778aab12fe7749540e5e1011a9a516b470bb70bbc92b5f6d7895b2ae8'
if hashlib.sha256(raw).hexdigest() != expected:
    raise SystemExit('Source checksum mismatch')
data = lzma.decompress(raw)
if len(data) > 2_000_000: raise SystemExit('Source archive exceeds limit')
out = root.parent / 'personal'
seen = set()
with tarfile.open(fileobj=io.BytesIO(data), mode='r:') as archive:
    for member in archive.getmembers():
        path = PurePosixPath(member.name)
        if not member.isfile() or path.is_absolute() or '..' in path.parts or member.name in seen:
            raise SystemExit('Invalid source entry: ' + member.name)
        seen.add(member.name)
        target = out.joinpath(*path.parts)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(archive.extractfile(member).read())
print(f'Verified and materialized {len(seen)} source/config/test files into {out}')
