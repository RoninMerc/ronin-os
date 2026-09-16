from pathlib import Path
import base64, hashlib, json, zlib

root = Path('ronin-vanta-windows').resolve()
payload = Path(__file__).with_name('ops.b64').read_text(encoding='utf-8').strip()
raw = zlib.decompress(base64.b64decode(payload, validate=True))
expected = '50804afc2776b763110756a27903aa0f1b88d0d3dd0eabaf7c7e6c3b4a04fd24'
actual = hashlib.sha256(raw).hexdigest()
if actual != expected:
    raise SystemExit(f'Windows 0.2.1 overlay checksum mismatch: {actual}')
ops = json.loads(raw.decode('utf-8'))

for rel, old, new in ops:
    target = (root / rel).resolve()
    if root != target and root not in target.parents:
        raise SystemExit(f'Unsafe overlay path: {rel}')
    target.parent.mkdir(parents=True, exist_ok=True)
    if old is None:
        if target.exists():
            raise SystemExit(f'Overlay expected new file but it exists: {rel}')
        target.write_text(new, encoding='utf-8')
        continue
    if not target.exists():
        raise SystemExit(f'Overlay target missing: {rel}')
    text = target.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'Overlay anchor count for {rel}: expected 1, found {count}')
    target.write_text(text.replace(old, new, 1), encoding='utf-8')

print(f'Applied Windows 0.2.1 Vanta Orchestrator overlay: {len(ops)} verified transformations.')
