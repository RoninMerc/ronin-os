#!/usr/bin/env python3
"""Apply the test3 UI/diagnostic changes to the delivered test2 source tree.
This intentionally does NOT claim to repair Android Auto's installer acceptance.
"""
from pathlib import Path
import hashlib
import json
import re
import shutil
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1]).resolve()
assets = Path(__file__).resolve().parent / 'ronan-v3'
A = '{http://schemas.android.com/apk/res/android}'
checks = []
def require(condition, message):
    if not condition:
        raise AssertionError(message)
    checks.append('PASS: ' + message)
def read(path): return (root / path).read_text()
def write(path, value): (root / path).write_text(value)
def replace(path, before, after):
    text = read(path)
    require(text.count(before) == 1, 'unique patch anchor: ' + path + ' / ' + before[:55].replace('\n', ' '))
    write(path, text.replace(before, after, 1))
def digest(path): return hashlib.sha256((root / path).read_bytes()).hexdigest()

main_manifest = 'fermata/src/main/AndroidManifest.xml'
old_main = ET.fromstring(read(main_manifest))
old_perms = sorted(ET.tostring(e) for e in old_main if e.tag.startswith('uses-permission'))
original_modules = {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
                    for p in (root / 'modules').rglob('*') if p.is_file()}
car_manifest = 'fermata/src/auto/AndroidManifest.xml'
car_descriptor = 'fermata/src/auto/res/xml/automotive_app_desc.xml'
car_hashes = {p: digest(p) for p in [car_manifest, car_descriptor]}

replace('gradle/libs.versions.toml', 'appVersionCode = "2"', 'appVersionCode = "3"')
replace('gradle/libs.versions.toml', 'appVersionName = "1.0.1-test2"', 'appVersionName = "1.1.0-test3"')

for name in ['RonanHubActivity.java', 'RonanDiagnostics.java']:
    require((assets / name).is_file(), 'new Java source available: ' + name)
    shutil.copy2(assets / name, root / 'fermata/src/main/java/me/aap/fermata/ui/activity' / name)

# Keep Android TV and all existing deep-link/media intent handling on MainActivity.
replace(main_manifest, '                <category android:name="android.intent.category.LAUNCHER" />\n', '')
replace(main_manifest, '    <queries>\n', '    <queries>\n        <package android:name="com.google.android.projection.gearhead" />\n')
replace(main_manifest, '        <activity\n            android:name="me.aap.fermata.ui.activity.MainActivity"', '''        <activity
            android:name="me.aap.fermata.ui.activity.RonanHubActivity"
            android:exported="true"
            android:resizeableActivity="true"
            android:theme="@android:style/Theme.Material.NoActionBar">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name="me.aap.fermata.ui.activity.MainActivity"''')

# Only known internal fragment targets and ordinary web URLs are accepted.
route = '''
        if ("au.com.ronin.drive.OPEN".equals(intent.getAction())) {
            int target = intent.getIntExtra("ronan_target", R.id.folders_fragment);
            if (target != R.id.folders_fragment && target != R.id.settings_fragment
                    && target != R.id.web_browser_fragment && target != R.id.youtube_fragment)
                return completed(false);
            if ((target == R.id.web_browser_fragment || target == R.id.youtube_fragment)
                    && !AddonManager.get().hasAddon(target)) {
                showFragment(R.id.settings_fragment);
                return completed(true);
            }
            String address = intent.getStringExtra("ronan_url");
            if (target == R.id.web_browser_fragment && address != null) {
                Uri uri = Uri.parse(address);
                if (address.length() > 2048 || uri.getHost() == null || uri.getUserInfo() != null
                        || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())))
                    return completed(false);
                showFragment(target, address);
            } else showFragment(target);
            return completed(true);
        }
'''
replace('fermata/src/main/java/me/aap/fermata/ui/activity/MainActivityDelegate.java',
        '\tprivate FutureSupplier<Boolean> handleIntent(Intent intent) {\n',
        '\tprivate FutureSupplier<Boolean> handleIntent(Intent intent) {\n' + route)

replace('fermata/src/main/java/me/aap/fermata/ui/activity/MainActivityPrefs.java',
        'Pref<IntSupplier> THEME_MAIN = Pref.i("THEME_MAIN", THEME_CLASSIC);',
        'Pref<IntSupplier> THEME_MAIN = Pref.i("THEME_MAIN", THEME_DARK);')

