from pathlib import Path
import sys,shutil
root=Path(sys.argv[1]);here=Path(__file__).parent;j=root/'app/src/main/java/au/com/roningroup/patrollink'
p=root/'app/build.gradle';s=p.read_text();assert 'versionCode 167' in s and "versionName '1.1.55'" in s
p.write_text(s.replace('versionCode 167','versionCode 168').replace("versionName '1.1.55'","versionName '1.1.56'"))
for source in here.glob('*.java'):
 if source.stem.endswith('Test'):continue
 shutil.copyfile(source,j/source.name)
p=root/'app/src/main/AndroidManifest.xml';s=p.read_text();assert 'android:theme="@style/TRLTheme"' in s;p.write_text(s.replace('android:theme="@style/TRLTheme"','android:theme="@style/AppTheme"'))
tests=root/'app/src/test/java/au/com/roningroup/patrollink';shutil.copyfile(here/'ReportPhotoPolicyTest.java',tests/'ReportPhotoPolicyTest.java');shutil.copyfile(here/'CaptureTimestampTest.java',tests/'CaptureTimestampTest.java')
android=root/'app/src/androidTest/java/au/com/roningroup/patrollink'
for name in ['MonitorFilterUiTest','BrowserUiTest','AutoCaptureFlowTest']:shutil.copyfile(here/(name+'.java'),android/(name+'.java'))
p=android/'TabletUpgradeTest.java';s=p.read_text();assert 'assertEquals(167,' in s;p.write_text(s.replace('assertEquals(167,','assertEquals(168,'))
assert 'showPhoneBrowser' in (j/'MainActivity.java').read_text()
assert 'AutoCaptureJob.bind' in (j/'MainActivity.java').read_text()
print('AUTO_CAPTURE_OK: 1.1.56/code168, tablet-only automatic pagination, durable reports/photos and original phone UI')
