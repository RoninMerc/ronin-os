#!/usr/bin/env bash
set -euo pipefail
adb install -r distribution/baseline-094-debug.apk
adb shell am start -W -n com.ronin.vanta.fixed/com.ronin.vanta.MainActivity | tee baseline-launch.txt
adb install -r distribution/app-debug.apk
adb install -r distribution/app-debug-androidTest.apk
adb logcat -c
adb shell am instrument -w -r -e notClass com.ronin.vanta.ProcessProbe com.ronin.vanta.fixed.test/androidx.test.runner.AndroidJUnitRunner | tee client-debug-tests.txt
adb logcat -d > client-debug-logcat.txt
adb pull /sdcard/Android/data/com.ronin.vanta.fixed/files client-qa-debug || true
grep -Eq '^OK \([0-9]+ tests?\)' client-debug-tests.txt
adb shell am instrument -w -r -e class com.ronin.vanta.ProcessProbe#prepare com.ronin.vanta.fixed.test/androidx.test.runner.AndroidJUnitRunner | tee process-prepare.txt
grep -Eq '^OK \([0-9]+ tests?\)' process-prepare.txt
adb shell am force-stop com.ronin.vanta.fixed
adb shell am start -n com.ronin.vanta.fixed/com.ronin.vanta.MainActivity
sleep 3
adb shell am instrument -w -r -e class com.ronin.vanta.ProcessProbe#verify com.ronin.vanta.fixed.test/androidx.test.runner.AndroidJUnitRunner | tee process-restore.txt
grep -Eq '^OK \([0-9]+ tests?\)' process-restore.txt
adb install -r distribution/fixed-release-qa.apk
adb shell am instrument -w -r -e notClass com.ronin.vanta.ProcessProbe,com.ronin.vanta.BackgroundDeviceTest com.ronin.vanta.fixed.test/androidx.test.runner.AndroidJUnitRunner | tee client-release-tests.txt
adb logcat -d > client-release-logcat.txt
adb pull /sdcard/Android/data/com.ronin.vanta.fixed/files client-qa-release || true
grep -Eq '^OK \([0-9]+ tests?\)' client-release-tests.txt
adb install -r distribution/field-release-qa.apk
adb install -r distribution/field-test.apk
adb shell am instrument -w -r com.ronin.fieldreport.test/androidx.test.runner.AndroidJUnitRunner | tee field-tests.txt
adb pull /sdcard/Android/data/com.ronin.fieldreport/files field-evidence || true
grep -Eq '^OK \([0-9]+ tests?\)' field-tests.txt
adb uninstall com.ronin.fieldreport
adb install -r client-qa-release/field-report-client-signed.apk
adb shell am start -W -n com.ronin.fieldreport/com.ronin.fieldreport.MainActivity | tee field-client-artifact-launch.txt
grep -q 'Status: ok' field-client-artifact-launch.txt
sleep 2
adb exec-out screencap -p > field-client-artifact.png
adb install -r distribution/field-demo-qa.apk
adb install -r distribution/field-demo-test.apk
adb shell am instrument -w -r com.ronin.fieldreport.demo.test/androidx.test.runner.AndroidJUnitRunner | tee field-demo-tests.txt
adb logcat -d > field-demo-logcat.txt
adb pull /sdcard/Android/data/com.ronin.fieldreport.demo/files field-demo-evidence || true
grep -Eq '^OK \([0-9]+ tests?\)' field-demo-tests.txt
adb shell am force-stop com.ronin.fieldreport.demo
adb shell am start -W -n com.ronin.fieldreport.demo/com.ronin.fieldreport.MainActivity | tee field-demo-launch.txt
grep -q 'Status: ok' field-demo-launch.txt
adb shell am start -W -n com.ronin.vanta.fixed/com.ronin.vanta.MainActivity | tee client-launch.txt
adb exec-out screencap -p > client-launch.png
