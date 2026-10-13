# TRL Patrol Link 1.1.51 — SilverTrack filter fix

Install TRL-Patrol-Link-v1.1.51.apk over the existing Patrol Link app. Do not uninstall or clear data. Package and signing identity are preserved.

On the Master phone, open Silvertracker and the signed-in Issue Monitor.
- Tap an HTML dropdown to open an activity-backed native picker using the website's actual options.
- If the page's dropdown still fails on your WebView, use the new **Filter reported issue** button above the page.
- Choose the parking-breach option you want; use SilverTrack's normal Update control or Patrol Link's Refresh now to read the filtered monitor.
- Automatic polling pauses while the picker is open and during a short interaction window.

The change preserves the existing authorised WebView session, collector, checkpoint syncing, device settings, GPS and signing format. It does not add administrator access or store SilverTrack credentials elsewhere.

Verification before publication: existing JVM regression suite, signed APK build, comparison with the original installed signing certificate, and three Android emulator checks. The checks use real touch input on an application-context WebView, verify native selection and the website change event, preserve unrelated filters, test the toolbar fallback, and reject an untrusted page.

Actual live SilverTrack layout and full report extraction still require acceptance on the real Master phone.
