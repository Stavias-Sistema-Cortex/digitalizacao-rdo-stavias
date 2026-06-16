#!/usr/bin/env bash
set -euo pipefail

echo "Importando ativos..."
./scripts/dev/import-assets.sh

echo
echo "Importando colaboradores..."
./scripts/dev/import-colaboradores.sh

echo
echo "Importando obras..."
./scripts/dev/import-obras.sh

echo
echo "Importando programações..."
./scripts/dev/import-programacoes.sh

echo
echo "Importações finalizadas."
