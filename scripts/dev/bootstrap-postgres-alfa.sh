#!/usr/bin/env bash
set -euo pipefail
umask 077

# Explicit transition 2/4: bootstrap exactly one initial ALFA from Academy.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=postgres-cortex-common.sh
source "${SCRIPT_DIR}/postgres-cortex-common.sh"

cortex_require_postgres_url
cortex_require_text CORTEX_POSTGRES_USER
cortex_prepare_postgres_password
cortex_require_secret_file CORTEX_BOOTSTRAP_ADMIN_CPF_FILE
cortex_require_text CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_ID
cortex_require_secret_file CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE
cortex_require_text CORTEX_ACADEMY_DB_URL
cortex_require_text CORTEX_ACADEMY_DB_USER
cortex_export_secret_file CORTEX_ACADEMY_DB_PASSWORD CORTEX_ACADEMY_DB_PASSWORD_FILE
cortex_require_psql
cortex_psql_connection
cortex_verify_target_database
cortex_verify_v44
export CORTEX_POSTGRES_BOOTSTRAP_ENABLED=true

REPOSITORY_ROOT="$(cortex_repository_root)"
cd "${REPOSITORY_ROOT}/apps/api"
exec ./mvnw \
  -Dspring-boot.run.main-class=com.projeto.cortex.postgresql.bootstrap.PostgresqlBootstrapApplication \
  -Dspring-boot.run.profiles=postgresql-bootstrap \
  -DskipTests \
  spring-boot:run
