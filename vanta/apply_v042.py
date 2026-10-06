from pathlib import Path
import base64, gzip, hashlib, lzma, subprocess

root = Path(__file__).resolve().parent
project = root / 'reviewed'
encoded = ''.join((root / f'v042_patch_{i:02d}.txt').read_text().strip() for i in range(3))
packed = base64.b64decode(encoded, validate=True)
expected = '059ab53fa666e956de566d2f00d35e8e0a7be825f9767f7d38e96faf9b4daaf1'
actual = hashlib.sha256(packed).hexdigest()
if actual != expected:
    raise SystemExit(f'Fix patch checksum mismatch: {actual}')
patch = lzma.decompress(packed, memlimit=256*1024*1024)
if len(patch) > 300000:
    raise SystemExit('Unexpected patch size')
subprocess.run(['patch', '--batch', '--forward', '--fuzz=0', '-p1', '-d', str(project)], input=patch, check=True)
fixture = base64.b64decode((root / 'v042_catalogue_fixture.txt').read_text().strip(), validate=True)
if hashlib.sha256(fixture).hexdigest() != '8067a8eaa62dc54edcf3ba84864f063ce813fe075b222927d58f5b6aeca6b00e':
    raise SystemExit('Frozen Venice catalogue checksum mismatch')
gzip.decompress(fixture)
target = project / 'app/src/test/resources/venice-models-2026-09-07.json.gz'
target.parent.mkdir(parents=True, exist_ok=True)
target.write_bytes(fixture)
print('Applied 0.4.2: provider policy flags, model-specific video controls, quote/queue/retrieve/download and regression tests.')
