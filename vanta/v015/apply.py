from pathlib import Path
import base64, hashlib, lzma, subprocess

encoded = Path('vanta/v015/patch.b64').read_text(encoding='utf-8').strip()
packed = base64.b64decode(encoded, validate=True)
packed_expected = '7436d197e4b5dfcc90bbaa23ca93faccac6d73805b9be4947f41cbc08e53fbf1'
if hashlib.sha256(packed).hexdigest() != packed_expected:
    raise SystemExit('Android 0.15 packed patch checksum mismatch')
patch = lzma.decompress(packed, memlimit=128 * 1024 * 1024)
patch_expected = '8d11ca33d94110fecc8d0fbef93933f2fb053355510c0b8303d07a0242fa5e54'
if hashlib.sha256(patch).hexdigest() != patch_expected:
    raise SystemExit('Android 0.15 patch checksum mismatch')
subprocess.run(['patch','--batch','--fuzz=0','-p1','-d','vanta/personal'], input=patch, check=True)
print(f'Applied Android 0.15 Forge integrity patch ({len(patch)} bytes).')
