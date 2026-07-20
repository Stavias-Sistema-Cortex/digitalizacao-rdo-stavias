#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/compose.local.yml"

if [ ! -f "$COMPOSE_FILE" ]; then
  echo "Missing compose.local.yml at repo root."
  exit 1
fi

docker compose -f "$COMPOSE_FILE" down

echo ""
echo "Córtex local stack stopped."
echo ""
echo "Database volume was preserved."
echo "To delete the local Docker database, run manually:"
echo "  docker compose -f compose.local.yml down -v"
