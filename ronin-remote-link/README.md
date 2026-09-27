# Ronin Remote Link v0.1.0

Phase 1 of Ronin Remote Link uses an unmodified RustDesk 1.4.9 client engine and RustDesk Server OSS 1.1.16 behind Ronin-owned Windows and Android launch/control applications.

## Targets
- Windows 10/11 x64: native .NET 8 WinForms controller/host setup utility.
- Android phone: native Android APK.
- Android tablet: native Android APK with a separate package/flavor and adaptive large-screen layout.
- Self-hosted rendezvous/relay: Docker Compose for RustDesk Server OSS.

## Security model
- Do not expose Windows RDP/VNC ports.
- Use a self-hosted RustDesk ID/relay server.
- Pin the Windows engine download to RustDesk 1.4.9 and verify SHA-256 before installation.
- Android launches the installed RustDesk engine with an explicit package intent so another app cannot claim the connection URI.
- Ronin Remote Link does not store the access password unless the user explicitly enables local encrypted storage on Android.
- Server WebSocket ports 21118/21119 are not exposed by the supplied Compose file because v0.1 does not use the web client.

## First setup order
1. Deploy `server/compose.yml` on a small Linux VPS with a public DNS name or stable IP.
2. Start it and read `server/data/id_ed25519.pub`.
3. Install the Windows Ronin Remote Link build.
4. Use **Install / Repair Engine**, then configure the RustDesk engine to the self-hosted ID server and public key.
5. Use **Install Service**, **Get This PC ID**, and **Set Unattended Password** on the home desktop.
6. Install the Ronin Android APK plus the pinned RustDesk 1.4.9 Android engine.
7. Enter the desktop ID, server and public key in Ronin Remote Link and connect.

The remote engine is isolated deliberately. Phase 2 can replace RustDesk with Ronin Core without changing the Ronin device/settings model.

## Build
GitHub Actions workflow: `.github/workflows/ronin-remote-link.yml`.
