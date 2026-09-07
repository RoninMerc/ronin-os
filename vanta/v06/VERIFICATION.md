# Ronin Vanta 0.6 — verified Android redesign

## Frozen application source

Commit: `0fa385530d09462ced4dffad14ad699d462c030f`.

Build and debug-emulator run: https://github.com/RoninMerc/ronin-os/actions/runs/34168746764 — SUCCESS.

Release-payload and layout run: https://github.com/RoninMerc/ronin-os/actions/runs/34169071470 — SUCCESS.

Java 17, Gradle 8.13, AGP 8.11.1, compile/target SDK36, minimum SDK29, versionCode60, versionName0.6.0.

## Executed results

- Debug, release, instrumentation APK and alternative personal-package release compilation passed.
- JVM tests: 118 passed, zero failures/errors/skips (38 reliability, 22 catalogue/video, 26 experience, 22 protocol, 10 input protocol).
- Android 10/API29 debug: 37 passed.
- Android 16/API36 debug: 37 passed.
- Android 10/API29 release payload: 37 passed.
- Android 16/API36 release payload: 37 passed.
- Release startup, actual soft-keyboard input, 1.3x system text and landscape checks passed on both API levels; actual screenshots inspected. Large-text header clipping found during review was corrected before these final runs.
- Lint: 0 errors, 105 warnings; no blanket suppression. Most warnings are programmatic English UI strings.
- No Vanta-process fatal exception, ANR or leaked-window markers were found in the captured test runs. The API36 debug log retains a separate Google Play services background crash; this is not an assertion that every emulator system service is error-free.
- The 1,500-message RecyclerView test retained fewer than 40 attached transcript children. This is not a frame-rate/latency benchmark.

## Delivered updates

Main track: `com.ronin.vanta.fixed`, compatible with the supplied 0.4.2 APK signing identity.
APK SHA-256: `ed99f50d9d97403aa78508cc65908a201be2bc6e479db3df27fdd4957bd818a9`.
Certificate SHA-256: `e67ead57ec87b387a898b6e804e9c5207b254e9fd1393eab5c2ee60a5115ce47`.

Alternate track: `com.ronin.vanta.personal`, compatible with the supplied 0.5 APK signing identity.
APK SHA-256: `c88851cab3db35b979fdfda437b4ba4959b249253310898cca26d95ecb1332e6`.
Certificate SHA-256: `113a98302e5642c6f5e2a3df1da892a848115a80f7911a37c25f9a49e21c6c94`.

Both delivered APKs: 6,625,477 bytes; signature/ZIP integrity verified; every non-signature entry matches its compiled release candidate. Matching package/certificate avoids forcing an uninstall to update the corresponding existing installation. The earlier temporary 0.4.1 Preview is a different package.

Owner signing keys were used only in the local protected signing environment, never uploaded to CI/repository. Release instrumentation used disposable QA signing identities. The owner-signed delivery APKs and the alternate personal package were not separately installed on the user's physical phone.

## Implemented experience

Conversation-first native shell and composer; reusable dark Ronin design tokens/original icons; contextual mode/tools/settings sheets; unified model search/favourites/recent/details; truthful uncensored metadata; opt-in local rules routing for new text/code/Forge work; recycled native transcript/code formatting; searchable history; encrypted output index; encrypted restart-recoverable image preview; consolidated 0.4.2 video contracts; preserved actual provider/voice/Forge engines; scoped lifecycle cancellation and back/inset/text-scale handling.

## Boundaries

No provider API credentials or physical phone/audio/camera/Bluetooth connection were available. Paid generation, cloud voice quality and live model-driven Forge repair were not executed. Tests use isolated fixtures; production calls use actual providers. Existing private Android Forge worker was preserved, not re-tested remotely in this redesign turn. Arbitrary offline model/voice runtimes, proprietary E2EE/media-edit adapters, full-duplex/background calling, commercial billing/cloud sync and Windows packaging remain outside this delivered Android implementation. No placeholder controls advertise them as functional. Main/default and separate desktop branches remain unchanged.
