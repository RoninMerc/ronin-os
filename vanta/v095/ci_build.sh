#!/usr/bin/env bash
set -euo pipefail
export VANTA_FIXTURES_OUT="$RUNNER_TEMP/generated-fixtures"
export VANTA_BUILD_EVIDENCE="$RUNNER_TEMP/generated-build-evidence"
python3 -m pip install --quiet PyYAML==6.0.2
python3 - <<'PY'
from pathlib import Path
import yaml,subprocess
w=yaml.safe_load(Path('.github/workflows/vanta-094.yml').read_text())
a=[s for s in w['jobs']['build']['steps'] if s.get('name')=='Reconstruct exact baseline and apply reviewed transport changes']
assert len(a)==1
subprocess.run(['bash','-e','-o','pipefail','-c',a[0]['run']],check=True)
PY
gradle -p vanta/personal --no-daemon --max-workers=2 assembleDebug
cp vanta/personal/app/build/outputs/apk/debug/app-debug.apk distribution/baseline-094-debug.apk
python3 vanta/v095/apply.py
python3 vanta/v095/materialize_field.py
python3 vanta/v095/field_review.py
python3 vanta/v095/review.py
if test -f vanta/v095/final_review.py; then python3 vanta/v095/final_review.py; fi
find vanta/personal/app/src vanta/field-report/app/src -name '*.java' -print0 | xargs -0 java -jar /tmp/format.jar --replace
python3 vanta/v095/capture_field.py
sed -i 's/Ronin-Vanta-0.9.4-Source.zip/Ronin-Vanta-0.9.5-Source.zip/g' vanta/personal/ci/verify_generated_builds.py
gradle -p vanta/personal --no-daemon --max-workers=2 --continue testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest --stacktrace 2>&1 | tee client-build.log
gradle -p vanta/field-report --no-daemon --max-workers=2 --continue assembleRelease lintRelease assembleDebugAndroidTest --stacktrace 2>&1 | tee field-build.log
python3 - <<'PY'
from pathlib import Path
import shutil,zipfile,xml.etree.ElementTree as ET
r=Path('vanta/field-report');reports=[ET.parse(p).getroot().attrib for p in r.glob('app/build/test-results/testReleaseUnitTest/TEST-*.xml')]
assert sum(int(x.get('tests',0)) for x in reports)==14
assert not any(int(x.get('failures',0))+int(x.get('errors',0)) for x in reports)
apk=r/'app/build/outputs/apk/release/app-release-unsigned.apk'
with zipfile.ZipFile(apk) as z: assert z.testzip() is None and 'AndroidManifest.xml' in z.namelist() and 'classes.dex' in z.namelist()
dest=Path('vanta/personal/app/src/androidTest/assets/field-acceptance');dest.mkdir(parents=True,exist_ok=True)
shutil.copy(apk,dest/'app-built.apk');shutil.copy('field-build.log',dest/'build.log')
shutil.copy(apk,'distribution/field-release-unsigned.apk')
shutil.copy(r/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk','distribution/field-test.apk')
shutil.copy('vanta/field-project-exact.json','distribution/field-project-exact.json')
PY
python3 vanta/personal/ci/verify_generated_builds.py
cp vanta/personal/app/build/outputs/apk/debug/app-debug.apk distribution/
cp vanta/personal/app/build/outputs/apk/release/app-release-unsigned.apk distribution/fixed-release-unsigned.apk
gradle -p vanta/personal --no-daemon --console=plain signingReport > /tmp/signing.txt
KEY=$(sed -n 's/^Store: //p' /tmp/signing.txt | grep -v '^null$' | head -n1)
test -f "$KEY"
for name in fixed field; do "$ANDROID_HOME/build-tools/35.0.0/apksigner" sign --ks "$KEY" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out "distribution/$name-release-qa.apk" "distribution/$name-release-unsigned.apk"; done
for name in fixed field; do "$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs "distribution/$name-release-qa.apk" > "distribution/$name-signature.txt"; "$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "distribution/$name-release-qa.apk" > "distribution/$name-package.txt"; done
gradle -p vanta/field-report -PfieldApplicationId=com.ronin.fieldreport.demo --no-daemon --max-workers=2 --continue assembleRelease lintRelease assembleDebugAndroidTest --stacktrace 2>&1 | tee field-demo-build.log
cp vanta/field-report/app/build/outputs/apk/release/app-release-unsigned.apk distribution/field-demo-unsigned.apk
cp vanta/field-report/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk distribution/field-demo-test.apk
"$ANDROID_HOME/build-tools/35.0.0/apksigner" sign --ks "$KEY" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out distribution/field-demo-qa.apk distribution/field-demo-unsigned.apk
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs distribution/field-demo-qa.apk > distribution/field-demo-signature.txt
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging distribution/field-demo-qa.apk > distribution/field-demo-package.txt
gradle -p vanta/field-report --no-daemon wrapper --gradle-version 8.13
python3 - <<'PY'
from pathlib import Path
import zipfile
root=Path('vanta/field-report')
with zipfile.ZipFile('distribution/Field-Report-Source.zip','w',zipfile.ZIP_DEFLATED) as z:
 for p in root.rglob('*'):
  if p.is_file() and not any(x in ('build','.gradle') for x in p.relative_to(root).parts):z.write(p,p.relative_to(root))
PY
gradle -p vanta/personal -PvantaApplicationId=com.ronin.vanta.personal --no-daemon --max-workers=2 assembleRelease --stacktrace
cp vanta/personal/app/build/outputs/apk/release/app-release-unsigned.apk distribution/personal-release-unsigned.apk
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging distribution/personal-release-unsigned.apk > distribution/personal-package.txt
cp "$ANDROID_HOME/build-tools/35.0.0/lib/apksigner.jar" distribution/
cp /tmp/format.jar distribution/
cp vanta/v095/ci_device.sh distribution/
printf '%s\n' "$GITHUB_SHA" > distribution/source-commit.txt
sha256sum distribution/*.apk > distribution/SHA256SUMS.txt
