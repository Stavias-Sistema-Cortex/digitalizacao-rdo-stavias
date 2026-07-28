#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
postgres_init="$repo_root/deploy/production/postgres-init.sh"
contract_dir="$(mktemp -d "${TMPDIR:-/tmp}/cortex-role-reconciliation.XXXXXX")"
container_name="cortex-role-reconciliation-$$"

cleanup() {
  docker rm -f "$container_name" >/dev/null 2>&1 || true
  find "$contract_dir" -type f -delete 2>/dev/null || true
  rmdir "$contract_dir" 2>/dev/null || true
}
trap cleanup EXIT

printf '%s' 'contract-admin' > "$contract_dir/admin"
printf '%s' 'contract-migrator' > "$contract_dir/migrator"
printf '%s' 'contract-runtime' > "$contract_dir/runtime"
chmod 600 "$contract_dir/admin" "$contract_dir/migrator" "$contract_dir/runtime"

docker run --detach --rm \
  --name "$container_name" \
  --env POSTGRES_DB=cortex_contract \
  --env POSTGRES_USER=cortex_admin \
  --env POSTGRES_PASSWORD_FILE=/run/secrets/postgres_admin_password \
  --env CORTEX_POSTGRES_MIGRATOR_USER=cortex_migrator \
  --env CORTEX_POSTGRES_RUNTIME_USER=cortex_runtime \
  --mount "type=bind,src=$contract_dir/admin,dst=/run/secrets/postgres_admin_password,readonly" \
  --mount "type=bind,src=$contract_dir/migrator,dst=/run/secrets/postgres_migrator_password,readonly" \
  --mount "type=bind,src=$contract_dir/runtime,dst=/run/secrets/postgres_runtime_password,readonly" \
  --mount "type=bind,src=$postgres_init,dst=/docker-entrypoint-initdb.d/10-cortex-roles.sh,readonly" \
  postgres:18-alpine >/dev/null

for _ in $(seq 1 60); do
  role_count="$(
    docker exec "$container_name" psql \
      --username=cortex_admin \
      --dbname=cortex_contract \
      --tuples-only \
      --no-align \
      --command="
        SELECT COUNT(*)
        FROM pg_roles
        WHERE rolname IN ('cortex_migrator', 'cortex_runtime');
      " 2>/dev/null || true
  )"
  if [[ "$role_count" == "2" ]]; then
    break
  fi
  sleep 1
done

if [[ "${role_count:-}" != "2" ]]; then
  echo "PostgreSQL role initialization did not complete." >&2
  exit 1
fi

docker exec "$container_name" psql \
  --username=cortex_admin \
  --dbname=cortex_contract \
  --set=ON_ERROR_STOP=1 \
  --command='
    CREATE ROLE unexpected_parent NOLOGIN;
    CREATE ROLE unexpected_member NOLOGIN;
    GRANT unexpected_parent TO cortex_migrator, cortex_runtime;
    GRANT cortex_migrator, cortex_runtime TO unexpected_member;
  ' >/dev/null

membership_count="$(
  docker exec "$container_name" psql \
    --username=cortex_admin \
    --dbname=cortex_contract \
    --tuples-only \
    --no-align \
    --command="
      SELECT COUNT(*)
      FROM pg_auth_members membership
      JOIN pg_roles granted_role ON granted_role.oid = membership.roleid
      JOIN pg_roles member_role ON member_role.oid = membership.member
      WHERE granted_role.rolname IN ('cortex_migrator', 'cortex_runtime')
         OR member_role.rolname IN ('cortex_migrator', 'cortex_runtime');
    "
)"
[[ "$membership_count" == "4" ]]

docker exec "$container_name" \
  /docker-entrypoint-initdb.d/10-cortex-roles.sh >/dev/null

membership_count="$(
  docker exec "$container_name" psql \
    --username=cortex_admin \
    --dbname=cortex_contract \
    --tuples-only \
    --no-align \
    --command="
      SELECT COUNT(*)
      FROM pg_auth_members membership
      JOIN pg_roles granted_role ON granted_role.oid = membership.roleid
      JOIN pg_roles member_role ON member_role.oid = membership.member
      WHERE granted_role.rolname IN ('cortex_migrator', 'cortex_runtime')
         OR member_role.rolname IN ('cortex_migrator', 'cortex_runtime');
    "
)"
if [[ "$membership_count" != "0" ]]; then
  echo "unexpected PostgreSQL role memberships survived reconciliation" >&2
  exit 1
fi

privileged_role_count="$(
  docker exec "$container_name" psql \
    --username=cortex_admin \
    --dbname=cortex_contract \
    --tuples-only \
    --no-align \
    --command="
      SELECT COUNT(*)
      FROM pg_roles
      WHERE rolname IN ('cortex_migrator', 'cortex_runtime')
        AND (
          rolsuper OR rolcreatedb OR rolcreaterole OR rolinherit
          OR rolreplication OR rolbypassrls
        );
    "
)"
if [[ "$privileged_role_count" != "0" ]]; then
  echo "a reconciled PostgreSQL role retained privileged attributes" >&2
  exit 1
fi

if docker exec \
  --env CORTEX_POSTGRES_MIGRATOR_USER=cortex_collision \
  --env CORTEX_POSTGRES_RUNTIME_USER=cortex_collision \
  "$container_name" \
  /docker-entrypoint-initdb.d/10-cortex-roles.sh >/dev/null 2>&1; then
  echo "PostgreSQL role reconciliation accepted colliding role names" >&2
  exit 1
fi

echo "PostgreSQL 18 role reconciliation contract passed."
