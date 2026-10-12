# TRL Mother Base 0.1.0 — integration preview

## Delivery status

The Windows installer and cloud integration are built from the `trl-mother-base-v1` branch in RoninMerc/ronin-os. This is an integration preview, not a verified production rollout.

Implemented:
- Windows desktop for Patrol 1/2/3 fleet status, SilverTrack activity, vehicle administration, breach history, pairing, import review and checkpoint editing.
- Windows DPAPI protection for relay keys and local data caches, scoped to the signed-in Windows user.
- Persistent PostgreSQL cloud snapshots; cloud operations have no dependency on the Windows app being open.
- SilverTrack vehicle import with source report IDs, duplicate suppression, report revision history, supervisor correction protection and deleted-vehicle review.
- Central checkpoint names, PBC1/PBC2/PBC3 groups, route order, latitude/longitude, scan distance and QR photos, using the existing Android wire format.
- Patrol Link 1.1.49: cloud checkpoint sync for Master and patrol devices, central ownership enforcement, and optional structured vehicle fields in the Master feed.

Still required:
- Recover the actual TRL Breach Android source/signing project from the “TRL breach App” conversation. The shared page is too large for the browser reader. No Breach APK or compatibility with its existing local data model has been verified.
- Verify the SilverTrack report-detail DOM using a representative report. The existing extractor exports activity summaries. The new uploader forwards structured `vehicle`/`vehicleText` fields if present; it does not yet automatically open detail reports. Reports without a labelled registration go into review and never create guessed vehicles.
- Configure/deploy the cloud adapter against the existing Render database.
- Run the live acceptance check with the real Master and Patrol 1/2/3 devices. Automated tests cover the relay API against real PostgreSQL, not a real SilverTrack session or physical Android devices.

## Windows installation

1. Download the Windows setup ZIP from the Mother Base Windows GitHub Actions artifact.
2. Extract it and run `TRL-Mother-Base-Setup-0.1.0.exe`.
3. Launch **TRL Mother Base** from the desktop or Start menu.
4. Open **Settings** and enter the relay URLs and relay keys described below.
5. Select **Save and connect**.

Windows 10/11 x64 is required. The installer includes the .NET runtime and needs no administrator access. The Windows EXE is not Authenticode-signed.

Relay keys are saved under `%LOCALAPPDATA%\TRL\MotherBase` using DPAPI. They cannot be copied to another Windows account by copying the settings files. No SilverTrack login fields exist in the desktop settings.

## Preserve checkpoint configuration before deployment

1. Leave the current Master phone and feed online.
2. In Mother Base Settings, use the existing Master feed URL, Publisher key and Reader key. These are relay pairing keys, not SilverTrack credentials.
3. In **Checkpoints**, choose **Load cloud checkpoints**. The app supports reading the old feed through the Reader key for migration.
4. Choose **Export checkpoint backup** and keep the JSON file in a safe location. It includes QR photographs and contains no relay keys.
5. Confirm names, mapped GPS points, groups and photos are present before changing the feed service.
6. Keep the existing Master APK until the checkpoint backup is complete. Keep a copy of the current APK for rollback.

## Existing Render workspace

Workspace: My Workspace (`tea-dattuirncjis73a19abg`).

Existing resources:
- Master feed: `ronin-silvertrack-master-feed` / `srv-db5p5odckfvc73avldeg`.
- Patrol relay: `ronin-patrol-link-relay` / `srv-db1se1s9v7es738vuhl0`.
- Secure fleet: `ronin-trl-secure-fleet` / `srv-db5ql9g473hc73c32kvg`.
- PostgreSQL: `ronin-patrol-link-db` / `dpg-db1sbvid0e5s7395ntg0-a`.

The existing secure fleet service previously failed at startup. It must not be treated as a working deployed Breach backend.

The adapter consolidates the feed and fleet API on one service and uses the existing fleet table layouts. Do not create a second source of truth on the PC.

### Cloud deployment configuration

After taking the checkpoint backup, use the existing Master feed service with:
- Repository: `https://github.com/RoninMerc/ronin-os`
- Branch: `trl-mother-base-v1`
- Root directory: `mother-base-cloud`
- Build command: `npm install --omit=dev && npm test`
- Start command: `npm start`
- Health check: `/health`
- Node: 22 LTS

Required environment settings:
- `DATABASE_URL`: the existing Render PostgreSQL internal connection URL. Configure it inside Render; do not paste it into chat, GitHub, the APK or desktop settings.
- `MASTER_FEED_KEY`: preserve the current Publisher key.
- `READER_FEED_KEY`: preserve the current Reader key; it must differ from the Publisher key.
- `TRL_MASTER_KEY`: preserve the existing fleet administrator key if one exists; otherwise create a cryptographically random secret of at least 32 characters.
- `TRL_EVIDENCE_KEY`: preserve the existing encryption key if evidence already exists. For an unused database, generate 32 random bytes and encode as 64 hexadecimal characters.

Generate keys with a trusted cryptographic generator. For example, Node's `crypto.randomBytes(32).toString('hex')`. Keep the evidence key backed up securely. Changing it makes existing encrypted evidence unreadable.

These values are unavailable to this chat's connectors, so production configuration has not been applied. Startup fails closed if database or key configuration is missing. Migrations add `trl_*` tables and retain existing vehicles, correction history, devices and evidence tables.

