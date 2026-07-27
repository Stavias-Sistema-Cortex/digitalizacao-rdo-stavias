#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/compose.local.yml"

if [ ! -f "$COMPOSE_FILE" ]; then
  echo "Missing compose.local.yml at repo root."
  exit 1
fi

source "$ROOT_DIR/scripts/dev/load-local-env.sh"
source "$ROOT_DIR/scripts/dev/postgres-cortex-common.sh"

cortex_require_postgres_url
cortex_require_text CORTEX_POSTGRES_USER
cortex_require_secret_file CORTEX_POSTGRES_PASSWORD_FILE

canonical_database="$(cortex_postgres_database_name)"
if [[ ! "${CORTEX_POSTGRES_DOCKER_URL:-}" =~ ^jdbc:postgresql://([^/:?]+)(:([0-9]+))?/${canonical_database}(\?.*)?$ ]]; then
  echo "CORTEX_POSTGRES_DOCKER_URL must target the Docker-reachable canonical database." >&2
  exit 1
fi

if [[ "${CORTEX_POSTGRES_RUNTIME_READY:-false}" != "true" ]]; then
  echo "CORTEX_POSTGRES_RUNTIME_READY must be true only after V60 and a real ALFA bootstrap." >&2
  exit 1
fi

cortex_require_text CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_ID
cortex_require_secret_file CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE
cortex_require_secret_file CORTEX_AUTH_OTP_HMAC_KEY_FILE
cortex_require_text CORTEX_AUTH_OFFLINE_GRANT_KEY_ID
cortex_require_secret_file CORTEX_AUTH_OFFLINE_GRANT_PRIVATE_KEY_FILE
cortex_require_secret_file CORTEX_AUTH_OFFLINE_GRANT_PUBLIC_KEY_FILE
cortex_require_text CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID
cortex_require_secret_file CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_FILE
cortex_require_text VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256

docker compose -f "$COMPOSE_FILE" up --build -d --remove-orphans

echo ""
echo "Córtex local stack is running."
echo ""
docker compose -f "$COMPOSE_FILE" ps
echo ""
echo "API:"
echo "  http://localhost:8081/api/health"
echo "  http://localhost:8081/api/readiness"
echo ""
echo "PWA:"
echo "  http://localhost:5173"
echo ""
echo "Canonical database:"
echo "  PostgreSQL only (no local MySQL primary)"
echo ""
echo "Readiness:"
echo "  curl -s http://localhost:8081/api/readiness | jq"
echo ""
echo "To view API logs:"
echo "  docker compose -f compose.local.yml logs -f cortex-api"
echo ""
echo "To stop:"
echo "  ./scripts/dev/stop-compose.sh"
