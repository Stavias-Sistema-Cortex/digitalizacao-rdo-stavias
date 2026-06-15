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

cd "$ROOT_DIR/apps/api"

export CORTEX_IMPORT_ENABLED="${CORTEX_IMPORT_ENABLED:-false}"

mvn spring-boot:run
