#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
script="$repo_root/scripts/deploy/migrate-local-postgres-to-neon.sh"
sql="$repo_root/scripts/deploy/prepare-neon-database.sql"
reconcile_sql="$repo_root/scripts/deploy/reconcile-neon-ownership.sql"

[[ -x "$script" ]] || {
  echo "Neon migration script is missing or not executable" >&2
  exit 1
}
[[ -f "$sql" ]] || {
  echo "Neon database preparation SQL is missing" >&2
  exit 1
}
[[ -f "$reconcile_sql" ]] || {
  echo "Neon ownership reconciliation SQL is missing" >&2
  exit 1
}

if [[ "${CORTEX_NEON_CONTRACT_SKIP_STATIC:-false}" != "true" ]]; then
  bash -n "$script"
  grep -Fq 'set -euo pipefail' "$script"
  grep -Fq 'umask 077' "$script"
  grep -Fq 'mktemp -d' "$script"
  grep -Fq 'trap cleanup EXIT' "$script"
  grep -Fq 'PGSERVICEFILE' "$script"
  grep -Fq 'PGPASSFILE' "$script"
  grep -Fq 'pg_export_snapshot' "$script"
  grep -Fq -- '--snapshot=' "$script"
  grep -Fq -- '--format=custom' "$script"
  grep -Fq -- '--no-owner' "$script"
  grep -Fq -- '--no-acl' "$script"
  grep -Fq -- '--exit-on-error' "$script"
  grep -Fq -- '--single-transaction' "$script"
  if ! grep -Fq -- '--role="$migrator_role"' "$script"; then
    echo "Migration restore does not assign restored objects to cortex_migrator" >&2
    exit 1
  fi
  grep -Fq 'public.auth_identity' "$script"
  if grep -Fq 'postgres-error.log' "$script"; then
    echo "Migration script persists PostgreSQL client diagnostics" >&2
    exit 1
  fi

  if grep -Eqi 'CREATE ROLE|ALTER ROLE|GRANT ' "$sql"; then
    echo "Database bootstrap SQL mutates a role before the empty-target gate" >&2
    exit 1
  fi
  if grep -Eq -- '--clean|--if-exists|dropdb|DROP DATABASE' "$script" "$sql"; then
    echo "Migration tooling contains a destructive target operation" >&2
    exit 1
  fi
  if grep -Eq 'set -x|echo .*(PGURI|PASSWORD)' "$script"; then
    echo "Migration script can disclose a credential" >&2
    exit 1
  fi
fi

fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/cortex-neon-contract.XXXXXX")"
pg18_container=""
cleanup() {
  if [[ -n "$pg18_container" ]]; then
    docker rm -f -v "$pg18_container" >/dev/null 2>&1 || true
  fi
  rm -rf "$fixture_root"
}
trap cleanup EXIT

fake_bin="$fixture_root/bin"
mkdir -p "$fake_bin"

file_mode() {
  local path="$1"
  local mode
  if mode="$(stat -f '%Lp' "$path" 2>/dev/null)" &&
    [[ "$mode" =~ ^[0-7]{3,4}$ ]]; then
    printf '%s\n' "$mode"
    return
  fi
  stat -c '%a' "$path"
}

cat > "$fake_bin/pg-tool" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail

tool_name="${0##*/}"

file_mode() {
  local path="$1"
  local mode
  if mode="$(stat -f '%Lp' "$path" 2>/dev/null)" &&
    [[ "$mode" =~ ^[0-7]{3,4}$ ]]; then
    printf '%s\n' "$mode"
    return
  fi
  stat -c '%a' "$path"
}

if [[ "${1:-}" == "--version" ]]; then
  printf '%s (PostgreSQL) 18.4 (contract fixture)\n' "$tool_name"
  exit 0
fi

mark_failure() {
  : > "$FAKE_CAPTURE/$1"
  exit 90
}

for argument in "$@"; do
  [[ "$argument" != *"postgresql://"* && "$argument" != *"postgres://"* ]] ||
    mark_failure secret-in-argv
  [[ "$argument" != *"$FIXTURE_SOURCE_URI"* &&
    "$argument" != *"$FIXTURE_ADMIN_URI"* &&
    "$argument" != *"$FIXTURE_PASSWORD"* &&
    "$argument" != *"$FIXTURE_RUNTIME_PASSWORD"* ]] ||
    mark_failure secret-in-argv
done

[[ -z "${CORTEX_SOURCE_PGURI:-}" &&
  -z "${CORTEX_NEON_ADMIN_PGURI:-}" &&
  -z "${CORTEX_NEON_RUNTIME_PASSWORD:-}" ]] ||
  mark_failure inherited-secret-environment
[[ -z "${PGHOST:-}" &&
  -z "${PGPASSWORD:-}" &&
  -z "${PGOPTIONS:-}" ]] ||
  mark_failure inherited-libpq-environment

[[ -f "${PGSERVICEFILE:-}" && -f "${PGPASSFILE:-}" ]] ||
  mark_failure missing-private-connection-files
service_mode="$(file_mode "$PGSERVICEFILE")"
pass_mode="$(file_mode "$PGPASSFILE")"
[[ "$service_mode" == "600" && "$pass_mode" == "600" ]] ||
  mark_failure unsafe-connection-file-mode

