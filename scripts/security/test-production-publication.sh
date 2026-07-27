#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
bash "$repo_root/scripts/security/test-hosted-deployment-contract.sh"
bash "$repo_root/scripts/security/test-hosted-deployment-contract-regressions.sh"
workflow_file="$repo_root/.github/workflows/production.yml"
ci_workflow_file="$repo_root/.github/workflows/api-ci.yml"
compose_file="$repo_root/deploy/production/compose.yml"
caddy_file="$repo_root/deploy/production/Caddyfile"
postgres_init_file="$repo_root/deploy/production/postgres-init.sh"
runbook_file="$repo_root/deploy/production/README.md"
prepare_script="$repo_root/scripts/deploy/prepare-local-production.sh"

for required_file in \
  "$workflow_file" \
  "$ci_workflow_file" \
  "$compose_file" \
  "$caddy_file" \
  "$postgres_init_file" \
  "$runbook_file" \
  "$prepare_script"; do
  [[ -f "$required_file" ]] || {
    echo "missing production publication file: $required_file" >&2
    exit 1
  }
done

bash -n "$prepare_script"
grep -Fq 'A PostgreSQL 18 pg_dump client is required' "$prepare_script"

grep -Fq 'environment: production' "$workflow_file"
grep -Fq 'packages: write' "$workflow_file"
grep -Fq 'attestations: write' "$workflow_file"
grep -Fq 'docker/build-push-action@' "$workflow_file"
grep -Fq 'push: true' "$workflow_file"
grep -Fq 'persist-credentials: false' "$workflow_file"
grep -Fq 'github.ref_name == '\''develop'\''' "$workflow_file"
grep -Fq -- '--build-arg VITE_CORTEX_API_BASE_URL=/api' "$ci_workflow_file"
grep -Fq -- '--build-arg VITE_CORTEX_AUTH_MODE=postgresql' "$ci_workflow_file"
grep -Eq -- '--build-arg VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256=[A-Za-z0-9_-]{43}' \
  "$ci_workflow_file"

if grep -Eq 'pull_request_target|secrets:[[:space:]]*inherit' "$workflow_file"; then
  echo "production publication workflow exposes an unsafe trigger or inherited secrets" >&2
  exit 1
fi

retired_runtime_pattern='stavi''a[_ -]?(ass''istant|launcher|llm|api)'
if grep -Eiq "openai|anthropic|gemini|${retired_runtime_pattern}" \
  "$workflow_file" "$compose_file" "$caddy_file" "$prepare_script"; then
  echo "production publication still contains a retired runtime dependency" >&2
  exit 1
fi

command -v docker >/dev/null 2>&1
docker compose version >/dev/null 2>&1
command -v python3 >/dev/null 2>&1

contract_dir="$(mktemp -d "${TMPDIR:-/tmp}/cortex-production-contract.XXXXXX")"
cleanup() {
  if [[ -n "${contract_dir:-}" ]]; then
    find "$contract_dir" -type f -delete 2>/dev/null || true
    rmdir "$contract_dir" 2>/dev/null || true
  fi
}
trap cleanup EXIT

for secret_name in \
  postgres_admin \
  postgres_migrator \
  postgres_runtime \
  academy \
  zeladoria \
  cpf_hmac \
  offline_private \
  offline_public \
  memory_cursor_hmac; do
  printf 'contract-only' > "$contract_dir/$secret_name"
done

rendered_file="$contract_dir/rendered.json"
env \
  CORTEX_API_IMAGE='cortex-api:contract' \
  CORTEX_WEB_IMAGE='cortex-web:contract' \
  CORTEX_POSTGRES_DB='StaviasCortex' \
  CORTEX_POSTGRES_ADMIN_USER='cortex_admin' \
  CORTEX_POSTGRES_ADMIN_PASSWORD_FILE="$contract_dir/postgres_admin" \
  CORTEX_POSTGRES_MIGRATOR_USER='cortex_migrator' \
  CORTEX_POSTGRES_MIGRATOR_PASSWORD_FILE="$contract_dir/postgres_migrator" \
  CORTEX_POSTGRES_USER='cortex_runtime' \
  CORTEX_POSTGRES_PASSWORD_FILE="$contract_dir/postgres_runtime" \
  CORTEX_PUBLIC_ORIGIN='https://cortex.localhost:18443' \
  CORTEX_HTTPS_PORT='18443' \
  CORTEX_AUTH_WEBAUTHN_RP_ID='cortex.localhost' \
  CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_ID='cpf-contract-v1' \
  CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE="$contract_dir/cpf_hmac" \
  CORTEX_AUTH_OFFLINE_GRANT_KEY_ID='offline-contract-v1' \
  CORTEX_AUTH_OFFLINE_GRANT_PRIVATE_KEY_FILE="$contract_dir/offline_private" \
  CORTEX_AUTH_OFFLINE_GRANT_PUBLIC_KEY_FILE="$contract_dir/offline_public" \
  CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID='memory-contract-v1' \
  CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_FILE="$contract_dir/memory_cursor_hmac" \
  VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256='contract-public-key-fingerprint' \
  CORTEX_ACADEMY_DB_URL='jdbc:mysql://academy.contract.internal:3306/academy' \
  CORTEX_ACADEMY_DB_USER='academy_readonly' \
  CORTEX_ACADEMY_DB_PASSWORD_FILE="$contract_dir/academy" \
  CORTEX_ZELADORIA_DB_URL='jdbc:mysql://zeladoria.contract.internal:3306/zeladoria' \
  CORTEX_ZELADORIA_DB_USER='zeladoria_readonly' \
  CORTEX_ZELADORIA_DB_PASSWORD_FILE="$contract_dir/zeladoria" \
  docker compose -f "$compose_file" config --format json > "$rendered_file"

