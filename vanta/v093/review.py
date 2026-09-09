from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'))
p=root/'app/src/androidTest/java/com/ronin/vanta/FoundationDeviceTest.java'
s=p.read_text();assert s.count('source_files_plan_response_output_limit')==2
p.write_text(s.replace('source_files_plan_response_output_limit','author_plan_response_output_limit'))
print('Reviewed recovery assertions target the exact persisted author phase.')
