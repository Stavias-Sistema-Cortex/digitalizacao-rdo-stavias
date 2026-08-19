#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
validator="$repo_root/scripts/deploy/validate-local-release-inputs.sh"

valid_sha='63f315df6bd0377b162851d22bdd4f644a938c2b'
valid_marker='igfgku3z2pPhpBClBEpnaMTZs8HLLhfa-vuAM6E269Q'
image_repository='ghcr.io/stavias-sistema-cortex/digitalizacao-rdo-stavias'
valid_api="${image_repository}-api@sha256:6c64976326ee90cfc57b487e34174095a5fd74a705979cc0cb23f8fa74bf4244"
valid_web="${image_repository}-web@sha256:79b7d935c480954c1a93e1e827fa4b7126c546210d5b77b2a57535a5b680b1e9"

run_validator() {
  env \
    CORTEX_PRODUCTION_MODE="${CORTEX_TEST_MODE:-rehearsal}" \
    COMPOSE_PROJECT_NAME="${CORTEX_TEST_PROJECT:-cortex-production-rehearsal}" \
    CORTEX_HTTPS_PORT="${CORTEX_TEST_PORT:-18444}" \
    CORTEX_RELEASE_SHA="${CORTEX_TEST_SHA:-$valid_sha}" \
    CORTEX_DATABASE_RELEASE_MARKER="${CORTEX_TEST_MARKER:-$valid_marker}" \
    CORTEX_API_IMAGE="${CORTEX_TEST_API_IMAGE:-$valid_api}" \
    CORTEX_WEB_IMAGE="${CORTEX_TEST_WEB_IMAGE:-$valid_web}" \
    CORTEX_PUBLIC_ORIGIN="${CORTEX_TEST_ORIGIN:-https://cortex.portalstavias.com.br}" \
    CORTEX_AUTH_WEBAUTHN_RP_ID="${CORTEX_TEST_RP_ID:-cortex.portalstavias.com.br}" \
    bash "$validator"
}

expect_rejected() {
  local description="$1"
  shift
  if env "$@" bash -c 'run_validator' 2>/dev/null; then
    echo "validator accepted $description" >&2
    exit 1
  fi
}

export -f run_validator
export validator valid_sha valid_marker valid_api valid_web image_repository

run_validator

expect_rejected 'a short release SHA' CORTEX_TEST_SHA=63f315df
expect_rejected 'a mutable API tag' CORTEX_TEST_API_IMAGE="${image_repository}-api:production"
expect_rejected 'an image from another repository' CORTEX_TEST_WEB_IMAGE=ghcr.io/example/web@sha256:79b7d935c480954c1a93e1e827fa4b7126c546210d5b77b2a57535a5b680b1e9
expect_rejected 'an invalid release marker' CORTEX_TEST_MARKER='not-a-marker'
expect_rejected 'a noncanonical browser origin' CORTEX_TEST_ORIGIN=https://cortex-stavias.pages.dev
expect_rejected 'a noncanonical WebAuthn RP ID' CORTEX_TEST_RP_ID=cortex.localhost
expect_rejected 'the live project name in rehearsal mode' CORTEX_TEST_PROJECT=cortex-production
expect_rejected 'the rehearsal project name in cutover mode' \
  CORTEX_TEST_MODE=cutover CORTEX_TEST_PROJECT=cortex-production-rehearsal CORTEX_TEST_PORT=18443
expect_rejected 'a privileged HTTPS port' CORTEX_TEST_PORT=443

CORTEX_TEST_MODE=cutover \
CORTEX_TEST_PROJECT=cortex-production \
CORTEX_TEST_PORT=18443 \
  run_validator

echo "Immutable local release input contracts passed."
