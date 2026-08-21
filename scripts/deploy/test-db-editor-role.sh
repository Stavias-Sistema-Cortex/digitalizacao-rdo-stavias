#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
editor_script="$repo_root/scripts/deploy/db-editor-role.sh"
fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/cortex-db-editor-contract.XXXXXX")"
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

  password_file="$case_root/editor-password"
  printf 'senha-de-teste-nao-real\n' > "$password_file"
  chmod 600 "$password_file"

  cat > "$case_root/bin/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$CORTEX_TEST_DOCKER_LOG"
all="$*"
if [[ "$all" == "ps --filter label=com.docker.compose.project="*"label=com.docker.compose.service=cortex-postgres"* ]]; then
  [[ -f "$CORTEX_TEST_MARKERS/no-postgres" ]] || printf 'postgres-container\n'
elif [[ "$all" == "exec"*"psql"* ]]; then
  printf '%s\n' "${CORTEX_DB_EDITOR_ROLE_PASSWORD:-<unset>}" > "$CORTEX_TEST_PSQL_ENV"
  cat > "$CORTEX_TEST_PSQL_SQL"
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
    CORTEX_DOCKER_BIN="$case_root/bin/docker"
  )
}

run_editor() {
  "${base_env[@]}" "$@" bash "$editor_script" "$subcommand_under_test" \
    > "$case_root/stdout.txt" 2> "$case_root/stderr.txt"
}

expect_failure() {
  local message="$1"
  shift
  if run_editor "$@"; then
    fail "$case_root expected a refusal: $message"
  fi
  grep -q "$message" "$case_root/stderr.txt" \
    || fail "$case_root did not explain: $message"
}

# --- The subcommand is mandatory and validated. -----------------------------
prepare_case usage
subcommand_under_test="grant-everything"
expect_failure "Usage: db-editor-role.sh"

prepare_case missing-docker
subcommand_under_test="create-role"
if env -i PATH="/usr/bin:/bin" HOME="$case_root" bash "$editor_script" create-role \
  > /dev/null 2> "$case_root/stderr.txt"; then
  fail "create-role must refuse to run without CORTEX_DOCKER_BIN"
fi
grep -q "CORTEX_DOCKER_BIN must be one non-empty line." "$case_root/stderr.txt" \
  || fail "the missing Docker binary refusal is not explained"

# --- The password file contract matches the rest of the repository. ---------
prepare_case password-file-required
subcommand_under_test="create-role"
expect_failure "CORTEX_DB_EDITOR_ROLE_PASSWORD_FILE must point"

prepare_case password-symlink
subcommand_under_test="create-role"
ln -s "$password_file" "$case_root/password-link"
expect_failure "readable regular file" \
  CORTEX_DB_EDITOR_ROLE_PASSWORD_FILE="$case_root/password-link"

prepare_case password-group-readable
subcommand_under_test="create-role"
chmod 640 "$password_file"
expect_failure "must be owner-only" \
  CORTEX_DB_EDITOR_ROLE_PASSWORD_FILE="$password_file"

prepare_case password-multiline
subcommand_under_test="create-role"
printf 'linha-um\nlinha-dois\n' > "$password_file"
expect_failure "exactly one non-empty line" \
  CORTEX_DB_EDITOR_ROLE_PASSWORD_FILE="$password_file"

# --- A stack role must never be hijacked by the editing role. ---------------
for reserved in cortex_admin cortex_migrator cortex_runtime cortex_readonly; do
  prepare_case "reserved-$reserved"
  subcommand_under_test="create-role"
  expect_failure "must not reuse an administrative, runtime or viewing role" \
    CORTEX_DB_EDITOR_ROLE_PASSWORD_FILE="$password_file" \
    CORTEX_DB_EDITOR_ROLE="$reserved"
done

prepare_case role-name-invalid
subcommand_under_test="create-role"
expect_failure "lowercase SQL identifiers" \
  CORTEX_DB_EDITOR_ROLE_PASSWORD_FILE="$password_file" \
  CORTEX_DB_EDITOR_ROLE="Cortex-Editor"

prepare_case without-postgres
subcommand_under_test="create-role"
touch "$CORTEX_TEST_MARKERS/no-postgres"
expect_failure "Exactly one running cortex-postgres container is required." \
  CORTEX_DB_EDITOR_ROLE_PASSWORD_FILE="$password_file"

# --- create-role grants data rights and nothing beyond them. ----------------
prepare_case role-created
subcommand_under_test="create-role"
run_editor CORTEX_DB_EDITOR_ROLE_PASSWORD_FILE="$password_file" \
  || fail "create-role must succeed with a valid password file"
grep -q "exec -i -e CORTEX_DB_EDITOR_ROLE_PASSWORD postgres-container psql -X --set=ON_ERROR_STOP=1" \
  "$CORTEX_TEST_DOCKER_LOG" \
  || fail "psql must run through exec with the password delivered by environment"
