#!/usr/bin/env bash
set -euo pipefail
umask 077

# Read-only database viewing access for the local production stack.
#
# The canonical PostgreSQL container lives on the internal-only Compose
# network and never publishes a port. This script provides the two
# administrative pieces that make safe graphical inspection possible:
#
#   create-role  Reconcile a SELECT-only PostgreSQL role for human viewers.
#   start        Run a loopback-only socat bridge so SSH tunnels can reach
#                PostgreSQL through 127.0.0.1 on the server.
#   status       Report the bridge container state and probe connectivity.
#   stop         Remove the bridge container.
#
# The bridge binds exclusively to 127.0.0.1 and is never restarted
# automatically after a reboot; viewing access is an operator decision
# every time. See docs/operations/visualizar-tabelas-producao.md.

usage() {
  echo "Usage: db-view-bridge.sh <create-role|start|status|stop>" >&2
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
  create-role | start | status | stop) ;;
  *) usage ;;
esac

require_line CORTEX_DOCKER_BIN
[[ -f "$CORTEX_DOCKER_BIN" && -x "$CORTEX_DOCKER_BIN" && ! -L "$CORTEX_DOCKER_BIN" ]] \
  || fail "CORTEX_DOCKER_BIN must be an executable regular file."
docker_bin="$CORTEX_DOCKER_BIN"

project="${CORTEX_COMPOSE_PROJECT_NAME:-cortex-production}"
[[ "$project" =~ ^[a-z0-9][a-z0-9_-]*$ ]] \
  || fail "CORTEX_COMPOSE_PROJECT_NAME must be a lowercase Compose project name."

bridge_container="${CORTEX_DB_VIEW_CONTAINER_NAME:-cortex-db-view}"
[[ "$bridge_container" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]*$ ]] \
  || fail "CORTEX_DB_VIEW_CONTAINER_NAME must be a valid container name."

bridge_port="${CORTEX_DB_VIEW_PORT:-15432}"
[[ "$bridge_port" =~ ^[1-9][0-9]{3,4}$ ]] && ((bridge_port >= 1024 && bridge_port <= 65535)) \
  || fail "CORTEX_DB_VIEW_PORT must be an unprivileged TCP port (1024-65535)."

socat_image="${CORTEX_DB_VIEW_SOCAT_IMAGE:-alpine/socat:1.8.1.3}"
[[ "$socat_image" =~ ^[a-z0-9][a-z0-9._/-]*:[A-Za-z0-9._-]+(@sha256:[a-f0-9]{64})?$ ]] \
  || fail "CORTEX_DB_VIEW_SOCAT_IMAGE must be an explicitly tagged image reference."

database_name="${CORTEX_POSTGRES_DB:-StaviasCortex}"
admin_user="${CORTEX_POSTGRES_ADMIN_USER:-cortex_admin}"
migrator_user="${CORTEX_POSTGRES_MIGRATOR_USER:-cortex_migrator}"
runtime_user="${CORTEX_POSTGRES_USER:-cortex_runtime}"
view_role="${CORTEX_DB_VIEW_ROLE:-cortex_readonly}"
statement_timeout="${CORTEX_DB_VIEW_STATEMENT_TIMEOUT:-30s}"
connection_limit="${CORTEX_DB_VIEW_CONNECTION_LIMIT:-10}"

network_name="${project}_cortex_private"
view_network="${project}_db_view_edge"
postgres_service="cortex-postgres"

