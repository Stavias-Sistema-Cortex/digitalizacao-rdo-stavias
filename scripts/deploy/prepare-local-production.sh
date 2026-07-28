#!/usr/bin/env bash
set -euo pipefail
umask 077

repo_root="$(git rev-parse --show-toplevel)"
compose_file="$repo_root/deploy/production/compose.yml"
runtime_dir="${CORTEX_PRODUCTION_RUNTIME_DIR:-$repo_root/.runtime/production}"
secret_dir="$runtime_dir/secrets"
backup_dir="$runtime_dir/backups"
runtime_env="$runtime_dir/production.env"

main_worktree="$(
  git worktree list --porcelain |
    awk '/^worktree / { print substr($0, 10); exit }'
)"
source_env="${CORTEX_SOURCE_ENV_FILE:-$main_worktree/.env.local}"
source_env_dir="$(cd "$(dirname "$source_env")" && pwd)"

[[ -f "$source_env" ]] || {
  echo "CORTEX_SOURCE_ENV_FILE must name the existing canonical local environment." >&2
  exit 1
}

# The existing loader treats the value after the first '=' as inert text. That
# keeps JDBC query strings and passwords containing shell metacharacters from
# being evaluated as shell code.
source "$repo_root/scripts/dev/load-local-env.sh"
load_env_file "$source_env"

unset CORTEX_AUTH_JWT_SECRET
unset CORTEX_AUTH_OTP_HMAC_KEY_FILE
unset CORTEX_DB_URL
unset PGPASSWORD

for required_command in docker openssl psql; do
  command -v "$required_command" >/dev/null 2>&1 || {
    echo "$required_command is required to prepare production." >&2
    exit 1
  }
done
docker compose version >/dev/null

pg_dump_bin="${CORTEX_PG_DUMP_BIN:-}"
if [[ -z "$pg_dump_bin" ]]; then
  for candidate in \
    /opt/homebrew/opt/postgresql@18/bin/pg_dump \
    /Applications/Postgres.app/Contents/Versions/18/bin/pg_dump \
    "$(command -v pg_dump 2>/dev/null || true)"; do
    if [[ -x "$candidate" ]] && "$candidate" --version | grep -Eq 'PostgreSQL\) 18\.'; then
      pg_dump_bin="$candidate"
      break
    fi
  done
fi
if [[ -z "$pg_dump_bin" || ! -x "$pg_dump_bin" ]] ||
  ! "$pg_dump_bin" --version | grep -Eq 'PostgreSQL\) 18\.'; then
  echo "A PostgreSQL 18 pg_dump client is required for the canonical backup." >&2
  exit 1
fi

mkdir -p "$secret_dir" "$backup_dir"
chmod 700 "$runtime_dir" "$secret_dir" "$backup_dir"

require_text() {
  local variable_name="$1"
  local value="${!variable_name:-}"
  if [[ -z "$value" || "$value" == *$'\r'* || "$value" == *$'\n'* ]]; then
    echo "$variable_name must be configured in the source environment." >&2
    exit 1
  fi
}

