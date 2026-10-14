# Verification scope — 1.1.58

The release workflow compiles signed/debug/instrumentation APKs, verifies the original certificate, independently compiles the full exported source, and runs existing unit and Android regression checks. See CONFIRMED-TEST-RESULTS.md and individual API transcripts for completed results.

New tests verify diagnostic privacy, original uncaught-handler delegation, and a recovery screen retaining encrypted pairing. GPS/source fixtures are synthetic. Real-device exception, live imports, fleet/GPS, physical QR and Ronin Forge still require field verification. No user crash report is automatically uploaded.