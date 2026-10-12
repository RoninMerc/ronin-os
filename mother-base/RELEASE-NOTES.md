TRL Mother Base Windows installer and compatible Patrol Link 1.1.49 checkpoint update.

Includes persistent PostgreSQL relay code for SilverTrack vehicle normalisation, deduplication, report revisions, protected supervisor corrections and central checkpoint ownership. Windows keys/cache use DPAPI.

Verified: Windows publish/startup/install checks, Android JVM tests and signing-certificate match, 12 cloud regression tests and a real-PostgreSQL relay integration test.

**Integration preview:** production Render rollout, actual SilverTrack report-detail extraction, the TRL Breach Android source/data-model adapter and physical-device acceptance are still pending. The current feed extractor supplies activity summaries; it does not yet automatically open detailed vehicle reports.

Read SETUP.md before changing production services or APKs. Back up the existing checkpoint set first. Use the consolidated feed URL as both feed and fleet URL after cloud deployment. No production credentials are included.
