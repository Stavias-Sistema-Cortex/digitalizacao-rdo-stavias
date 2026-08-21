#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
bridge_script="$repo_root/scripts/deploy/db-view-bridge.sh"
fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/cortex-db-view-contract.XXXXXX")"
trap 'rm -rf "$fixture_root"' EXIT

fail() {
  echo "$1" >&2
  exit 1
}

prepare_case() {
  local name="$1"
  case_root="$fixture_root/$name"
  mkdir -p "$case_root/bin"
  chmod 700 "$case_root"

  export CORTEX_TEST_DOCKER_LOG="$case_root/docker.log"
  export CORTEX_TEST_PSQL_SQL="$case_root/psql-input.sql"
  export CORTEX_TEST_PSQL_ENV="$case_root/psql-env.txt"
  export CORTEX_TEST_MARKERS="$case_root/markers"
  mkdir -p "$CORTEX_TEST_MARKERS"
  : > "$CORTEX_TEST_DOCKER_LOG"

  password_file="$case_root/view-role-password"
  printf 'senha-de-teste-nao-real\n' > "$password_file"
  chmod 600 "$password_file"

  cat > "$case_root/bin/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$CORTEX_TEST_DOCKER_LOG"
all="$*"
if [[ "$all" == "container inspect"*"PortBindings"* ]]; then
  if [[ ! -f "$CORTEX_TEST_MARKERS/bridge-exists" ]]; then exit 1; fi
  printf '%s\n' "${CORTEX_TEST_PUBLISHED_PORT:-15432}"
elif [[ "$all" == "container inspect"*"--format {{.State.Status}}" ]]; then
  if [[ ! -f "$CORTEX_TEST_MARKERS/bridge-exists" ]]; then exit 1; fi
  printf '%s\n' "${CORTEX_TEST_BRIDGE_STATE:-running}"
elif [[ "$all" == "container inspect"* ]]; then
  [[ -f "$CORTEX_TEST_MARKERS/bridge-exists" ]] || exit 1
elif [[ "$all" == "ps --filter label=com.docker.compose.project="*"label=com.docker.compose.service=cortex-postgres"* ]]; then
  [[ -f "$CORTEX_TEST_MARKERS/no-postgres" ]] || printf 'postgres-container\n'
elif [[ "$all" == "network inspect"*db_view_edge* ]]; then
  if [[ ! -f "$CORTEX_TEST_MARKERS/view-network-exists" ]]; then exit 1; fi
  printf 'cortex-production_db_view_edge\n'
elif [[ "$all" == "network inspect"* ]]; then
  if [[ -f "$CORTEX_TEST_MARKERS/no-network" ]]; then exit 1; fi
  printf 'cortex-production_cortex_private\n'
elif [[ "$all" == "network create"* ]]; then
  if [[ -f "$CORTEX_TEST_MARKERS/network-create-fails" ]]; then exit 1; fi
  printf 'cortex-production_db_view_edge\n'
elif [[ "$all" == "network rm"* ]]; then
  exit 0
elif [[ "$all" == "run --detach"* ]]; then
  if [[ -f "$CORTEX_TEST_MARKERS/run-fails" ]]; then exit 1; fi
  printf 'bridge-container\n'
elif [[ "$all" == "network connect"* ]]; then
  if [[ -f "$CORTEX_TEST_MARKERS/connect-fails" ]]; then exit 1; fi
  exit 0
elif [[ "$all" == "exec"*"psql"* ]]; then
  printf '%s\n' "${CORTEX_DB_VIEW_ROLE_PASSWORD:-<unset>}" > "$CORTEX_TEST_PSQL_ENV"
  cat > "$CORTEX_TEST_PSQL_SQL"
elif [[ "$all" == "exec"*"socat"* ]]; then
  if [[ -f "$CORTEX_TEST_MARKERS/probe-fails" ]]; then exit 1; fi
  exit 0
elif [[ "$all" == "rm -f"* ]]; then
  exit 0
else
  echo "unexpected docker invocation: $all" >&2
  exit 64
fi
SH
  chmod 700 "$case_root/bin/docker"

  base_env=(
    env -i
    PATH="/usr/bin:/bin"
    HOME="$case_root"
    CORTEX_TEST_DOCKER_LOG="$CORTEX_TEST_DOCKER_LOG"
    CORTEX_TEST_PSQL_SQL="$CORTEX_TEST_PSQL_SQL"
    CORTEX_TEST_PSQL_ENV="$CORTEX_TEST_PSQL_ENV"
    CORTEX_TEST_MARKERS="$CORTEX_TEST_MARKERS"
    CORTEX_TEST_BRIDGE_STATE="${CORTEX_TEST_BRIDGE_STATE:-running}"
    CORTEX_TEST_PUBLISHED_PORT="${CORTEX_TEST_PUBLISHED_PORT:-15432}"
    CORTEX_DOCKER_BIN="$case_root/bin/docker"
  )
}

