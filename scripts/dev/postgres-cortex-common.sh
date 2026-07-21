#!/usr/bin/env bash

# Shared, source-only safeguards for the explicit PostgreSQL Córtex commands.
# This file never prints a secret value and never opens a database connection.

cortex_postgres_database_name() {
  printf '%s' 'StaviasCortex'
}

cortex_repository_root() {
  local script_dir
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  cd "${script_dir}/../.." && pwd
}

cortex_require_text() {
  local variable_name="$1"
  local value="${!variable_name:-}"
  if [[ -z "$value" || "$value" == *$'\r'* || "$value" == *$'\n'* ]]; then
    echo "${variable_name} must be configured." >&2
    exit 1
  fi
}

cortex_require_secret_file() {
  local variable_name="$1"
  local path="${!variable_name:-}"
  if [[ -z "$path" || ! -f "$path" || -L "$path" || ! -r "$path" ]]; then
    echo "${variable_name} must name a readable regular secret file." >&2
    exit 1
  fi
}

cortex_export_secret_file() {
  local destination_name="$1"
  local source_name="$2"
  local secret

  cortex_require_secret_file "$source_name"
  secret="$(<"${!source_name}")"
  if [[ -z "$secret" || "$secret" == *$'\r'* || "$secret" == *$'\n'* ]]; then
    echo "${source_name} must contain one non-empty secret value." >&2
    exit 1
  fi
  printf -v "$destination_name" '%s' "$secret"
  export "$destination_name"
  unset secret
}

cortex_require_postgres_url() {
  local database_name
  local postgres_url="${CORTEX_POSTGRES_URL:-}"
  database_name="$(cortex_postgres_database_name)"

  if [[ ! "$postgres_url" =~ ^jdbc:postgresql://([^/:?]+)(:([0-9]+))?/${database_name}(\?.*)?$ ]]; then
    echo "CORTEX_POSTGRES_URL must target jdbc:postgresql://HOST[:PORT]/${database_name}." >&2
    exit 1
  fi
  CORTEX_POSTGRES_HOST="${BASH_REMATCH[1]}"
  CORTEX_POSTGRES_PORT="${BASH_REMATCH[3]:-5432}"
  export CORTEX_POSTGRES_HOST CORTEX_POSTGRES_PORT
}

cortex_require_psql() {
  PSQL_BIN="${PSQL_BIN:-psql}"
  if ! command -v "$PSQL_BIN" >/dev/null 2>&1; then
    echo "psql was not found. Install PostgreSQL client tools before this transition." >&2
    exit 1
  fi
  export PSQL_BIN
}

cortex_prepare_postgres_password() {
  if [[ -n "${CORTEX_POSTGRES_PASSWORD:-}" || -n "${PGPASSWORD:-}" ]]; then
    echo "CORTEX_POSTGRES_PASSWORD must be supplied via CORTEX_POSTGRES_PASSWORD_FILE." >&2
    exit 1
  fi
  if [[ -z "${CORTEX_POSTGRES_PASSWORD_FILE:-}" ]]; then
    # A passwordless local PostgreSQL policy is allowed only when the server
    # itself accepts the connection. Production operators provide a file path.
    return 0
  fi
  cortex_export_secret_file CORTEX_POSTGRES_PASSWORD CORTEX_POSTGRES_PASSWORD_FILE
  export PGPASSWORD="$CORTEX_POSTGRES_PASSWORD"
}

cortex_psql_connection() {
  PSQL_CONNECTION=(
    "$PSQL_BIN"
    -X
    -w
    -v ON_ERROR_STOP=1
    -h "$CORTEX_POSTGRES_HOST"
    -p "$CORTEX_POSTGRES_PORT"
    -U "$CORTEX_POSTGRES_USER"
  )
}

cortex_verify_target_database() {
  local database_name
  local current_database
  database_name="$(cortex_postgres_database_name)"
  current_database="$("${PSQL_CONNECTION[@]}" -d "$database_name" -Atqc "SELECT current_database()")"
  if [[ "$current_database" != "$database_name" ]]; then
    echo "PostgreSQL verification did not connect to ${database_name}." >&2
    exit 1
  fi
}

cortex_verify_v44() {
  local database_name
  local completed
  database_name="$(cortex_postgres_database_name)"
  completed="$("${PSQL_CONNECTION[@]}" -d "$database_name" -Atqc "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '44' AND success = TRUE")"
  if [[ "$completed" != "1" ]]; then
    echo "PostgreSQL Córtex baseline V44 must be complete before this transition." >&2
    exit 1
  fi
}
