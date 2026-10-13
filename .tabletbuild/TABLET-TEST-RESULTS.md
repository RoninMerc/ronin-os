# Verification scope

Release publication requires successful release/debug/instrumentation compilation, existing and new JVM suites, real APK verification and signing comparison with the installed-release baseline.

Android emulator checks exercise: native landscape map and permanent guard panels; marker movement and stale retention under synthetic network loss; a real 5 m activation policy with poor-accuracy rejection; settings persistence after restart; settings cog and missing location; supervisor access boundaries; and the retained SilverTrack dropdown/July-link checks. Additional smaller-screen rendering checks accompany API-specific transcripts.

Synthetic coordinates appear only in the visibly labelled debug-only fixture Activity, which is absent from the production APK. Fixture screenshots are test data, not live patrol positions.

A probe signed with the original application certificate installs actual 1.1.52, stores synthetic guard/checkpoint/voice/Keystore state, upgrades through Android PackageManager to 1.1.54, checks state and UID, and launches the production tablet UI.

Physical-device verification remains necessary for actual GPS accuracy at 5–15 m, live on-duty patrol availability, screen-off battery behavior, physical QR scanning and source scan recognition, street-tile access on the user's network, tablet-specific density/font/orientation behavior and Android Auto projection. These are not claimed as emulator-verified.

Exact passed counts, API levels, APK certificate/checksum, compilation output and device/upgrade transcripts are delivered with the successful release.

New verification exercises production MainActivity on a 360 dp phone: Auto initially shows the normal phone UI, manual Tablet selection creates the native landscape map, patrol labels fit all three panels, the mode persists after recreation, and Phone/Auto return correctly. Ronin Forge mirroring and your car monitor remain unverified physically.
