# Ronin Remote Link - self-hosted server

Use a Linux VPS with Docker Engine and Docker Compose.

1. Copy `compose.yml` and `.env.example` to the server.
2. Rename `.env.example` to `.env` and set `PUBLIC_HOST`.
3. Permit inbound TCP 21115-21117 and UDP 21116 in the VPS/firewall.
4. Run:
   ```bash
   docker compose pull
   docker compose up -d
   ```
5. After first start:
   ```bash
   cat data/id_ed25519.pub
   ```
   Save that public key. It is the **Key** used by every Ronin client.

This v0.1 compose file intentionally does not publish 21118/21119 because the web client is not used.

Back up the `data` directory. The server identity keys live there.
