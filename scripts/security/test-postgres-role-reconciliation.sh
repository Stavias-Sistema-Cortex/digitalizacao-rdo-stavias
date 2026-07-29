#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
postgres_init="$repo_root/deploy/production/postgres-init.sh"
postgres_entrypoint="$repo_root/deploy/production/postgres-entrypoint.sh"
contract_dir="$(mktemp -d "${TMPDIR:-/tmp}/cortex-role-reconciliation.XXXXXX")"
container_name="cortex-role-reconciliation-$$"

cleanup() {
  docker rm -f -v "$container_name" >/dev/null 2>&1 || true
  find "$contract_dir" -type f -delete 2>/dev/null || true
  rmdir "$contract_dir" 2>/dev/null || true
}
trap cleanup EXIT

file_mode() {
  local path="$1"
  local mode

  if mode="$(stat -f '%Lp' "$path" 2>/dev/null)"; then
    printf '%s' "$mode"
    return
  fi
  stat -c '%a' "$path"
}

emit_sanitized_container_logs() {
  docker logs "$container_name" 2>&1 |
    sed \
      -e 's/contract-admin/[REDACTED]/g' \
      -e 's/contract-migrator/[REDACTED]/g' \
      -e 's/contract-runtime/[REDACTED]/g' |
    tail -n 80 >&2 || true
}

printf '%s' 'contract-admin' > "$contract_dir/admin"
printf '%s' 'contract-migrator' > "$contract_dir/migrator"
printf '%s' 'contract-runtime' > "$contract_dir/runtime"
chmod 700 "$contract_dir"
chmod 600 "$contract_dir/admin" "$contract_dir/migrator" "$contract_dir/runtime"

for secret_file in admin migrator runtime; do
  if [[ "$(file_mode "$contract_dir/$secret_file")" != "600" ]]; then
    echo "PostgreSQL contract source secrets must remain mode 0600." >&2
    exit 1
  fi
done

docker run --detach \
  --name "$container_name" \
  --entrypoint=/usr/local/bin/cortex-postgres-entrypoint.sh \
  --env POSTGRES_DB=cortex_contract \
  --env POSTGRES_USER=cortex_admin \
  --env POSTGRES_PASSWORD_FILE=/run/secrets/postgres_admin_password \
  --env CORTEX_POSTGRES_MIGRATOR_USER=cortex_migrator \
  --env CORTEX_POSTGRES_RUNTIME_USER=cortex_runtime \
  --mount "type=bind,src=$contract_dir/admin,dst=/run/secrets/postgres_admin_password,readonly" \
  --mount "type=bind,src=$contract_dir/migrator,dst=/run/secrets/postgres_migrator_password,readonly" \
  --mount "type=bind,src=$contract_dir/runtime,dst=/run/secrets/postgres_runtime_password,readonly" \
  --mount "type=bind,src=$postgres_init,dst=/docker-entrypoint-initdb.d/10-cortex-roles.sh,readonly" \
  --mount "type=bind,src=$postgres_entrypoint,dst=/usr/local/bin/cortex-postgres-entrypoint.sh,readonly" \
  --tmpfs /run/postgresql:rw,noexec,nosuid,nodev,size=16m,mode=0775 \
  postgres:18-alpine \
  postgres >/dev/null

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
  emit_sanitized_container_logs
  exit 1
fi

postgres_uid="$(docker exec "$container_name" id -u postgres)"
postgres_gid="$(docker exec "$container_name" id -g postgres)"
for secret_file in admin migrator runtime; do
  staged_metadata="$(
    docker exec "$container_name" \
      stat -c '%a:%u:%g' "/run/postgresql/cortex-secrets/$secret_file"
  )"
  if [[ "$staged_metadata" != "600:$postgres_uid:$postgres_gid" ]]; then
    echo "A staged PostgreSQL secret is not postgres-owned mode 0600." >&2
    exit 1
  fi
done

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
