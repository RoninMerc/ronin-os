#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
mkdir -p backups
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="backups/ronin-remote-server-${STAMP}.tar.gz"

if [ ! -d data ]; then
  echo "No data directory exists yet. Nothing to back up." >&2
  exit 1
fi

tar -czf "$OUT" data .env compose.yml
chmod 600 "$OUT" 2>/dev/null || true
echo "Backup created: $OUT"