resolve_source_file() {
  local configured_path="$1"
  if [[ "$configured_path" = /* ]]; then
    printf '%s' "$configured_path"
  else
    printf '%s/%s' "$source_env_dir" "$configured_path"
  fi
}

install_secret_file() {
  local variable_name="$1"
  local destination="$2"
  local configured_path="${!variable_name:-}"
  local source_path

  require_text "$variable_name"
  source_path="$(resolve_source_file "$configured_path")"
  if [[ ! -f "$source_path" || -L "$source_path" || ! -r "$source_path" ]]; then
    echo "$variable_name must name a readable regular secret file." >&2
    exit 1
  fi
  install -m 600 "$source_path" "$destination"
}

write_secret_value() {
  local variable_name="$1"
  local destination="$2"
  local value="${!variable_name:-}"

  require_text "$variable_name"
  printf '%s' "$value" > "$destination"
  chmod 600 "$destination"
  unset value
}

ensure_random_secret() {
  local destination="$1"
  if [[ ! -f "$destination" ]]; then
    openssl rand -hex 32 > "$destination"
    chmod 600 "$destination"
  fi
}

postgres_admin_secret="$secret_dir/postgres-admin"
postgres_migrator_secret="$secret_dir/postgres-migrator"
postgres_runtime_secret="$secret_dir/postgres-runtime"
cpf_hmac_secret="$secret_dir/cpf-hmac"
offline_private_secret="$secret_dir/offline-private.pem"
offline_public_secret="$secret_dir/offline-public.pem"
memory_cursor_secret="$secret_dir/memory-cursor-hmac"
academy_secret="$secret_dir/academy-password"
zeladoria_secret="$secret_dir/zeladoria-password"

ensure_random_secret "$postgres_admin_secret"
ensure_random_secret "$postgres_migrator_secret"
ensure_random_secret "$postgres_runtime_secret"

if [[ -n "${CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE:-}" ]]; then
  install_secret_file CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE "$cpf_hmac_secret"
else
  write_secret_value CORTEX_AUTH_CPF_HMAC_CURRENT_KEY "$cpf_hmac_secret"
fi
install_secret_file CORTEX_AUTH_OFFLINE_GRANT_PRIVATE_KEY_FILE "$offline_private_secret"
install_secret_file CORTEX_AUTH_OFFLINE_GRANT_PUBLIC_KEY_FILE "$offline_public_secret"
install_secret_file CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_FILE "$memory_cursor_secret"
install_secret_file CORTEX_ACADEMY_DB_PASSWORD_FILE "$academy_secret"
unset CORTEX_ACADEMY_DB_PASSWORD ACAD_DB_PASSWORD
write_secret_value CORTEX_ZELADORIA_DB_PASSWORD "$zeladoria_secret"

for key_file in "$cpf_hmac_secret" "$memory_cursor_secret"; do
  if (( $(wc -c < "$key_file") < 32 )); then
    echo "HMAC secret material must contain at least 32 bytes." >&2
    exit 1
  fi
done

private_fingerprint="$(
  openssl pkey -in "$offline_private_secret" -pubout -outform DER 2>/dev/null |
    openssl dgst -sha256 -binary |
    openssl base64 -A |
    tr '+/' '-_' |
    tr -d '='
)"
public_fingerprint="$(
  openssl pkey -pubin -in "$offline_public_secret" -outform DER 2>/dev/null |
    openssl dgst -sha256 -binary |
    openssl base64 -A |
    tr '+/' '-_' |
    tr -d '='
)"
if [[ "$private_fingerprint" != "$public_fingerprint" || ${#public_fingerprint} -ne 43 ]]; then
  echo "The offline private/public key pair is invalid or mismatched." >&2
  exit 1
fi

for variable_name in \
  CORTEX_POSTGRES_URL \
  CORTEX_POSTGRES_USER \
  CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_ID \
  CORTEX_AUTH_OFFLINE_GRANT_KEY_ID \
  CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID \
  CORTEX_ACADEMY_DB_URL \
  CORTEX_ACADEMY_DB_USER \
  CORTEX_ZELADORIA_DB_URL \
  CORTEX_ZELADORIA_DB_USER; do
  require_text "$variable_name"
done

if [[ ! "$CORTEX_POSTGRES_URL" =~ ^jdbc:postgresql://([^/:?]+)(:([0-9]+))?/StaviasCortex(\?.*)?$ ]]; then
  echo "The source PostgreSQL URL must target StaviasCortex." >&2
  exit 1
fi
source_postgres_host="${BASH_REMATCH[1]}"
source_postgres_port="${BASH_REMATCH[3]:-5432}"
source_postgres_user="$CORTEX_POSTGRES_USER"

runtime_env_tmp="$(mktemp "$runtime_dir/production.env.XXXXXX")"
{
  printf 'COMPOSE_PROJECT_NAME=cortex-production\n'
  printf 'CORTEX_API_IMAGE=cortex-api:production-local\n'
  printf 'CORTEX_WEB_IMAGE=cortex-web:production-local\n'
  printf 'CORTEX_POSTGRES_DB=StaviasCortex\n'
  printf 'CORTEX_POSTGRES_ADMIN_USER=cortex_admin\n'
  printf 'CORTEX_POSTGRES_MIGRATOR_USER=cortex_migrator\n'
  printf 'CORTEX_POSTGRES_USER=cortex_runtime\n'
  printf 'CORTEX_POSTGRES_ADMIN_PASSWORD_FILE=%s\n' "$postgres_admin_secret"
  printf 'CORTEX_POSTGRES_MIGRATOR_PASSWORD_FILE=%s\n' "$postgres_migrator_secret"
  printf 'CORTEX_POSTGRES_PASSWORD_FILE=%s\n' "$postgres_runtime_secret"
  printf 'CORTEX_PUBLIC_ORIGIN=https://cortex.localhost:18443\n'
  printf 'CORTEX_HTTPS_PORT=18443\n'
  printf 'CORTEX_AUTH_WEBAUTHN_RP_ID=cortex.localhost\n'
  printf 'CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_ID=%s\n' "$CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_ID"
  printf 'CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE=%s\n' "$cpf_hmac_secret"
  printf 'CORTEX_AUTH_OFFLINE_GRANT_KEY_ID=%s\n' "$CORTEX_AUTH_OFFLINE_GRANT_KEY_ID"
  printf 'CORTEX_AUTH_OFFLINE_GRANT_PRIVATE_KEY_FILE=%s\n' "$offline_private_secret"
  printf 'CORTEX_AUTH_OFFLINE_GRANT_PUBLIC_KEY_FILE=%s\n' "$offline_public_secret"
  printf 'CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID=%s\n' "$CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID"
  printf 'CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_FILE=%s\n' "$memory_cursor_secret"
  printf 'VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256=%s\n' "$public_fingerprint"
  printf 'CORTEX_ACADEMY_DB_URL=%s\n' "$CORTEX_ACADEMY_DB_URL"
  printf 'CORTEX_ACADEMY_DB_USER=%s\n' "$CORTEX_ACADEMY_DB_USER"
  printf 'CORTEX_ACADEMY_DB_PASSWORD_FILE=%s\n' "$academy_secret"
  printf 'CORTEX_ZELADORIA_DB_URL=%s\n' "$CORTEX_ZELADORIA_DB_URL"
  printf 'CORTEX_ZELADORIA_DB_USER=%s\n' "$CORTEX_ZELADORIA_DB_USER"
  printf 'CORTEX_ZELADORIA_DB_PASSWORD_FILE=%s\n' "$zeladoria_secret"
  printf 'CORTEX_SYNC_ACADEMY_ENABLED=false\n'
  printf 'CORTEX_SYNC_ZELADORIA_ENABLED=false\n'
} > "$runtime_env_tmp"
chmod 600 "$runtime_env_tmp"
mv "$runtime_env_tmp" "$runtime_env"

compose=(
  docker compose
  --env-file "$runtime_env"
  -f "$compose_file"
)

backup_file="$backup_dir/StaviasCortex-$(date -u +%Y%m%dT%H%M%SZ).dump"
source_password=""
if [[ -n "${CORTEX_POSTGRES_PASSWORD_FILE:-}" ]]; then
  source_password_file="$(resolve_source_file "$CORTEX_POSTGRES_PASSWORD_FILE")"
  if [[ ! -f "$source_password_file" || -L "$source_password_file" ]]; then
    echo "The source PostgreSQL password file is invalid." >&2
    exit 1
  fi
  source_password="$(<"$source_password_file")"
fi

dump_command=(
  "$pg_dump_bin"
  --no-password
  --format=custom
  --no-owner
  --no-acl
  --host="$source_postgres_host"
  --port="$source_postgres_port"
  --username="$source_postgres_user"
  --dbname=StaviasCortex
  --file="$backup_file"
)
if [[ -n "$source_password" ]]; then
  PGPASSWORD="$source_password" "${dump_command[@]}"
else
  "${dump_command[@]}"
fi
unset source_password
chmod 600 "$backup_file"

# Docker Compose gives already-exported shell variables precedence over
# --env-file. Remove every imported deployment name so the generated,
# allowlisted runtime file is the only configuration source.
unset \
  COMPOSE_PROJECT_NAME \
  CORTEX_API_IMAGE \
  CORTEX_WEB_IMAGE \
  CORTEX_POSTGRES_DB \
  CORTEX_POSTGRES_ADMIN_USER \
  CORTEX_POSTGRES_MIGRATOR_USER \
  CORTEX_POSTGRES_USER \
  CORTEX_POSTGRES_ADMIN_PASSWORD_FILE \
  CORTEX_POSTGRES_MIGRATOR_PASSWORD_FILE \
  CORTEX_POSTGRES_PASSWORD_FILE \
  CORTEX_PUBLIC_ORIGIN \
  CORTEX_HTTPS_PORT \
  CORTEX_AUTH_WEBAUTHN_RP_ID \
  CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_ID \
  CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE \
  CORTEX_AUTH_CPF_HMAC_CURRENT_KEY \
  CORTEX_AUTH_OFFLINE_GRANT_KEY_ID \
  CORTEX_AUTH_OFFLINE_GRANT_PRIVATE_KEY_FILE \
  CORTEX_AUTH_OFFLINE_GRANT_PUBLIC_KEY_FILE \
  CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID \
  CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_FILE \
  VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256 \
  CORTEX_ACADEMY_DB_URL \
  CORTEX_ACADEMY_DB_USER \
  CORTEX_ACADEMY_DB_PASSWORD \
  CORTEX_ACADEMY_DB_PASSWORD_FILE \
  CORTEX_ZELADORIA_DB_URL \
  CORTEX_ZELADORIA_DB_USER \
  CORTEX_ZELADORIA_DB_PASSWORD \
  CORTEX_ZELADORIA_DB_PASSWORD_FILE \
  CORTEX_SYNC_ACADEMY_ENABLED \
  CORTEX_SYNC_ZELADORIA_ENABLED

"${compose[@]}" up -d cortex-postgres
postgres_container="$("${compose[@]}" ps -q cortex-postgres)"
for _ in $(seq 1 60); do
  health="$(docker inspect --format '{{.State.Health.Status}}' "$postgres_container" 2>/dev/null || true)"
  [[ "$health" == "healthy" ]] && break
  sleep 2
done
if [[ "$(docker inspect --format '{{.State.Health.Status}}' "$postgres_container")" != "healthy" ]]; then
  echo "The isolated PostgreSQL 18 service did not become healthy." >&2
  exit 1
fi

target_table_count="$(
  "${compose[@]}" exec -T cortex-postgres \
    psql --username=cortex_admin --dbname=StaviasCortex --tuples-only --no-align \
      --command="SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public';"
)"
if [[ "$target_table_count" == "0" ]]; then
  "${compose[@]}" exec -T cortex-postgres sh -ec '
    export PGPASSWORD="$(cat /run/secrets/postgres_migrator_password)"
    exec pg_restore \
      --exit-on-error \
      --no-owner \
      --no-acl \
      --host=127.0.0.1 \
      --username="$CORTEX_POSTGRES_MIGRATOR_USER" \
      --dbname="$POSTGRES_DB"
  ' < "$backup_file"
else
  echo "The isolated PostgreSQL already contains data; restore was skipped." >&2
fi

"${compose[@]}" build cortex-migrate cortex-api cortex-web
"${compose[@]}" up --force-recreate cortex-migrate
"${compose[@]}" up -d --force-recreate cortex-api cortex-web cortex-edge

edge_container="$("${compose[@]}" ps -q cortex-edge)"
for _ in $(seq 1 120); do
  if [[ -n "$edge_container" ]] && [[ "$(docker inspect --format '{{.State.Running}}' "$edge_container" 2>/dev/null || true)" == "true" ]]; then
    break
  fi
  sleep 2
  edge_container="$("${compose[@]}" ps -q cortex-edge)"
done
if [[ -z "$edge_container" ]] || [[ "$(docker inspect --format '{{.State.Running}}' "$edge_container")" != "true" ]]; then
  "${compose[@]}" ps >&2
  echo "The HTTPS edge did not start because a required service is unhealthy." >&2
  exit 1
fi

ca_certificate="$runtime_dir/caddy-local-root.crt"
for _ in $(seq 1 30); do
  if docker cp \
    "$edge_container:/data/caddy/pki/authorities/local/root.crt" \
    "$ca_certificate" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
[[ -s "$ca_certificate" ]] || {
  echo "The local HTTPS certificate authority was not generated." >&2
  exit 1
}
chmod 600 "$ca_certificate"

https_ready=false
for _ in $(seq 1 30); do
  if curl \
    --cacert "$ca_certificate" \
    --fail \
    --silent \
    --show-error \
    https://cortex.localhost:18443/healthz >/dev/null 2>&1; then
    https_ready=true
    break
  fi
  sleep 1
done
if [[ "$https_ready" != "true" ]]; then
  echo "The HTTPS edge did not become reachable on the published origin." >&2
  exit 1
fi

CORTEX_BASE_URL=https://cortex.localhost:18443 \
CORTEX_SMOKE_CA_CERT="$ca_certificate" \
  "$repo_root/scripts/smoke-deploy.sh"

printf 'Production runtime is ready at https://cortex.localhost:18443\n'
printf 'Backup: %s\n' "$backup_file"
printf 'Environment: %s\n' "$runtime_env"
