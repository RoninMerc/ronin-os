from pathlib import Path
import sys,shutil
root=Path(sys.argv[1]);here=Path(__file__).parent;j=root/'app/src/main/java/au/com/roningroup/patrollink'
p=root/'app/build.gradle';s=p.read_text();assert 'versionCode 166' in s and "versionName '1.1.54'" in s
p.write_text(s.replace('versionCode 166','versionCode 167').replace("versionName '1.1.54'","versionName '1.1.55'"))
names=['MainActivity','TabletDashboard','SatelliteTiles','SilverTrackBrowserUi','IssueFilterScripts','MonitorFilterUi','CaptureLinkPolicy','VehicleCaptureScripts','SilverTrackVehicleCollector','VehicleCaptureUi','MasterFeedClient']
for name in names:shutil.copyfile(here/(name+'.java'),j/(name+'.java'))
p=j/'PatrolEngine.java';s=p.read_text();assert 's.setLoadWithOverviewMode(false);' in s and 'web.setInitialScale(135);' in s
s=s.replace('s.setLoadWithOverviewMode(false);','s.setLoadWithOverviewMode(true);').replace('web.setInitialScale(135);','web.setInitialScale(0);')
p.write_text(s)
manifest=root/'app/src/main/AndroidManifest.xml';s=manifest.read_text();assert 'android:theme="@style/AppTheme"' in s
manifest.write_text(s.replace('android:theme="@style/AppTheme"','android:theme="@style/TRLTheme"'))
res=root/'app/src/main/res/values';res.mkdir(parents=True,exist_ok=True);shutil.copyfile(here/'trl-theme.xml',res/'trl-theme.xml')
tests=root/'app/src/test/java/au/com/roningroup/patrollink';shutil.copyfile(here/'CaptureLinkPolicyTest.java',tests/'CaptureLinkPolicyTest.java')
android=root/'app/src/androidTest/java/au/com/roningroup/patrollink'
for name in ['MonitorFilterUiTest','TabletDashboardTest','SilverTrackCaptureTest','BrowserUiTest']:shutil.copyfile(here/(name+'.java'),android/(name+'.java'))
p=android/'TabletUpgradeTest.java';s=p.read_text();assert 'assertEquals(166,' in s;p.write_text(s.replace('assertEquals(166,','assertEquals(167,'))
assert 'setInitialScale(135)' not in (j/'PatrolEngine.java').read_text()
assert 'buttonRow(toolbar' not in (j/'MainActivity.java').read_text()
assert 'TilesOverlay.INVERT_COLORS' not in (j/'TabletDashboard.java').read_text()
print('TRL_REPAIR_OK: 1.1.55/code167; branding, satellite, fit-page, multiple issue selections and direct attached report capture')
