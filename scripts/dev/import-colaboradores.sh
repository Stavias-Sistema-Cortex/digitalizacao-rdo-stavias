#!/usr/bin/env bash
set -euo pipefail

API_BASE_URL="${CORTEX_API_BASE_URL:-http://localhost:8080}"

curl -s -X POST "$API_BASE_URL/api/colaboradores/import/acad" | jq
