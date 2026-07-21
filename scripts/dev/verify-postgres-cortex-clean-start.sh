#!/usr/bin/env bash
set -euo pipefail

# Safe rehearsal only: disposable test fixtures, never a local DB transition.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=postgres-cortex-common.sh
source "${SCRIPT_DIR}/postgres-cortex-common.sh"

REPOSITORY_ROOT="$(cortex_repository_root)"
cd "${REPOSITORY_ROOT}/apps/api"
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
export JAVA_HOME
exec ./mvnw -Ppostgresql-it verify
