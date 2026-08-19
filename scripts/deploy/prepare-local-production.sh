#!/usr/bin/env bash
set -euo pipefail
umask 077

repo_root="$(git rev-parse --show-toplevel)"
compose_file="$repo_root/deploy/production/compose.yml"
validator="$repo_root/scripts/deploy/validate-local-release-inputs.sh"

CORTEX_PRODUCTION_MODE="${CORTEX_PRODUCTION_MODE:-rehearsal}"
case "$CORTEX_PRODUCTION_MODE" in
  rehearsal)
    COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-cortex-production-rehearsal}"
    CORTEX_HTTPS_PORT="${CORTEX_HTTPS_PORT:-18444}"
    default_runtime_dir="$repo_root/.runtime/production-rehearsal"
    ;;
  cutover)
    COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-cortex-production}"
    CORTEX_HTTPS_PORT="${CORTEX_HTTPS_PORT:-18443}"
    default_runtime_dir="$repo_root/.runtime/production"
    ;;
  *)
    echo "CORTEX_PRODUCTION_MODE must be rehearsal or cutover." >&2
    exit 1
    ;;
esac
CORTEX_PUBLIC_ORIGIN="${CORTEX_PUBLIC_ORIGIN:-https://cortex.portalstavias.com.br}"
CORTEX_AUTH_WEBAUTHN_RP_ID="${CORTEX_AUTH_WEBAUTHN_RP_ID:-cortex.portalstavias.com.br}"

export CORTEX_PRODUCTION_MODE COMPOSE_PROJECT_NAME CORTEX_HTTPS_PORT
export CORTEX_PUBLIC_ORIGIN CORTEX_AUTH_WEBAUTHN_RP_ID
bash "$validator"

release_mode="$CORTEX_PRODUCTION_MODE"
release_project="$COMPOSE_PROJECT_NAME"
release_https_port="$CORTEX_HTTPS_PORT"
release_sha="$CORTEX_RELEASE_SHA"
release_database_marker="$CORTEX_DATABASE_RELEASE_MARKER"
release_api_image="$CORTEX_API_IMAGE"
release_web_image="$CORTEX_WEB_IMAGE"
release_public_origin="$CORTEX_PUBLIC_ORIGIN"
release_rp_id="$CORTEX_AUTH_WEBAUTHN_RP_ID"

runtime_dir="${CORTEX_PRODUCTION_RUNTIME_DIR:-$default_runtime_dir}"
secret_dir="$runtime_dir/secrets"
backup_dir="$runtime_dir/backups"
evidence_dir="$runtime_dir/evidence"
runtime_env="$runtime_dir/production.env"
source_snapshot_pid=""
source_snapshot_dir=""
source_snapshot_release_fifo=""
source_snapshot=""

abort_source_snapshot() {
  if [[ -n "$source_snapshot_pid" ]] && kill -0 "$source_snapshot_pid" 2>/dev/null; then
    kill "$source_snapshot_pid" 2>/dev/null || true
    wait "$source_snapshot_pid" 2>/dev/null || true
  fi
  if [[ -n "$source_snapshot_dir" && -d "$source_snapshot_dir" ]]; then
    rm -f "$source_snapshot_dir/id" \
      "$source_snapshot_dir/error" \
      "$source_snapshot_release_fifo"
    rmdir "$source_snapshot_dir" 2>/dev/null || true
  fi
}
trap abort_source_snapshot EXIT

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

docker pull "$release_api_image"
docker pull "$release_web_image"

verify_release_image() {
  local image="$1"
  local component="$2"
  local actual_revision

  actual_revision="$(
    docker image inspect \
      --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' \
      "$image"
  )"
  if [[ "$actual_revision" != "$release_sha" ]]; then
    echo "$component image revision does not match CORTEX_RELEASE_SHA." >&2
    exit 1
  fi
}

verify_release_image "$release_api_image" API
verify_release_image "$release_web_image" PWA

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

mkdir -p "$secret_dir" "$backup_dir" "$evidence_dir"
chmod 700 "$runtime_dir" "$secret_dir" "$backup_dir" "$evidence_dir"

runtime_uid="$(id -u)"
runtime_gid="$(id -g)"
if [[ ! "$runtime_uid" =~ ^[0-9]+$ || ! "$runtime_gid" =~ ^[0-9]+$ ]] \
  || (( runtime_uid == 0 || runtime_gid == 0 )); then
  echo "Production preparation must run as a non-root host account." >&2
  exit 1
