#!/usr/bin/env bash
set -euo pipefail

QUERY="${1:-}"
BASE_URL="${CORTEX_API_BASE_URL:-http://localhost:8080}"

if [ -z "$QUERY" ]; then
  URL="${BASE_URL}/api/colaboradores"
else
  ENCODED_QUERY=$(python3 -c "import urllib.parse; print(urllib.parse.quote('''$QUERY'''))")
  URL="${BASE_URL}/api/colaboradores?query=${ENCODED_QUERY}"
fi

curl -s "$URL" \
  | jq -r '
      if length == 0 then
        "No colaboradores found"
      else
        ["CODIGO", "NOME", "GRUPO", "PERFIL", "ATIVO"],
        (.[] | [
          (.codigoColaborador // "-"),
          (.nome // "-"),
          (.nomeGrupo // "-"),
          (.nomePerfil // "-"),
          (.ativo | tostring)
        ])
        | @tsv
      end
    ' \
  | column -t -s $'\t'
