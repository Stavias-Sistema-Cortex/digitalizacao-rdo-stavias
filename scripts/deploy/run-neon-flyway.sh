#!/usr/bin/env bash
set -euo pipefail

require_value() {
  local name="$1"
  local value="${!name:-}"
  if [[ -z "$value" || "$value" == *$'\r'* || "$value" == *$'\n'* ]]; then
    echo "$name must be configured as one non-empty line." >&2
    exit 1
  fi
}

for name in \
  CORTEX_API_IMAGE_DIGEST \
  CORTEX_NEON_MIGRATION_URL \
  CORTEX_NEON_MIGRATION_USER \
  CORTEX_NEON_MIGRATION_PASSWORD; do
  require_value "$name"
done

if [[ ! "$CORTEX_API_IMAGE_DIGEST" =~ ^ghcr\.io/[a-z0-9._/-]+@sha256:[a-f0-9]{64}$ ]]; then
  echo "CORTEX_API_IMAGE_DIGEST must be an immutable GHCR sha256 reference." >&2
  exit 1
fi

if [[ "$CORTEX_NEON_MIGRATION_USER" != "cortex_migrator" ]]; then
  echo "CORTEX_NEON_MIGRATION_USER must be the dedicated cortex_migrator role." >&2
  exit 1
fi

canonical_database='Sta''viasCortex'
if [[ ! "$CORTEX_NEON_MIGRATION_URL" =~ ^jdbc:postgresql://([A-Za-z0-9.-]+)(:[0-9]+)?/${canonical_database}\?([^[:space:]#]+)$ ]]; then
  echo "CORTEX_NEON_MIGRATION_URL must target the canonical Neon Córtex database." >&2
  exit 1
fi
neon_host="${BASH_REMATCH[1]}"
neon_query="${BASH_REMATCH[3]}"
if [[ "$neon_host" != *.neon.tech ]]; then
  echo "CORTEX_NEON_MIGRATION_URL must use a Neon hostname." >&2
  exit 1
fi

sslmode_count=0
sslmode_value=""
channel_binding_count=0
channel_binding_value=""
IFS='&' read -r -a query_parts <<< "$neon_query"
for part in "${query_parts[@]}"; do
  key="${part%%=*}"
  value="${part#*=}"
  if [[ "$key" == "sslmode" ]]; then
    sslmode_count=$((sslmode_count + 1))
    sslmode_value="$value"
  fi
  if [[ "$key" == "channelBinding" ]]; then
    channel_binding_count=$((channel_binding_count + 1))
    channel_binding_value="$value"
  fi
  if [[ "$key" =~ ^(password|user)$ ]]; then
    echo "CORTEX_NEON_MIGRATION_URL must not embed database credentials." >&2
    exit 1
  fi
done
if [[ "$sslmode_count" -ne 1 || ! "$sslmode_value" =~ ^(require|verify-full)$ ]]; then
  echo "CORTEX_NEON_MIGRATION_URL must declare one verified TLS policy." >&2
  exit 1
fi
if [[ "$channel_binding_count" -gt 1 ]] \
  || [[ "$channel_binding_count" -eq 1 && "$channel_binding_value" != "require" ]] \
  || [[ "$sslmode_value" == "require" && "$channel_binding_count" -ne 1 ]]; then
  echo "CORTEX_NEON_MIGRATION_URL must require channel binding unless sslmode=verify-full." >&2
  exit 1
fi

command -v docker >/dev/null 2>&1 || {
  echo "docker is required for the isolated Flyway release step." >&2
  exit 1
}

secret_root="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/cortex-neon-flyway.XXXXXX")"
url_file="$secret_root/CORTEX_POSTGRES_URL"
user_file="$secret_root/CORTEX_POSTGRES_USER"
password_file="$secret_root/CORTEX_POSTGRES_PASSWORD"
cleanup() {
  [[ ! -f "$url_file" ]] || rm -f "$url_file"
  [[ ! -f "$user_file" ]] || rm -f "$user_file"
  [[ ! -f "$password_file" ]] || rm -f "$password_file"
  [[ ! -d "$secret_root" ]] || rmdir "$secret_root"
}
trap cleanup EXIT

umask 077
printf '%s' "$CORTEX_NEON_MIGRATION_URL" > "$url_file"
printf '%s' "$CORTEX_NEON_MIGRATION_USER" > "$user_file"
printf '%s' "$CORTEX_NEON_MIGRATION_PASSWORD" > "$password_file"
chmod 600 "$url_file" "$user_file" "$password_file"
unset CORTEX_NEON_MIGRATION_URL
unset CORTEX_NEON_MIGRATION_USER
unset CORTEX_NEON_MIGRATION_PASSWORD

if [[ "$secret_root" == *,* ]]; then
  echo "The temporary secret path cannot contain a comma." >&2
  exit 1
fi

docker run \
  --rm \
  --read-only \
  --user "$(id -u):$(id -g)" \
  --cap-drop ALL \
  --security-opt no-new-privileges:true \
  --tmpfs /tmp:rw,noexec,nosuid,nodev,size=64m,mode=1777 \
  --mount "type=bind,src=$secret_root,dst=/run/secrets,readonly" \
  --env SPRING_CONFIG_IMPORT=configtree:/run/secrets/ \
  --env SPRING_PROFILES_ACTIVE=postgresql-migrate \
  --env CORTEX_POSTGRES_RUNTIME_READY=false \
  --env CORTEX_MAIN_CLASS=com.projeto.cortex.postgresql.migrate.PostgresqlMigrationApplication \
  "$CORTEX_API_IMAGE_DIGEST"
