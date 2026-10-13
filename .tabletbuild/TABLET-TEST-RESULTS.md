# Verification scope
Build results are included in CONFIRMED-TEST-RESULTS.md after the release pipeline succeeds.
The suite includes retained unit and Android regression tests plus injected browser/map/engine startup failures, interrupted-browser recovery, pairing preservation, and real signed release force-stop/relaunch checks on APIs30 and35.
Automated GPS/report data are explicitly synthetic. Physical tablet/phone launches, live SilverTrack imports, field GPS and Ronin Forge mirroring remain device verification items.