if [[ ! -f "$FAKE_CAPTURE/service-checked" ]]; then
  grep -Fq 'sslmode=verify-full' "$PGSERVICEFILE" ||
    mark_failure weak-target-tls
  grep -Fq 'channel_binding=require' "$PGSERVICEFILE" ||
    mark_failure lost-channel-binding
  grep -Fq 'sslrootcert=/tmp/fixture-ca.pem' "$PGSERVICEFILE" ||
    mark_failure lost-certificate-option
  grep -Fq 'options=endpoint=fixture-endpoint' "$PGSERVICEFILE" ||
    mark_failure lost-neon-option
  ! grep -Fq "$FIXTURE_PASSWORD" "$PGSERVICEFILE" ||
    mark_failure password-in-service-file
  grep -Fq "$FIXTURE_PASSWORD" "$PGPASSFILE" ||
    mark_failure password-missing-from-passfile
  : > "$FAKE_CAPTURE/service-checked"
fi

log_operation() {
  printf '%s\n' "$1" >> "$FAKE_OPERATION_LOG"
}

emit_counts() {
  local service="$1"
  local table_name
  for table_name in \
    auth_identity \
    auth_capacidade_administrativa \
    colaborador \
    obra \
    rdo \
    rdo_mao_obra \
    vinculo_colaborador_obra \
    cortex_evento_operacional \
    finance_lancamento \
    stored_object; do
    if [[ "$service" == "neon_target" &&
      "${FAKE_MISMATCH_TABLE:-}" == "$table_name" ]]; then
      printf '%s|8\n' "$table_name"
    else
      printf '%s|7\n' "$table_name"
    fi
  done
}

