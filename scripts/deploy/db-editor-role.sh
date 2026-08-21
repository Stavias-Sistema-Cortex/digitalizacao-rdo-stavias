#!/usr/bin/env bash
set -euo pipefail
umask 077

# Read-write database access for an authorized human operator.
#
# The viewing role reconciled by db-view-bridge.sh is SELECT-only, which is
# the right default for inspection. This script provisions the narrower case
# of an operator who must also correct data by hand:
#
#   create-role  Reconcile a DML role (SELECT/INSERT/UPDATE/DELETE) that owns
#                nothing and cannot change the schema.
#   revoke       Take the login away without dropping the role or its grants.
#
# The role deliberately stops at data. Schema changes stay with Flyway and
# the migrator role, so an out-of-band ALTER can never diverge from the
# migration history. Manual writes still bypass the application's own
# validation, event trail and row versioning: see
# docs/operations/visualizar-tabelas-producao.md before handing it over.

usage() {
  echo "Usage: db-editor-role.sh <create-role|revoke>" >&2
  exit 1
}

fail() {
  echo "$1" >&2
  exit 1
}

require_line() {
  local name="$1"
  local value="${!name:-}"
  if [[ -z "$value" || "$value" == *$'\r'* || "$value" == *$'\n'* ]]; then
    fail "$name must be one non-empty line."
  fi
}

file_mode() {
  local path="$1"
  local mode
  if mode="$(stat -c '%a' "$path" 2>/dev/null)" \
    && [[ "$mode" =~ ^[0-7]{3,4}$ ]]; then
    printf '%s\n' "$mode"
    return
  fi
  if mode="$(stat -f '%Lp' "$path" 2>/dev/null)" \
    && [[ "$mode" =~ ^[0-7]{3,4}$ ]]; then
    printf '%s\n' "$mode"
    return
  fi
  return 1
}

(($# == 1)) || usage
subcommand="$1"
case "$subcommand" in
  create-role | revoke) ;;
  *) usage ;;
esac

require_line CORTEX_DOCKER_BIN
[[ -f "$CORTEX_DOCKER_BIN" && -x "$CORTEX_DOCKER_BIN" && ! -L "$CORTEX_DOCKER_BIN" ]] \
  || fail "CORTEX_DOCKER_BIN must be an executable regular file."
docker_bin="$CORTEX_DOCKER_BIN"

project="${CORTEX_COMPOSE_PROJECT_NAME:-cortex-production}"
[[ "$project" =~ ^[a-z0-9][a-z0-9_-]*$ ]] \
  || fail "CORTEX_COMPOSE_PROJECT_NAME must be a lowercase Compose project name."

database_name="${CORTEX_POSTGRES_DB:-StaviasCortex}"
admin_user="${CORTEX_POSTGRES_ADMIN_USER:-cortex_admin}"
migrator_user="${CORTEX_POSTGRES_MIGRATOR_USER:-cortex_migrator}"
runtime_user="${CORTEX_POSTGRES_USER:-cortex_runtime}"
view_role="${CORTEX_DB_VIEW_ROLE:-cortex_readonly}"
editor_role="${CORTEX_DB_EDITOR_ROLE:-cortex_editor}"
statement_timeout="${CORTEX_DB_EDITOR_STATEMENT_TIMEOUT:-60s}"
# The runbook makes review-then-COMMIT mandatory, so the idle ceiling has to
# leave room for a human to read the result of an open transaction.
idle_timeout="${CORTEX_DB_EDITOR_IDLE_TIMEOUT:-15min}"
connection_limit="${CORTEX_DB_EDITOR_CONNECTION_LIMIT:-4}"

postgres_service="cortex-postgres"

resolve_postgres_container() {
  local resolved
  resolved="$("$docker_bin" ps \
    --filter "label=com.docker.compose.project=$project" \
    --filter "label=com.docker.compose.service=$postgres_service" \
    --filter status=running \
    --format '{{.ID}}')" \
    || fail "'docker ps' failed; is the Docker daemon reachable?"
  [[ -n "$resolved" && "$resolved" != *$'\n'* ]] \
    || fail "Exactly one running $postgres_service container is required."
  printf '%s\n' "$resolved"
}

