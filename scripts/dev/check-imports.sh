#!/usr/bin/env bash
set -euo pipefail

set -a
source .env
set +a

echo "API health:"
curl -s "http://localhost:8080/api/health" | jq

echo
echo "Totais no banco:"
mysql -h 127.0.0.1 \
  -P 3306 \
  -u cortex_app \
  -p"$CORTEX_DB_PASSWORD" \
  cortex_dev \
  -e "
SELECT COUNT(*) AS total_assets FROM asset;
SELECT COUNT(*) AS total_colaboradores FROM colaborador;
SELECT COUNT(*) AS total_obras FROM obra;
SELECT COUNT(*) AS total_programacoes FROM programacao_operacional;
SELECT codigo_contrato_origem, COUNT(*) AS total
FROM programacao_operacional
GROUP BY codigo_contrato_origem
ORDER BY total DESC;
"

echo
echo "Últimas sync runs:"
./scripts/dev/sync-runs.sh
