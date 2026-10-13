# TRL Patrol Link 1.1.52 — historical parking capture

Install this APK over the existing Master phone app. The package, signing certificate, pairing, settings and checkpoints are preserved.

1. Open Silvertracker inside Patrol Link on the Master / Supervisor phone.
2. Load the July parking-breach results using the date and reported-issue filters.
3. Tap **Capture parking reports**, then **Capture this page**.
4. Leave the dialog open while it reads up to 30 eligible reports.
5. Read the counts and latest cloud result: created, updated, duplicate, review or ignored.
6. Refresh Mother Base and check Breach history / Import review. Sync the paired Breach app.

Capture follows only recognised same-origin read-only report URLs with stable report IDs. Unsupported links are reported instead of silently ignored. The full historical archive is not crawled automatically; repeat capture on each loaded results page.

Historical browsing does not replace the live patrol activity snapshot or announce July rows as live activity. Vehicle events retain source dates, and existing manual vehicle corrections are protected.

The previous filter fix is retained. Release checks include JVM regressions, the three native filter tests, a July report-link test in Android WebView, and signing comparison against the installed release.
