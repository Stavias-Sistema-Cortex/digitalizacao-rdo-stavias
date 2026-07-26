#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

source "$ROOT_DIR/scripts/dev/load-local-env.sh"
source "$ROOT_DIR/scripts/dev/postgres-cortex-common.sh"

canonical_database="$(cortex_postgres_database_name)"
if [[ ! "${CORTEX_POSTGRES_DOCKER_URL:-}" =~ ^jdbc:postgresql://([^/:?]+)(:([0-9]+))?/${canonical_database}(\?.*)?$ ]]; then
  echo "CORTEX_POSTGRES_DOCKER_URL must target the Docker-reachable canonical database." >&2
  exit 1
fi
if [[ "${CORTEX_POSTGRES_RUNTIME_READY:-false}" != "true" ]]; then
  echo "CORTEX_POSTGRES_RUNTIME_READY must be true only after V60 and a real ALFA bootstrap." >&2
  exit 1
fi

cortex_require_text CORTEX_POSTGRES_USER
cortex_require_secret_file CORTEX_POSTGRES_PASSWORD_FILE
cortex_require_text CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_ID
cortex_require_secret_file CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE
cortex_require_secret_file CORTEX_AUTH_OTP_HMAC_KEY_FILE
cortex_require_text CORTEX_AUTH_OFFLINE_GRANT_KEY_ID
cortex_require_secret_file CORTEX_AUTH_OFFLINE_GRANT_PRIVATE_KEY_FILE
cortex_require_secret_file CORTEX_AUTH_OFFLINE_GRANT_PUBLIC_KEY_FILE
cortex_require_text CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID
cortex_require_secret_file CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_FILE

docker build -t cortex-api:local "$ROOT_DIR/apps/api"

docker run --rm \
  --add-host host.docker.internal:host-gateway \
  -p 127.0.0.1:8081:8080 \
  --mount "type=bind,src=$CORTEX_POSTGRES_PASSWORD_FILE,dst=/run/secrets/CORTEX_POSTGRES_PASSWORD,readonly" \
  --mount "type=bind,src=$CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE,dst=/run/secrets/cortex_cpf_hmac,readonly" \
  --mount "type=bind,src=$CORTEX_AUTH_OTP_HMAC_KEY_FILE,dst=/run/secrets/cortex_otp_hmac,readonly" \
  --mount "type=bind,src=$CORTEX_AUTH_OFFLINE_GRANT_PRIVATE_KEY_FILE,dst=/run/secrets/cortex_offline_private,readonly" \
  --mount "type=bind,src=$CORTEX_AUTH_OFFLINE_GRANT_PUBLIC_KEY_FILE,dst=/run/secrets/cortex_offline_public,readonly" \
  --mount "type=bind,src=$CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_FILE,dst=/run/secrets/cortex_memory_cursor_hmac,readonly" \
  -e SPRING_PROFILES_ACTIVE=local,postgresql \
  -e SPRING_CONFIG_IMPORT=configtree:/run/secrets/ \
  -e CORTEX_POSTGRES_URL="$CORTEX_POSTGRES_DOCKER_URL" \
  -e CORTEX_POSTGRES_USER="$CORTEX_POSTGRES_USER" \
  -e CORTEX_POSTGRES_RUNTIME_READY=true \
  -e CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_ID="$CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_ID" \
  -e CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE=/run/secrets/cortex_cpf_hmac \
  -e CORTEX_AUTH_OTP_HMAC_KEY_FILE=/run/secrets/cortex_otp_hmac \
  -e CORTEX_AUTH_OFFLINE_GRANT_KEY_ID="$CORTEX_AUTH_OFFLINE_GRANT_KEY_ID" \
  -e CORTEX_AUTH_OFFLINE_GRANT_PRIVATE_KEY_FILE=/run/secrets/cortex_offline_private \
  -e CORTEX_AUTH_OFFLINE_GRANT_PUBLIC_KEY_FILE=/run/secrets/cortex_offline_public \
  -e CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID="$CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID" \
  -e CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_FILE=/run/secrets/cortex_memory_cursor_hmac \
  -e CORTEX_AUTH_DEV_ADMIN_ENABLED=false \
  -e CORTEX_AUTH_PROVISIONING_ENABLED=false \
  -e CORTEX_IMPORT_ENABLED=false \
  -e CORTEX_SYNC_ENABLED=true \
  -e CORTEX_EMAIL_PROVIDER="${CORTEX_EMAIL_PROVIDER:-fake}" \
  -e CORTEX_STORAGE_PROVIDER=local \
  -e CORTEX_STORAGE_LOCAL_ROOT=/var/lib/cortex/objects \
  -e CORTEX_STORAGE_LOCAL_PERSISTENT=true \
  -v cortex_object_data:/var/lib/cortex/objects \
  cortex-api:local
