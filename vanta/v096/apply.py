from pathlib import Path
import base64,gzip,hashlib,subprocess
root=Path(__file__).resolve().parent
project=root.parent/'personal'
encoded=(root/'client.patch.gz.b64').read_text().strip()
raw=base64.b64decode(encoded,validate=True)
assert hashlib.sha256(raw).hexdigest()=='c3110b85d363c86610629ff5db902e832aa0f9a3ead0d87a87df3eaaf4be40c1','Vanta 0.9.6 compressed patch checksum mismatch'
patch=gzip.decompress(raw)
assert len(patch)==22440,'Vanta 0.9.6 patch length mismatch'
assert hashlib.sha256(patch).hexdigest()=='35bf322a38a74485324264393c3079933c65c901b3362b4830fe2a5acd0893d7','Vanta 0.9.6 source patch checksum mismatch'
result=subprocess.run(['patch','--batch','--fuzz=0','-p1','-d',str(project)],input=patch)
if result.returncode:
    rejects=list(project.rglob('*.rej'))
    expected=project/'app/src/main/java/com/ronin/vanta/VantaHub.java.rej'
    assert rejects==[expected],f'Unexpected Vanta 0.9.6 patch rejects: {rejects}'
    hub=project/'app/src/main/java/com/ronin/vanta/VantaHub.java'
    text=hub.read_text()
    replacements={
      'Use verified low-refusal coding models':'Use low-refusal-labelled coding models',
      'Normal selects by coding suitability. Low-refusal uses verified provider/model labels,':'Normal selects by coding suitability. Low-refusal accepts provider metadata or explicit provider model-name labels,'
    }
    for old,new in replacements.items():
      if old in text:text=text.replace(old,new,1)
      elif new not in text:raise AssertionError('Expected VantaHub text missing: '+old)
    hub.write_text(text)
    expected.unlink()
    orig=hub.with_suffix('.java.orig')
    if orig.exists():orig.unlink()
print('Applied Vanta 0.9.6 Featherless low-refusal routing, label evidence and 400/422 request compatibility fallback.')
