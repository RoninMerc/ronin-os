from pathlib import Path
p=Path('vanta/personal/app/src/androidTest/java/com/ronin/vanta/UnifiedUpgradeProbe.java')
s=p.read_text(encoding='utf-8')
old='''    assertEquals(
        160, c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode());'''
new='''    assertEquals(
        170, c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode());'''
if s.count(old)!=1:
    raise SystemExit('Expected 0.16 upgrade-probe assertion before 0.17 certification fix')
p.write_text(s.replace(old,new,1),encoding='utf-8')
print('Updated 0.17 upgrade probe to versionCode 170.')
