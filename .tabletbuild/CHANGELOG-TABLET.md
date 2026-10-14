# 1.1.58 / code170

Adds local technical crash diagnostics and a native recovery screen after an unexpected process exit. Copy crash details works without starting monitoring, GPS or the dashboard. Continue restarts normal operation with original settings. Uncaught exceptions still go through Android's original crash handler; no crash is fabricated as successful activity. Diagnostics exclude exception messages, keys, SilverTrack credentials, URLs and report contents. Android 11+ native crash/ANR exit reasons are recorded without traces or descriptions.

Monitoring and GPS services pause automatic restart only while an unacknowledged crash is pending. Acknowledgement retains the diagnostic text and changes no pairing, role, checkpoint or voice data. Dashboard observer callbacks are marshalled to the UI thread. Removed unnecessary offscreen preraster allocation for report-detail capture.

Retains the TRL tablet design, satellite map, Phone/Tablet/Auto modes, automatic read-only report paging, protected photos, physical QR, fleet, guard and voice workflows. This is a recovery/diagnostic release; the actual reported device exception has not yet been obtained.