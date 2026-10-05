#!/usr/bin/env bash
set -euo pipefail
mkdir -p device-evidence
adb wait-for-device
adb install -r distribution/baseline-qa.apk
adb install -r distribution/tests.apk
adb shell am instrument -w -r -e class com.ronin.vanta.Vanta175UpgradeDeviceTest#seed com.ronin.vanta.fixed.test/androidx.test.runner.AndroidJUnitRunner | tee device-evidence/seed.txt
grep -q 'OK (1 test)' device-evidence/seed.txt
adb install -r distribution/release-qa.apk
adb shell am instrument -w -r -e class com.ronin.vanta.Vanta175UpgradeDeviceTest#verify com.ronin.vanta.fixed.test/androidx.test.runner.AndroidJUnitRunner | tee device-evidence/verify.txt
grep -q 'OK (1 test)' device-evidence/verify.txt
adb shell am start -W -n com.ronin.vanta.fixed/com.ronin.vanta.MainActivity | tee device-evidence/launch.txt
sleep 3
adb exec-out screencap -p > device-evidence/portrait.png
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 3
adb exec-out screencap -p > device-evidence/landscape.png
adb shell dumpsys package com.ronin.vanta.fixed > device-evidence/package.txt
adb logcat -d -s AndroidRuntime:E > device-evidence/runtime-errors.txt
if grep -q 'FATAL EXCEPTION' device-evidence/runtime-errors.txt; then exit 1; fi
printf '%s\n' 'Encrypted credential, draft, conversation, failed task, partial file checkpoint, task-specific model and device-held generated APK signing certificate survived an in-place QA-signed update.' > device-evidence/summary.txt
