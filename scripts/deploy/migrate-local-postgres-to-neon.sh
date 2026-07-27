#!/usr/bin/env bash
set -euo pipefail
umask 077

: "${CORTEX_SOURCE_PGURI:?Set the local PostgreSQL URI}"
: "${CORTEX_NEON_ADMIN_PGURI:?Set the Neon owner URI with sslmode=require}"
: "${CORTEX_NEON_RUNTIME_PASSWORD:?Set the runtime-role password}"

[[ "$CORTEX_NEON_ADMIN_PGURI" =~ (\?|&)sslmode=require(&|$) ]] || {
  echo "Neon admin URI must require TLS." >&2
  exit 1
}

source_uri_without_query="${CORTEX_SOURCE_PGURI%%\?*}"
[[ "${source_uri_without_query##*/}" == "StaviasCortex" ]] || {
  echo "Source PostgreSQL URI must target StaviasCortex." >&2
  exit 1
}
unset source_uri_without_query

postgres_bin_dir="${CORTEX_POSTGRES_BIN_DIR:-}"
if [[ -z "$postgres_bin_dir" ]]; then
  candidate_dirs=(
    /opt/homebrew/opt/postgresql@18/bin
    /Applications/Postgres.app/Contents/Versions/18/bin
    /usr/lib/postgresql/18/bin
    /usr/pgsql-18/bin
  )
  for command_name in psql pg_dump pg_restore; do
    command_path="$(command -v "$command_name" 2>/dev/null || true)"
    if [[ -n "$command_path" ]]; then
      candidate_dirs+=("$(dirname "$command_path")")
    fi
  done

  for candidate_dir in "${candidate_dirs[@]}"; do
    if [[ -x "$candidate_dir/psql" &&
      -x "$candidate_dir/pg_dump" &&
      -x "$candidate_dir/pg_restore" ]] &&
      "$candidate_dir/psql" --version | grep -Eq 'PostgreSQL\) 18\.' &&
      "$candidate_dir/pg_dump" --version | grep -Eq 'PostgreSQL\) 18\.' &&
      "$candidate_dir/pg_restore" --version | grep -Eq 'PostgreSQL\) 18\.'; then
      postgres_bin_dir="$candidate_dir"
      break
    fi
  done
fi

for command_name in psql pg_dump pg_restore; do
  command_path="$postgres_bin_dir/$command_name"
  [[ -x "$command_path" ]] &&
    "$command_path" --version | grep -Eq 'PostgreSQL\) 18\.' || {
      echo "A coherent PostgreSQL 18 client toolset is required." >&2
      exit 1
    }
done

psql_bin="$postgres_bin_dir/psql"
pg_dump_bin="$postgres_bin_dir/pg_dump"
pg_restore_bin="$postgres_bin_dir/pg_restore"
unset command_path postgres_bin_dir

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/cortex-neon-migration.XXXXXX")"
cleanup() {
  find "$work_dir" -type f -delete 2>/dev/null || true
  rmdir "$work_dir" 2>/dev/null || true
}
trap cleanup EXIT

dump_file="$work_dir/StaviasCortex.dump"
target_admin_base="${CORTEX_NEON_ADMIN_PGURI%%\?*}"
target_admin_uri="${target_admin_base%/*}/StaviasCortex?sslmode=require"
unset target_admin_base

"$psql_bin" "$CORTEX_NEON_ADMIN_PGURI" \
  -v ON_ERROR_STOP=1 \
  -v runtime_password="$CORTEX_NEON_RUNTIME_PASSWORD" \
  -f "$(git rev-parse --show-toplevel)/scripts/deploy/prepare-neon-database.sql" \
  >/dev/null

target_tables="$("$psql_bin" "$target_admin_uri" -v ON_ERROR_STOP=1 -Atqc \
  "select count(*) from pg_tables where schemaname='public'")"
[[ "$target_tables" == "0" ]] || {
  echo "Target StaviasCortex database is not empty; migration stopped." >&2
  exit 1
}

"$pg_dump_bin" \
  --dbname="$CORTEX_SOURCE_PGURI" \
  --format=custom \
  --no-owner \
  --no-acl \
  --file="$dump_file"

[[ -s "$dump_file" ]] || {
  echo "PostgreSQL dump is empty; migration stopped." >&2
  exit 1
}
"$pg_restore_bin" --list "$dump_file" >/dev/null

"$pg_restore_bin" \
  --dbname="$target_admin_uri" \
  --exit-on-error \
  --single-transaction \
  --no-owner \
  --no-acl \
  "$dump_file"

"$psql_bin" "$target_admin_uri" -v ON_ERROR_STOP=1 >/dev/null <<'SQL'
GRANT USAGE ON SCHEMA public TO cortex_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE
    ON ALL TABLES IN SCHEMA public TO cortex_runtime;
GRANT USAGE, SELECT, UPDATE
    ON ALL SEQUENCES IN SCHEMA public TO cortex_runtime;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO cortex_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cortex_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO cortex_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT EXECUTE ON FUNCTIONS TO cortex_runtime;
SQL

core_tables=(
  auth_identity
  auth_capacidade_administrativa
  colaborador
  obra
  rdo
  rdo_mao_obra
  vinculo_colaborador_obra
  cortex_evento_operacional
  finance_lancamento
  stored_object
)

for table_name in "${core_tables[@]}"; do
  source_count="$("$psql_bin" "$CORTEX_SOURCE_PGURI" -v ON_ERROR_STOP=1 -Atqc \
    "select count(*) from $table_name")"
  target_count="$("$psql_bin" "$target_admin_uri" -v ON_ERROR_STOP=1 -Atqc \
    "select count(*) from $table_name")"

  [[ "$source_count" =~ ^[0-9]+$ && "$target_count" =~ ^[0-9]+$ ]] || {
    echo "A core-table count was not numeric; migration validation stopped." >&2
    exit 1
  }

  printf '%s|%s|%s\n' "$table_name" "$source_count" "$target_count"
  [[ "$source_count" == "$target_count" ]] || exit 1
done
