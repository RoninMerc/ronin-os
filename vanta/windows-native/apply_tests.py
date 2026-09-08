from pathlib import Path
import base64,hashlib,json,lzma
base=Path(__file__).resolve().parent
raw=base64.b64decode(''.join((base/f'tests_{n:02d}.txt').read_text().strip() for n in range(2)),validate=True)
assert hashlib.sha256(raw).hexdigest()=='7d8f83efd15c61c8f157eac178f452cf215a075933583ed1594f7d99a1e6e1cb','Test source checksum mismatch'
files=json.loads(lzma.decompress(raw,memlimit=268435456));assert len(files)==4
out=Path('ronin-vanta-windows/tests/Vanta.Tests');out.mkdir(parents=True,exist_ok=True)
for name,text in files.items():
 assert name in ['Program.cs','CoreTests.cs','WorkflowTests.cs','DesktopTests.cs']
 if name=='CoreTests.cs':text=text.replace('Provider.BuiltIn.Count','Provider.BuiltIn.Length')
 (out/name).write_text(text,encoding='utf-8')
print('Materialized actual native test harness; production API calls use isolated test-only HTTP fixtures.')
