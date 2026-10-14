from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]);here=Path(__file__).parent;j=root/'app/src/main/java/au/com/roningroup/patrollink'
p=root/'app/build.gradle';s=p.read_text();assert 'versionCode 170' in s;p.write_text(s.replace('versionCode 170','versionCode 171').replace("versionName '1.1.58'","versionName '1.1.59'"))
for p in here.glob('*.java'):
 if not p.stem.endswith('Test'):shutil.copyfile(p,j/p.name)
shutil.copyfile(here/'AnrFramesTest.java',root/'app/src/test/java/au/com/roningroup/patrollink/AnrFramesTest.java')
tests=root/'app/src/androidTest/java/au/com/roningroup/patrollink';shutil.copyfile(here/'AnrWorkTest.java',tests/'AnrWorkTest.java')
p=tests/'TabletUpgradeTest.java';s=p.read_text();assert 'assertEquals(170,' in s;p.write_text(s.replace('assertEquals(170,','assertEquals(171,'))
print('ANR_UI_WORK_REMOVED: code171; encrypted polling and checkpoint photo writes run off UI; bounded main-thread ANR frames')

p=root/'app/src/main/AndroidManifest.xml';s=p.read_text();start=s.index('<application');end=s.index('>',start)+1
s=s[:end]+'\n        <property android:name="android.window.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY" android:value="true" />'+s[end:]
p.write_text(s)
