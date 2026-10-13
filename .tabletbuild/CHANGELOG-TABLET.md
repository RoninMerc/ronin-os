# 1.1.57 / code169
- Native TRL startup frame appears before engine, map and embedded browser initialization.
- Paired Readers display fleet/activity without constructing SilverTrack WebView.
- Dashboard renderer attachment no longer rebuilds the satellite map on every renderer replacement.
- Browser construction/renderer failures have a native retry path; incomplete browser/map startup is remembered across process restarts.
- Engine startup failures show bounded phase/class diagnostics and a retry control, with no credentials or exception messages.
- Removed offscreen browser preraster allocation during startup; saved sessions and original authentication formats remain intact.
- Lifecycle callbacks handle deferred initialization and recovery.
- Retained dark TRL landscape dashboard, satellite imagery, Phone/Tablet/Auto selection, physical QR, fleet/GPS, guard selection, laps, voice, SilverTrack filters, automatic report paging and protected photos.
No SilverTrack credentials are sent to patrol devices. No cloud service, database or signing key changed.

- Saved engine/voice state loads on a worker, including foreground-service restarts; Android's foreground notification appears promptly while loading.

- Existing Android 9 minimum retained; startup checks include the oldest supported Android version.

- Vehicle report links no longer require String.matchAll; labelled field enumeration avoids Array.flatMap, preserving capture on the Android9 bundled browser.
