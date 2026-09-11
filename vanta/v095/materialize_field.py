from pathlib import Path
import base64,gzip,hashlib,json,os
root=Path(__file__).resolve().parent
packed=base64.b64decode(''.join((root/f'field_{i}.txt').read_text().strip() for i in range(3)),validate=True)
assert hashlib.sha256(packed).hexdigest()=='979c04d5300e9f82287244a11996dd03675e0194742b178c7fd34319fbe94824','Field-report source checksum mismatch'
project=json.loads(gzip.decompress(packed))
destination=Path(os.environ.get('FIELD_PROJECT','vanta/field-report'))
for file in project['files']:
 name=Path(file['path']);assert not name.is_absolute() and '..' not in name.parts
 path=destination/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(file['content'],encoding='utf-8')
client=Path(os.environ.get('VANTA_PROJECT','vanta/personal'))
if (client/'app/src/main').is_dir():
 target=client/'app/src/main/assets/forge-examples/field-report.json';target.parent.mkdir(parents=True,exist_ok=True)
 # Instrumentation is retained in the editable source. Nothing reports it as executed by the worker.
 target.write_text(json.dumps(project,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
print('Field-report source materialized:',len(project['files']),'files; no provider call or generated success response.')
