from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'))
p=root/'app/src/main/java/com/ronin/vanta/ModelTitleButton.java'
s=p.read_text();old='Typeface.create("sans-serif-medium", 0)'
assert s.count(old)==1,'Expected one model-title typeface'
p.write_text(s.replace(old,'Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)'))
print('Corrected typeface constant without suppressing Android lint.')
