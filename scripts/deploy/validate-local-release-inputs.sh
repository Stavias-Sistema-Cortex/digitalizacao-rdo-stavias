#!/usr/bin/env bash
set -euo pipefail

require_release_value() {
  local name="$1"
  local value="${!name:-}"
  if [[ -z "$value" || "$value" == *$'\r'* || "$value" == *$'\n'* ]]; then
    echo "$name must be configured as one non-empty line." >&2
    return 1
  fi
}

for name in \
  CORTEX_PRODUCTION_MODE \
  COMPOSE_PROJECT_NAME \
  CORTEX_HTTPS_PORT \
  CORTEX_RELEASE_SHA \
  CORTEX_DATABASE_RELEASE_MARKER \
  CORTEX_API_IMAGE \
  CORTEX_WEB_IMAGE \
  CORTEX_PUBLIC_ORIGIN \
  CORTEX_AUTH_WEBAUTHN_RP_ID; do
  require_release_value "$name"
done

case "$CORTEX_PRODUCTION_MODE" in
  rehearsal)
    [[ "$COMPOSE_PROJECT_NAME" == "cortex-production-rehearsal" ]] || {
      echo "Rehearsal mode requires COMPOSE_PROJECT_NAME=cortex-production-rehearsal." >&2
      exit 1
    }
    ;;
  cutover)
    [[ "$COMPOSE_PROJECT_NAME" == "cortex-production" ]] || {
      echo "Cutover mode requires COMPOSE_PROJECT_NAME=cortex-production." >&2
      exit 1
    }
    ;;
  *)
    echo "CORTEX_PRODUCTION_MODE must be rehearsal or cutover." >&2
    exit 1
    ;;
esac

if [[ ! "$CORTEX_HTTPS_PORT" =~ ^[0-9]{4,5}$ ]] ||
  (( CORTEX_HTTPS_PORT < 1024 || CORTEX_HTTPS_PORT > 65535 )); then
  echo "CORTEX_HTTPS_PORT must be an unprivileged TCP port." >&2
  exit 1
fi

if [[ ! "$CORTEX_RELEASE_SHA" =~ ^[a-f0-9]{40}$ ]]; then
  echo "CORTEX_RELEASE_SHA must be a full lowercase Git commit SHA." >&2
  exit 1
fi
if [[ ! "$CORTEX_DATABASE_RELEASE_MARKER" =~ ^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$ ]]; then
  echo "CORTEX_DATABASE_RELEASE_MARKER must be canonical SHA-256 base64url." >&2
  exit 1
fi

expected_marker="$({
  printf 'cortex-release-v1:%s' "$CORTEX_RELEASE_SHA" |
    openssl dgst -sha256 -binary |
    openssl base64 -A |
    tr '+/' '-_' |
    tr -d '='
})"
if [[ "$CORTEX_DATABASE_RELEASE_MARKER" != "$expected_marker" ]]; then
  echo "CORTEX_DATABASE_RELEASE_MARKER does not match CORTEX_RELEASE_SHA." >&2
  exit 1
fi

api_pattern='^ghcr\.io/stavias-sistema-cortex/digitalizacao-rdo-stavias-api@sha256:[a-f0-9]{64}$'
web_pattern='^ghcr\.io/stavias-sistema-cortex/digitalizacao-rdo-stavias-web@sha256:[a-f0-9]{64}$'
if [[ ! "$CORTEX_API_IMAGE" =~ $api_pattern ]]; then
  echo "CORTEX_API_IMAGE must be the immutable Córtex API GHCR digest." >&2
  exit 1
fi
if [[ ! "$CORTEX_WEB_IMAGE" =~ $web_pattern ]]; then
  echo "CORTEX_WEB_IMAGE must be the immutable Córtex PWA GHCR digest." >&2
  exit 1
fi

if [[ "$CORTEX_PUBLIC_ORIGIN" != "https://cortex.portalstavias.com.br" ]]; then
  echo "CORTEX_PUBLIC_ORIGIN must remain https://cortex.portalstavias.com.br." >&2
  exit 1
fi
if [[ "$CORTEX_AUTH_WEBAUTHN_RP_ID" != "cortex.portalstavias.com.br" ]]; then
  echo "CORTEX_AUTH_WEBAUTHN_RP_ID must remain cortex.portalstavias.com.br." >&2
  exit 1
fi
