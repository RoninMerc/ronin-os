# Ronin Remote Link v0.2 - self-hosted server

Use a small Linux VPS with Docker Engine and Docker Compose. The supplied Compose file runs the pinned RustDesk Server OSS engine behind Ronin-owned configuration.

## First deployment

1. Copy the `server` folder to the VPS.
2. Rename `.env.example` to `.env`.
3. Set `PUBLIC_HOST` to the VPS DNS name or stable public IP.
4. Permit inbound TCP **21115-21117** and UDP **21116** in the VPS provider firewall and the host firewall.
5. Run:
   ```bash
   chmod +x status.sh backup.sh
   docker compose pull
   docker compose up -d
   ./status.sh
   ```
6. After first start, obtain the server public key:
   ```bash
   cat data/id_ed25519.pub
   ```
   Save this as the **Server public key** used by the Ronin clients.

## Hardening in v0.2

- RustDesk Server OSS remains pinned to `1.1.16` unless `.env` is deliberately changed.
- Containers run with `no-new-privileges` and all Linux capabilities dropped.
- Logs rotate at 10 MB with three files retained per container.
- Only the native client ports are published; WebSocket ports 21118/21119 remain closed.
- `data/` contains the server identity and must be backed up.

## Backup

Run:
```bash
./backup.sh
```

This writes a timestamped archive to `backups/`. Keep a copy somewhere other than the VPS. Losing `data/id_ed25519` changes the server identity/key and requires every client to be reconfigured.

## Status

Run:
```bash
./status.sh
```

It shows container state and whether the server public key exists. Client applications also have a connection-health check for TCP 21116 and 21117.
