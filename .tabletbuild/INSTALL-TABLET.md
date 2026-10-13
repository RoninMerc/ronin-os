# TRL Patrol Link 1.1.55

Install **TRL-Patrol-Link-v1.1.55.apk** over the existing Patrol Link installation. Do not uninstall or clear app data. Allow Android's “Install unknown apps” permission for the browser/file manager used to open the APK.

Settings cog → Display mode → Phone, Tablet or Auto. Tablet mode uses landscape for a mirrored car monitor; Auto follows the device display size. Mirror the phone through Ronin Forge as usual, then choose Tablet if required. Actual Forge/monitor scaling needs device verification.

The map now uses satellite imagery and requires an internet connection for uncached tiles. GPS and fleet data retain their existing relay and role restrictions.

On the Master phone, open SilverTrack through the dashboard. One small tools cog provides app actions. Use Reported issues to check several categories, then Apply. For a site single-select, All issues is requested and matching rows are shown on the loaded page. Date, guard and site controls remain available. Use paging to load additional historical rows.

Tools cog → Capture parking reports starts reading eligible reports on the currently loaded page. The device must have Publisher pairing, rather than Reader pairing alone. Watch the capture status for missing detail links, missing labelled registration or relay errors. Capture does not silently claim success; retained stable IDs let retries update rather than duplicate reports.

No new SilverTrack login is needed on Breach devices. Existing local data and cloud relay operation remain intact. Keep the existing vehicle/checkpoint backups.

The full source ZIP omits production signing keys and personal voice recordings. Debug builds are not compatible in-place updates to the operational signed app.
