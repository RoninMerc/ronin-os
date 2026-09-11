from pathlib import Path
import json,os,hashlib
root=Path(os.environ.get('FIELD_PROJECT','vanta/field-report'))
client=Path(os.environ.get('VANTA_PROJECT','vanta/personal'))
files=[]
for p in sorted(root.rglob('*')):
 if not p.is_file():continue
 rel=p.relative_to(root)
 if any(x in ('build','.gradle','gradle') for x in rel.parts) or p.name in ('gradlew','gradlew.bat','local.properties'):continue
 files.append({'path':str(rel),'content':p.read_text(encoding='utf-8')})
project={'name':'Ronin Field Report','platform':'android','files':files}
encoded=json.dumps(project,ensure_ascii=False,separators=(',',':')).encode()
for target in [client/'app/src/main/assets/forge-examples/field-report.json',client/'app/src/androidTest/assets/field-acceptance/project.json',root/'../field-project-exact.json']:
 target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(encoded)
print('Exact field source captured:',len(files),'files; JSON SHA-256',hashlib.sha256(encoded).hexdigest())
