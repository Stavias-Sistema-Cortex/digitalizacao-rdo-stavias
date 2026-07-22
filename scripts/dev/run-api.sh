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

if [ -z "${CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_ID:-}" ]; then
  echo "Missing CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_ID."
  exit 1
fi

if [ -z "${CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE:-}" ] &&
  [ -z "${CORTEX_AUTH_CPF_HMAC_CURRENT_KEY:-}" ]; then
  echo "Missing CPF lookup HMAC key."
  echo "Set CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE or CORTEX_AUTH_CPF_HMAC_CURRENT_KEY in your local .env."
  exit 1
fi

if [ -n "${CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE:-}" ] &&
  [ -n "${CORTEX_AUTH_CPF_HMAC_CURRENT_KEY:-}" ]; then
  echo "Configure only one CPF lookup HMAC key source."
  exit 1
fi

cd "$ROOT_DIR/apps/api"

export CORTEX_IMPORT_ENABLED="${CORTEX_IMPORT_ENABLED:-true}"
export CORTEX_SYNC_ENABLED="${CORTEX_SYNC_ENABLED:-true}"
export CORTEX_AUTH_DEV_ADMIN_ENABLED="${CORTEX_AUTH_DEV_ADMIN_ENABLED:-true}"
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"

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