case "$tool_name" in
  psql)
    query=""
    query_is_next=false
    for argument in "$@"; do
      if [[ "$query_is_next" == "true" ]]; then
        query="$argument"
        break
      fi
      if [[ "$argument" == "-c" ]]; then
        query_is_next=true
      fi
    done

    if [[ " $* " == *" -f "* &&
      " $* " == *"prepare-neon-database.sql"* ]]; then
      [[ "${PGSERVICE:-}" == "neon_admin" ]] ||
        mark_failure bootstrap-wrong-service
      log_operation bootstrap
      exit 0
    fi

    if [[ " $* " == *" -f "* &&
      " $* " == *"reconcile-neon-ownership.sql"* ]]; then
      [[ "${PGSERVICE:-}" == "neon_target" ]] ||
        mark_failure reconcile-wrong-service
      log_operation reconcile
      [[ "${FAKE_FAIL_RECONCILE:-false}" != "true" ]] || exit 93
      exit 0
    fi

    if [[ -n "$query" && "$query" == *"server_version_num"* ]]; then
      [[ "${PGSERVICE:-}" == "neon_admin" ]] ||
        mark_failure version-wrong-service
      log_operation version_check
      printf '180004\n'
      exit 0
    fi

    if [[ -n "$query" && "$query" == *"pg_namespace"* ]]; then
      [[ "${PGSERVICE:-}" == "neon_target" ]] ||
        mark_failure gate-wrong-service
      [[ "$query" == *"pg_proc"* && "$query" == *"pg_type"* &&
        "$query" == *"pg_class"* && "$query" == *"pg_collation"* &&
        "$query" == *"pg_operator"* && "$query" == *"pg_ts_config"* &&
        "$query" == *"pg_extension"* ]] ||
        mark_failure incomplete-empty-gate
      log_operation empty_gate
      if [[ -f "$FAKE_CAPTURE/target-populated" ]]; then
        printf '1\n'
      else
        printf '%s\n' "${FAKE_TARGET_OBJECT_COUNT:-0}"
      fi
      exit 0
    fi

    if [[ "${PGSERVICE:-}" == "source" &&
      " $* " == *" source_snapshot="* ]]; then
      payload="$(</dev/stdin)"
      [[ "$payload" == *"SET TRANSACTION SNAPSHOT"* &&
        "$payload" == *"public.auth_identity"* &&
        "$payload" == *"public.stored_object"* ]] ||
        mark_failure counts-without-shared-snapshot
      log_operation source_counts
      emit_counts source
      exit 0
    fi

    if [[ "${PGSERVICE:-}" == "source" ]]; then
      log_operation snapshot_open
      while IFS= read -r line; do
        if [[ "$line" == *"pg_export_snapshot()"* ]]; then
          printf '00000001-00000001-1\n'
        elif [[ "$line" == "\\q" ]]; then
          exit 0
        fi
      done
      exit 0
    fi

    if [[ "${PGSERVICE:-}" == "neon_target" ]]; then
      payload="$(</dev/stdin)"
      if [[ "$payload" == *"pg_auth_members"* ]]; then
        [[ "$payload" == *"pg_get_userbyid"* &&
          "$payload" == *"datdba"* &&
          "$payload" == *"current_user"* &&
          "$payload" == *"'neondb_owner'"* &&
          "$payload" == *"rolcanlogin"* &&
          "$payload" == *"rolsuper"* &&
          "$payload" == *"rolcreatedb"* &&
          "$payload" == *"rolcreaterole"* &&
          "$payload" == *"rolreplication"* &&
          "$payload" == *"rolbypassrls"* &&
          "$payload" == *"admin_option"* &&
          "$payload" == *"inherit_option"* &&
          "$payload" == *"set_option"* &&
          "$payload" == *"cortex_runtime"* &&
          "$payload" == *"pg_default_acl"* &&
          "$payload" == *"has_schema_privilege"* &&
          "$payload" == *"has_database_privilege"* &&
          "$payload" == *"'CONNECT'"* &&
          "$payload" == *"'CREATE'"* ]] ||
          mark_failure incomplete-role-preflight
        if [[ "${FAKE_UNSAFE_MIGRATOR_DEFAULT_ACL:-false}" == "true" ]]; then
          [[ "$payload" == *"aclexplode"* &&
            "$payload" == *"defaults.defaclobjtype"* &&
            "$payload" == *"defaults.defaclnamespace = 0"* &&
            "$payload" == *"'public'::regnamespace"* &&
            "$payload" == *"acl.is_grantable"* &&
            "$payload" == *"acl.grantee"* &&
            "$payload" == *"acl.grantor"* ]] ||
            mark_failure incomplete-migrator-default-acl-check
          log_operation role_preflight
          printf '0\n'
          exit 0
        fi
        log_operation role_preflight
        printf '%s\n' "${FAKE_ROLE_PREFLIGHT_READY:-1}"
        exit 0
      fi

      if [[ "$payload" == *"pg_get_userbyid"* &&
        "$payload" == *"datdba"* ]]; then
        [[ "$payload" == *"current_database()"* &&
          "$payload" == *"current_user"* &&
          "$payload" == *"'StaviasCortex'"* &&
          "$payload" == *"'neondb_owner'"* ]] ||
          mark_failure incomplete-post-restore-owner-check
        log_operation owner_check
        printf '%s\n' "${FAKE_DATABASE_OWNER_UNCHANGED:-1}"
        exit 0
      fi

      if [[ "$payload" == *"ALTER ROLE cortex_runtime"* ]]; then
        [[ "${CORTEX_RUNTIME_PASSWORD_INPUT:-}" == "$FIXTURE_RUNTIME_PASSWORD" ]] ||
          mark_failure missing-runtime-password-environment
        [[ "$payload" == *"BEGIN;"* && "$payload" == *"COMMIT;"* &&
          "$payload" == *"rolsuper"* &&
          "$payload" == *"rolcreatedb"* &&
          "$payload" == *"rolcreaterole"* &&
          "$payload" == *"rolreplication"* &&
          "$payload" == *"rolbypassrls"* &&
          "$payload" == *"NOSUPERUSER"* &&
          "$payload" == *"NOCREATEDB"* &&
          "$payload" == *"NOCREATEROLE"* &&
          "$payload" == *"NOREPLICATION"* &&
          "$payload" == *"NOBYPASSRLS"* &&
          "$payload" != *"GRANT "* &&
          "$payload" != *"ALTER DEFAULT PRIVILEGES"* ]] ||
          mark_failure unsafe-runtime-role-transaction
        log_operation runtime_role
        [[ "${FAKE_FAIL_RUNTIME_ROLE:-false}" != "true" ]] || exit 91
        exit 0
      fi

      if [[ "$payload" == *"public.auth_identity"* &&
        "$payload" == *"public.stored_object"* ]]; then
        log_operation target_counts
        emit_counts neon_target
        exit 0
      fi
    fi

    mark_failure unexpected-psql-call
    ;;

  pg_dump)
    [[ "${PGSERVICE:-}" == "source" ]] ||
      mark_failure dump-wrong-service
    dump_file=""
    has_snapshot=false
    for argument in "$@"; do
      case "$argument" in
        --file=*) dump_file="${argument#--file=}" ;;
        --snapshot=00000001-00000001-1) has_snapshot=true ;;
      esac
    done
    [[ "$has_snapshot" == "true" && -n "$dump_file" ]] ||
      mark_failure dump-without-shared-snapshot
    log_operation dump
    printf 'contract-dump\n' > "$dump_file"
    ;;

  pg_restore)
    if [[ " $* " == *" --list "* ]]; then
      log_operation dump_list
      exit 0
    fi
    [[ "${PGSERVICE:-}" == "neon_target" ]] ||
      mark_failure restore-wrong-service
    [[ " $* " == *" --dbname=service=neon_target "* &&
      " $* " == *" --single-transaction "* &&
      " $* " == *" --exit-on-error "* &&
      " $* " == *" --role=cortex_migrator "* &&
      " $* " == *" --no-owner "* &&
      " $* " == *" --no-acl "* ]] ||
      mark_failure non-atomic-restore
    log_operation restore
    [[ "${FAKE_FAIL_RESTORE:-false}" != "true" ]] || exit 92
    : > "$FAKE_CAPTURE/target-populated"
    ;;

  *)
    mark_failure unexpected-tool
    ;;
esac
FAKE

chmod +x "$fake_bin/pg-tool"
bash -n "$fake_bin/pg-tool"
ln -s pg-tool "$fake_bin/psql"
ln -s pg-tool "$fake_bin/pg_dump"
ln -s pg-tool "$fake_bin/pg_restore"

fixture_user="contract_test_owner"
fixture_password="${fixture_user}-test-password"
fixture_runtime_password="${fixture_user}-test-runtime-password"
source_uri="postgresql://${fixture_user}:${fixture_password}@source.fixture.invalid:5432/StaviasCortex"
admin_uri="postgresql://${fixture_user}:${fixture_password}@ep-contract.us-east-2.aws.neon.tech:5432/admin?sslmode=verify-full&channel_binding=require&sslrootcert=%2Ftmp%2Ffixture-ca.pem&options=endpoint%3Dfixture-endpoint"

assert_redacted() {
  local output="$1"
  [[ "$output" != *"$source_uri"* &&
    "$output" != *"$admin_uri"* &&
    "$output" != *"$fixture_password"* &&
    "$output" != *"$fixture_runtime_password"* ]] || {
    echo "Migration contract output disclosed fixture connection material" >&2
    exit 1
  }
}

