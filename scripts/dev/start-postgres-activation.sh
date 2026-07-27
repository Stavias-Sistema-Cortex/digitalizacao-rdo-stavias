#!/usr/bin/env bash
set -euo pipefail
umask 077

# Explicit transition 3/4: serve only health/readiness and e-mail OTP activation.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=postgres-cortex-common.sh
source "${SCRIPT_DIR}/postgres-cortex-common.sh"

cortex_require_postgres_url
cortex_require_text CORTEX_POSTGRES_USER
cortex_prepare_postgres_password
cortex_require_secret_file CORTEX_AUTH_OTP_HMAC_KEY_FILE
cortex_require_text CORTEX_EMAIL_PROVIDER
if [[ "$CORTEX_EMAIL_PROVIDER" != "smtp" ]]; then
  echo "CORTEX_EMAIL_PROVIDER must be smtp for PostgreSQL activation." >&2
  exit 1
fi
cortex_require_text CORTEX_SMTP_HOST
cortex_require_text CORTEX_SMTP_USERNAME
cortex_require_text CORTEX_SMTP_FROM
cortex_require_secret_file CORTEX_SMTP_PASSWORD_FILE
cortex_require_text CORTEX_PUBLIC_ORIGIN
export CORTEX_CORS_ALLOWED_ORIGINS="$CORTEX_PUBLIC_ORIGIN"
cortex_require_psql
cortex_psql_connection
cortex_verify_target_database
cortex_verify_v44

REPOSITORY_ROOT="$(cortex_repository_root)"
cd "${REPOSITORY_ROOT}/apps/api"
exec ./mvnw \
  -Dspring-boot.run.main-class=com.projeto.cortex.postgresql.activation.PostgresqlActivationApplication \
  -Dspring-boot.run.profiles=postgresql-activation \
  -DskipTests \
  spring-boot:run
