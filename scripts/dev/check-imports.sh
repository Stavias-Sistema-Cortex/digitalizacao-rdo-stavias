#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

source "$ROOT_DIR/scripts/dev/load-local-env.sh"
source "$ROOT_DIR/scripts/dev/postgres-cortex-common.sh"

cortex_require_postgres_url
cortex_require_text CORTEX_POSTGRES_USER
cortex_require_secret_file CORTEX_POSTGRES_PASSWORD_FILE
cortex_require_psql

if [[ -n "${CORTEX_POSTGRES_PASSWORD:-}" || -n "${PGPASSWORD:-}" ]]; then
  echo "Use CORTEX_POSTGRES_PASSWORD_FILE; inline PostgreSQL passwords are refused." >&2
  exit 1
fi

pgpass_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//:/\\:}"
  printf '%s' "$value"
}

postgres_password="$(<"$CORTEX_POSTGRES_PASSWORD_FILE")"
if [[ -z "$postgres_password" || "$postgres_password" == *$'\r'* || "$postgres_password" == *$'\n'* ]]; then
  echo "CORTEX_POSTGRES_PASSWORD_FILE must contain one non-empty secret value." >&2
  exit 1
fi

umask 077
pgpass_file="$(mktemp "${TMPDIR:-/tmp}/cortex-pgpass.XXXXXX")"
cleanup_pgpass() {
  rm -f "$pgpass_file"
}
trap cleanup_pgpass EXIT

printf '%s:%s:%s:%s:%s\n' \
  "$(pgpass_escape "$CORTEX_POSTGRES_HOST")" \
  "$(pgpass_escape "$CORTEX_POSTGRES_PORT")" \
  "$(pgpass_escape "$(cortex_postgres_database_name)")" \
  "$(pgpass_escape "$CORTEX_POSTGRES_USER")" \
  "$(pgpass_escape "$postgres_password")" > "$pgpass_file"
unset postgres_password
export PGPASSFILE="$pgpass_file"

cortex_psql_connection
cortex_verify_target_database

api_health_url="${CORTEX_API_HEALTH_URL:-http://127.0.0.1:8080/api/health}"
echo "API health:"
curl --fail --silent --show-error "$api_health_url"
printf '\n\n'

database_name="$(cortex_postgres_database_name)"
echo "Totais no PostgreSQL canônico:"
"${PSQL_CONNECTION[@]}" -d "$database_name" -P pager=off -c "
SELECT 'asset' AS entidade, COUNT(*) AS total FROM asset
UNION ALL
SELECT 'colaborador', COUNT(*) FROM colaborador
UNION ALL
SELECT 'obra', COUNT(*) FROM obra
UNION ALL
SELECT 'programacao_operacional', COUNT(*) FROM programacao_operacional
ORDER BY entidade;
"

echo
echo "Programações por contrato de origem:"
"${PSQL_CONNECTION[@]}" -d "$database_name" -P pager=off -c "
SELECT codigo_contrato_origem, COUNT(*) AS total
FROM programacao_operacional
GROUP BY codigo_contrato_origem
ORDER BY total DESC, codigo_contrato_origem;
"

echo
echo "Últimas execuções de sincronização registradas no PostgreSQL:"
"${PSQL_CONNECTION[@]}" -d "$database_name" -P pager=off -c "
SELECT
  connector_name,
  source_database,
  source_table,
  started_at,
  finished_at,
  status,
  records_read,
  records_inserted,
  records_updated,
  records_deactivated,
  error_message
FROM source_sync_run
ORDER BY started_at DESC
LIMIT 20;
"
