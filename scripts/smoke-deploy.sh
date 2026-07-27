#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${CORTEX_BASE_URL:?Defina CORTEX_BASE_URL com a origem publicada, sem barra final}"
BASE_URL="${BASE_URL%/}"

curl_tls_args=()
if [[ -n "${CORTEX_SMOKE_CA_CERT:-}" ]]; then
  [[ -f "$CORTEX_SMOKE_CA_CERT" && ! -L "$CORTEX_SMOKE_CA_CERT" ]] || {
    echo "CORTEX_SMOKE_CA_CERT must name a regular CA certificate file." >&2
    exit 1
  }
  curl_tls_args=(--cacert "$CORTEX_SMOKE_CA_CERT")
fi

request() {
  curl --fail --silent --show-error "${curl_tls_args[@]}" "$@"
}

request "$BASE_URL/healthz" | grep -q '^ok$'
request "$BASE_URL/api/health" | grep -q '"status":"UP"'
request "$BASE_URL/api/readiness" | grep -q '"status":"READY"'
request "$BASE_URL/" | grep -q '<div id="root"></div>'
request "$BASE_URL/manifest.webmanifest" | grep -q '"name":"Córtex Stavias"'

if [[ -n "${CORTEX_SMOKE_COOKIE_JAR:-}" ]]; then
  request -b "$CORTEX_SMOKE_COOKIE_JAR" \
    "$BASE_URL/api/auth/session" | grep -q '"authenticated":true'

  if [[ -n "${CORTEX_SMOKE_OBRA_ID:-}" ]]; then
    request -b "$CORTEX_SMOKE_COOKIE_JAR" \
      "$BASE_URL/api/financeiro/capacidades?obraId=${CORTEX_SMOKE_OBRA_ID}" \
      | grep -q '"obraId"'
    request -b "$CORTEX_SMOKE_COOKIE_JAR" \
      "$BASE_URL/api/financeiro/visao-geral?obraId=${CORTEX_SMOKE_OBRA_ID}" \
      | grep -q '"moeda"'
  fi
fi

echo "Smoke de deploy concluído sem erro."
