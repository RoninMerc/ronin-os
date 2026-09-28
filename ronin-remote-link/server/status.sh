#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"

echo "=== Ronin Remote Link server ==="
docker compose ps

echo
if [ -f data/id_ed25519.pub ]; then
  echo "Server public key:"
  cat data/id_ed25519.pub
else
  echo "Server public key not present yet. Start the stack and check again."
fi
