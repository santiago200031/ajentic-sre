#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== Seeding Multi-Cloud Emulation Environment ==="
echo "Loading fleet baseline from ${SCRIPT_DIR}/checkout-fleet.json"

if command -v aws >/dev/null 2>&1; then
    bash "${SCRIPT_DIR}/seed-localstack.sh" || echo "LocalStack seed skipped (not reachable or aws cli not installed)"
else
    echo "[warning] aws CLI not found in path; LocalStack seeding requires aws CLI. WireMock stubs serve recorded responses directly."
fi

echo "=== Emulation Seeding Ready ==="
