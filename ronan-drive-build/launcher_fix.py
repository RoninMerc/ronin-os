"""Ronan Drive 1.0.1-test2: focused Android Auto registration fix. GPL-3.0."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(sys.argv[1])
ANDROID = '{http://schemas.android.com/apk/res/android}'
DESC = Path('fermata/src/auto/res/xml/automotive_app_desc.xml')
MANIFEST = Path('fermata/src/auto/AndroidManifest.xml')

# Keep all upstream capabilities; explicitly advertise the Car App Library service.
p = ROOT / DESC
s = p.read_text()
old = '<!--    <uses name="template" />-->'
assert s.count(old) == 1, 'Unexpected descriptor: review the current source before patching'
p.write_text(s.replace(old, '    <uses name="template" />', 1))

# Make inclusion explicit; do not add additional services, permissions or launchers.
p = ROOT / MANIFEST
s = p.read_text()
for name in ['CarService', 'MirrorService', 'MirrorServiceFS']:
    old = 'android:name="me.aap.fermata.auto.' + name + '"\n'
    assert s.count(old) == 1, 'Service registration missing or duplicated: ' + name
    s = s.replace(old, old + '            android:enabled="true"\n', 1)
p.write_text(s)

p = ROOT / 'gradle/libs.versions.toml'
s = p.read_text()
assert 'appVersionCode = "1"' in s
assert 'appVersionName = "1.0.0-test1"' in s
s = s.replace('appVersionCode = "1"', 'appVersionCode = "2"', 1)
s = s.replace('appVersionName = "1.0.0-test1"', 'appVersionName = "1.0.1-test2"', 1)
p.write_text(s)

p = ROOT / 'RONAN-DRIVE-README.md'
s = p.read_text().replace('# Ronan Drive 1.0.0-test1', '# Ronan Drive 1.0.1-test2', 1)
s += '''
## 1.0.1-test2 launcher-registration update
Adds the missing `template` capability for the existing FS Mirror CarAppService.
Makes the existing car UI, Mirror and FS Mirror services explicitly enabled.
Retains the media, browser, mirror, adaptive-fitting, addons and privacy changes.
Package and signer are unchanged; versionCode increases from 1 to 2.
Install this APK over test1; do not uninstall Ronan Drive or Fermata, and do not clear app data.
After installing, open Ronan Drive once on the phone, then restart the phone while disconnected
from the car. Reconnect Android Auto while parked and recheck Customise Launcher.
No separate Mirror APK is needed. This is not a root, DRM or Android Auto trust bypass.
Android Auto may still reject a sideloaded projection/template service independently of the
app's registrations. A manifest check cannot prove recognition on a physical phone/head unit.
The standard Mirror service already had its legacy registration; its absence on the reported
phone has not been explained by runtime logs. Do not claim that this change proves it fixed.
Official requirements: https://developer.android.com/training/cars/apps/auto
Testing/trust: https://developer.android.com/training/cars/testing
'''
p.write_text(s)

# Build-time contract tests. Inspect exact XML nodes, not comments or loose substrings.
def validate(desc_text, manifest_text):
    desc = ET.fromstring(desc_text)
    capabilities = [e.attrib['name'] for e in desc.findall('uses')]
    assert sorted(capabilities) == sorted(['media', 'service', 'projection', 'template'])
    assert len(capabilities) == len(set(capabilities))
    app = ET.fromstring(manifest_text).find('application')
    services = {e.attrib[ANDROID+'name']: e for e in app.findall('service')}
    for name in ['CarService', 'MirrorService', 'MirrorServiceFS']:
        e = services['me.aap.fermata.auto.' + name]
        assert e.attrib[ANDROID+'enabled'] == 'true'
        assert e.attrib[ANDROID+'exported'] == 'true'
        assert e.attrib.get(ANDROID+'label')
        filters = e.findall('intent-filter')
        action = 'androidx.car.app.CarAppService' if name == 'MirrorServiceFS' else 'android.intent.action.MAIN'
        category = 'androidx.car.app.category.NAVIGATION' if name == 'MirrorServiceFS' else 'com.google.android.gms.car.category.CATEGORY_PROJECTION'
        assert any(action in [x.attrib[ANDROID+'name'] for x in f.findall('action')] and category in [x.attrib[ANDROID+'name'] for x in f.findall('category')] for f in filters)

source_desc = (ROOT / DESC).read_text()
source_manifest = (ROOT / MANIFEST).read_text()
validate(source_desc, source_manifest)
for broken_desc, broken_manifest in [
    (source_desc.replace('<uses name="template" />', '<!-- <uses name="template" /> -->'), source_manifest),
    (source_desc, source_manifest.replace('android:enabled="true"', 'android:enabled="false"', 1)),
    (source_desc, source_manifest.replace('androidx.car.app.CarAppService', 'invalid.service.Action', 1)),
]:
    try:
        validate(broken_desc, broken_manifest)
    except AssertionError:
        pass
    else:
        raise AssertionError('Regression fixture unexpectedly passed')
(ROOT / 'RONAN-LAUNCHER-FIX.py').write_text(Path(__file__).read_text())
(ROOT / 'RONAN-LAUNCHER-TESTS.txt').write_text('PASS: source capabilities and all three car service registrations\nPASS: regression fixtures for commented template, disabled service, incorrect service action\nRuntime Android Auto acceptance is not tested by these checks.\n')
print((ROOT / 'RONAN-LAUNCHER-TESTS.txt').read_text())
