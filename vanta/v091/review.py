from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'))
p=root/'app/src/main/java/com/ronin/vanta/VantaHub.java'
s=p.read_text()
old='            JSONObject validation=engine.store.document(job.id(),"source_validation");'
assert s.count(old)==1
s=s.replace(old,old+'\n            final String validationText=validation==null?"":Errors.redact(validation.toString(2));')
old='"Source validation (not a build result)\\n"+validation.toString(2)'
assert s.count(old)==1
s=s.replace(old,'"Source validation (not a build result)\\n"+validationText')
p.write_text(s)
print('Preformatted diagnostic JSON off the UI thread; checked exception is handled by the existing error path.')
