from pathlib import Path
p=Path('vanta/personal/app/src/androidTest/java/com/ronin/vanta/UnifiedUpgradeProbe.java')
s=p.read_text(encoding='utf-8')
old='''    assertEquals(
        150, c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode());'''
new='''    assertEquals(
        160, c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode());'''
if s.count(old) != 1:
    raise SystemExit(f'0.16 upgrade probe assertion: expected 1, found {s.count(old)}')
p.write_text(s.replace(old,new,1),encoding='utf-8')
print('Updated Android 0.16 upgrade probe version assertion.')
