#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/compose.local.yml"

if [ ! -f "$COMPOSE_FILE" ]; then
  echo "Missing compose.local.yml at repo root."
  exit 1
fi

source "$ROOT_DIR/scripts/dev/load-local-env.sh"

if [ -z "${CORTEX_DB_PASSWORD:-}" ]; then
  echo "Missing CORTEX_DB_PASSWORD."
  exit 1
fi

if [ -z "${CORTEX_MYSQL_ROOT_PASSWORD:-}" ]; then
  echo "Missing CORTEX_MYSQL_ROOT_PASSWORD."
  exit 1
fi

if [ -z "${CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_ID:-}" ]; then
  echo "Missing CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_ID."
  exit 1
fi

if [ -z "${CORTEX_AUTH_CPF_HMAC_CURRENT_KEY:-}" ]; then
  echo "Missing CORTEX_AUTH_CPF_HMAC_CURRENT_KEY for the local compose stack."
  exit 1
fi

docker compose -f "$COMPOSE_FILE" up --build -d

echo ""
echo "Córtex local stack is running."
echo ""
docker compose -f "$COMPOSE_FILE" ps
echo ""
echo "API:"
echo "  http://localhost:8081/api/health"
echo ""
echo "Useful commands:"
echo "  curl -s http://localhost:8081/api/health | jq"
echo "  curl -s \"http://localhost:8081/api/assets?query=CBA\" | jq"
echo "  curl -i -X POST http://localhost:8081/api/assets/import/zld"
echo ""
echo "To view API logs:"
echo "  docker compose -f compose.local.yml logs -f cortex-api"
echo ""
echo "To stop:"
echo "  ./scripts/dev/stop-compose.sh"
