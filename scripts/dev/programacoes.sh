#!/usr/bin/env bash
set -euo pipefail

API_BASE_URL="${CORTEX_API_BASE_URL:-http://localhost:8080}"
QUERY="${1:-}"

if [ -z "$QUERY" ]; then
  curl -s "$API_BASE_URL/api/programacoes" | jq
else
  curl -s "$API_BASE_URL/api/programacoes?query=$QUERY" | jq
fi
