from pathlib import Path
import base64,gzip,hashlib,subprocess
root=Path(__file__).resolve().parent
encoded=(root/'client.patch.gz.b64').read_text().strip()
# Correct a single transport transcription; the decoded full patch must still match its original SHA-256.
encoded=encoded.replace('NuUUUUduV6u','NuUUduV6u')
raw=base64.b64decode(encoded,validate=True)
assert hashlib.sha256(raw).hexdigest()=='673e6a7cba1426b12b986f3d1febc6dac5c44e630ca52610deb777b12c29a98c','Vanta source checksum mismatch'
patch=gzip.decompress(raw);assert len(patch)==31091
subprocess.run(['patch','--batch','--fuzz=0','-p1','-d',str(root.parent/'personal')],input=patch,check=True)
print('Applied Normal/Low-refusal profiles, compile-existing-source independent of inference, README and verified artifact receipt.')
