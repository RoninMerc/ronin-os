from pathlib import Path
import base64, hashlib, io, lzma, shutil, subprocess, tarfile, tempfile, zipfile

root = Path(__file__).resolve().parent
project = Path('ronin-vanta-windows')

# The branch contains an exact 0.2 source bundle as five Git-tracked Base64/XZ
# fragments. Reconstruct that canonical bundle instead of relying on the older
# malformed patch transport. Git blob identities + XZ integrity + archive path
# validation protect the reconstruction.
exact_parts = [(root / f'exact_{i:02d}.txt').read_text().strip() for i in range(5)]
encoded = ''.join(exact_parts)
packed = base64.b64decode(encoded, validate=True)
raw = lzma.decompress(packed, memlimit=268435456)
print('Exact 0.2 payload:', len(raw), 'bytes; sha256', hashlib.sha256(raw).hexdigest())


def safe_target(base: Path, name: str) -> Path:
    target = (base / name).resolve()
    resolved = base.resolve()
    if target != resolved and resolved not in target.parents:
        raise SystemExit(f'Unsafe archive path: {name}')
    return target


def install_tree(staging: Path):
    roots = [p for p in staging.iterdir() if p.name != '__MACOSX']
    source = staging
    if len(roots) == 1 and roots[0].is_dir():
        source = roots[0]
    required = source / 'src' / 'Vanta.Windows' / 'Vanta.Windows.csproj'
    if not required.is_file():
        raise SystemExit(f'Exact source bundle is missing {required}')
    if project.exists():
        shutil.rmtree(project)
    shutil.copytree(source, project)

with tempfile.TemporaryDirectory(prefix='vanta-020-') as td:
    staging = Path(td)
    if zipfile.is_zipfile(io.BytesIO(raw)):
        with zipfile.ZipFile(io.BytesIO(raw)) as z:
            for info in z.infolist():
                safe_target(staging, info.filename)
            z.extractall(staging)
        install_tree(staging)
    else:
        temp = staging / 'payload'
        temp.write_bytes(raw)
        try:
            is_tar = tarfile.is_tarfile(temp)
        except Exception:
            is_tar = False
        if is_tar:
            with tarfile.open(temp) as t:
                for member in t.getmembers():
                    safe_target(staging / 'tree', member.name)
                (staging / 'tree').mkdir()
                t.extractall(staging / 'tree', filter='data')
            install_tree(staging / 'tree')
        elif raw.startswith(b'diff --git ') or raw.startswith(b'--- '):
            subprocess.run(['git', 'apply', '--check', f'--directory={project}'], input=raw, check=True)
            subprocess.run(['git', 'apply', f'--directory={project}'], input=raw, check=True)
        else:
            raise SystemExit('Exact source payload is neither ZIP, TAR nor a patch: ' + repr(raw[:32]))

print('Reconstructed canonical Ronin Vanta Windows 0.2 source tree.')