prepare_case() {
  local case_name="$1"
  case_root="$fixture_root/$case_name"
  capture_dir="$case_root/capture"
  rollback_dir="$case_root/rollback"
  operation_log="$capture_dir/operations"
  mkdir -p "$capture_dir" "$rollback_dir"
  chmod 700 "$capture_dir" "$rollback_dir"
  : > "$operation_log"
}

run_case() {
  env \
    PATH="$PATH" \
    CORTEX_POSTGRES_BIN_DIR="$fake_bin" \
    CORTEX_SOURCE_PGURI="${CASE_SOURCE_URI:-$source_uri}" \
    CORTEX_NEON_ADMIN_PGURI="${CASE_ADMIN_URI:-$admin_uri}" \
    CORTEX_NEON_RUNTIME_PASSWORD="$fixture_runtime_password" \
    CORTEX_NEON_MIGRATOR_ROLE="${CASE_MIGRATOR_ROLE:-cortex_migrator}" \
    CORTEX_NEON_ROLLBACK_DIR="$rollback_dir" \
    TMPDIR="${CASE_TMPDIR:-${TMPDIR:-/tmp}}" \
    PGHOST=ambient.fixture.invalid \
    PGPASSWORD="$fixture_password" \
    PGOPTIONS="-c application_name=ambient-contract-test" \
    FIXTURE_SOURCE_URI="${CASE_SOURCE_URI:-$source_uri}" \
    FIXTURE_ADMIN_URI="${CASE_ADMIN_URI:-$admin_uri}" \
    FIXTURE_PASSWORD="$fixture_password" \
    FIXTURE_RUNTIME_PASSWORD="$fixture_runtime_password" \
    FAKE_CAPTURE="$capture_dir" \
    FAKE_OPERATION_LOG="${CASE_OPERATION_LOG:-$operation_log}" \
    FAKE_TARGET_OBJECT_COUNT="${FAKE_TARGET_OBJECT_COUNT:-0}" \
    FAKE_FAIL_RESTORE="${FAKE_FAIL_RESTORE:-false}" \
    FAKE_FAIL_RUNTIME_ROLE="${FAKE_FAIL_RUNTIME_ROLE:-false}" \
    FAKE_FAIL_RECONCILE="${FAKE_FAIL_RECONCILE:-false}" \
    FAKE_MISMATCH_TABLE="${FAKE_MISMATCH_TABLE:-}" \
    FAKE_ROLE_PREFLIGHT_READY="${FAKE_ROLE_PREFLIGHT_READY:-1}" \
    FAKE_UNSAFE_MIGRATOR_DEFAULT_ACL="${FAKE_UNSAFE_MIGRATOR_DEFAULT_ACL:-false}" \
    FAKE_DATABASE_OWNER_UNCHANGED="${FAKE_DATABASE_OWNER_UNCHANGED:-1}" \
    bash "$script"
}

prepare_case wrong-migrator-role
CASE_MIGRATOR_ROLE=another_migrator
set +e
wrong_migrator_output="$(run_case 2>&1)"
wrong_migrator_status=$?
set -e
unset CASE_MIGRATOR_ROLE
[[ $wrong_migrator_status -ne 0 &&
  "$wrong_migrator_output" == "Neon migrator role must be exactly cortex_migrator." &&
  ! -s "$operation_log" ]] || {
  echo "Migration contract did not reject a divergent migrator role" >&2
  exit 1
}
assert_redacted "$wrong_migrator_output"

prepare_case duplicate-tls
CASE_ADMIN_URI="${admin_uri}&sslmode=disable"
set +e
duplicate_output="$(run_case 2>&1)"
duplicate_status=$?
set -e
unset CASE_ADMIN_URI
assert_redacted "$duplicate_output"
[[ $duplicate_status -ne 0 &&
  "$duplicate_output" == "Neon admin URI has duplicate query parameter." &&
  ! -s "$operation_log" ]] || {
  printf 'status=%s output=%q operation-bytes=%s\n' \
    "$duplicate_status" \
    "$duplicate_output" \
    "$(wc -c < "$operation_log")" >&2
  echo "Migration contract did not reject duplicate TLS parameters before PostgreSQL" >&2
  exit 1
}

prepare_case multiple-neon-hosts
CASE_ADMIN_URI="${admin_uri/ep-contract.us-east-2.aws.neon.tech/attacker.example,ep-contract.us-east-2.aws.neon.tech}"
set +e
multiple_hosts_output="$(run_case 2>&1)"
multiple_hosts_status=$?
set -e
unset CASE_ADMIN_URI
[[ $multiple_hosts_status -ne 0 &&
  "$multiple_hosts_output" == "Neon admin URI must target exactly one Neon host." &&
  ! -s "$operation_log" ]] || {
  echo "Migration contract allowed a multi-host libpq target" >&2
  exit 1
}
assert_redacted "$multiple_hosts_output"

prepare_case certificate-verification-required
CASE_ADMIN_URI="${admin_uri/sslmode=verify-full/sslmode=require}"
set +e
weak_tls_output="$(run_case 2>&1)"
weak_tls_status=$?
set -e
unset CASE_ADMIN_URI
[[ $weak_tls_status -ne 0 &&
  "$weak_tls_output" == "Neon admin URI must use sslmode=verify-full." &&
  ! -s "$operation_log" ]] || {
  echo "Migration contract did not require Neon certificate and hostname verification" >&2
  exit 1
}
assert_redacted "$weak_tls_output"

