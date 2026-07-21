#!/usr/bin/env bash
set -euo pipefail
umask 077

# Explicit transition 1/4: install V44 in an already provisioned StaviasCortex.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=postgres-cortex-common.sh
source "${SCRIPT_DIR}/postgres-cortex-common.sh"

cortex_require_postgres_url
cortex_require_text CORTEX_POSTGRES_USER
cortex_prepare_postgres_password
cortex_require_psql
cortex_psql_connection
cortex_verify_target_database

REPOSITORY_ROOT="$(cortex_repository_root)"
cd "${REPOSITORY_ROOT}/apps/api"
exec ./mvnw \
  -Dspring-boot.run.main-class=com.projeto.cortex.postgresql.migrate.PostgresqlMigrationApplication \
  -Dspring-boot.run.profiles=postgresql-migrate \
  -DskipTests \
  spring-boot:run
