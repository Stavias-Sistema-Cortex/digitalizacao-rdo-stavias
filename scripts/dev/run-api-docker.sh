#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [ -f "$ROOT_DIR/.env" ]; then
  set -a
  source "$ROOT_DIR/.env"
  set +a
fi

if [ -z "${CORTEX_DB_PASSWORD:-}" ]; then
  echo "Missing CORTEX_DB_PASSWORD."
  echo "Set it with:"
  echo "export CORTEX_DB_PASSWORD='your-local-password'"
  echo ""
  echo "Or create a local .env file. Never commit .env."
  exit 1
fi

docker build -t cortex-api:local "$ROOT_DIR/apps/api"

docker run --rm -p 8081:8080 \
  -e CORTEX_DB_URL="${CORTEX_DB_URL:-jdbc:mysql://host.docker.internal:3306/cortex_dev?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC}" \
  -e CORTEX_DB_USER="${CORTEX_DB_USER:-cortex_app}" \
  -e CORTEX_DB_PASSWORD="$CORTEX_DB_PASSWORD" \
  -e CORTEX_IMPORT_ENABLED="${CORTEX_IMPORT_ENABLED:-false}" \
  cortex-api:local
