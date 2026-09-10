from pathlib import Path
import base64,gzip,hashlib,subprocess,tempfile
root=Path(__file__).resolve().parent
encoded=''.join((root/f'patch{i}.txt').read_text().strip() for i in range(3))
# Correct a known duplicated transport fragment; the SHA below authenticates the exact source patch.
encoded=encoded.replace('YfWSESEEF5y','YfWSEEF5y')
raw=base64.b64decode(encoded,validate=True)
actual=hashlib.sha256(raw).hexdigest()
assert actual=='79f74b31f900b1249d56bbaa4911440ab8b577915837525f801baea06c540109',actual
patch=gzip.decompress(raw)
assert len(patch)==57289
project=root.parent/'personal'
subprocess.run(['patch','--batch','--fuzz=0','-p1','-d',str(project)],input=patch,check=True)
# Test-only HTTPS server credentials. Never shipped in the production application, never provider keys.
assets=project/'app/src/androidTest/assets/transport-test'
assets.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory() as directory:
 key=Path(directory)/'server.pem';cert=Path(directory)/'cert.pem'
 subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(key),'-out',str(cert),'-days','3650','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost,IP:127.0.0.1'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 subprocess.run(['openssl','pkcs8','-topk8','-nocrypt','-in',str(key),'-outform','DER','-out',str(assets/'server-key.pk8')],check=True)
 subprocess.run(['openssl','x509','-in',str(cert),'-outform','DER','-out',str(assets/'server-cert.der')],check=True)
print('Applied Vanta 0.9.4: completed-stream handling, consented interrupted inference recovery, foreground polling and compiler-targeted context. Tests include real loopback HTTPS.')