run_bridge() {
  "${base_env[@]}" "$@" bash "$bridge_script" "$subcommand_under_test" \
    > "$case_root/stdout.txt" 2> "$case_root/stderr.txt"
}

expect_failure() {
  local message="$1"
  shift
  if run_bridge "$@"; then
    fail "$case_root expected a refusal: $message"
  fi
  grep -q "$message" "$case_root/stderr.txt" \
    || fail "$case_root did not explain: $message"
}

# --- The subcommand is mandatory and validated. -----------------------------
prepare_case usage
subcommand_under_test="destroy-everything"
expect_failure "Usage: db-view-bridge.sh"

# --- The Docker binary contract is enforced. --------------------------------
prepare_case missing-docker
subcommand_under_test="status"
if env -i PATH="/usr/bin:/bin" HOME="$case_root" bash "$bridge_script" status \
  > /dev/null 2> "$case_root/stderr.txt"; then
  fail "status must refuse to run without CORTEX_DOCKER_BIN"
fi
grep -q "CORTEX_DOCKER_BIN must be one non-empty line." "$case_root/stderr.txt" \
  || fail "the missing Docker binary refusal is not explained"

prepare_case non-executable-docker
subcommand_under_test="status"
chmod 600 "$case_root/bin/docker"
expect_failure "CORTEX_DOCKER_BIN must be an executable regular file."

# --- create-role guards its inputs. -----------------------------------------
prepare_case role-password-file-required
subcommand_under_test="create-role"
expect_failure "CORTEX_DB_VIEW_ROLE_PASSWORD_FILE must point"

prepare_case role-password-symlink
subcommand_under_test="create-role"
ln -s "$password_file" "$case_root/password-link"
expect_failure "readable regular file" \
  CORTEX_DB_VIEW_ROLE_PASSWORD_FILE="$case_root/password-link"

prepare_case role-password-group-readable
subcommand_under_test="create-role"
chmod 640 "$password_file"
expect_failure "must be owner-only" \
  CORTEX_DB_VIEW_ROLE_PASSWORD_FILE="$password_file"

prepare_case role-password-multiline
subcommand_under_test="create-role"
printf 'linha-um\nlinha-dois\n' > "$password_file"
expect_failure "exactly one non-empty line" \
  CORTEX_DB_VIEW_ROLE_PASSWORD_FILE="$password_file"

prepare_case role-name-invalid
subcommand_under_test="create-role"
expect_failure "lowercase SQL identifiers" \
  CORTEX_DB_VIEW_ROLE_PASSWORD_FILE="$password_file" \
  CORTEX_DB_VIEW_ROLE="Cortex-Leitura"

prepare_case role-name-reserved
subcommand_under_test="create-role"
expect_failure "must not reuse an administrative runtime role" \
  CORTEX_DB_VIEW_ROLE_PASSWORD_FILE="$password_file" \
  CORTEX_DB_VIEW_ROLE="cortex_admin"

prepare_case role-without-postgres
subcommand_under_test="create-role"
touch "$CORTEX_TEST_MARKERS/no-postgres"
expect_failure "Exactly one running cortex-postgres container is required." \
  CORTEX_DB_VIEW_ROLE_PASSWORD_FILE="$password_file"

