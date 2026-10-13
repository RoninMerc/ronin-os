from pathlib import Path
import shutil,sys,re
root=Path(sys.argv[1]);here=Path(__file__).parent;j=root/'app/src/main/java/au/com/roningroup/patrollink'
gradle=root/'app/build.gradle';s=gradle.read_text()
assert 'versionCode 162' in s and "versionName '1.1.52'" in s
s=s.replace('versionCode 162','versionCode 163').replace("versionName '1.1.52'","versionName '1.1.53'")
assert "implementation 'androidx.car.app:app:1.7.0'" in s
s=s.replace("implementation 'androidx.car.app:app:1.7.0'","implementation 'org.osmdroid:osmdroid-android:6.1.20'\n    implementation 'androidx.car.app:app:1.7.0'")
gradle.write_text(s)
for name in ['MainActivity.java','CheckpointProximityUi.java','FleetRelay.java','FleetUi.java','LocalGpsUi.java','TabletPolicy.java','CheckpointGate.java','TabletSettings.java','TabletDashboard.java']:shutil.copyfile(here/name,j/name)
tests=root/'app/src/test/java/au/com/roningroup/patrollink';tests.mkdir(parents=True,exist_ok=True);shutil.copyfile(here/'TabletPolicyTest.java',tests/'TabletPolicyTest.java')
androidtests=root/'app/src/androidTest/java/au/com/roningroup/patrollink';androidtests.mkdir(parents=True,exist_ok=True);shutil.copyfile(here/'TabletDashboardTest.java',androidtests/'TabletDashboardTest.java');shutil.copyfile(here/'TabletUpgradeTest.java',androidtests/'TabletUpgradeTest.java')
debug=root/'app/src/debug/java/au/com/roningroup/patrollink';debug.mkdir(parents=True,exist_ok=True);shutil.copyfile(here/'TabletHostActivity.java',debug/'TabletHostActivity.java')
manifest=root/'app/src/debug/AndroidManifest.xml';s=manifest.read_text();s=s.replace('</application>','<activity android:name="au.com.roningroup.patrollink.TabletHostActivity" android:configChanges="orientation|screenSize|smallestScreenSize|keyboardHidden" android:exported="false" android:theme="@android:style/Theme.Material.NoActionBar" /></application>');manifest.write_text(s)
assert 'Math.max(20f' not in (j/'CheckpointProximityUi.java').read_text()
assert 'MonitorFilterUi.bind' in (j/'MainActivity.java').read_text() and 'Capture parking reports' in (j/'MainActivity.java').read_text()
assert 'X-Ronin-Key' in (j/'FleetRelay.java').read_text()
print('TABLET_PATCH_OK: v1.1.53/code163; native OSM street map; existing package/storage/signing preserved')