validate_role_names() {
  [[ "$editor_role" =~ ^[a-z_][a-z0-9_]*$ ]] \
    || fail "PostgreSQL role names must be lowercase SQL identifiers."
  for role_name in "$migrator_user" "$runtime_user" "$view_role"; do
    [[ "$role_name" =~ ^[a-z_][a-z0-9_]*$ ]] \
      || fail "PostgreSQL role names must be lowercase SQL identifiers."
  done
  # The editing role is provisioned for a person; reusing a runtime role
  # would reset the password the stack itself authenticates with.
  for reserved in "$admin_user" "$migrator_user" "$runtime_user" "$view_role"; do
    [[ "$editor_role" != "$reserved" ]] \
      || fail "The editing role must not reuse an administrative, runtime or viewing role."
  done
}

create_role() {
  validate_role_names
  [[ "$statement_timeout" =~ ^[0-9]+(ms|s|min|h)$ ]] \
    || fail "CORTEX_DB_EDITOR_STATEMENT_TIMEOUT must be a duration such as 60s."
  [[ "$idle_timeout" =~ ^[0-9]+(ms|s|min|h)$ ]] \
    || fail "CORTEX_DB_EDITOR_IDLE_TIMEOUT must be a duration such as 5min."
  [[ "$connection_limit" =~ ^[0-9]+$ ]] && ((connection_limit >= 1)) \
    || fail "CORTEX_DB_EDITOR_CONNECTION_LIMIT must be a positive integer."

  local password_file="${CORTEX_DB_EDITOR_ROLE_PASSWORD_FILE:-}"
  [[ -n "$password_file" ]] \
    || fail "CORTEX_DB_EDITOR_ROLE_PASSWORD_FILE must point to the editing role password file."
  [[ -f "$password_file" && -r "$password_file" && ! -L "$password_file" ]] \
    || fail "The editing role password file must be a readable regular file."
  local password_mode
  password_mode="$(file_mode "$password_file")" \
    || fail "The editing role password file permissions could not be read safely."
  [[ "$password_mode" =~ ^[0-7]{3,4}$ ]] && (((8#$password_mode & 077) == 0)) \
    || fail "The editing role password file must be owner-only."
  local password
  password="$(<"$password_file")"
  if [[ -z "$password" || "$password" == *$'\r'* || "$password" == *$'\n'* ]]; then
    fail "The editing role password file must contain exactly one non-empty line."
  fi

  local postgres_container
  postgres_container="$(resolve_postgres_container)"

  # The password travels only through the exec environment; it never
  # appears in command arguments on the host or inside the container.
  CORTEX_DB_EDITOR_ROLE_PASSWORD="$password" "$docker_bin" exec -i \
    -e CORTEX_DB_EDITOR_ROLE_PASSWORD \
    "$postgres_container" \
    psql -X \
    --set=ON_ERROR_STOP=1 \
    --username "$admin_user" \
    --dbname "$database_name" \
    --set=editor_role="$editor_role" \
    --set=migrator_user="$migrator_user" \
    --set=database_name="$database_name" \
    --set=statement_timeout="$statement_timeout" \
    --set=idle_timeout="$idle_timeout" \
    --set=connection_limit="$connection_limit" \
    <<'SQL'
\getenv editor_role_password CORTEX_DB_EDITOR_ROLE_PASSWORD
\if :{?editor_role_password}
\else
SELECT missing_editor_role_password_environment_variable;
\endif

-- The reconcile below resets the password and attributes of whatever role
-- it is pointed at, so refuse any pre-existing role that carries privileges
-- or memberships: an editing role owns nothing and inherits nothing.
SELECT 'SELECT refusing_to_reconcile_a_privileged_existing_role'
FROM pg_roles
WHERE rolname = :'editor_role'
  AND (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls)
\gexec

SELECT 'SELECT refusing_to_reconcile_a_role_with_memberships'
FROM pg_auth_members membership
JOIN pg_roles member_role ON member_role.oid = membership.member
WHERE member_role.rolname = :'editor_role'
\gexec

SELECT 'SELECT refusing_to_reconcile_a_role_that_owns_objects'
FROM pg_class owned
JOIN pg_roles owner_role ON owner_role.oid = owned.relowner
WHERE owner_role.rolname = :'editor_role'
\gexec

SELECT format(
  'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS',
  :'editor_role',
  :'editor_role_password'
)
WHERE NOT EXISTS (
  SELECT 1 FROM pg_roles WHERE rolname = :'editor_role'
)
\gexec

SELECT format(
  'ALTER ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS',
  :'editor_role',
  :'editor_role_password'
)
\gexec

SELECT format('ALTER ROLE %I CONNECTION LIMIT %s', :'editor_role', :'connection_limit')
\gexec

SELECT format('ALTER ROLE %I SET statement_timeout = %L', :'editor_role', :'statement_timeout')
\gexec

SELECT format('ALTER ROLE %I SET idle_in_transaction_session_timeout = %L', :'editor_role', :'idle_timeout')
\gexec

GRANT CONNECT ON DATABASE :"database_name" TO :"editor_role";
GRANT USAGE ON SCHEMA public TO :"editor_role";
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO :"editor_role";
-- USAGE alone already covers nextval(); withholding UPDATE keeps setval()
-- out of reach, so a hand edit cannot rewind a sequence and collide with
-- every identifier the application allocates next.
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO :"editor_role";

ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_user" IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"editor_role";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_user" IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO :"editor_role";

-- The blanket grant above reaches every table in the schema, including the
-- two that govern deployment integrity: Flyway's migration history and the
-- release marker the runtime checks at boot. Data correction must never be
-- able to rewrite the record of which schema and which release are live, so
-- both are put back to read-only for this role.
SELECT format(
  'REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON TABLE %I.%I FROM %I',
  schemaname, tablename, :'editor_role'
)
FROM pg_tables
WHERE schemaname = 'public'
  AND tablename IN ('flyway_schema_history', 'cortex_release_marker')
\gexec

-- Data only: creating objects in the schema stays with the migrator, so a
-- hand-made table can never drift from the Flyway history.
REVOKE CREATE ON SCHEMA public FROM :"editor_role";
REVOKE ALL ON SCHEMA public FROM :"editor_role";
GRANT USAGE ON SCHEMA public TO :"editor_role";
SQL

  echo "The editing role '$editor_role' is reconciled on '$database_name'."
  echo "It can read and change rows; it cannot change the schema or own objects."
}

revoke_role() {
  validate_role_names
  local postgres_container
  postgres_container="$(resolve_postgres_container)"

  "$docker_bin" exec -i \
    "$postgres_container" \
    psql -X \
    --set=ON_ERROR_STOP=1 \
    --username "$admin_user" \
    --dbname "$database_name" \
    --set=editor_role="$editor_role" \
    --set=database_name="$database_name" \
    <<'SQL'
SELECT 'SELECT the_editing_role_does_not_exist'
WHERE NOT EXISTS (
  SELECT 1 FROM pg_roles WHERE rolname = :'editor_role'
)
\gexec

-- NOLOGIN plus CONNECT revoked closes existing and future sessions without
-- dropping the role, so the same name can be restored by create-role.
SELECT format('ALTER ROLE %I NOLOGIN', :'editor_role')
\gexec

REVOKE CONNECT ON DATABASE :"database_name" FROM :"editor_role";

SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE usename = :'editor_role'
  AND pid <> pg_backend_pid();
SQL

  echo "The editing role '$editor_role' can no longer log in."
  echo "Restore it by running create-role with a fresh password file."
}

case "$subcommand" in
  create-role) create_role ;;
  revoke) revoke_role ;;
esac