# --- create-role reconciles through psql without leaking the password. ------
prepare_case role-created
subcommand_under_test="create-role"
run_bridge CORTEX_DB_VIEW_ROLE_PASSWORD_FILE="$password_file" \
  || fail "create-role must succeed with a valid password file"
grep -q "exec -i -e CORTEX_DB_VIEW_ROLE_PASSWORD postgres-container psql -X --set=ON_ERROR_STOP=1" \
  "$CORTEX_TEST_DOCKER_LOG" \
  || fail "psql must run through exec with the password delivered by environment"
! grep -q "senha-de-teste-nao-real" "$CORTEX_TEST_DOCKER_LOG" \
  || fail "the role password must never appear in command arguments"
[[ "$(cat "$CORTEX_TEST_PSQL_ENV")" == "senha-de-teste-nao-real" ]] \
  || fail "the role password must reach psql through the exec environment"
grep -q '\\getenv view_role_password CORTEX_DB_VIEW_ROLE_PASSWORD' "$CORTEX_TEST_PSQL_SQL" \
  || fail "the SQL must read the password with \\getenv"
grep -q 'GRANT SELECT ON ALL TABLES IN SCHEMA public TO :"view_role";' "$CORTEX_TEST_PSQL_SQL" \
  || fail "the SQL must grant SELECT on the existing tables"
grep -q 'ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_user" IN SCHEMA public' "$CORTEX_TEST_PSQL_SQL" \
  || fail "future tables must be covered through the migrator default privileges"
grep -q "default_transaction_read_only = on" "$CORTEX_TEST_PSQL_SQL" \
  || fail "the viewing role must default to read-only transactions"
grep -q -- "--set=view_role=cortex_readonly" "$CORTEX_TEST_PSQL_SQL" "$CORTEX_TEST_DOCKER_LOG" \
  || fail "the default viewing role name must be cortex_readonly"
grep -q "refusing_to_reconcile_a_privileged_existing_role" "$CORTEX_TEST_PSQL_SQL" \
  || fail "the SQL must refuse to reconcile a pre-existing privileged role"
grep -q "refusing_to_reconcile_a_role_with_memberships" "$CORTEX_TEST_PSQL_SQL" \
  || fail "the SQL must refuse a viewing role that inherits other roles"
! grep -Eq 'INSERT|UPDATE|DELETE|TRUNCATE|GRANT[[:space:]]+ALL|ALL[[:space:]]+PRIVILEGES|pg_write_all_data' \
  "$CORTEX_TEST_PSQL_SQL" \
  || fail "the viewing role must never receive a write grant"
# The blacklist above cannot name every spelling of a write grant, so every
# GRANT the reconcile emits has to match the read-only allowlist as well.
if grep -E '\bGRANT\b' "$CORTEX_TEST_PSQL_SQL" | grep -Evq \
  '^[[:space:]]*GRANT (CONNECT ON DATABASE :"database_name"|USAGE ON SCHEMA public|SELECT ON ALL TABLES IN SCHEMA public|SELECT ON TABLES) TO :"view_role";$'; then
  fail "only CONNECT, USAGE and SELECT may be granted to the viewing role"
fi

# --- start refuses unsafe configurations before touching Docker. ------------
prepare_case start-invalid-port
subcommand_under_test="start"
expect_failure "CORTEX_DB_VIEW_PORT must be an unprivileged TCP port" \
  CORTEX_DB_VIEW_PORT="80"
! grep -q "run --detach" "$CORTEX_TEST_DOCKER_LOG" \
  || fail "an invalid port must be refused before any container starts"

prepare_case start-floating-image
subcommand_under_test="start"
expect_failure "explicitly tagged image reference" \
  CORTEX_DB_VIEW_SOCAT_IMAGE="alpine/socat"

