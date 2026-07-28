#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

source "$ROOT_DIR/scripts/dev/load-local-env.sh"
source "$ROOT_DIR/scripts/dev/normal-runtime-env.sh"
source "$ROOT_DIR/scripts/dev/postgres-cortex-common.sh"

canonical_database="$(cortex_postgres_database_name)"
if [[ ! "${CORTEX_POSTGRES_DOCKER_URL:-}" =~ ^jdbc:postgresql://([^/:?]+)(:([0-9]+))?/${canonical_database}(\?.*)?$ ]]; then
  echo "CORTEX_POSTGRES_DOCKER_URL must target the Docker-reachable canonical database." >&2
  exit 1
fi
if [[ "${CORTEX_POSTGRES_RUNTIME_READY:-false}" != "true" ]]; then
  echo "CORTEX_POSTGRES_RUNTIME_READY must be true only after V63 and a real ALFA bootstrap." >&2
  exit 1
fi

cortex_require_text CORTEX_POSTGRES_USER
cortex_require_secret_file CORTEX_POSTGRES_PASSWORD_FILE
cortex_require_text CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_ID
cortex_require_secret_file CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE
cortex_require_text CORTEX_AUTH_OFFLINE_GRANT_KEY_ID
cortex_require_secret_file CORTEX_AUTH_OFFLINE_GRANT_PRIVATE_KEY_FILE
cortex_require_secret_file CORTEX_AUTH_OFFLINE_GRANT_PUBLIC_KEY_FILE
cortex_require_text CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID
cortex_require_secret_file CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_FILE

academy_sync_enabled="${CORTEX_SYNC_ACADEMY_ENABLED:-false}"
academy_docker_environment=(
  -e "CORTEX_SYNC_ACADEMY_READINESS_MAX_AGE_MS=${CORTEX_SYNC_ACADEMY_READINESS_MAX_AGE_MS:-900000}"
)
case "$academy_sync_enabled" in
  true)
    cortex_require_text CORTEX_ACADEMY_DB_URL
    cortex_require_text CORTEX_ACADEMY_DB_USER
    cortex_require_text CORTEX_ACADEMY_DB_PASSWORD
    if [[ -n "${CORTEX_ACADEMY_DB_PASSWORD_FILE:-}" ]]; then
      echo "Local Academy QA requires only CORTEX_ACADEMY_DB_PASSWORD; unset CORTEX_ACADEMY_DB_PASSWORD_FILE." >&2
      exit 1
    fi
    academy_docker_environment+=(
      -e CORTEX_ACADEMY_DB_URL
      -e CORTEX_ACADEMY_DB_USER
      -e CORTEX_ACADEMY_DB_PASSWORD
    )
    ;;
  false)
    unset \
      CORTEX_ACADEMY_DB_URL \
      CORTEX_ACADEMY_DB_USER \
      CORTEX_ACADEMY_DB_PASSWORD \
      CORTEX_ACADEMY_DB_PASSWORD_FILE
    ;;
  *)
    echo "CORTEX_SYNC_ACADEMY_ENABLED must be true or false." >&2
    exit 1
    ;;
esac

CORTEX_WEB_PORT="${CORTEX_WEB_PORT:-5173}"
CORTEX_API_PORT="${CORTEX_API_PORT:-8081}"

docker build -t cortex-api:local "$ROOT_DIR/apps/api"

docker run --rm \
  --add-host host.docker.internal:host-gateway \
  -p "127.0.0.1:${CORTEX_API_PORT}:8080" \
  --mount "type=bind,src=$CORTEX_POSTGRES_PASSWORD_FILE,dst=/run/secrets/CORTEX_POSTGRES_PASSWORD,readonly" \
  --mount "type=bind,src=$CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE,dst=/run/secrets/cortex_cpf_hmac,readonly" \
  --mount "type=bind,src=$CORTEX_AUTH_OFFLINE_GRANT_PRIVATE_KEY_FILE,dst=/run/secrets/cortex_offline_private,readonly" \
  --mount "type=bind,src=$CORTEX_AUTH_OFFLINE_GRANT_PUBLIC_KEY_FILE,dst=/run/secrets/cortex_offline_public,readonly" \
  --mount "type=bind,src=$CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_FILE,dst=/run/secrets/cortex_memory_cursor_hmac,readonly" \
  -e SPRING_PROFILES_ACTIVE=local,postgresql \
  -e SPRING_CONFIG_IMPORT=configtree:/run/secrets/ \
  -e CORTEX_POSTGRES_URL="$CORTEX_POSTGRES_DOCKER_URL" \
  -e CORTEX_POSTGRES_USER="$CORTEX_POSTGRES_USER" \
  -e CORTEX_POSTGRES_RUNTIME_READY=true \
  -e CORTEX_WEB_PORT="$CORTEX_WEB_PORT" \
  -e CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_ID="$CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_ID" \
  -e CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE=/run/secrets/cortex_cpf_hmac \
  -e CORTEX_AUTH_OFFLINE_GRANT_KEY_ID="$CORTEX_AUTH_OFFLINE_GRANT_KEY_ID" \
  -e CORTEX_AUTH_OFFLINE_GRANT_PRIVATE_KEY_FILE=/run/secrets/cortex_offline_private \
  -e CORTEX_AUTH_OFFLINE_GRANT_PUBLIC_KEY_FILE=/run/secrets/cortex_offline_public \
  -e CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID="$CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID" \
  -e CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_FILE=/run/secrets/cortex_memory_cursor_hmac \
  -e CORTEX_AUTH_DEV_ADMIN_ENABLED=false \
  -e CORTEX_AUTH_PROVISIONING_ENABLED=false \
  -e CORTEX_IMPORT_ENABLED=false \
  -e CORTEX_SYNC_ACADEMY_ENABLED="$academy_sync_enabled" \
  "${academy_docker_environment[@]}" \
  -e CORTEX_SYNC_ZELADORIA_ENABLED="${CORTEX_SYNC_ZELADORIA_ENABLED:-false}" \
  -e CORTEX_STORAGE_PROVIDER=local \
  -e CORTEX_STORAGE_LOCAL_ROOT=/var/lib/cortex/objects \
  -e CORTEX_STORAGE_LOCAL_PERSISTENT=true \
  -v cortex_object_data:/var/lib/cortex/objects \
  cortex-api:local
