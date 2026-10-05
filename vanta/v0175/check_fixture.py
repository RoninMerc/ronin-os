from pathlib import Path
import hashlib,json,os,shutil,subprocess,xml.etree.ElementTree as ET,zipfile
root=Path(os.environ['FORGE175_FIXTURE_OUT']);p=root/'project'
command=['gradle','-p',str(p),'--no-daemon','--max-workers=2','--console=plain','testReleaseUnitTest','lintRelease','assembleRelease','--stacktrace']
with (root/'positive.log').open('w') as log:r=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,timeout=600)
assert r.returncode==0,'Generated fixture failed; inspect positive.log'
text=(root/'positive.log').read_text()
assert 'VANTA_TEST_REPORT|v1|:app:testReleaseUnitTest|2|2|0|0' in text,'Real test report missing'
reports=list(p.glob('app/build/test-results/testReleaseUnitTest/TEST-*.xml'))
assert reports and sum(int(ET.parse(f).getroot().attrib.get('tests',0)) for f in reports)==2
apks=list(p.glob('app/build/outputs/apk/release/*.apk'));assert len(apks)==1
with zipfile.ZipFile(apks[0]) as z:assert z.testzip() is None and 'classes.dex' in z.namelist() and 'AndroidManifest.xml' in z.namelist()
shutil.copy(apks[0],root/'fixture-release-unsigned.apk')
for f in reports:shutil.copy(f,root/f.name)
source=next(p.glob('app/src/test/java/**/ArithmeticTest.java'))
source.write_text(source.read_text().replace('assertEquals(4,2+2)','assertEquals(5,2+2)'))
shutil.rmtree(p/'app/build')
with (root/'negative.log').open('w') as log:r=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,timeout=600)
assert r.returncode!=0 and 'There were failing tests' in (root/'negative.log').read_text(),'Failed assertion not enforced'
assert not list(p.glob('app/build/outputs/apk/release/*.apk')),'Failing test allowed APK assembly'
source.write_text(source.read_text().replace('assertEquals(5,2+2)','assertEquals(4,2+2)'))
(root/'verification.json').write_text(json.dumps({'positive_build':'PASS','executed_passing_tests':2,'foreign_gate_preserved':True,'production_gate_report':'PASS','intentionally_failing_test_blocks_apk':'PASS','apk_sha256':hashlib.sha256((root/'fixture-release-unsigned.apk').read_bytes()).hexdigest(),'paid_provider_generation':'NOT RUN'},indent=2))
print('POSITIVE_COMPILE_AND_NEGATIVE_TEST_GATE_PASS')