resolve_postgres_container() {
  local resolved
  # The explicit guard reports a broken Docker CLI as itself: errexit does
  # not reach inside a command substitution, so a failed ps would otherwise
  # be reported as a missing container.
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

bridge_exists() {
  "$docker_bin" container inspect "$bridge_container" >/dev/null 2>&1
}

bridge_state() {
  "$docker_bin" container inspect "$bridge_container" \
    --format '{{.State.Status}}'
}

remove_bridge_artifacts() {
  "$docker_bin" rm -f "$bridge_container" >/dev/null 2>&1 || true
  "$docker_bin" network rm "$view_network" >/dev/null 2>&1 || true
}

probe_bridge() {
  "$docker_bin" exec "$bridge_container" \
    socat -u OPEN:/dev/null "TCP:$postgres_service:5432,connect-timeout=5" \
    >/dev/null 2>&1
}

create_role() {
  for role_name in "$view_role" "$migrator_user" "$runtime_user"; do
    [[ "$role_name" =~ ^[a-z_][a-z0-9_]*$ ]] \
      || fail "PostgreSQL role names must be lowercase SQL identifiers."
  done
  for reserved in "$admin_user" "$migrator_user" "$runtime_user"; do
    [[ "$view_role" != "$reserved" ]] \
      || fail "The viewing role must not reuse an administrative runtime role."
  done
  [[ "$statement_timeout" =~ ^[0-9]+(ms|s|min|h)$ ]] \
    || fail "CORTEX_DB_VIEW_STATEMENT_TIMEOUT must be a duration such as 30s."
  [[ "$connection_limit" =~ ^[0-9]+$ ]] && ((connection_limit >= 1)) \
    || fail "CORTEX_DB_VIEW_CONNECTION_LIMIT must be a positive integer."

  local password_file="${CORTEX_DB_VIEW_ROLE_PASSWORD_FILE:-}"
  [[ -n "$password_file" ]] \
    || fail "CORTEX_DB_VIEW_ROLE_PASSWORD_FILE must point to the viewing role password file."
  [[ -f "$password_file" && -r "$password_file" && ! -L "$password_file" ]] \
    || fail "The viewing role password file must be a readable regular file."
  local password_mode
  password_mode="$(file_mode "$password_file")" \
    || fail "The viewing role password file permissions could not be read safely."
  [[ "$password_mode" =~ ^[0-7]{3,4}$ ]] && (((8#$password_mode & 077) == 0)) \
    || fail "The viewing role password file must be owner-only."
  local password
  password="$(<"$password_file")"
  if [[ -z "$password" || "$password" == *$'\r'* || "$password" == *$'\n'* ]]; then
    fail "The viewing role password file must contain exactly one non-empty line."
  fi

  local postgres_container
  postgres_container="$(resolve_postgres_container)"

  # The password travels only through the exec environment; it never
  # appears in command arguments on the host or inside the container.
  CORTEX_DB_VIEW_ROLE_PASSWORD="$password" "$docker_bin" exec -i \
    -e CORTEX_DB_VIEW_ROLE_PASSWORD \
    "$postgres_container" \
    psql -X \
    --set=ON_ERROR_STOP=1 \
    --username "$admin_user" \
    --dbname "$database_name" \
    --set=view_role="$view_role" \
    --set=migrator_user="$migrator_user" \
    --set=database_name="$database_name" \
    --set=statement_timeout="$statement_timeout" \
    --set=connection_limit="$connection_limit" \
    <<'SQL'
\getenv view_role_password CORTEX_DB_VIEW_ROLE_PASSWORD
\if :{?view_role_password}
\else
SELECT missing_view_role_password_environment_variable;
\endif

-- The reconcile below resets the password and attributes of whatever role
-- it is pointed at, so refuse any pre-existing role that carries privileges
-- or memberships: a viewing role owns nothing and inherits nothing.
SELECT 'SELECT refusing_to_reconcile_a_privileged_existing_role'
FROM pg_roles
WHERE rolname = :'view_role'
  AND (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls)
\gexec

SELECT 'SELECT refusing_to_reconcile_a_role_with_memberships'
FROM pg_auth_members membership
JOIN pg_roles member_role ON member_role.oid = membership.member
WHERE member_role.rolname = :'view_role'
\gexec

SELECT format(
  'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS',
  :'view_role',
  :'view_role_password'
)
WHERE NOT EXISTS (
  SELECT 1 FROM pg_roles WHERE rolname = :'view_role'
)
\gexec

SELECT format(
  'ALTER ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS',
  :'view_role',
  :'view_role_password'
)
\gexec

SELECT format('ALTER ROLE %I CONNECTION LIMIT %s', :'view_role', :'connection_limit')
\gexec

SELECT format('ALTER ROLE %I SET default_transaction_read_only = on', :'view_role')
\gexec

SELECT format('ALTER ROLE %I SET statement_timeout = %L', :'view_role', :'statement_timeout')
\gexec

SELECT format('ALTER ROLE %I SET idle_in_transaction_session_timeout = %L', :'view_role', '5min')
\gexec

GRANT CONNECT ON DATABASE :"database_name" TO :"view_role";
GRANT USAGE ON SCHEMA public TO :"view_role";
GRANT SELECT ON ALL TABLES IN SCHEMA public TO :"view_role";

ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_user" IN SCHEMA public
  GRANT SELECT ON TABLES TO :"view_role";
SQL

  echo "The read-only viewing role '$view_role' is reconciled on '$database_name'."
}

start_bridge() {
  # Docker's own stderr is left visible here: an unreachable daemon must
  # not be reported as a missing Compose network.
  "$docker_bin" network inspect "$network_name" --format '{{.Name}}' >/dev/null \
    || fail "The Compose network '$network_name' does not exist; is the stack up?"
  resolve_postgres_container >/dev/null
  if bridge_exists; then
    local existing_state
    existing_state="$(bridge_state)" \
      || fail "The state of '$bridge_container' could not be read."
    [[ "$existing_state" != "running" ]] \
      || fail "The bridge container '$bridge_container' is already running; run stop first."
    # A stopped bridge is what a host reboot leaves behind, so clear it
    # here instead of making the operator run stop first.
    "$docker_bin" rm -f "$bridge_container" >/dev/null \
      || fail "The stopped bridge container '$bridge_container' could not be removed."
  fi

  # The listener accepts any source inside its own network, so the bridge
  # gets a dedicated one instead of the shared default bridge, where every
  # unrelated container would otherwise be able to reach PostgreSQL. It
  # then joins the internal Compose network, publishes nothing outside
  # 127.0.0.1, and never restarts on its own after a reboot.
  "$docker_bin" network inspect "$view_network" >/dev/null 2>&1 \
    || "$docker_bin" network create "$view_network" >/dev/null \
    || fail "The bridge network '$view_network' could not be created."

  trap remove_bridge_artifacts EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM

  "$docker_bin" run --detach \
    --name "$bridge_container" \
    --network "$view_network" \
    --restart no \
    --read-only \
    --cap-drop ALL \
    --security-opt no-new-privileges:true \
    --publish "127.0.0.1:${bridge_port}:5432" \
    "$socat_image" \
    "tcp-listen:5432,fork,reuseaddr" \
    "tcp-connect:${postgres_service}:5432" >/dev/null \
    || fail "The bridge container could not start and was removed."

  "$docker_bin" network connect "$network_name" "$bridge_container" \
    || fail "The bridge could not join '$network_name' and was removed."

  probe_bridge \
    || fail "The bridge could not reach ${postgres_service}:5432 and was removed."

  trap - EXIT INT TERM

  echo "The viewing bridge is listening on 127.0.0.1:${bridge_port} (server loopback only)."
  echo "Tunnel from a workstation with: ssh -N -L ${bridge_port}:127.0.0.1:${bridge_port} <user>@<server>"
  echo "Remove it with: db-view-bridge.sh stop"
}

status_bridge() {
  if ! bridge_exists; then
    echo "The viewing bridge '$bridge_container' is not present."
    return 1
  fi
  local state published_port
  state="$(bridge_state)" \
    || fail "The state of '$bridge_container' could not be read."
  # The published port is read back from the container: the environment of
  # this shell may not be the one that started the bridge.
  published_port="$("$docker_bin" container inspect "$bridge_container" \
    --format '{{ with index .HostConfig.PortBindings "5432/tcp" }}{{ (index . 0).HostPort }}{{ end }}')" \
    || published_port=""
  echo "Bridge container: $bridge_container ($state), loopback port ${published_port:-unknown}."
  if [[ "$state" != "running" ]]; then
    echo "The bridge exists but is not running; run stop and then start again."
    return 1
  fi
  if probe_bridge; then
    echo "PostgreSQL is reachable through the bridge."
    return 0
  fi
  echo "The bridge is running but cannot reach ${postgres_service}:5432."
  return 1
}

stop_bridge() {
  if ! bridge_exists; then
    echo "The viewing bridge '$bridge_container' is already absent."
    "$docker_bin" network rm "$view_network" >/dev/null 2>&1 || true
    return 0
  fi
  "$docker_bin" rm -f "$bridge_container" >/dev/null
  "$docker_bin" network rm "$view_network" >/dev/null 2>&1 || true
  echo "The viewing bridge '$bridge_container' was removed."
}

case "$subcommand" in
  create-role) create_role ;;
  start) start_bridge ;;
  status) status_bridge ;;
  stop) stop_bridge ;;
esac
