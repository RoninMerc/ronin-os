from pathlib import Path
import base64, hashlib, json, lzma, subprocess

root = Path(__file__).resolve().parent
project = root.parents[0] / 'personal'
encoded = ''.join((root / f'payload-{i:02}.txt').read_text().strip() for i in range(12))
packed = base64.b64decode(encoded, validate=True)
expected = '8eff33af5a09afec6b809230e7c7349b1c5984b29a81c64edf8a190daf95f287'
if hashlib.sha256(packed).hexdigest() != expected:
    raise SystemExit('Experience source checksum mismatch')
raw = lzma.decompress(packed, memlimit=256*1024*1024)
if len(raw) > 1000000:
    raise SystemExit('Unexpected source delta size')
data = json.loads(raw)
# The delivered source archive added its handover README after compilation.
# Reconstruct only that complete documentation hunk, never missing source code.
readme = project / 'README.md'
if not readme.exists():
    first = data['patch'].split('--- a/README.md\n+++ b/README.md\n',1)[1].split('\n--- ',1)[0]
    readme.write_text(''.join(line[1:] for line in first.splitlines(keepends=True) if line.startswith(('-', ' '))))
errors = []
for name, digest in data['base_hashes'].items():
    path = project / name
    actual = hashlib.sha256(path.read_bytes()).hexdigest() if path.exists() else 'missing'
    if actual != digest:
        errors.append(f'{name}: expected {digest}, observed {actual}')
if errors:
    raise SystemExit('Original source baseline differs:\n'+'\n'.join(errors))
subprocess.run(['patch', '--batch', '--forward', '--fuzz=0', '-p1', '-d', str(project)], input=data['patch'].encode(), check=True)
for name, content in data['binary'].items():
    target = project / name
    if '..' in Path(name).parts or Path(name).is_absolute():
        raise SystemExit('Invalid resource path')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(base64.b64decode(content, validate=True))
for name in ('DELIVERY.json', 'IMPLEMENTATION-AND-VERIFICATION.md'):
    (project / name).unlink(missing_ok=True)
print('Applied 0.6 source: reusable UI system, conversation-first shell, router, model experience, encrypted output library, consolidated media contracts and regression tests.')
