#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
bash "$repo_root/scripts/security/test-hosted-deployment-contract.sh"
bash "$repo_root/scripts/security/test-hosted-deployment-contract-regressions.sh"
bash "$repo_root/scripts/security/test-production-workflow-contract.sh"
bash "$repo_root/scripts/security/test-production-workflow-contract-regressions.sh"
bash "$repo_root/scripts/security/test-github-actions-pinning.sh"
bash "$repo_root/scripts/security/test-github-actions-pinning-regressions.sh"
bash "$repo_root/scripts/security/test-docker-base-image-pinning.sh"
bash "$repo_root/scripts/security/test-neon-migration-contract.sh"
bash "$repo_root/scripts/security/test-neon-ownership-reconciliation.sh"
bash "$repo_root/scripts/security/test-scan-cortex-secrets.sh"
bash "$repo_root/scripts/security/test-postgres-role-reconciliation.sh"
bash "$repo_root/scripts/deploy/test-run-neon-flyway.sh"
bash "$repo_root/scripts/deploy/test-capture-render-instance-fingerprint.sh"
bash "$repo_root/scripts/deploy/test-trigger-and-wait-render.sh"
bash "$repo_root/scripts/deploy/test-deploy-and-verify-cloudflare-pages.sh"
workflow_file="$repo_root/.github/workflows/production.yml"
ci_workflow_file="$repo_root/.github/workflows/api-ci.yml"
compose_file="$repo_root/deploy/production/compose.yml"
caddy_file="$repo_root/deploy/production/Caddyfile"
postgres_init_file="$repo_root/deploy/production/postgres-init.sh"
postgres_entrypoint_file="$repo_root/deploy/production/postgres-entrypoint.sh"
runbook_file="$repo_root/deploy/production/README.md"
prepare_script="$repo_root/scripts/deploy/prepare-local-production.sh"

for required_file in \
  "$workflow_file" \
  "$ci_workflow_file" \
  "$compose_file" \
  "$caddy_file" \
  "$postgres_init_file" \
  "$postgres_entrypoint_file" \
  "$runbook_file" \
  "$prepare_script"; do
  [[ -f "$required_file" ]] || {
    echo "missing production publication file: $required_file" >&2
    exit 1
  }
done

bash -n "$prepare_script"
grep -Fq 'A PostgreSQL 18 pg_dump client is required' "$prepare_script"
grep -Fq 'install_secret_file CORTEX_ACADEMY_DB_PASSWORD_FILE' \
  "$prepare_script"
if [[ "$(grep -Fc 'ALTER ROLE %I WITH LOGIN PASSWORD %L' "$postgres_init_file")" -ne 2 ]]; then
  echo "local PostgreSQL role credentials are not reconciled for both roles" >&2
  exit 1
fi
grep -Fq 'PostgreSQL admin, migrator, and runtime roles must be distinct.' \
  "$postgres_init_file"
grep -Fq 'FROM pg_auth_members membership' "$postgres_init_file"
grep -Fq "'REVOKE %I FROM %I;'" "$postgres_init_file"
grep -Fq '/docker-entrypoint-initdb.d/10-cortex-roles.sh' "$prepare_script"
reconcile_line="$(
  grep -n -F '/docker-entrypoint-initdb.d/10-cortex-roles.sh' \
    "$prepare_script" | cut -d: -f1
)"
table_count_line="$(
  grep -n -F 'target_table_count="$(' "$prepare_script" | cut -d: -f1
)"
if [[ -z "$reconcile_line" || -z "$table_count_line" ]] ||
  (( reconcile_line >= table_count_line )); then
  echo "local PostgreSQL roles are not reconciled before restore inspection" >&2
  exit 1
fi
if [[ "$(grep -Fc 'NOREPLICATION NOBYPASSRLS' "$postgres_init_file")" -lt 2 ]] ||
  [[ "$(grep -Fc 'NOREPLICATION NOBYPASSRLS;' "$postgres_init_file")" -lt 2 ]]; then
  echo "local PostgreSQL roles do not explicitly revoke replication or RLS bypass" >&2
  exit 1
fi
if grep -Fq 'write_secret_value CORTEX_ACADEMY_DB_PASSWORD ' \
  "$prepare_script"; then
  echo "production preparation still accepts an inline Academy password" >&2
  exit 1
fi

grep -Fq 'environment: production' "$workflow_file"
grep -Fq 'packages: write' "$workflow_file"
grep -Fq 'attestations: write' "$workflow_file"
grep -Fq 'docker/build-push-action@' "$workflow_file"
grep -Fq 'push: true' "$workflow_file"
grep -Fq 'persist-credentials: false' "$workflow_file"
grep -Fq 'github.ref == '\''refs/heads/develop'\''' "$workflow_file"
grep -Fq -- '--build-arg VITE_CORTEX_API_BASE_URL=/api' "$ci_workflow_file"
grep -Fq -- '--build-arg VITE_CORTEX_AUTH_MODE=postgresql' "$ci_workflow_file"
grep -Eq -- '--build-arg VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256=[A-Za-z0-9_-]{43}' \
  "$ci_workflow_file"
grep -Fq 'npm run typecheck:functions' "$workflow_file"
grep -Fq 'npm run build:functions' "$workflow_file"
grep -Fq 'bash ../../scripts/security/scan-cortex-secrets.sh' "$workflow_file"

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
  CORTEX_ACADEMY_DB_URL='jdbc:mysql://academy.contract.internal:3306/academy?sslMode=VERIFY_IDENTITY' \
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
assert postgres["entrypoint"] == [
    "/usr/local/bin/cortex-postgres-entrypoint.sh"
], postgres["entrypoint"]
assert not postgres.get("ports"), "PostgreSQL must never publish a host port"
postgres_mounts = postgres.get("volumes", [])
assert any(
    mount.get("target") == "/var/lib/postgresql" for mount in postgres_mounts
), "PostgreSQL 18 must persist the version-aware parent data directory"
assert any(
    mount.get("target") == "/usr/local/bin/cortex-postgres-entrypoint.sh"
    and mount.get("read_only") is True
    for mount in postgres_mounts
), "PostgreSQL must stage mode-0600 secrets through the reviewed entrypoint"

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
assert api_environment["CORTEX_ACADEMY_DB_PASSWORD_FILE"] == (
    "/run/secrets/cortex_academy_password"
)
assert api_environment["CORTEX_SYNC_ACADEMY_ENABLED"] == "false"
assert api_environment["CORTEX_SYNC_ACADEMY_READINESS_MAX_AGE_MS"] == "900000"
assert api_environment["CORTEX_SYNC_ZELADORIA_ENABLED"] == "false"
assert "CORTEX_SYNC_ENABLED" not in api_environment
assert api_environment["CORTEX_CORS_ALLOWED_ORIGINS"] == "https://cortex.localhost:18443"
assert api_environment["CORTEX_AUTH_WEBAUTHN_ALLOWED_ORIGINS"] == "https://cortex.localhost:18443"
assert api_environment["CORTEX_AUTH_DEV_ADMIN_ENABLED"] == "false"
assert api_environment["CORTEX_AUTH_PROVISIONING_ENABLED"] == "false"

api_secrets = services["cortex-api"].get("secrets", [])
assert any(
    secret.get("source") == "cortex_academy_password"
    and secret.get("target") == "cortex_academy_password"
    for secret in api_secrets
), "Academy password must be mounted at the explicit file-backed path"

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
