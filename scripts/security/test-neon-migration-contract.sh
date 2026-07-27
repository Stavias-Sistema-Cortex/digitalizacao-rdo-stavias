#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
script="$repo_root/scripts/deploy/migrate-local-postgres-to-neon.sh"
sql="$repo_root/scripts/deploy/prepare-neon-database.sql"

[[ -x "$script" ]] || {
  echo "Neon migration script is missing or not executable" >&2
  exit 1
}
[[ -f "$sql" ]] || {
  echo "Neon database preparation SQL is missing" >&2
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
  grep -Fq 'FOR ROLE' "$script"
  grep -Fq 'NOSUPERUSER NOCREATEDB NOCREATEROLE' "$script"
  grep -Fq 'NOREPLICATION NOBYPASSRLS' "$script"
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
cleanup() {
  rm -rf "$fixture_root"
}
trap cleanup EXIT

fake_bin="$fixture_root/bin"
mkdir -p "$fake_bin"

cat > "$fake_bin/pg-tool" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail

tool_name="${0##*/}"

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
service_mode="$(stat -f '%Lp' "$PGSERVICEFILE" 2>/dev/null ||
  stat -c '%a' "$PGSERVICEFILE")"
pass_mode="$(stat -f '%Lp' "$PGPASSFILE" 2>/dev/null ||
  stat -c '%a' "$PGPASSFILE")"
[[ "$service_mode" == "600" && "$pass_mode" == "600" ]] ||
  mark_failure unsafe-connection-file-mode

if [[ ! -f "$FAKE_CAPTURE/service-checked" ]]; then
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

    if [[ -n "$query" && "$query" == *"pg_has_role"* ]]; then
      [[ "${PGSERVICE:-}" == "neon_target" ]] ||
        mark_failure migrator-check-wrong-service
      log_operation migrator_check
      printf '%s\n' "${FAKE_MIGRATOR_READY:-1}"
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
      if [[ "$payload" == *"ALTER ROLE cortex_runtime"* ]]; then
        [[ "${CORTEX_RUNTIME_PASSWORD_INPUT:-}" == "$FIXTURE_RUNTIME_PASSWORD" ]] ||
          mark_failure missing-runtime-password-environment
        [[ "$payload" == *"BEGIN;"* && "$payload" == *"COMMIT;"* &&
          "$payload" == *"NOSUPERUSER NOCREATEDB NOCREATEROLE"* &&
          "$payload" == *"NOREPLICATION NOBYPASSRLS"* &&
          "$payload" == *"ALTER DEFAULT PRIVILEGES FOR ROLE"* ]] ||
          mark_failure unsafe-role-grant-transaction
        log_operation role_grants
        [[ "${FAKE_FAIL_ROLE_GRANTS:-false}" != "true" ]] || exit 91
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
admin_uri="postgresql://${fixture_user}:${fixture_password}@ep-contract.us-east-2.aws.neon.tech:5432/admin?sslmode=require&channel_binding=require&sslrootcert=%2Ftmp%2Ffixture-ca.pem&options=endpoint%3Dfixture-endpoint"

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
    CORTEX_NEON_MIGRATOR_ROLE=cortex_migrator \
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
    FAKE_FAIL_ROLE_GRANTS="${FAKE_FAIL_ROLE_GRANTS:-false}" \
    FAKE_MISMATCH_TABLE="${FAKE_MISMATCH_TABLE:-}" \
    FAKE_MIGRATOR_READY="${FAKE_MIGRATOR_READY:-1}" \
    bash "$script"
}

prepare_case duplicate-tls
CASE_ADMIN_URI="${admin_uri}&sslmode=disable"
set +e
duplicate_output="$(run_case 2>&1)"
duplicate_status=$?
set -e
unset CASE_ADMIN_URI
[[ $duplicate_status -ne 0 &&
  "$duplicate_output" == "Neon admin URI has duplicate query parameter." &&
  ! -s "$operation_log" ]] || {
  echo "Migration contract did not reject duplicate TLS parameters before PostgreSQL" >&2
  exit 1
}
assert_redacted "$duplicate_output"

prepare_case weak-tls
CASE_ADMIN_URI="${admin_uri/sslmode=require/sslmode=prefer}"
set +e
weak_tls_output="$(run_case 2>&1)"
weak_tls_status=$?
set -e
unset CASE_ADMIN_URI
[[ $weak_tls_status -ne 0 &&
  "$weak_tls_output" == "Neon admin URI must enforce TLS." &&
  ! -s "$operation_log" ]] || {
  echo "Migration contract did not reject an ineffective TLS mode" >&2
  exit 1
}
assert_redacted "$weak_tls_output"

prepare_case weak-channel-binding
CASE_ADMIN_URI="${admin_uri/channel_binding=require/channel_binding=disable}"
set +e
weak_binding_output="$(run_case 2>&1)"
weak_binding_status=$?
set -e
unset CASE_ADMIN_URI
[[ $weak_binding_status -ne 0 &&
  "$weak_binding_output" == "Neon admin URI has unsafe channel binding." &&
  ! -s "$operation_log" ]] || {
  echo "Migration contract did not reject disabled channel binding" >&2
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

prepare_case migrator-unavailable
FAKE_MIGRATOR_READY=0
set +e
migrator_output="$(run_case 2>&1)"
migrator_status=$?
set -e
unset FAKE_MIGRATOR_READY
migrator_gate_operations="${pre_gate_operations}migrator_check "
[[ $migrator_status -ne 0 &&
  "$migrator_output" == "Neon owner must be a member of the configured migrator role." &&
  "$(tr '\n' ' ' < "$operation_log")" == "$migrator_gate_operations" ]] || {
  echo "Migration contract did not preflight migrator-role authority" >&2
  exit 1
}
assert_redacted "$migrator_output"

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

prepare_case grant-failure
FAKE_FAIL_ROLE_GRANTS=true
set +e
grant_output="$(run_case 2>&1)"
grant_status=$?
set -e
unset FAKE_FAIL_ROLE_GRANTS
[[ $grant_status -ne 0 && -f "$capture_dir/target-populated" ]] || {
  echo "Migration contract did not expose a post-restore grant failure" >&2
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
expected_operations="version_check bootstrap empty_gate migrator_check snapshot_open dump dump_list source_counts restore role_grants target_counts "
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
rollback_mode="$(stat -f '%Lp' "$rollback_dir/StaviasCortex-pre-neon.dump" 2>/dev/null ||
  stat -c '%a' "$rollback_dir/StaviasCortex-pre-neon.dump")"
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
  incomplete-empty-gate \
  counts-without-shared-snapshot \
  unsafe-role-grant-transaction; do
  [[ ! -e "$capture_dir/$failure_marker" ]] || {
    echo "Migration contract observed unsafe behavior: $failure_marker" >&2
    exit 1
  }
done

echo "Neon migration static and behavioral contracts passed."
