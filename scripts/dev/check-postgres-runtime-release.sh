#!/usr/bin/env bash
set -euo pipefail

# Explicit transition 4/4 is a static preflight only; it never starts runtime.
RUNTIME_PROFILE="postgresql"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=postgres-cortex-common.sh
source "${SCRIPT_DIR}/postgres-cortex-common.sh"

cortex_require_postgres_url
if [[ "${CORTEX_POSTGRES_RUNTIME_READY:-false}" != "true" ]]; then
  echo "CORTEX_POSTGRES_RUNTIME_READY=true is required for the ${RUNTIME_PROFILE} runtime release check." >&2
  exit 1
fi

REPOSITORY_ROOT="$(cortex_repository_root)"
REGISTRY="${REPOSITORY_ROOT}/apps/api/src/main/java/com/projeto/cortex/config/PostgresqlRuntimeSurfaceRegistry.java"
if [[ ! -f "$REGISTRY" ]] || grep -Eq 'this\(Set\.of\(\)\)' "$REGISTRY"; then
  echo "${RUNTIME_PROFILE} runtime remains blocked: no PostgreSQL-safe operational surface is released." >&2
  exit 1
fi

echo "${RUNTIME_PROFILE} runtime preflight passed. Start commands remain intentionally separate."
