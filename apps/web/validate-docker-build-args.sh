#!/bin/sh
set -eu

case "${VITE_CORTEX_AUTH_MODE:-}" in
  legacy|postgresql)
    ;;
  *)
    echo "VITE_CORTEX_AUTH_MODE deve ser legacy ou postgresql." >&2
    exit 1
    ;;
esac

fingerprint="${VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256:-}"
if [ "${#fingerprint}" -ne 43 ]; then
  echo "VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256 deve ser um fingerprint SHA-256 base64url de 43 caracteres." >&2
  exit 1
fi
case "$fingerprint" in
  *[!A-Za-z0-9_-]*)
    echo "VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256 deve usar base64url canônico sem padding." >&2
    exit 1
    ;;
esac