replace('fermata/src/main/java/me/aap/fermata/ui/fragment/SettingsFragment.java',
        '\t\tPreferenceSet set = new PreferenceSet();\n',
        '''\t\tPreferenceSet set = new PreferenceSet();
        if (!isCar) set.addButton(o -> {
            o.title = R.string.ronan_dashboard;
            o.subtitle = R.string.ronan_dashboard_subtitle;
            o.onClick = () -> a.startActivity(new android.content.Intent(a.getContext(),
                    me.aap.fermata.ui.activity.RonanHubActivity.class));
        });
''')
write('fermata/src/main/res/values/ronan_hub.xml', '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="ronan_dashboard">Ronan Drive dashboard</string>
    <string name="ronan_dashboard_subtitle">Ronin home, local tools, display and connection diagnostics</string>
</resources>
''')

write('fermata/src/main/res/values/theme_dark.xml', '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Ronin palette; all other upstream themes remain selectable. -->
    <style name="AppTheme.Dark">
        <item name="android:colorBackground">#090C10</item>
        <item name="colorPrimaryDark">#121820</item>
        <item name="colorPrimary">?colorPrimaryDark</item>
        <item name="colorOnPrimary">#D8AB55</item>
        <item name="android:textColorPrimary">#F4F1E9</item>
        <item name="colorSecondary">#141B24</item>
        <item name="colorOnSecondary">#D8AB55</item>
        <item name="android:textColorSecondary">#DEE4EC</item>
        <item name="boxStrokeColor">#3A4758</item>
        <item name="alpha">1</item>
        <item name="colorSecondaryVariant">#493A22</item>
        <item name="android:textColorTertiary">#FFFFFF</item>
        <item name="colorSurface">#121820</item>
        <item name="colorOnSurface">#F4F1E9</item>
        <item name="colorPrimarySurface">#1B2430</item>
        <item name="colorOnPrimarySurface">#F4F1E9</item>
        <item name="colorAccent">#D8AB55</item>
        <item name="rippleColor">#55D8AB55</item>
        <item name="android:textColorHint">#B1BAC8</item>
        <item name="colorControlNormal">#9AA6B7</item>
        <item name="colorControlActivated">#D8AB55</item>
    </style>
</resources>
''')

for service in ['CarService', 'MirrorService', 'MirrorServiceFS']:
    replace('fermata/src/auto/java/me/aap/fermata/auto/' + service + '.java',
            '\t\tsuper.onCreate();', '\t\tsuper.onCreate();\n'
            '        me.aap.fermata.ui.activity.RonanDiagnostics.serviceCreated(this, "' + service + '");')
replace('fermata/src/auto/java/me/aap/fermata/auto/MirrorDisplay.java',
        '        if (sc.getSurface() == null || sc.getWidth() <= 0 || sc.getHeight() <= 0) return;',
        '''        if (sc.getSurface() == null || sc.getWidth() <= 0 || sc.getHeight() <= 0) return;
        me.aap.fermata.ui.activity.RonanDiagnostics.surface(FermataApplication.get(), sc.getWidth(), sc.getHeight());''')

# Source regression checks; compilation and APK checks are separate workflow steps.
new_main = ET.fromstring(read(main_manifest))
require(old_perms == sorted(ET.tostring(e) for e in new_main if e.tag.startswith('uses-permission')),
        'no new or altered main-manifest permissions')
for path, sha in car_hashes.items():
    require(digest(path) == sha, 'car registration byte-for-byte retained: ' + path)
require(original_modules == {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
        for p in (root / 'modules').rglob('*') if p.is_file()},
        'all original media/browser/add-on module files byte-for-byte retained')
app = new_main.find('application')
require(app.get(A + 'allowBackup') == 'false', 'Android backup remains disabled')
require(app.get(A + 'fullBackupContent') == 'false', 'full backup remains disabled')
launchers = []
for activity in app.findall('activity'):
    for intent in activity.findall('intent-filter'):
        categories = [v.get(A + 'name') for v in intent.findall('category')]
        if 'android.intent.category.LAUNCHER' in categories:
            launchers.append(activity.get(A + 'name'))
require(launchers == ['me.aap.fermata.ui.activity.RonanHubActivity'], 'one phone launcher: Ronan dashboard')
original = next(e for e in app.findall('activity') if e.get(A + 'name') == 'me.aap.fermata.ui.activity.MainActivity')
require(any(c.get(A + 'name') == 'android.intent.category.LEANBACK_LAUNCHER'
            for c in original.findall('intent-filter/category')), 'original Android TV launcher retained')
require('RONAN_ALLOW_ROOT", false' in read('fermata/src/main/java/me/aap/fermata/ui/activity/MainActivityPrefs.java'),
        'root remains disabled by default')
require('new android.content.Intent' in read('fermata/src/main/java/me/aap/fermata/ui/fragment/SettingsFragment.java'),
        'original settings include dashboard return link')
for name in ['RonanHubActivity.java', 'RonanDiagnostics.java']:
    text = read('fermata/src/main/java/me/aap/fermata/ui/activity/' + name)
    require(not any(token in text for token in ['java.net.', 'okhttp', 'HttpClient', 'Firebase', 'Runtime.getRuntime', 'ProcessBuilder']),
            'no network, telemetry or command execution API added by ' + name)
require('ronan_url' in route and 'uri.getUserInfo()' in route and '2048' in route,
        'new browser routing limits input length, scheme and embedded credentials')
