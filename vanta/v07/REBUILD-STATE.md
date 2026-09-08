# Ronin Vanta master rebuild — incomplete checkpoint

This is not a release, not a verified build, and not a completed implementation report.

## Baseline

- Exact delivered Android 0.6 baseline: commit `0fa385530d09462ced4dffad14ad699d462c030f`.
- New isolated branch: `build/vanta-master-07`.
- Preserve both existing package/signing tracks: `com.ronin.vanta.fixed` and `com.ronin.vanta.personal`.
- No external Forge worker, other Ronin application, desktop project or local-model infrastructure was modified.

## Last confirmed working tree

`/mnt/data/vanta07/ronin-vanta-0.6`

Before the execution failure, this container tree contained source changes for:

- A canonical SQLite-backed API model registry and first-class Featherless configuration, catalogue pagination and metadata provenance.
- Model facets/comparison, preserving existing selection and policy-label behavior.
- Prompt Architect strategy, real API execution stages, prompt history/actions and target handoff.
- Durable encrypted job records, event-derived progress, application-owned execution, foreground data-transfer service, scheduled remote polling, cancellation and notifications.
- Prompt/Activity/project/speech workspaces using the original shared design system.
- MainActivity integration for durable chat/image/video/catalogue/Forge jobs and simplified Forge entry.
- Vanta-side generic source project handling and consumption of the already-existing Android worker.

These feature changes were written to the container but had NOT been compiled, tested or uploaded to this branch when runtime access failed. Do not infer that this GitHub branch contains those changes merely because this note lists them. They require filesystem recovery, code review, compilation and the entire regression/background/progress test loop.

Local patch helpers at the last confirmed checkpoint:

- `/mnt/data/vanta07/integrate_registry.py`
- `/mnt/data/vanta07/engine_patches.py`
- `/mnt/data/vanta07/model_picker_upgrade.py`
- `/mnt/data/vanta07/integrate_main07.py`
- `/mnt/data/vanta07/fix07_contracts.py`

The working tree also contained `docs/BASELINE-07-INVENTORY.json` and `docs/07-REGRESSION-INVENTORY.md`.

## What actually ran

The existing 0.6 baseline was reconstructed, compiled and tested in GitHub Actions while preparing an isolated public compiler/dependency bundle:

https://github.com/RoninMerc/ronin-os/actions/runs/34172027349

That success verifies the existing baseline and compiler preparation, NOT the new 0.7 source.

The compiler transfer workflow also completed:

https://github.com/RoninMerc/ronin-os/actions/runs/34173161476

## Observed blocker

Container execution and both Python execution tools began returning `ClientError`, including basic filesystem/echo commands. This persisted across repeated probes. One compiler archive separately exceeded the Files materialization limit: 419,430,846 bytes versus 104,857,600 bytes. The artifact-size rejection and runtime errors were observed; no unverified claim is made that one caused the other.

The GitHub connector remained usable. However, current container source changes and retained signing materials could no longer be read/packaged from the working filesystem. No new signed compatible APK could be produced or verified.

## Outstanding

- Recover and inspect the actual modified working tree; do not replace it with this note.
- Correct MainActivity/Hub/registry interface contracts and test seams through real compilation, not visual inspection.
- Finish audit items: transactional job/summary persistence, notification progress updates, asynchronous large catalogue filtering, source/media lifecycle and secure handoffs.
- Run new Prompt Architect, metadata, progress and background tests plus all inherited 0.6 tests.
- Test scheduled resumption, ambiguous paid requests, process death, Home/lock/network changes and concurrent jobs.
- Package/release-sign with the existing retained owner certificates only; never publish signing keys or provider credentials.

No new release APK, successful 0.7 test run, or completed master-spec implementation is asserted.
