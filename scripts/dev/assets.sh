#!/usr/bin/env bash
set -euo pipefail

QUERY="${1:-}"

if [ -z "$QUERY" ]; then
  URL="http://localhost:8080/api/assets"
else
  ENCODED_QUERY=$(python3 -c "import urllib.parse; print(urllib.parse.quote('''$QUERY'''))")
  URL="http://localhost:8080/api/assets?query=${ENCODED_QUERY}"
fi

curl -s "$URL" \
  | jq -r '
      if length == 0 then
        "No assets found"
      else
        ["CODE", "CATEGORY", "NAME"],
        (.[] | [.externalCode, .category, .name])
        | @tsv
      end
    ' \
  | column -t -s $'\t'
