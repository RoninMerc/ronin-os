from pathlib import Path
import sys,shutil
root=Path(sys.argv[1]);here=Path(__file__).parent;j=root/'app/src/main/java/au/com/roningroup/patrollink'
p=root/'app/build.gradle';s=p.read_text();assert 'versionCode 168' in s
p.write_text(s.replace('versionCode 168','versionCode 169').replace("versionName '1.1.56'","versionName '1.1.57'"))
for source in here.glob('*.java'):
 if source.stem.endswith('Test'):continue
 shutil.copyfile(source,j/source.name)
tests=root/'app/src/androidTest/java/au/com/roningroup/patrollink'
shutil.copyfile(here/'StartupRecoveryTest.java',tests/'StartupRecoveryTest.java')
p=tests/'TabletUpgradeTest.java';s=p.read_text();assert 'assertEquals(168,' in s;s=s.replace('assertEquals(168,','assertEquals(169,')
anchor='  Activity main=ins.startActivitySync'
assert anchor in s
s=s.replace(anchor,'  FleetRelay.configure(c,true,1);TabletSettings.prefs(c).edit().putBoolean("read_existing_master",true).commit();\n'+anchor)
s=s.replace('c.getPackageManager().getPackageInfo(c.getPackageName(),0).getLongVersionCode()','(Build.VERSION.SDK_INT>=28?c.getPackageManager().getPackageInfo(c.getPackageName(),0).getLongVersionCode():c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionCode)')
p.write_text(s)
print('STARTUP_REPAIR_OK: native first frame, isolated browser/map recovery, Reader without renderer, certificate/storage unchanged')
