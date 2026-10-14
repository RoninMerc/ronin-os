# 1.1.59 / code171

The reported Android exit reason 6 identifies an ANR. Repeated feed and checkpoint polling previously decrypted relay keys on the UI thread. Polling now decrypts on its worker; configured checks inspect encrypted envelope structure without accessing Keystore. The encrypted storage and API authentication formats remain.

Checkpoint photo Base64 decoding, file writes and metadata serialization now run on a worker. The UI applies a completed newer revision atomically; filenames include the revision so stale work cannot overwrite newer photo content. Existing QR rules, coordinates, groups, order and custom radius persist.

Supported Android ANR traces are read on a worker and reduced to bounded main-thread method/line identifiers. Raw trace text, messages, credentials, URLs, report contents and non-main threads are discarded. Native recovery and original crash-handler delegation remain.

Retains TRL Phone/Tablet/Auto design, satellite map, fleet/GPS, voice, physical QR, automatic read-only reports/photos and signing. Tests now include Android 16/API36 and injected slow Keystore/photo work. The reported device's exact blocked stack has not yet been obtained.

- Android16 large-screen compatibility property preserves explicit Tablet landscape orientation while the existing layout remains responsive.
