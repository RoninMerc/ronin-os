# Verification scope — TRL Patrol Link 1.1.55

Use CONFIRMED-TEST-RESULTS.md, APK-VERIFY.txt, APK-MANIFEST.txt, JVM-TESTS.json and the API-specific test transcripts in the delivery for confirmed build results.

The release pipeline must pass compilation, original-certificate verification, 87 JVM checks, independent sanitized-source compilation and 56 emulator checks across Android API 30 and 35 before publication.

Checks cover Phone/Tablet/Auto switching, tablet/phone panel bounds, settings persistence, satellite zoom/y/x addressing, browser layout and overview settings, multiple reported-issue selections/reload, nested/DIV/JavaScript report links, hidden login/frame fields, date extraction, read-only URL policy, Publisher feedback and a genuine signed upgrade from 1.1.52 preserving UID, encrypted Reader pairing, guard/checkpoint and voice state.

Screenshots are explicitly labelled synthetic fixtures. They do not show actual patrol positions or prove network imagery availability.

Remaining field checks: authenticated SilverTrack report formats and successful real vehicle import; live fleet reception; satellite tile delivery; Ronin Forge/car-monitor rendering; physical QR scanning; GPS accuracy, sleep/wake and battery behavior. The supplied recording was not processed because it was not accessible in this execution environment.
