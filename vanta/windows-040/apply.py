from pathlib import Path
import base64, hashlib, lzma, subprocess

encoded = Path('vanta/windows-040/patch.b64').read_text(encoding='utf-8').strip()
packed = base64.b64decode(encoded, validate=True)
if hashlib.sha256(packed).hexdigest() != '5db78996769e36b8a4bea39b254fa0ebe68fff56500ce28a93fd4058533789a8':
    raise SystemExit('Windows 0.4 packed patch checksum mismatch')
patch = lzma.decompress(packed, memlimit=128 * 1024 * 1024)
if hashlib.sha256(patch).hexdigest() != '08cef9be5f4f4bd9c387e6a3a5ba0a524e8935d2bb1a7f6699e0f9ff5cc479a7':
    raise SystemExit('Windows 0.4 patch checksum mismatch')
subprocess.run(['patch','--batch','--fuzz=0','-p1','-d','.'], input=patch, check=True)
print(f'Applied Windows Vanta 0.4 next-level patch ({len(patch)} bytes).')

# Trigger Windows 0.4 validation run after workflow creation.