require('INSTALLATION ROUTE' in read('fermata/src/main/java/me/aap/fermata/ui/activity/RonanHubActivity.java'),
        'dashboard includes installation-route explanation')

note = '''# Ronan Drive 1.1.0-test3 — Ronin interface and installation diagnostics

This is a compiled test update, NOT a confirmed fix for the missing Android Auto mirror icons.
The earlier manifest-only update did not fix the user's installation. The user now reports
that the working original Fermata was installed through AAAD, while Ronan Drive was installed
as a downloaded APK. Installation method is a useful lead, not a proven device-specific diagnosis.

## Identity and update
Package au.com.ronin.drive.auto; versionCode 3; versionName 1.1.0-test3; ARM64.
Same original signing certificate. Install as an update; do not uninstall or clear app data.
No root, Shizuku, installer-spoofing code, DRM override or driving-interlock modification was added.
Android Auto can reject a sideloaded mirror service even when its manifest is correctly registered.

## New phone interface
A charcoal-and-gold Ronin dashboard with Home, Add-ons, Display and Diagnose tabs.
Original media library, browser, YouTube, player and complete settings remain accessible.
Original Android TV launcher and car services remain in place; this is not a replacement car engine.
A matching dark theme is applied to the original dark palette. Existing explicit theme choices
are preserved; use Display > Apply Ronin dark theme to change those deliberately.

## Add-on controls and new local tools
The dashboard lists the existing bundled Fermata modules and exposes their enable/disable settings.
Their provider keys, source configuration and advanced controls remain in the original settings.
Web Shortcuts (default enabled) adds up to 20 named http/https links, stored on the phone and opened
in the existing Fermata browser. Long-press to delete a shortcut.
Session Notes (default disabled) adds a local text pad, explicitly saved in private app storage.
Notes and URLs are not included in the diagnostic report. Uninstall or Clear data removes them.
Google Drive remains unconfigured, as in the previous APK. No new commercial streaming providers,
AI providers or subscription access were added. Existing modules may require your own setup.

## Display
Direct access to Auto Fit, Auto Fill, Stretch and Compatibility using the same adaptive renderer.
Auto Fit preserves proportions and may retain borders; Fill crops, Stretch distorts.
Android Auto's own system bar cannot be reclaimed. Last reported car-surface dimensions are shown
when this build actually receives a surface. A saved timestamp is not a live connection indicator.

## Connection test
Rather than repeatedly opening the APK normally, test a local-APK installer appropriate to
Android Auto. AAAD's publicly documented workflow covers its listed catalog, not arbitrary custom
APK import. KingInstaller is a third-party option whose maintainer documents local-APK selection
and a classic install method without root or Shizuku. It changes installer identity metadata;
that is not an actual Play Store release, Google approval, or a security guarantee. Obtain any
external installer only from its official developer. This project does not bundle or audit it.
The supported Google testing route is Play Console internal sharing or a testing track, which
requires an account/setup outside this APK. No automatic installation-trust fix is claimed here.

After installing, open Diagnose > Copy diagnostic report. The report records app/Android/Android
Auto versions, installer identity, permission state, registered car components and local service
creation/surface timestamps. It cannot read Android Auto's private allowlist or rejection decision.
It excludes browsing history, URLs, notes, serials, account tokens and raw logcat. No automatic upload.
Share it explicitly only when needed for troubleshooting. Keep root off and test while parked.

## Validation boundary
The build checks compilation, certificate/update identity, permissions, source preservation,
Android Auto declarations and the existing 1,173 adaptive geometry assertions. They do not establish
installation, dashboard layout, playback, mirror discovery or touch behavior on the actual phone/car.
The APK and new UI still require device testing. This is not a complete security audit.

## Source and licenses
Based on the exact delivered 1.0.1-test2 source archive, SHA256
8ee834e606be2d0159880f1df757f3d1506e61b51da1510a856f9b5a7e4e042a.
Underlying official Fermata commit a81307383cfbd7740990eba41166c968f0d9fe73.
All original licenses/notices are retained. Corresponding modified source is included.
The native whisper cache is the same previously source-built library; its provenance is retained.
Private signing material is excluded. Historical test1/test2 patch scripts are provenance; do not
reapply those or refresh_v3.py to this already-prepared source tree.

Primary references, checked for this build:
https://github.com/shmykelsa/AAAD
https://github.com/fcaronte/KingInstaller
https://developer.android.com/training/cars/testing
'''
write('RONAN-DRIVE-README.md', note)
write('RONAN-V3-SOURCE-CHECKS.txt', '\n'.join(checks) + '\nNOT TESTED: physical Android Auto discovery, runtime UI and playback.\n')
shutil.copy2(__file__, root / 'RONAN-V3-PATCH.py')
shutil.copytree(assets, root / 'ronan-v3', dirs_exist_ok=True)
print('\n'.join(checks))
print('Prepared test3; installer acceptance is not repaired or proven by these changes.')