python3 - "$rendered_file" <<'PY'
import json
import pathlib
import sys

document = json.loads(pathlib.Path(sys.argv[1]).read_text())
services = document["services"]
required = {
    "cortex-api",
    "cortex-edge",
    "cortex-migrate",
    "cortex-postgres",
    "cortex-web",
}
missing = sorted(required - services.keys())
assert not missing, f"missing production services: {missing}"

postgres = services["cortex-postgres"]
assert postgres["image"].startswith("postgres:18"), postgres["image"]
assert not postgres.get("ports"), "PostgreSQL must never publish a host port"
postgres_mounts = postgres.get("volumes", [])
assert any(
    mount.get("target") == "/var/lib/postgresql" for mount in postgres_mounts
), "PostgreSQL 18 must persist the version-aware parent data directory"

for private_service in ("cortex-api", "cortex-web"):
    assert not services[private_service].get("ports"), (
        f"{private_service} must be reachable only through the HTTPS edge"
    )

edge_ports = services["cortex-edge"].get("ports", [])
assert len(edge_ports) == 1, edge_ports
edge_port = edge_ports[0]
assert edge_port["host_ip"] == "127.0.0.1", edge_port
assert edge_port["target"] == 443, edge_port
assert edge_port["published"] == "18443", edge_port

api_environment = services["cortex-api"]["environment"]
assert api_environment["SPRING_PROFILES_ACTIVE"] == "production,postgresql"
assert api_environment["CORTEX_POSTGRES_USER"] == "cortex_runtime"
assert api_environment["CORTEX_POSTGRES_RUNTIME_READY"] == "true"
assert api_environment["CORTEX_SYNC_ENABLED"] == "false"
assert api_environment["CORTEX_CORS_ALLOWED_ORIGINS"] == "https://cortex.localhost:18443"
assert api_environment["CORTEX_AUTH_WEBAUTHN_ALLOWED_ORIGINS"] == "https://cortex.localhost:18443"
assert api_environment["CORTEX_AUTH_DEV_ADMIN_ENABLED"] == "false"
assert api_environment["CORTEX_AUTH_PROVISIONING_ENABLED"] == "false"

migrate_environment = services["cortex-migrate"]["environment"]
assert migrate_environment["CORTEX_POSTGRES_USER"] == "cortex_migrator"
assert migrate_environment["CORTEX_MAIN_CLASS"].endswith(
    ".PostgresqlMigrationApplication"
)
postgres_environment = services["cortex-postgres"]["environment"]
assert postgres_environment["POSTGRES_USER"] == "cortex_admin"

assert "cortex_private" in document["networks"]
assert document["networks"]["cortex_private"]["internal"] is True
assert "cortex_source_egress" in services["cortex-api"]["networks"]
assert "cortex_source_egress" not in services["cortex-postgres"]["networks"]
assert "cortex_host_edge" in services["cortex-edge"]["networks"]
assert "cortex_host_edge" not in services["cortex-postgres"]["networks"]
assert "cortex_host_edge" not in services["cortex-api"]["networks"]

serialized = json.dumps(document).lower()
assert "contract-only" not in serialized, "Compose leaked secret file content"
for forbidden in (
    "cortex_postgres_" + "pass" + "word=",
    "cortex_postgres_admin_" + "pass" + "word=",
    "cortex_postgres_migrator_" + "pass" + "word=",
    "cortex_academy_db_" + "pass" + "word=",
    "cortex_zeladoria_db_" + "pass" + "word=",
    "aws_secret_access_" + "key=",
):
    assert forbidden not in serialized, f"inline secret found: {forbidden}"
PY

echo "GitHub production environment, GHCR publication, HTTPS edge, PostgreSQL 18, and secret-mount contracts passed."
