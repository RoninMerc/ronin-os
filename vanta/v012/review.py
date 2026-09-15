from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'))
p=root/'app/src/androidTest/java/com/ronin/vanta/UnifiedUpgradeProbe.java'
s=p.read_text()
old='''    assertEquals(
        110, c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode());'''
new='''    assertEquals(
        120, c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode());'''
assert old in s,(p,'expected the retained 0.11 probe version assertion')
p.write_text(s.replace(old,new,1))
print('Reviewed Vanta 0.12 upgrade probe: expected installed versionCode 120.')