prepare_case weak-channel-binding
CASE_ADMIN_URI="${admin_uri/channel_binding=require/channel_binding=prefer}"
set +e
weak_binding_output="$(run_case 2>&1)"
weak_binding_status=$?
set -e
unset CASE_ADMIN_URI
[[ $weak_binding_status -ne 0 &&
  "$weak_binding_output" == "Neon admin URI must require channel binding." &&
  ! -s "$operation_log" ]] || {
  echo "Migration contract did not require channel binding" >&2
  exit 1
}
assert_redacted "$weak_binding_output"

prepare_case unsafe-options
CASE_ADMIN_URI="${admin_uri/options=endpoint%3Dfixture-endpoint/options=-c%20search_path%3Dattacker}"
set +e
unsafe_options_output="$(run_case 2>&1)"
unsafe_options_status=$?
set -e
unset CASE_ADMIN_URI
[[ $unsafe_options_status -ne 0 &&
  "$unsafe_options_output" == "Neon admin URI has unsafe options." &&
  ! -s "$operation_log" ]] || {
  echo "Migration contract did not reject unsafe Neon startup options" >&2
  exit 1
}
assert_redacted "$unsafe_options_output"

prepare_case checkout-tmpdir
CASE_TMPDIR="$repo_root"
set +e
tmpdir_output="$(run_case 2>&1)"
tmpdir_status=$?
set -e
unset CASE_TMPDIR
[[ $tmpdir_status -ne 0 &&
  "$tmpdir_output" == "Temporary migration directory must remain outside the Git checkout." &&
  ! -s "$operation_log" ]] || {
  echo "Migration contract allowed private temporaries inside the checkout" >&2
  exit 1
}
assert_redacted "$tmpdir_output"

prepare_case neon-source
CASE_SOURCE_URI="${source_uri/source.fixture.invalid/ep-contract.us-east-2.aws.neon.tech}"
set +e
neon_source_output="$(run_case 2>&1)"
neon_source_status=$?
set -e
unset CASE_SOURCE_URI
[[ $neon_source_status -ne 0 &&
  "$neon_source_output" == "Source PostgreSQL URI must remain outside Neon." &&
  ! -s "$operation_log" ]] || {
  echo "Migration contract did not reject a Neon source endpoint" >&2
  exit 1
}
assert_redacted "$neon_source_output"

prepare_case non-table-object
FAKE_TARGET_OBJECT_COUNT=1
set +e
nonempty_output="$(run_case 2>&1)"
nonempty_status=$?
set -e
unset FAKE_TARGET_OBJECT_COUNT
assert_redacted "$nonempty_output"
[[ $nonempty_status -ne 0 &&
  "$nonempty_output" == "Target StaviasCortex database is not empty; migration stopped." ]] || {
  echo "Migration contract did not reject a non-table target object" >&2
  exit 1
}
pre_gate_operations="version_check bootstrap empty_gate "
[[ "$(tr '\n' ' ' < "$operation_log")" == "$pre_gate_operations" ]] || {
  echo "Migration contract allowed a target mutation before the empty gate" >&2
  exit 1
}

prepare_case rollback-symlink
escaped_dump="$case_root/escaped.dump"
ln -s "$escaped_dump" "$rollback_dir/StaviasCortex-pre-neon.dump"
set +e
symlink_output="$(run_case 2>&1)"
symlink_status=$?
set -e
[[ $symlink_status -ne 0 &&
  "$symlink_output" == "Protected rollback dump already exists; migration stopped." &&
  ! -e "$escaped_dump" &&
  "$(tr '\n' ' ' < "$operation_log")" == "$pre_gate_operations" ]] || {
  echo "Migration contract did not reject an existing rollback symlink" >&2
  exit 1
}
assert_redacted "$symlink_output"

prepare_case unsafe-role-topology
FAKE_ROLE_PREFLIGHT_READY=0
set +e
migrator_output="$(run_case 2>&1)"
migrator_status=$?
set -e
unset FAKE_ROLE_PREFLIGHT_READY
migrator_gate_operations="${pre_gate_operations}role_preflight "
[[ $migrator_status -ne 0 &&
  "$migrator_output" == "Neon owner, migrator, or runtime role topology is unsafe." &&
  "$(tr '\n' ' ' < "$operation_log")" == "$migrator_gate_operations" ]] || {
  echo "Migration contract did not preflight the exact owner and role topology" >&2
  exit 1
}
assert_redacted "$migrator_output"

prepare_case unsafe-migrator-default-acl
FAKE_UNSAFE_MIGRATOR_DEFAULT_ACL=true
set +e
default_acl_output="$(run_case 2>&1)"
default_acl_status=$?
set -e
unset FAKE_UNSAFE_MIGRATOR_DEFAULT_ACL
[[ $default_acl_status -ne 0 &&
  "$default_acl_output" == "Neon owner, migrator, or runtime role topology is unsafe." &&
  "$(tr '\n' ' ' < "$operation_log")" == "$migrator_gate_operations" ]] || {
  echo "Migration contract did not reject an unsafe migrator default ACL before restore" >&2
  exit 1
}
assert_redacted "$default_acl_output"

