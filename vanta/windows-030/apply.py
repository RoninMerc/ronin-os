from pathlib import Path
import hashlib, lzma, subprocess

base = Path(__file__).resolve().parent
packed = (base / 'workstation.patch.xz').read_bytes()
expected = '2055dd5980f5c4961c406a83ad6b75f8c45b2ef8a780073833f99d137fefd0df'
actual = hashlib.sha256(packed).hexdigest()
if actual != expected:
    raise SystemExit(f'Windows 0.3.0 patch checksum mismatch: {actual}')
patch = lzma.decompress(packed, memlimit=512 * 1024 * 1024)
if hashlib.sha256(patch).hexdigest() != 'ede634bd1a87a448c82fc22d25bd3288c85706c967abbc57a933ffe6b3423cca':
    raise SystemExit('Windows 0.3.0 decompressed patch checksum mismatch')
root = base.parents[1] / 'ronin-vanta-windows'
patch_file = base / '_workstation.patch'
patch_file.write_bytes(patch)
try:
    subprocess.run(['git','apply','--check','--whitespace=error-all',str(patch_file)], cwd=root, check=True)
    subprocess.run(['git','apply','--whitespace=error-all',str(patch_file)], cwd=root, check=True)
finally:
    patch_file.unlink(missing_ok=True)
props = (root / 'Directory.Build.props').read_text(encoding='utf-8')
required = [
    '<Version>0.3.0</Version>',
]
for token in required:
    if token not in props:
        raise SystemExit(f'Missing release marker: {token}')
for rel in [
    'src/Vanta.Core/Workstation.cs',
    'src/Vanta.Core/WorkstationServices.cs',
    'src/Vanta.Windows/WorkstationPage.cs',
    'src/Vanta.Windows/ProjectsPage.cs',
    'src/Vanta.Windows/AgentsPage.cs',
    'src/Vanta.Windows/PullRequestsPage.cs',
    'src/Vanta.Windows/ScheduledPage.cs',
    'src/Vanta.Windows/PluginsPage.cs',
    'src/Vanta.Windows/WindowsComputer.cs',
    'tests/Vanta.Tests/WorkstationTests.cs',
]:
    if not (root / rel).is_file():
        raise SystemExit(f'Missing workstation source: {rel}')
print('Applied checksum-verified Ronin Vanta Windows Workstation 0.3.0 patch.')
