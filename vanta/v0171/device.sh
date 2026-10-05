#!/usr/bin/env bash
set -euo pipefail
mkdir -p device-evidence
PACKAGE=com.ronin.vanta.fixed
RUNNER=com.ronin.vanta.fixed.test/androidx.test.runner.AndroidJUnitRunner
collect() {
  adb logcat -d > device-evidence/logcat.txt || true
  adb pull /sdcard/Android/data/$PACKAGE/files device-evidence/app-files >/dev/null 2>&1 || true
  adb shell dumpsys meminfo $PACKAGE > device-evidence/memory.txt || true
}
trap collect EXIT
check() {
  local name="$1"; local classes="$2"
  adb shell am instrument -w -r -e class "$classes" "$RUNNER" | tee "device-evidence/$name.txt"
  grep -q 'OK (' "device-evidence/$name.txt"
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "device-evidence/$name.txt"; then exit 1; fi
}
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb logcat -c
adb install -r distribution/baseline-qa.apk
adb install -r distribution/tests.apk
check before-upgrade com.ronin.vanta.Continuity171DeviceTest#seed
adb install -r distribution/release-qa.apk
check after-upgrade com.ronin.vanta.Continuity171DeviceTest#verify
check retained com.ronin.vanta.DeviceTest,com.ronin.vanta.StateAndSigningTest,com.ronin.vanta.ResponsiveLayoutTest
check catalogue com.ronin.vanta.FeatherlessCatalogueDeviceTest
adb shell am force-stop "$PACKAGE"
adb shell svc wifi disable
adb shell svc data disable
check offline-reopen com.ronin.vanta.Continuity171DeviceTest#reopenCatalogueWithoutNetwork
adb shell svc wifi enable
adb shell svc data enable
adb shell am start -W -n "$PACKAGE/com.ronin.vanta.MainActivity" | tee device-evidence/launch.txt
grep -q 'Status: ok' device-evidence/launch.txt
sleep 3
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
sleep 2
adb exec-out screencap -p > device-evidence/portrait.png
adb shell settings put system user_rotation 1
sleep 2
adb exec-out screencap -p > device-evidence/landscape.png
adb shell dumpsys package "$PACKAGE" > device-evidence/package.txt
printf 'Install-over, retained native tests, catalogue recovery, live public catalogue, offline reopen and orientation launch checks completed.\n' > device-evidence/complete.txt
