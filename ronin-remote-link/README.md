# Ronin Remote Link v0.2.0

Phase 1 of Ronin Remote Link uses an **unmodified RustDesk 1.4.9 client engine** and **RustDesk Server OSS 1.1.16** behind Ronin-owned Windows and Android launcher, setup, security and diagnostics applications.

## Targets

- **Windows 10/11 x64**: native .NET 8 WinForms host/controller utility.
- **Android phone**: native Android APK.
- **Android tablet**: separate Android flavor/package with a large-screen layout.
- **Self-hosted rendezvous/relay**: hardened Docker Compose package for RustDesk Server OSS.

## What changed in v0.2

### Windows
- Added one-click host setup.
- Added host-service and engine-state checks.
- Added self-hosted server connectivity tests for TCP 21116/21117.
- Added copyable host ID and local diagnostic log.
- Streamed SHA-256 verification of the pinned Windows engine installer.
- Access passwords are still never written to the Ronin Windows settings file and are cleared from the UI after a launch request.

### Android phone + tablet
- Detects whether the remote engine is installed and reports its installed version.
- Chooses the correct official pinned engine download for the device ABI.
- Adds optional device-credential authentication before every remote session (enabled by default).
- A saved password is no longer decrypted and displayed during app startup; it is retrieved only when a connection is launched.
- Adds TCP 21116/21117 server reachability tests.
- Adds privacy-safe copyable diagnostics that omit the password and server-key value.
- Preserves separate phone and tablet builds.

### Server
- Added `no-new-privileges`, dropped Linux capabilities, PID limits and bounded log rotation.
- Added status and backup scripts.
- Kept WebSocket ports 21118/21119 closed because Phase 1 does not use the web client.

## Security model

- Do not expose Windows RDP or VNC ports.
- Use the self-hosted RustDesk ID/relay server.
- Windows engine download is pinned to RustDesk 1.4.9 and SHA-256 verified before installation.
- Android launches the installed RustDesk package explicitly so another application cannot claim the connection URI.
- Android optional password storage uses AES-256-GCM with a key generated and retained by Android Keystore.
- Device unlock can be required before each Android remote session.
- Server identity material lives under `server/data/` and should be backed up securely.

## First setup order

1. Deploy `server/compose.yml` on a Linux VPS with a public DNS name or stable IP.
2. Start it and copy `server/data/id_ed25519.pub`.
3. Install the Windows Ronin Remote Link build on the home/host PC.
4. Run **ONE-CLICK HOST SETUP**.
5. Configure the engine to your self-hosted server/public key and set a strong unattended password.
6. Install the Ronin Android phone/tablet APK and the pinned official RustDesk 1.4.9 Android engine.
7. Enter the desktop ID, server and public key, run **TEST SERVER**, then connect.

The remote engine remains deliberately isolated so Phase 2 can replace RustDesk with Ronin Core without replacing the Ronin device/settings model.

## Build

GitHub Actions workflow: `.github/workflows/ronin-remote-link.yml`.