fi

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
  local source_mode

  require_text "$variable_name"
  source_path="$(resolve_source_file "$configured_path")"
  if [[ ! -s "$source_path" || -L "$source_path" || ! -r "$source_path" ]]; then
    echo "$variable_name must name a non-empty readable regular secret file." >&2
    exit 1
  fi
  if source_mode="$(stat -c '%a' "$source_path" 2>/dev/null)" \
    && [[ "$source_mode" =~ ^[0-7]{3,4}$ ]]; then
    :
  elif source_mode="$(stat -f '%Lp' "$source_path" 2>/dev/null)" \
    && [[ "$source_mode" =~ ^[0-7]{3,4}$ ]]; then
    :
  else
    echo "$variable_name permissions could not be read safely." >&2
    exit 1
  fi
  if [[ ! "$source_mode" =~ ^[0-7]{3,4}$ ]] ||
    (( (8#$source_mode & 077) != 0 )); then
    echo "$variable_name must not be readable or writable by group or others." >&2
    exit 1
  fi
  if [[ -L "$destination" ]]; then
    echo "Refusing to replace a symbolic-link destination for $variable_name." >&2
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
password_setup_hmac_secret="$secret_dir/password-setup-hmac"
offline_private_secret="$secret_dir/offline-private.pem"
offline_public_secret="$secret_dir/offline-public.pem"
memory_cursor_secret="$secret_dir/memory-cursor-hmac"
academy_secret="$secret_dir/academy-password"
zeladoria_secret="$secret_dir/zeladoria-password"
academy_truststore="$secret_dir/academy-truststore.p12"
zeladoria_truststore="$secret_dir/zeladoria-truststore.p12"
storage_access_key_secret="$secret_dir/storage-source-access-key-id"
storage_secret_key_secret="$secret_dir/storage-source-secret-access-key"

ensure_random_secret "$postgres_admin_secret"
ensure_random_secret "$postgres_migrator_secret"
ensure_random_secret "$postgres_runtime_secret"
ensure_random_secret "$password_setup_hmac_secret"

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
install_secret_file CORTEX_ZELADORIA_DB_PASSWORD_FILE "$zeladoria_secret"
unset CORTEX_ZELADORIA_DB_PASSWORD ZEL_DB_PASSWORD
install_secret_file CORTEX_ACADEMY_TRUSTSTORE_FILE "$academy_truststore"
install_secret_file CORTEX_ZELADORIA_TRUSTSTORE_FILE "$zeladoria_truststore"
if [[ -n "${AWS_ACCESS_KEY_ID_FILE:-}" ]]; then
  install_secret_file AWS_ACCESS_KEY_ID_FILE "$storage_access_key_secret"
else
  write_secret_value AWS_ACCESS_KEY_ID "$storage_access_key_secret"
fi
if [[ -n "${AWS_SECRET_ACCESS_KEY_FILE:-}" ]]; then
  install_secret_file AWS_SECRET_ACCESS_KEY_FILE "$storage_secret_key_secret"
else
  write_secret_value AWS_SECRET_ACCESS_KEY "$storage_secret_key_secret"
fi
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY

for key_file in "$cpf_hmac_secret" "$password_setup_hmac_secret" "$memory_cursor_secret"; do
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
  CORTEX_ZELADORIA_DB_USER \
  CORTEX_STORAGE_S3_BUCKET \
  CORTEX_STORAGE_S3_REGION; do
  require_text "$variable_name"
done

if [[ ! "$CORTEX_POSTGRES_URL" =~ ^jdbc:postgresql://([^/:?]+)(:([0-9]+))?/StaviasCortex(\?.*)?$ ]]; then
  echo "The source PostgreSQL URL must target StaviasCortex." >&2
  exit 1
fi
source_postgres_host="${BASH_REMATCH[1]}"
source_postgres_port="${BASH_REMATCH[3]:-5432}"
source_postgres_user="$CORTEX_POSTGRES_USER"
source_psql_args=(
  psql
  --no-password
  --no-psqlrc
  --quiet
  --tuples-only
  --no-align
  --set=ON_ERROR_STOP=1
  --host="$source_postgres_host"
  --port="$source_postgres_port"
  --username="$source_postgres_user"
  --dbname=StaviasCortex
)

table_count_sql() {
  cat <<'SQL'
SELECT format(
  'SELECT %L || E''\t'' || count(*)::text FROM %I.%I;',
  namespace.nspname || '.' || relation.relname,
  namespace.nspname,
  relation.relname
)
FROM pg_class relation
JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
WHERE namespace.nspname = 'public'
  AND relation.relkind IN ('r', 'p')
ORDER BY namespace.nspname, relation.relname
\gexec
SQL
}

capture_source_table_counts() {
  local destination="$1"
  local snapshot_id="$2"
  local temporary
  temporary="$(mktemp "$backup_dir/source-table-counts.tsv.XXXXXX")"
  if [[ -n "$source_password" ]]; then
    {
      printf 'BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;\n'
      printf "SET TRANSACTION SNAPSHOT '%s';\n" "$snapshot_id"
      table_count_sql
      printf 'COMMIT;\n'
    } | PGPASSWORD="$source_password" "${source_psql_args[@]}" > "$temporary"
  else
    {
      printf 'BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;\n'
      printf "SET TRANSACTION SNAPSHOT '%s';\n" "$snapshot_id"
      table_count_sql
      printf 'COMMIT;\n'
    } | "${source_psql_args[@]}" > "$temporary"
  fi
  LC_ALL=C sort -o "$temporary" "$temporary"
  chmod 600 "$temporary"
  mv "$temporary" "$destination"
}

capture_source_sequence_exclusions() {
  local destination="$1"
  local snapshot_id="$2"
  local temporary
  local sequence_name
  temporary="$(mktemp "$backup_dir/source-sequence-exclusions.txt.XXXXXX")"
  if [[ -n "$source_password" ]]; then
    {
      printf 'BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;\n'
      printf "SET TRANSACTION SNAPSHOT '%s';\n" "$snapshot_id"
      cat <<'SQL'
SELECT namespace.nspname || '.' || relation.relname
FROM pg_class relation
JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
WHERE namespace.nspname = 'public'
  AND relation.relkind = 'S'
ORDER BY namespace.nspname, relation.relname;
SQL
      printf 'COMMIT;\n'
    } | PGPASSWORD="$source_password" "${source_psql_args[@]}" > "$temporary"
  else
    {
      printf 'BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;\n'
      printf "SET TRANSACTION SNAPSHOT '%s';\n" "$snapshot_id"
      cat <<'SQL'
SELECT namespace.nspname || '.' || relation.relname
FROM pg_class relation
JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
WHERE namespace.nspname = 'public'
  AND relation.relkind = 'S'
ORDER BY namespace.nspname, relation.relname;
SQL
      printf 'COMMIT;\n'
    } | "${source_psql_args[@]}" > "$temporary"
  fi
  LC_ALL=C sort -u -o "$temporary" "$temporary"
  while IFS= read -r sequence_name; do
    [[ -z "$sequence_name" ]] && continue
    if [[ ! "$sequence_name" =~ ^[A-Za-z_][A-Za-z0-9_]*\.[A-Za-z_][A-Za-z0-9_]*$ ]]; then
      echo "The source contains a sequence name that cannot be exported safely." >&2
      exit 1
    fi
  done < "$temporary"
  chmod 600 "$temporary"
  mv "$temporary" "$destination"
}

start_source_snapshot() {
  local snapshot_file
  local snapshot_error

  source_snapshot_dir="$(mktemp -d "$runtime_dir/source-snapshot.XXXXXX")"
  chmod 700 "$source_snapshot_dir"
  snapshot_file="$source_snapshot_dir/id"
  snapshot_error="$source_snapshot_dir/error"
  source_snapshot_release_fifo="$source_snapshot_dir/release"
  mkfifo -m 600 "$source_snapshot_release_fifo"
  export CORTEX_SOURCE_SNAPSHOT_RELEASE_FIFO="$source_snapshot_release_fifo"

  if [[ -n "$source_password" ]]; then
    PGPASSWORD="$source_password" "${source_psql_args[@]}" \
      > "$snapshot_file" 2> "$snapshot_error" <<'SQL' &
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT pg_export_snapshot();
\! read cortex_release_snapshot < "$CORTEX_SOURCE_SNAPSHOT_RELEASE_FIFO"
COMMIT;
SQL
  else
    "${source_psql_args[@]}" > "$snapshot_file" 2> "$snapshot_error" <<'SQL' &
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT pg_export_snapshot();
\! read cortex_release_snapshot < "$CORTEX_SOURCE_SNAPSHOT_RELEASE_FIFO"
COMMIT;
SQL
  fi
  source_snapshot_pid=$!

  for _ in $(seq 1 100); do
    source_snapshot="$(sed -n '/^[0-9A-Fa-f][0-9A-Fa-f]*-[0-9A-Fa-f][0-9A-Fa-f]*-[0-9][0-9]*$/p' "$snapshot_file" 2>/dev/null | head -n 1)"
    [[ -n "$source_snapshot" ]] && break
    if ! kill -0 "$source_snapshot_pid" 2>/dev/null; then
      echo "Unable to open a consistent read-only PostgreSQL source snapshot." >&2
      exit 1
    fi
    sleep 0.1
  done
  if [[ -z "$source_snapshot" ]]; then
    echo "Timed out while opening a consistent PostgreSQL source snapshot." >&2
    exit 1
  fi
}

finish_source_snapshot() {
  printf 'release\n' > "$source_snapshot_release_fifo"
  wait "$source_snapshot_pid"
  source_snapshot_pid=""
  rm -f "$source_snapshot_dir/id" \
    "$source_snapshot_dir/error" \
    "$source_snapshot_release_fifo"
  rmdir "$source_snapshot_dir"
  source_snapshot_dir=""
  source_snapshot_release_fifo=""
  unset CORTEX_SOURCE_SNAPSHOT_RELEASE_FIFO
}

capture_target_table_counts() {
  local destination="$1"
  local temporary
  temporary="$(mktemp "$backup_dir/target-table-counts.tsv.XXXXXX")"
  "${compose[@]}" exec -T cortex-postgres \
    psql --no-psqlrc --quiet --tuples-only --no-align \
      --set=ON_ERROR_STOP=1 \
      --username=cortex_admin \
      --dbname=StaviasCortex \
      > "$temporary" < <(table_count_sql)
  LC_ALL=C sort -o "$temporary" "$temporary"
  chmod 600 "$temporary"
  mv "$temporary" "$destination"
}

reset_restored_sequences() {
  "${compose[@]}" exec -T cortex-postgres \
    psql --no-psqlrc --quiet --set=ON_ERROR_STOP=1 \
      --username=cortex_admin \
      --dbname=StaviasCortex <<'SQL'
DO $cortex_sequence_reset$
DECLARE
  item record;
  aggregate_name text;
  restored_value bigint;
BEGIN
  FOR item IN
    SELECT
      format('%I.%I', sequence_namespace.nspname, sequence_class.relname) AS sequence_name,
      table_namespace.nspname AS table_schema,
      table_class.relname AS table_name,
      attribute.attname AS column_name,
      sequence_parameters.seqstart AS sequence_start,
      sequence_parameters.seqincrement AS sequence_increment
    FROM pg_class sequence_class
    JOIN pg_namespace sequence_namespace
      ON sequence_namespace.oid = sequence_class.relnamespace
    JOIN pg_sequence sequence_parameters
      ON sequence_parameters.seqrelid = sequence_class.oid
    JOIN pg_depend dependency
      ON dependency.classid = 'pg_class'::regclass
     AND dependency.objid = sequence_class.oid
     AND dependency.refclassid = 'pg_class'::regclass
     AND dependency.deptype IN ('a', 'i')
    JOIN pg_class table_class ON table_class.oid = dependency.refobjid
    JOIN pg_namespace table_namespace ON table_namespace.oid = table_class.relnamespace
    JOIN pg_attribute attribute
      ON attribute.attrelid = table_class.oid
     AND attribute.attnum = dependency.refobjsubid
    WHERE sequence_class.relkind = 'S'
      AND sequence_namespace.nspname = 'public'
    ORDER BY sequence_namespace.nspname, sequence_class.relname
  LOOP
    aggregate_name := CASE WHEN item.sequence_increment > 0 THEN 'max' ELSE 'min' END;
    EXECUTE format(
      'SELECT %s(%I)::bigint FROM %I.%I',
      aggregate_name,
      item.column_name,
      item.table_schema,
      item.table_name
    ) INTO restored_value;
    IF restored_value IS NULL THEN
      PERFORM pg_catalog.setval(item.sequence_name::regclass, item.sequence_start, false);
    ELSE
      PERFORM pg_catalog.setval(item.sequence_name::regclass, restored_value, true);
    END IF;
  END LOOP;
END
$cortex_sequence_reset$;
SQL
}

runtime_env_tmp="$(mktemp "$runtime_dir/production.env.XXXXXX")"
{
  printf 'CORTEX_PRODUCTION_MODE=%s\n' "$release_mode"
  printf 'COMPOSE_PROJECT_NAME=%s\n' "$release_project"
  printf 'CORTEX_API_IMAGE=%s\n' "$release_api_image"
  printf 'CORTEX_WEB_IMAGE=%s\n' "$release_web_image"
  printf 'CORTEX_RELEASE_SHA=%s\n' "$release_sha"
  printf 'CORTEX_DATABASE_RELEASE_MARKER=%s\n' "$release_database_marker"
  printf 'CORTEX_RUNTIME_UID=%s\n' "$runtime_uid"
  printf 'CORTEX_RUNTIME_GID=%s\n' "$runtime_gid"
  printf 'CORTEX_POSTGRES_DB=StaviasCortex\n'
  printf 'CORTEX_POSTGRES_ADMIN_USER=cortex_admin\n'
  printf 'CORTEX_POSTGRES_MIGRATOR_USER=cortex_migrator\n'
  printf 'CORTEX_POSTGRES_USER=cortex_runtime\n'
  printf 'CORTEX_POSTGRES_ADMIN_PASSWORD_FILE=%s\n' "$postgres_admin_secret"
  printf 'CORTEX_POSTGRES_MIGRATOR_PASSWORD_FILE=%s\n' "$postgres_migrator_secret"
  printf 'CORTEX_POSTGRES_PASSWORD_FILE=%s\n' "$postgres_runtime_secret"
  printf 'CORTEX_PUBLIC_ORIGIN=%s\n' "$release_public_origin"
  printf 'CORTEX_HTTPS_PORT=%s\n' "$release_https_port"
  printf 'CORTEX_AUTH_WEBAUTHN_RP_ID=%s\n' "$release_rp_id"
  printf 'CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_ID=%s\n' "$CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_ID"
  printf 'CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE=%s\n' "$cpf_hmac_secret"
  printf 'CORTEX_AUTH_PASSWORD_SETUP_HMAC_KEY_FILE=%s\n' "$password_setup_hmac_secret"
  printf 'CORTEX_AUTH_OFFLINE_GRANT_KEY_ID=%s\n' "$CORTEX_AUTH_OFFLINE_GRANT_KEY_ID"
  printf 'CORTEX_AUTH_OFFLINE_GRANT_PRIVATE_KEY_FILE=%s\n' "$offline_private_secret"
  printf 'CORTEX_AUTH_OFFLINE_GRANT_PUBLIC_KEY_FILE=%s\n' "$offline_public_secret"
  printf 'CORTEX_AUTH_OFFLINE_GRANT_TTL_SECONDS=604800\n'
  printf 'CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID=%s\n' "$CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID"
  printf 'CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_FILE=%s\n' "$memory_cursor_secret"
  printf 'VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256=%s\n' "$public_fingerprint"
  printf 'CORTEX_ACADEMY_DB_URL=%s\n' "$CORTEX_ACADEMY_DB_URL"
  printf 'CORTEX_ACADEMY_DB_USER=%s\n' "$CORTEX_ACADEMY_DB_USER"
  printf 'CORTEX_ACADEMY_DB_PASSWORD_FILE=%s\n' "$academy_secret"
  printf 'CORTEX_ACADEMY_TRUSTSTORE_FILE=%s\n' "$academy_truststore"
  printf 'CORTEX_ZELADORIA_DB_URL=%s\n' "$CORTEX_ZELADORIA_DB_URL"
  printf 'CORTEX_ZELADORIA_DB_USER=%s\n' "$CORTEX_ZELADORIA_DB_USER"
  printf 'CORTEX_ZELADORIA_DB_PASSWORD_FILE=%s\n' "$zeladoria_secret"
  printf 'CORTEX_ZELADORIA_TRUSTSTORE_FILE=%s\n' "$zeladoria_truststore"
  printf 'CORTEX_STORAGE_SOURCE_ACCESS_KEY_ID_FILE=%s\n' "$storage_access_key_secret"
  printf 'CORTEX_STORAGE_SOURCE_SECRET_ACCESS_KEY_FILE=%s\n' "$storage_secret_key_secret"
  printf 'CORTEX_OBJECT_MIGRATION_SOURCE_S3_BUCKET=%s\n' "$CORTEX_STORAGE_S3_BUCKET"
  printf 'CORTEX_OBJECT_MIGRATION_SOURCE_S3_REGION=%s\n' "$CORTEX_STORAGE_S3_REGION"
  printf 'CORTEX_OBJECT_MIGRATION_SOURCE_S3_ENDPOINT=%s\n' "${CORTEX_STORAGE_S3_ENDPOINT:-}"
  printf 'CORTEX_OBJECT_MIGRATION_SOURCE_S3_PREFIX=%s\n' "${CORTEX_STORAGE_S3_PREFIX:-}"
  printf 'CORTEX_OBJECT_MIGRATION_SOURCE_S3_PATH_STYLE=%s\n' "${CORTEX_STORAGE_S3_PATH_STYLE:-false}"
  printf 'CORTEX_OBJECT_MIGRATION_EVIDENCE_DIR=%s\n' "$evidence_dir"
  printf 'CORTEX_OBJECT_MIGRATION_PAGE_SIZE=250\n'
  printf 'CORTEX_SYNC_ACADEMY_ENABLED=false\n'
  printf 'CORTEX_SYNC_ACADEMY_READINESS_MAX_AGE_MS=900000\n'
  printf 'CORTEX_SYNC_ZELADORIA_ENABLED=false\n'
  printf 'CORTEX_SYNC_ZELADORIA_READINESS_MAX_AGE_MS=900000\n'
} > "$runtime_env_tmp"
chmod 600 "$runtime_env_tmp"
mv "$runtime_env_tmp" "$runtime_env"

compose=(
  docker compose
  --env-file "$runtime_env"
  -f "$compose_file"
)

backup_stamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_file="$backup_dir/StaviasCortex-$backup_stamp.dump"
source_count_manifest="$backup_dir/$backup_stamp-source-table-counts.tsv"
source_sequence_exclusion_manifest="$backup_dir/$backup_stamp-source-sequence-exclusions.txt"
target_count_manifest="$backup_dir/$backup_stamp-target-table-counts.tsv"
pg_restore_log="$backup_dir/$backup_stamp-pg-restore.log"
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
  --enable-row-security
  --host="$source_postgres_host"
  --port="$source_postgres_port"
  --username="$source_postgres_user"
  --dbname=StaviasCortex
  --file="$backup_file"
)
start_source_snapshot
capture_source_table_counts "$source_count_manifest" "$source_snapshot"
capture_source_sequence_exclusions "$source_sequence_exclusion_manifest" "$source_snapshot"
while IFS= read -r sequence_name; do
  [[ -z "$sequence_name" ]] || dump_command+=(--exclude-table-data="$sequence_name")
done < "$source_sequence_exclusion_manifest"
dump_command+=(--snapshot="$source_snapshot")
if [[ -n "$source_password" ]]; then
  PGPASSWORD="$source_password" "${dump_command[@]}"
else
  "${dump_command[@]}"
fi
finish_source_snapshot
unset source_password
chmod 600 "$backup_file"

# Docker Compose gives already-exported shell variables precedence over
# --env-file. Remove every imported deployment name so the generated,
# allowlisted runtime file is the only configuration source.
unset \
  CORTEX_PRODUCTION_MODE \
  COMPOSE_PROJECT_NAME \
  CORTEX_API_IMAGE \
  CORTEX_WEB_IMAGE \
  CORTEX_RELEASE_SHA \
  CORTEX_DATABASE_RELEASE_MARKER \
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
  CORTEX_AUTH_PASSWORD_SETUP_HMAC_KEY_FILE \
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
  CORTEX_ACADEMY_TRUSTSTORE_FILE \
  CORTEX_ZELADORIA_DB_URL \
  CORTEX_ZELADORIA_DB_USER \
  CORTEX_ZELADORIA_DB_PASSWORD \
  CORTEX_ZELADORIA_DB_PASSWORD_FILE \
  CORTEX_ZELADORIA_TRUSTSTORE_FILE \
  CORTEX_STORAGE_SOURCE_ACCESS_KEY_ID_FILE \
  CORTEX_STORAGE_SOURCE_SECRET_ACCESS_KEY_FILE \
  CORTEX_STORAGE_S3_BUCKET \
  CORTEX_STORAGE_S3_REGION \
  CORTEX_STORAGE_S3_ENDPOINT \
  CORTEX_STORAGE_S3_PREFIX \
  CORTEX_STORAGE_S3_PATH_STYLE \
  CORTEX_OBJECT_MIGRATION_SOURCE_S3_BUCKET \
  CORTEX_OBJECT_MIGRATION_SOURCE_S3_REGION \
  CORTEX_OBJECT_MIGRATION_SOURCE_S3_ENDPOINT \
  CORTEX_OBJECT_MIGRATION_SOURCE_S3_PREFIX \
  CORTEX_OBJECT_MIGRATION_SOURCE_S3_PATH_STYLE \
  CORTEX_OBJECT_MIGRATION_EVIDENCE_DIR \
  CORTEX_OBJECT_MIGRATION_PAGE_SIZE \
  CORTEX_SYNC_ACADEMY_ENABLED \
  CORTEX_SYNC_ACADEMY_READINESS_MAX_AGE_MS \
  CORTEX_SYNC_ZELADORIA_ENABLED \
  CORTEX_SYNC_ZELADORIA_READINESS_MAX_AGE_MS

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

echo "Reconciling local PostgreSQL role credentials and least-privilege flags." >&2
"${compose[@]}" exec -T cortex-postgres \
  /docker-entrypoint-initdb.d/10-cortex-roles.sh

target_table_count="$(
  "${compose[@]}" exec -T cortex-postgres \
    psql --username=cortex_admin --dbname=StaviasCortex --tuples-only --no-align \
      --command="SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public';"
)"
restored_source=false
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
  ' < "$backup_file" > "$pg_restore_log" 2>&1
  restored_source=true
else
  echo "The isolated PostgreSQL already contains data; restore was skipped." >&2
  : > "$pg_restore_log"
fi
chmod 600 "$pg_restore_log"

if [[ "$restored_source" == "true" ]]; then
  reset_restored_sequences
fi

capture_target_table_counts "$target_count_manifest"
if ! cmp -s "$source_count_manifest" "$target_count_manifest"; then
  echo "The restored PostgreSQL table counts differ from the source manifest." >&2
  exit 1
fi

database_evidence="$evidence_dir/database-copy-result.json"
database_evidence_temp="$(mktemp "$evidence_dir/.database-copy-result.XXXXXX")"
source_manifest_sha="$(openssl dgst -sha256 "$source_count_manifest" | awk '{print $NF}')"
target_manifest_sha="$(openssl dgst -sha256 "$target_count_manifest" | awk '{print $NF}')"
table_count="$(wc -l < "$source_count_manifest" | tr -d '[:space:]')"
python3 - \
  "$database_evidence_temp" \
  "$release_sha" \
  "$source_manifest_sha" \
  "$target_manifest_sha" \
  "$table_count" <<'PY'
import json
import pathlib
import sys

path, revision, source_sha, target_sha, table_count = sys.argv[1:]
pathlib.Path(path).write_text(json.dumps({
    "version": 1,
    "expectedRevision": revision,
    "sourceManifestSha256": source_sha,
    "targetManifestSha256": target_sha,
    "tableCount": int(table_count),
    "matched": source_sha == target_sha,
}, sort_keys=True, separators=(",", ":")) + "\n")
PY
chmod 600 "$database_evidence_temp"
mv -f "$database_evidence_temp" "$database_evidence"

"${compose[@]}" up \
  --force-recreate \
  --abort-on-container-exit \
  --exit-code-from cortex-migrate \
  cortex-migrate
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
candidate_base_url="https://$release_rp_id:$release_https_port"
candidate_resolve="$release_rp_id:$release_https_port:127.0.0.1"
for _ in $(seq 1 30); do
  if curl \
    --cacert "$ca_certificate" \
    --resolve "$candidate_resolve" \
    --fail \
    --silent \
    --show-error \
    "$candidate_base_url/healthz" >/dev/null 2>&1; then
    https_ready=true
    break
  fi
  sleep 1
done
if [[ "$https_ready" != "true" ]]; then
  echo "The HTTPS edge did not become reachable on the published origin." >&2
  exit 1
fi

CORTEX_BASE_URL="$candidate_base_url" \
CORTEX_SMOKE_CA_CERT="$ca_certificate" \
CORTEX_SMOKE_RESOLVE="$candidate_resolve" \
  "$repo_root/scripts/smoke-deploy.sh"

printf 'Candidate runtime is ready on loopback port %s for %s\n' \
  "$release_https_port" "$release_public_origin"
printf 'Backup: %s\n' "$backup_file"
printf 'Environment: %s\n' "$runtime_env"
