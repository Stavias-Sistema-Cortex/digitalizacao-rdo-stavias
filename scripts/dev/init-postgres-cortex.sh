#!/usr/bin/env bash
set -euo pipefail
umask 077

# Provisioning is deliberately separate from migration and bootstrap.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=postgres-cortex-common.sh
source "${SCRIPT_DIR}/postgres-cortex-common.sh"

cortex_require_postgres_url
cortex_require_text CORTEX_POSTGRES_USER
cortex_prepare_postgres_password
cortex_require_psql
cortex_psql_connection

DB_NAME="$(cortex_postgres_database_name)"

database_exists="$("${PSQL_CONNECTION[@]}" -d postgres -Atqc "SELECT 1 FROM pg_database WHERE datname = '${DB_NAME}'")"

if [[ "$database_exists" != "1" ]]; then
  "${PSQL_CONNECTION[@]}" -d postgres -c "CREATE DATABASE \"${DB_NAME}\""
  echo "PostgreSQL database ${DB_NAME} created."
else
  echo "PostgreSQL database ${DB_NAME} already exists."
fi

cortex_verify_target_database

echo "PostgreSQL database ${DB_NAME} is provisioned; V44 is not installed yet."