prepare_case start-without-network
subcommand_under_test="start"
touch "$CORTEX_TEST_MARKERS/no-network"
expect_failure "does not exist; is the stack up?"
! grep -q "run --detach" "$CORTEX_TEST_DOCKER_LOG" \
  || fail "a missing network must be refused before any container starts"

prepare_case start-already-running
subcommand_under_test="start"
touch "$CORTEX_TEST_MARKERS/bridge-exists"
expect_failure "is already running; run stop first."
! grep -q "run --detach" "$CORTEX_TEST_DOCKER_LOG" \
  || fail "a running bridge must never be replaced silently"

# --- start recovers the stopped bridge a host reboot leaves behind. ---------
prepare_case start-after-reboot
subcommand_under_test="start"
touch "$CORTEX_TEST_MARKERS/bridge-exists"
run_bridge CORTEX_TEST_BRIDGE_STATE="exited" \
  || fail "start must recover from the stopped bridge a reboot leaves behind"
grep -q "rm -f cortex-db-view" "$CORTEX_TEST_DOCKER_LOG" \
  || fail "the stopped bridge must be removed before starting a new one"
grep -q "run --detach" "$CORTEX_TEST_DOCKER_LOG" \
  || fail "start must build a new bridge after clearing the stopped one"

# --- start builds the hardened loopback-only bridge. ------------------------
prepare_case start-succeeds
subcommand_under_test="start"
run_bridge || fail "start must succeed with the stack running"
run_line="$(grep "^run --detach" "$CORTEX_TEST_DOCKER_LOG")"
for contract in \
  "--restart no" \
  "--read-only" \
  "--cap-drop ALL" \
  "--security-opt no-new-privileges:true" \
  "--publish 127.0.0.1:15432:5432" \
  "--network cortex-production_db_view_edge" \
  "alpine/socat:" \
  "tcp-connect:cortex-postgres:5432"; do
  [[ "$run_line" == *"$contract"* ]] \
    || fail "the bridge container must be started with: $contract"
done
[[ "$run_line" != *"0.0.0.0"* ]] \
  || fail "the bridge must never publish outside the loopback interface"
# The listener accepts any peer inside its own network, so sharing the
# default bridge would expose PostgreSQL to every unrelated container.
grep -q "network create cortex-production_db_view_edge" "$CORTEX_TEST_DOCKER_LOG" \
  || fail "the bridge must get a dedicated network instead of the default bridge"
grep -q "network connect cortex-production_cortex_private cortex-db-view" \
  "$CORTEX_TEST_DOCKER_LOG" \
  || fail "the bridge must join the internal Compose network after starting"
grep -q "exec cortex-db-view socat -u OPEN:/dev/null TCP:cortex-postgres:5432" \
  "$CORTEX_TEST_DOCKER_LOG" \
  || fail "start must probe PostgreSQL through the bridge"
grep -q "127.0.0.1:15432" "$case_root/stdout.txt" \
  || fail "start must tell the operator where the bridge listens"

# --- the bridge only survives a reboot when asked to. -----------------------
prepare_case start-persistent
subcommand_under_test="start"
run_bridge CORTEX_DB_VIEW_RESTART_POLICY="unless-stopped" \
  || fail "start must accept the self-service restart policy"
grep -q "restart unless-stopped" "$CORTEX_TEST_DOCKER_LOG" \
  || fail "the requested restart policy must reach the container"

prepare_case start-invalid-restart-policy
subcommand_under_test="start"
expect_failure "CORTEX_DB_VIEW_RESTART_POLICY must be 'no' or 'unless-stopped'." \
  CORTEX_DB_VIEW_RESTART_POLICY="always"
! grep -q "run --detach" "$CORTEX_TEST_DOCKER_LOG" \
  || fail "an unknown restart policy must be refused before any container starts"

prepare_case start-reuses-existing-view-network
subcommand_under_test="start"
touch "$CORTEX_TEST_MARKERS/view-network-exists"
run_bridge || fail "start must reuse an existing dedicated network"
! grep -q "network create" "$CORTEX_TEST_DOCKER_LOG" \
  || fail "an existing dedicated network must not be recreated"