prepare_case restore-retry
FAKE_FAIL_RESTORE=true
set +e
restore_output="$(run_case 2>&1)"
restore_status=$?
set -e
unset FAKE_FAIL_RESTORE
[[ $restore_status -ne 0 && ! -f "$capture_dir/target-populated" ]] || {
  echo "Migration contract did not keep a failed restore atomic" >&2
  exit 1
}
assert_redacted "$restore_output"
rm -f "$rollback_dir/StaviasCortex-pre-neon.dump"
: > "$operation_log"
retry_output="$(run_case 2>&1)"
[[ "$(grep -c '^' <<< "$retry_output")" == "10" ]] || {
  echo "Migration contract could not safely retry an atomic restore failure" >&2
  exit 1
}
assert_redacted "$retry_output"

prepare_case runtime-role-failure
FAKE_FAIL_RUNTIME_ROLE=true
set +e
grant_output="$(run_case 2>&1)"
grant_status=$?
set -e
unset FAKE_FAIL_RUNTIME_ROLE
[[ $grant_status -ne 0 && -f "$capture_dir/target-populated" ]] || {
  echo "Migration contract did not expose a post-restore runtime-role failure" >&2
  exit 1
}
assert_redacted "$grant_output"
second_log="$capture_dir/retry-operations"
: > "$second_log"
CASE_OPERATION_LOG="$second_log"
set +e
rerun_output="$(run_case 2>&1)"
rerun_status=$?
set -e
unset CASE_OPERATION_LOG
[[ $rerun_status -ne 0 &&
  "$rerun_output" == "Target StaviasCortex database is not empty; migration stopped." &&
  "$(tr '\n' ' ' < "$second_log")" == "$pre_gate_operations" ]] || {
  echo "Migration contract did not stop a safe rerun on a partial target" >&2
  exit 1
}
assert_redacted "$rerun_output"

prepare_case database-owner-changed
FAKE_DATABASE_OWNER_UNCHANGED=0
set +e
owner_output="$(run_case 2>&1)"
owner_status=$?
set -e
unset FAKE_DATABASE_OWNER_UNCHANGED
owner_gate_operations="${pre_gate_operations}role_preflight snapshot_open dump dump_list source_counts restore owner_check "
[[ $owner_status -ne 0 &&
  "$owner_output" == "Neon database owner changed during restore; migration stopped." &&
  "$(tr '\n' ' ' < "$operation_log")" == "$owner_gate_operations" ]] || {
  echo "Migration contract did not fail closed when restore changed the database owner" >&2
  exit 1
}
assert_redacted "$owner_output"

prepare_case reconciliation-failure
FAKE_FAIL_RECONCILE=true
set +e
reconcile_output="$(run_case 2>&1)"
reconcile_status=$?
set -e
unset FAKE_FAIL_RECONCILE
reconcile_gate_operations="${owner_gate_operations}runtime_role reconcile "
[[ $reconcile_status -ne 0 &&
  "$reconcile_output" == "Final Neon ownership reconciliation failed; target requires controlled reprovisioning." &&
  "$(tr '\n' ' ' < "$operation_log")" == "$reconcile_gate_operations" ]] || {
  echo "Migration contract did not make the final ownership reconciler mandatory" >&2
  exit 1
}
assert_redacted "$reconcile_output"

prepare_case count-mismatch
FAKE_MISMATCH_TABLE=finance_lancamento
set +e
mismatch_output="$(run_case 2>&1)"
mismatch_status=$?
set -e
unset FAKE_MISMATCH_TABLE
assert_redacted "$mismatch_output"
[[ $mismatch_status -ne 0 &&
  "$(grep -c '^' <<< "$mismatch_output")" == "9" &&
  "$mismatch_output" == *"finance_lancamento|7|8" &&
  "$mismatch_output" != *"stored_object|"* ]] || {
  echo "Migration contract did not stop on the first count mismatch" >&2
  exit 1
}

prepare_case success
success_output="$(run_case 2>&1)"
assert_redacted "$success_output"
expected_operations="version_check bootstrap empty_gate role_preflight snapshot_open dump dump_list source_counts restore owner_check runtime_role reconcile target_counts "
[[ "$(tr '\n' ' ' < "$operation_log")" == "$expected_operations" ]] || {
  echo "Migration contract observed an unsafe migration operation order" >&2
  exit 1
}
[[ "$(grep -c '^' <<< "$success_output")" == "10" ]] || {
  echo "Migration contract did not emit exactly ten count comparisons" >&2
  exit 1
}
while IFS='|' read -r table_name source_count target_count; do
  [[ -n "$table_name" && "$source_count" == "7" && "$target_count" == "7" ]] || {
    echo "Migration contract emitted an invalid count comparison" >&2
    exit 1
  }
done <<< "$success_output"
[[ -f "$rollback_dir/StaviasCortex-pre-neon.dump" ]] || {
  echo "Migration contract did not preserve the protected rollback dump" >&2
  exit 1
}
rollback_mode="$(file_mode "$rollback_dir/StaviasCortex-pre-neon.dump")"
[[ "$rollback_mode" == "600" ]] || {
  echo "Migration contract created an unsafe rollback dump mode" >&2
  exit 1
}
for failure_marker in \
  secret-in-argv \
  inherited-secret-environment \
  inherited-libpq-environment \
  missing-private-connection-files \
  unsafe-connection-file-mode \
  weak-target-tls \
  incomplete-empty-gate \
  incomplete-role-preflight \
  incomplete-migrator-default-acl-check \
  incomplete-post-restore-owner-check \
  counts-without-shared-snapshot \
  unsafe-runtime-role-transaction; do
  [[ ! -e "$capture_dir/$failure_marker" ]] || {
    echo "Migration contract observed unsafe behavior: $failure_marker" >&2
    exit 1
  }
done

