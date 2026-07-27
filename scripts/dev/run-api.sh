#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

source "$ROOT_DIR/scripts/dev/load-local-env.sh"
source "$ROOT_DIR/scripts/dev/operational-memory-cursor-preflight.sh"
source "$ROOT_DIR/scripts/dev/postgres-cortex-common.sh"

cortex_require_postgres_url
cortex_require_text CORTEX_POSTGRES_USER
cortex_prepare_postgres_password

if [[ "${CORTEX_POSTGRES_RUNTIME_READY:-false}" != "true" ]]; then
  echo "CORTEX_POSTGRES_RUNTIME_READY must be true only after V60 and a real ALFA bootstrap in the canonical database." >&2
  exit 1
fi

cortex_require_text CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_ID
cortex_require_secret_file CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE
cortex_require_text CORTEX_AUTH_OFFLINE_GRANT_KEY_ID
cortex_require_secret_file CORTEX_AUTH_OFFLINE_GRANT_PRIVATE_KEY_FILE
cortex_require_secret_file CORTEX_AUTH_OFFLINE_GRANT_PUBLIC_KEY_FILE

cortex_preflight_operational_memory_cursor_hmac

cd "$ROOT_DIR/apps/api"

export CORTEX_IMPORT_ENABLED="false"
export CORTEX_SYNC_ENABLED="${CORTEX_SYNC_ENABLED:-true}"
export CORTEX_AUTH_DEV_ADMIN_ENABLED="false"
export CORTEX_AUTH_PROVISIONING_ENABLED="false"
export SPRING_PROFILES_ACTIVE="local,postgresql"

API_PORT="${PORT:-${SERVER_PORT:-8080}}"
API_HEALTH_URL="http://127.0.0.1:${API_PORT}/api/health"

if command -v lsof >/dev/null 2>&1 &&
  lsof -nP -iTCP:"$API_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  if curl -fsS "$API_HEALTH_URL" >/dev/null 2>&1; then
    echo "Córtex API já está rodando em ${API_HEALTH_URL}."
    echo "Abra o app ou use Ctrl+C no terminal onde a API está rodando antes de iniciar outra instância."
    exit 0
  fi

  echo "A porta ${API_PORT} já está em uso, mas não respondeu ao health check do Córtex."
  echo "Processo usando a porta:"
  lsof -nP -iTCP:"$API_PORT" -sTCP:LISTEN
  echo ""
  echo "Pare esse processo ou defina PORT/SERVER_PORT para outra porta antes de rodar a API."
  exit 1
fi

./mvnw spring-boot:run