# --- start removes every partial bridge it leaves behind. -------------------
prepare_case start-probe-fails
subcommand_under_test="start"
touch "$CORTEX_TEST_MARKERS/probe-fails"
expect_failure "could not reach cortex-postgres:5432 and was removed."
grep -q "rm -f cortex-db-view" "$CORTEX_TEST_DOCKER_LOG" \
  || fail "a bridge that cannot reach PostgreSQL must be removed"

prepare_case start-connect-fails
subcommand_under_test="start"
touch "$CORTEX_TEST_MARKERS/connect-fails"
expect_failure "could not join 'cortex-production_cortex_private' and was removed."
grep -q "rm -f cortex-db-view" "$CORTEX_TEST_DOCKER_LOG" \
  || fail "a bridge that cannot join the internal network must be removed"

prepare_case start-run-fails
subcommand_under_test="start"
touch "$CORTEX_TEST_MARKERS/run-fails"
expect_failure "could not start and was removed."
grep -q "rm -f cortex-db-view" "$CORTEX_TEST_DOCKER_LOG" \
  || fail "a container left behind by a failed run must be removed"
grep -q "network rm cortex-production_db_view_edge" "$CORTEX_TEST_DOCKER_LOG" \
  || fail "a failed start must not leave its dedicated network behind"

prepare_case start-network-create-fails
subcommand_under_test="start"
touch "$CORTEX_TEST_MARKERS/network-create-fails"
expect_failure "bridge network 'cortex-production_db_view_edge' could not be created."
! grep -q "run --detach" "$CORTEX_TEST_DOCKER_LOG" \
  || fail "no container may start without its dedicated network"

# --- status and stop report and clean up. -----------------------------------
prepare_case status-running
subcommand_under_test="status"
touch "$CORTEX_TEST_MARKERS/bridge-exists"
run_bridge || fail "status must succeed for a healthy bridge"
grep -q "PostgreSQL is reachable through the bridge." "$case_root/stdout.txt" \
  || fail "status must confirm connectivity for a healthy bridge"

# The reported port comes from the container, so a shell without the
# variable that started the bridge still names the port to tunnel to.
prepare_case status-reports-the-published-port
subcommand_under_test="status"
touch "$CORTEX_TEST_MARKERS/bridge-exists"
run_bridge CORTEX_TEST_PUBLISHED_PORT="25432" \
  || fail "status must succeed for a healthy bridge on a custom port"
grep -q "loopback port 25432." "$case_root/stdout.txt" \
  || fail "status must report the port the bridge actually publishes"

prepare_case status-stopped
subcommand_under_test="status"
touch "$CORTEX_TEST_MARKERS/bridge-exists"
if run_bridge CORTEX_TEST_BRIDGE_STATE="exited"; then
  fail "status must exit non-zero for a stopped bridge"
fi
grep -q "exists but is not running" "$case_root/stdout.txt" \
  || fail "status must report a stopped bridge"

prepare_case status-absent
subcommand_under_test="status"
if run_bridge; then
  fail "status must exit non-zero when the bridge is absent"
fi
grep -q "is not present." "$case_root/stdout.txt" \
  || fail "status must report an absent bridge"

prepare_case stop-absent
subcommand_under_test="stop"
run_bridge || fail "stop must be idempotent when the bridge is absent"
! grep -q "rm -f" "$CORTEX_TEST_DOCKER_LOG" \
  || fail "stop must not remove anything when the bridge is absent"

prepare_case stop-running
subcommand_under_test="stop"
touch "$CORTEX_TEST_MARKERS/bridge-exists"
run_bridge || fail "stop must remove an existing bridge"
grep -q "rm -f cortex-db-view" "$CORTEX_TEST_DOCKER_LOG" \
  || fail "stop must remove the bridge container"
grep -q "network rm cortex-production_db_view_edge" "$CORTEX_TEST_DOCKER_LOG" \
  || fail "stop must remove the dedicated bridge network"

echo "The database viewing bridge honors its loopback-only, read-only contract."