if [[ "${CORTEX_NEON_CONTRACT_SKIP_PG18:-false}" != "true" ]]; then
  command -v docker >/dev/null 2>&1 || {
    echo "Docker is required for the PostgreSQL 18 migration preflight contract" >&2
    exit 1
  }
  docker info >/dev/null 2>&1 || {
    echo "Docker is unavailable for the PostgreSQL 18 migration preflight contract" >&2
    exit 1
  }

  pg18_container="cortex-neon-migration-preflight-contract-$$"
  docker run --detach \
    --name "$pg18_container" \
    --env POSTGRES_HOST_AUTH_METHOD=trust \
    postgres:18-alpine >/dev/null

  postgres_ready=false
  for _ in $(seq 1 60); do
    if [[ "$(docker logs "$pg18_container" 2>&1)" == \
        *"PostgreSQL init process complete; ready for start up."* ]] &&
      docker exec "$pg18_container" \
      pg_isready -U postgres >/dev/null 2>&1; then
      postgres_ready=true
      break
    fi
    sleep 1
  done
  [[ "$postgres_ready" == "true" ]] || {
    echo "PostgreSQL 18 migration preflight contract did not become ready" >&2
    exit 1
  }

  docker exec "$pg18_container" psql \
    -X -v ON_ERROR_STOP=1 -U postgres -d postgres \
    -c "
      CREATE ROLE neondb_owner LOGIN INHERIT
        NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
      CREATE ROLE cortex_migrator LOGIN INHERIT
        NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
      CREATE ROLE cortex_runtime LOGIN INHERIT
        NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
      CREATE ROLE unexpected_member NOLOGIN;
      CREATE ROLE unexpected_parent NOLOGIN;
      GRANT cortex_migrator TO neondb_owner
        WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;
    " >/dev/null
  docker exec "$pg18_container" createdb \
    -U postgres \
    -O neondb_owner \
    StaviasCortex
  docker exec "$pg18_container" psql \
    -X -v ON_ERROR_STOP=1 -U postgres -d StaviasCortex \
    -c "
      GRANT CONNECT, CREATE
        ON DATABASE \"StaviasCortex\" TO cortex_migrator;
      GRANT CREATE ON SCHEMA public TO cortex_migrator;
    " >/dev/null
  docker exec "$pg18_container" psql \
    -X -v ON_ERROR_STOP=1 -U cortex_migrator -d StaviasCortex \
    -c "
      ALTER DEFAULT PRIVILEGES
        REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
      ALTER DEFAULT PRIVILEGES
        REVOKE USAGE ON TYPES FROM PUBLIC;
      ALTER DEFAULT PRIVILEGES IN SCHEMA public
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cortex_runtime;
      ALTER DEFAULT PRIVILEGES IN SCHEMA public
        GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO cortex_runtime;
      ALTER DEFAULT PRIVILEGES IN SCHEMA public
        GRANT EXECUTE ON FUNCTIONS TO cortex_runtime;
    " >/dev/null

  run_pg18_preflight() {
    local database_user="${1:-neondb_owner}"
    awk '
      /^WITH database_context AS \($/ { emit = 1 }
      emit { print }
      emit && /^\)::int;$/ { exit }
    ' "$script" |
      docker exec -i "$pg18_container" psql \
        -X -Atq -v ON_ERROR_STOP=1 \
        -v migrator_role=cortex_migrator \
        -U "$database_user" \
        -d StaviasCortex
  }

  assert_pg18_preflight() {
    local expected="$1"
    local scenario="$2"
    local database_user="${3:-neondb_owner}"
    local actual
    actual="$(run_pg18_preflight "$database_user")" || {
      echo "PostgreSQL 18 preflight errored for $scenario" >&2
      exit 1
    }
    [[ "$actual" == "$expected" ]] || {
      echo "PostgreSQL 18 preflight returned $actual for $scenario; expected $expected" >&2
      exit 1
    }
  }

  assert_pg18_preflight 1 safe-topology
  assert_pg18_preflight 0 wrong-current-user postgres

  docker exec "$pg18_container" psql \
    -X -v ON_ERROR_STOP=1 -U postgres -d StaviasCortex \
    -c "GRANT cortex_migrator TO neondb_owner
      WITH ADMIN TRUE, INHERIT FALSE, SET TRUE" >/dev/null
  assert_pg18_preflight 0 owner-membership-admin-option
  docker exec "$pg18_container" psql \
    -X -v ON_ERROR_STOP=1 -U postgres -d StaviasCortex \
    -c "GRANT cortex_migrator TO neondb_owner
      WITH ADMIN FALSE, INHERIT FALSE, SET TRUE" >/dev/null

  docker exec "$pg18_container" psql \
    -X -v ON_ERROR_STOP=1 -U postgres -d StaviasCortex \
    -c "GRANT cortex_migrator TO unexpected_member" >/dev/null
  assert_pg18_preflight 0 unexpected-migrator-member
  docker exec "$pg18_container" psql \
    -X -v ON_ERROR_STOP=1 -U postgres -d StaviasCortex \
    -c "REVOKE cortex_migrator FROM unexpected_member" >/dev/null

  docker exec "$pg18_container" psql \
    -X -v ON_ERROR_STOP=1 -U postgres -d StaviasCortex \
    -c "GRANT unexpected_parent TO cortex_migrator" >/dev/null
  assert_pg18_preflight 0 migrator-parent-role
  docker exec "$pg18_container" psql \
    -X -v ON_ERROR_STOP=1 -U postgres -d StaviasCortex \
    -c "REVOKE unexpected_parent FROM cortex_migrator" >/dev/null

  docker exec "$pg18_container" psql \
    -X -v ON_ERROR_STOP=1 -U postgres -d StaviasCortex \
    -c "GRANT unexpected_parent TO cortex_runtime" >/dev/null
  assert_pg18_preflight 0 runtime-parent-role
  docker exec "$pg18_container" psql \
    -X -v ON_ERROR_STOP=1 -U postgres -d StaviasCortex \
    -c "REVOKE unexpected_parent FROM cortex_runtime" >/dev/null

  docker exec "$pg18_container" psql \
    -X -v ON_ERROR_STOP=1 -U postgres -d StaviasCortex \
    -c "GRANT cortex_runtime TO unexpected_member" >/dev/null
  assert_pg18_preflight 0 unexpected-runtime-member
  docker exec "$pg18_container" psql \
    -X -v ON_ERROR_STOP=1 -U postgres -d StaviasCortex \
    -c "REVOKE cortex_runtime FROM unexpected_member" >/dev/null

  docker exec "$pg18_container" psql \
    -X -v ON_ERROR_STOP=1 -U postgres -d StaviasCortex \
    -c "ALTER ROLE cortex_migrator CREATEDB" >/dev/null
  assert_pg18_preflight 0 unsafe-migrator-attributes
  docker exec "$pg18_container" psql \
    -X -v ON_ERROR_STOP=1 -U postgres -d StaviasCortex \
    -c "ALTER ROLE cortex_migrator NOCREATEDB" >/dev/null

  docker exec "$pg18_container" psql \
    -X -v ON_ERROR_STOP=1 -U postgres -d StaviasCortex \
    -c "ALTER ROLE cortex_runtime BYPASSRLS" >/dev/null
  assert_pg18_preflight 0 unsafe-runtime-attributes
  docker exec "$pg18_container" psql \
    -X -v ON_ERROR_STOP=1 -U postgres -d StaviasCortex \
    -c "ALTER ROLE cortex_runtime NOBYPASSRLS" >/dev/null

  docker exec "$pg18_container" psql \
    -X -v ON_ERROR_STOP=1 -U cortex_migrator -d StaviasCortex \
    -c "ALTER DEFAULT PRIVILEGES IN SCHEMA public
      GRANT TRUNCATE ON TABLES TO cortex_runtime" >/dev/null
  assert_pg18_preflight 0 extra-runtime-default-privilege
  docker exec "$pg18_container" psql \
    -X -v ON_ERROR_STOP=1 -U cortex_migrator -d StaviasCortex \
    -c "ALTER DEFAULT PRIVILEGES IN SCHEMA public
      REVOKE TRUNCATE ON TABLES FROM cortex_runtime" >/dev/null

  docker exec "$pg18_container" psql \
    -X -v ON_ERROR_STOP=1 -U cortex_migrator -d StaviasCortex \
    -c "ALTER DEFAULT PRIVILEGES IN SCHEMA public
      GRANT SELECT ON TABLES TO unexpected_member" >/dev/null
  assert_pg18_preflight 0 third-party-default-grant
  docker exec "$pg18_container" psql \
    -X -v ON_ERROR_STOP=1 -U cortex_migrator -d StaviasCortex \
    -c "ALTER DEFAULT PRIVILEGES IN SCHEMA public
      REVOKE SELECT ON TABLES FROM unexpected_member" >/dev/null

  docker exec "$pg18_container" psql \
    -X -v ON_ERROR_STOP=1 -U cortex_migrator -d StaviasCortex \
    -c "ALTER DEFAULT PRIVILEGES
      GRANT EXECUTE ON FUNCTIONS TO PUBLIC" >/dev/null
  assert_pg18_preflight 0 public-function-default-grant
  docker exec "$pg18_container" psql \
    -X -v ON_ERROR_STOP=1 -U cortex_migrator -d StaviasCortex \
    -c "ALTER DEFAULT PRIVILEGES
      REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC" >/dev/null

  docker exec "$pg18_container" psql \
    -X -v ON_ERROR_STOP=1 -U cortex_migrator -d StaviasCortex \
    -c "ALTER DEFAULT PRIVILEGES IN SCHEMA public
      REVOKE DELETE ON TABLES FROM cortex_runtime" >/dev/null
  assert_pg18_preflight 0 incomplete-runtime-default-allowlist
  docker exec "$pg18_container" psql \
    -X -v ON_ERROR_STOP=1 -U cortex_migrator -d StaviasCortex \
    -c "ALTER DEFAULT PRIVILEGES IN SCHEMA public
      GRANT DELETE ON TABLES TO cortex_runtime" >/dev/null

  docker exec "$pg18_container" psql \
    -X -v ON_ERROR_STOP=1 -U postgres -d StaviasCortex \
    -c "ALTER DEFAULT PRIVILEGES FOR ROLE cortex_runtime
      GRANT SELECT ON TABLES TO unexpected_member" >/dev/null
  assert_pg18_preflight 0 runtime-owned-default-acl
  docker exec "$pg18_container" psql \
    -X -v ON_ERROR_STOP=1 -U postgres -d StaviasCortex \
    -c "ALTER DEFAULT PRIVILEGES FOR ROLE cortex_runtime
      REVOKE SELECT ON TABLES FROM unexpected_member" >/dev/null

  assert_pg18_preflight 1 restored-safe-topology
fi

echo "Neon migration static and behavioral contracts passed."