The existing free PostgreSQL instance is scheduled to expire on 4 November 2026. Configure its continued hosting before that date if it is to remain the operational database.

### Mother Base connection settings

Once the consolidated feed service is healthy:
- Master feed URL: `https://ronin-silvertrack-master-feed.onrender.com`
- Feed admin key: `MASTER_FEED_KEY`
- Reader key: `READER_FEED_KEY` (used for old-feed checkpoint migration)
- Fleet URL: the **same Master feed URL** for this consolidated deployment
- Fleet admin key: `TRL_MASTER_KEY`
- Patrol relay URL: `https://ronin-patrol-link-relay.onrender.com`
- Patrol relay key: the existing fleet relay key already used by Patrol Link

Do not give the feed Publisher key or fleet administrator key to patrol/Breach devices. Paired Breach clients use individual tokens. SilverTrack cookies, passwords and credentials remain on the authorised Master phone.

### Restore central checkpoints

1. Load the new cloud checkpoint set to obtain its current revision.
2. Import your checkpoint backup.
3. Review the names, PBC groups, route order, GPS coordinates, scan distances and QR photos.
4. Select **Publish checkpoint configuration**.
5. Install Patrol Link 1.1.49 over the current APK without clearing data.
6. Keep each phone's existing role and feed pairing settings.
7. Master uses its Publisher key to read configuration; Patrol 1/2/3 use the Reader key.
8. Once Mother Base publishes, the cloud rejects phone writes/recovery and the updated phones display the central configuration as read-only.

For photo changes, select a checkpoint and choose **Attach QR photo**. Use JPEG/PNG files under 650 KB. Scan distances must be 10–500 metres.

Concurrent edits are checked with a generation value. If publishing conflicts, reload the latest cloud set and reapply your edits. A stale phone cannot overwrite the central configuration.

## Vehicle imports and Breach API contract

Master `POST /v1/activity` accepts existing activity rows plus optional:
- `sourceReportId`: stable SilverTrack report/action ID; falls back to the existing row ID.
- `vehicle`: explicitly labelled vehicle fields.
- `vehicleText`: labelled report text, parsed server-side.
- `occurredAt`: ISO timestamp including timezone, if available.

Only Parking Breach, Vehicle Breach and Parking Violation categories are eligible. The importer requires a valid registration and stable event ID. It preserves O versus 0. It does not infer a vehicle from an unlabelled description.

Imports and activity publication commit in one PostgreSQL transaction. Retries with the same event ID/payload produce no duplicate vehicle or breach. Changed reports update the same breach and append an audit revision. Distinct report IDs append history for the existing vehicle.

Existing nonempty vehicle fields, including manual corrections, take precedence; imports fill missing fields. Revised report details are retained in the event history rather than overwriting confirmed manual values. A changed registration for an existing report requires review. Deleted vehicles are not resurrected by imports.

Source timestamps are retained verbatim. Only unambiguous ISO timestamps with offsets are converted to UTC.

For the Breach Android adapter:
- `POST /v1/pair` exchanges a ten-minute invitation for a device token.
- `GET /v1/vehicles` returns existing `plate/details/revision/updated_at` records and tombstones.
- `PUT /v1/vehicles` preserves the existing optimistic revision contract.
- `GET /v1/breaches` returns separate event/history records.
- `GET /v1/import-review` is administrator-only.
- Device reads/writes use `x-trl-device-token`, never the administrator secret.

No current Breach Android integration is claimed until its actual source and schema are recovered.

## Verification and acceptance

Automated:
- Android baseline reconstruction, JVM tests and signed release build passed.
- Windows self-contained publish, startup smoke test, setup compilation, unattended install and installed startup passed.
- 11 cloud regression tests and one real-PostgreSQL integration test passed, with no skipped tests.
- The PostgreSQL test covers authenticated Master upload, paired-device reads, duplicate retries, report revisions, manual correction protection, deleted-record protection, checkpoint revision conflicts, phone write rejection and relay restart persistence.
- Updated Android certificate verification is compared with the 1.1.48 release in the build workflow.

Live acceptance still required:
1. Deploy the adapter and confirm `/health` reports `persistent: true`.
2. Use an authorised SilverTrack vehicle report with a real stable ID and explicit registration.
3. Confirm the same registration, make/model/colour and warning event appear in Mother Base and the adapted Breach APK.
4. Replay the report: vehicle and event counts must remain unchanged.
5. Correct the report: one event is updated with an audit revision.
6. Publish a checkpoint with a QR photo; confirm matching config on Master and Patrol 1/2/3.
7. Close Mother Base. Confirm the Master can still publish and phones can still read cloud records.
8. Restart the cloud service. Confirm stored checkpoints and vehicle/breach history survive.
9. Check existing GPS duty state, guard sync, voice announcements, checkpoint proximity UI and existing Android settings.

## Rollback

Keep the previous APK and checkpoint backup. Deploy the prior feed commit only if a rollout check fails, restore the previous service settings, and keep the new database tables for review rather than deleting them. The PC has no operational server role, so closing/uninstalling it does not stop the cloud.

Do not claim the complete production integration is finished until the missing Breach source, SilverTrack detail extraction and physical-device acceptance checks are resolved.