! grep -q "senha-de-teste-nao-real" "$CORTEX_TEST_DOCKER_LOG" \
  || fail "the role password must never appear in command arguments"
[[ "$(cat "$CORTEX_TEST_PSQL_ENV")" == "senha-de-teste-nao-real" ]] \
  || fail "the role password must reach psql through the exec environment"
grep -q '\\getenv editor_role_password CORTEX_DB_EDITOR_ROLE_PASSWORD' "$CORTEX_TEST_PSQL_SQL" \
  || fail "the SQL must read the password with \\getenv"
grep -q -- "--set=editor_role=cortex_editor" "$CORTEX_TEST_DOCKER_LOG" \
  || fail "the default editing role name must be cortex_editor"

for expected in \
  'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO :"editor_role";' \
  'GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO :"editor_role";' \
  'ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_user" IN SCHEMA public' \
  'REVOKE CREATE ON SCHEMA public FROM :"editor_role";'; do
  grep -qF "$expected" "$CORTEX_TEST_PSQL_SQL" \
    || fail "the reconcile must contain: $expected"
done

# The whole point of this role is that it stops at data.
for forbidden in \
  "SUPERUSER" \
  "CREATEROLE" \
  "CREATEDB" \
  "BYPASSRLS" \
  "REPLICATION"; do
  ! grep -Eq "(^|[^O])$forbidden" "$CORTEX_TEST_PSQL_SQL" \
    || fail "the editing role must never receive $forbidden"
done
! grep -Eq 'GRANT[[:space:]]+ALL|ALL[[:space:]]+PRIVILEGES|pg_write_all_data|pg_read_all_data' \
  "$CORTEX_TEST_PSQL_SQL" \
  || fail "the editing role must never receive a blanket grant"
! grep -Eq 'ALTER[[:space:]]+DATABASE[^;]*OWNER|OWNER[[:space:]]+TO' "$CORTEX_TEST_PSQL_SQL" \
  || fail "the editing role must never take ownership of the database"
! grep -Eq 'GRANT[^;]*CREATE[^;]*TO[[:space:]]*:"editor_role"' "$CORTEX_TEST_PSQL_SQL" \
  || fail "the editing role must never receive CREATE on the schema"
! grep -q "default_transaction_read_only" "$CORTEX_TEST_PSQL_SQL" \
  || fail "the editing role is deliberately not read-only"

# Every GRANT the reconcile emits has to match the data-only allowlist.
if grep -E '\bGRANT\b' "$CORTEX_TEST_PSQL_SQL" | grep -Evq \
  '^[[:space:]]*GRANT (CONNECT ON DATABASE :"database_name"|USAGE ON SCHEMA public|SELECT, INSERT, UPDATE, DELETE ON (ALL TABLES IN SCHEMA public|TABLES)|USAGE, SELECT, UPDATE ON (ALL SEQUENCES IN SCHEMA public|SEQUENCES)) TO :"editor_role";$'; then
  fail "only CONNECT, USAGE and row-level rights may be granted to the editing role"
fi

grep -q "refusing_to_reconcile_a_privileged_existing_role" "$CORTEX_TEST_PSQL_SQL" \
  || fail "the SQL must refuse to reconcile a pre-existing privileged role"
grep -q "refusing_to_reconcile_a_role_with_memberships" "$CORTEX_TEST_PSQL_SQL" \
  || fail "the SQL must refuse an editing role that inherits other roles"
grep -q "refusing_to_reconcile_a_role_that_owns_objects" "$CORTEX_TEST_PSQL_SQL" \
  || fail "the SQL must refuse an editing role that already owns objects"

# --- revoke closes the door without destroying the role. --------------------
prepare_case revoked
subcommand_under_test="revoke"
run_editor || fail "revoke must succeed against a running stack"
grep -q "ALTER ROLE %I NOLOGIN" "$CORTEX_TEST_PSQL_SQL" \
  || fail "revoke must take the login away"
grep -q 'REVOKE CONNECT ON DATABASE :"database_name" FROM :"editor_role";' "$CORTEX_TEST_PSQL_SQL" \
  || fail "revoke must take CONNECT away"
grep -q "pg_terminate_backend" "$CORTEX_TEST_PSQL_SQL" \
  || fail "revoke must close sessions that are already open"
! grep -q "DROP ROLE" "$CORTEX_TEST_PSQL_SQL" \
  || fail "revoke must not drop the role"
! grep -q -- "-e CORTEX_DB_EDITOR_ROLE_PASSWORD" "$CORTEX_TEST_DOCKER_LOG" \
  || fail "revoke must not need the role password"

echo "The editing role honors its data-only, revocable contract."
