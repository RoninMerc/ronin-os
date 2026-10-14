from pathlib import Path
import shutil,re,json,zipfile,os,xml.etree.ElementTree as E
original=Path('/tmp/patrol-v134');root=Path('/tmp/tablet-source/TRL-Patrol-Link-v1.1.58')
root.mkdir(parents=True,exist_ok=True)
for p in original.rglob('*'):
 rel=p.relative_to(original)
 if not p.is_file() or {'build','.gradle','.git','local.properties','bundled_voice'}.intersection(rel.parts):continue
 if p.suffix.lower() in {'.jks','.keystore','.pem','.key','.p12','.pfx'} and rel.as_posix()!='app/src/androidTest/assets/tls-fixture.p12':continue
 if p.name.lower() in {'key.properties','keystore.properties','.env'}:continue
 target=root/rel;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,target)
gradle=root/'app/build.gradle';s=gradle.read_text().split('// TABLET_TEST_SIGNING')[0]
while True:
 m=re.search(r'\bsigningConfigs\s*\{',s)
 if not m:break
 depth=1;i=m.end()
 while depth and i<len(s):
  if s[i]=='{':depth+=1
  elif s[i]=='}':depth-=1
  i+=1
 assert depth==0;s=s[:m.start()]+s[i:]
s=re.sub(r'\bsigningConfig\s+signingConfigs\.\w+[ \t]*;?', '',s);assert not re.search(r'storePassword|keyPassword|storeFile|keyAlias|signingConfigs',s);gradle.write_text(s)
for name in ['CHANGELOG-TABLET.md','INSTALL-TABLET.md','TABLET-TEST-RESULTS.md']:shutil.copyfile(Path('.tabletbuild')/name,root/name)
(root/'README.md').write_text("""# TRL Patrol Link v1.1.58 phone/tablet Android project
Open this folder in Android Studio. Use JDK 17, Android SDK/build tools 36, and the included Gradle 8.13 wrapper.
Linux/macOS: ./gradlew :app:testDebugUnitTest :app:assembleDebug
Windows: gradlew.bat :app:testDebugUnitTest :app:assembleDebug

The signed operational APK uses the original release certificate. This source export omits production signing keys and personal voice recordings; use the original RoninMerc/ronin-os tablet release pipeline to build compatible operational updates with the authorised voice bundle. Local debug APKs have a different certificate. Do not uninstall a deployed app to install a debug build.

UI and capture implementation: MainActivity.java, TabletDashboard.java, TabletSettings.java, SatelliteTiles.java, MonitorFilterUi.java, IssueFilterScripts.java, SilverTrackVehicleCollector.java and VehicleCaptureScripts.java.
The debug-only TabletHostActivity uses visibly labelled synthetic positions for automated tests and is absent from the release APK.
Automatic collection: AutoCaptureJob, CaptureQueue, CapturePager, CaptureCatalog and ReportPhotos. Photos use the authorised Master session and the encrypted Mother Base report evidence API; source URLs/cookies are not uploaded.\nStartup: native first frame precedes renderer construction; paired Readers do not instantiate SilverTrack WebView; interrupted browser/map construction has a native recovery path without deleting settings.\nExisting Master feed credentials, SilverTrack cookies, Android Keystore settings and patrol/checkpoint storage identifiers are preserved.
Full supervisor fleet visibility requires an existing Master / Supervisor device role. Patrol devices retain their existing restrictions.
""")
tests=[]
for p in (original/'app/build/test-results').rglob('TEST-*.xml'):
 tree=E.parse(p).getroot()
 for t in tree.iter('testsuite'):assert not any(int(t.get(k,'0')) for k in ['failures','errors'])
 tests.extend(c.attrib for c in tree.iter('testcase'))
assert tests
provenance={'version':'1.1.58','versionCode':169,'commit':os.environ['GITHUB_SHA'],'run':os.environ['GITHUB_RUN_ID'],'jvmTestsPassed':len(tests),'productionSigningKeysIncluded':False,'personalVoiceRecordingsIncluded':False,'syntheticFixtureExcludedFromRelease':True}
(root/'BUILD-PROVENANCE.json').write_text(json.dumps(provenance,indent=2))
(root/'.gitignore').write_text('.gradle/\n**/build/\nlocal.properties\n*.jks\n*.keystore\nkey.properties\nkeystore.properties\n.env\n')
out=Path('/tmp/tablet-delivery');out.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(out/'TRL-Patrol-Link-v1.1.58-FULL-SOURCE.zip','w',zipfile.ZIP_DEFLATED) as z:
 for p in sorted(root.rglob('*')):
  if p.is_file():z.write(p,p.relative_to(root.parent))
(out/'JVM-TESTS.json').write_text(json.dumps({'passed':len(tests),'testcases':tests},indent=2))
print('TABLET_SOURCE_EXPORTED: '+str(len(tests))+' JVM tests')
