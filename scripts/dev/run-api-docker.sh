#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

source "$ROOT_DIR/scripts/dev/load-local-env.sh"

if [ -z "${CORTEX_DB_PASSWORD:-}" ]; then
  echo "Missing CORTEX_DB_PASSWORD."
  echo "Set it with:"
  echo "export CORTEX_DB_PASSWORD='your-local-password'"
  echo ""
  echo "Or create a local .env file. Never commit .env."
  exit 1
fi

if [ -z "${CORTEX_AUTH_JWT_SECRET:-}" ]; then
  echo "Missing CORTEX_AUTH_JWT_SECRET."
  echo "Set it with a long random value in your shell or local .env."
  exit 1
fi

docker build -t cortex-api:local "$ROOT_DIR/apps/api"

docker run --rm -p 8081:8080 \
  -e CORTEX_DB_URL="${CORTEX_DB_URL:-jdbc:mysql://host.docker.internal:3306/cortex_dev?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC}" \
  -e CORTEX_DB_USER="${CORTEX_DB_USER:-cortex_app}" \
  -e CORTEX_DB_PASSWORD="$CORTEX_DB_PASSWORD" \
  -e CORTEX_AUTH_JWT_SECRET="$CORTEX_AUTH_JWT_SECRET" \
  -e CORTEX_IMPORT_ENABLED="${CORTEX_IMPORT_ENABLED:-true}" \
  -e CORTEX_SYNC_ENABLED="${CORTEX_SYNC_ENABLED:-true}" \
  -e CORTEX_SYNC_INITIAL_DELAY_MS="${CORTEX_SYNC_INITIAL_DELAY_MS:-10000}" \
  -e CORTEX_SYNC_FIXED_DELAY_MS="${CORTEX_SYNC_FIXED_DELAY_MS:-300000}" \
  -e CORTEX_ACADEMY_DB_URL="${CORTEX_ACADEMY_DB_URL:-${ACAD_DB_URL:-}}" \
  -e CORTEX_ACADEMY_DB_USER="${CORTEX_ACADEMY_DB_USER:-${ACAD_DB_USER:-}}" \
  -e CORTEX_ACADEMY_DB_PASSWORD="${CORTEX_ACADEMY_DB_PASSWORD:-${ACAD_DB_PASSWORD:-}}" \
  -e CORTEX_ZELADORIA_DB_URL="${CORTEX_ZELADORIA_DB_URL:-${ZLD_DB_URL:-}}" \
  -e CORTEX_ZELADORIA_DB_USER="${CORTEX_ZELADORIA_DB_USER:-${ZLD_DB_USER:-}}" \
  -e CORTEX_ZELADORIA_DB_PASSWORD="${CORTEX_ZELADORIA_DB_PASSWORD:-${ZLD_DB_PASSWORD:-}}" \
  cortex-api:local
