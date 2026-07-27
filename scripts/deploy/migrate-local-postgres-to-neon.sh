#!/usr/bin/env bash
set -euo pipefail
umask 077

: "${CORTEX_SOURCE_PGURI:?Set the local PostgreSQL URI}"
: "${CORTEX_NEON_ADMIN_PGURI:?Set the Neon owner URI with enforced TLS}"
: "${CORTEX_NEON_RUNTIME_PASSWORD:?Set the runtime-role password}"
: "${CORTEX_NEON_MIGRATOR_ROLE:?Set the Neon migrator role name}"
: "${CORTEX_NEON_ROLLBACK_DIR:?Set a protected rollback directory outside the checkout}"

repo_root="$(cd "$(git rev-parse --show-toplevel)" && pwd -P)"
runtime_password="$CORTEX_NEON_RUNTIME_PASSWORD"
migrator_role="$CORTEX_NEON_MIGRATOR_ROLE"

[[ "$migrator_role" =~ ^[a-z_][a-z0-9_]*$ ]] || {
  echo "Neon migrator role must be a lowercase PostgreSQL identifier." >&2
  exit 1
}

command -v python3 >/dev/null 2>&1 || {
  echo "python3 is required to parse PostgreSQL connection URIs safely." >&2
  exit 1
}

postgres_bin_dir="${CORTEX_POSTGRES_BIN_DIR:-}"
if [[ -z "$postgres_bin_dir" ]]; then
  candidate_dirs=(
    /opt/homebrew/opt/postgresql@18/bin
    /Applications/Postgres.app/Contents/Versions/18/bin
    /usr/lib/postgresql/18/bin
    /usr/pgsql-18/bin
  )
  for command_name in psql pg_dump pg_restore; do
    command_path="$(command -v "$command_name" 2>/dev/null || true)"
    if [[ -n "$command_path" ]]; then
      candidate_dirs+=("$(dirname "$command_path")")
    fi
  done

  for candidate_dir in "${candidate_dirs[@]}"; do
    if [[ -x "$candidate_dir/psql" &&
      -x "$candidate_dir/pg_dump" &&
      -x "$candidate_dir/pg_restore" ]] &&
      "$candidate_dir/psql" --version | grep -Eq 'PostgreSQL\) 18\.' &&
      "$candidate_dir/pg_dump" --version | grep -Eq 'PostgreSQL\) 18\.' &&
      "$candidate_dir/pg_restore" --version | grep -Eq 'PostgreSQL\) 18\.'; then
      postgres_bin_dir="$candidate_dir"
      break
    fi
  done
fi

for command_name in psql pg_dump pg_restore; do
  command_path="$postgres_bin_dir/$command_name"
  [[ -x "$command_path" ]] &&
    "$command_path" --version | grep -Eq 'PostgreSQL\) 18\.' || {
      echo "A coherent PostgreSQL 18 client toolset is required." >&2
      exit 1
    }
done

psql_bin="$postgres_bin_dir/psql"
pg_dump_bin="$postgres_bin_dir/pg_dump"
pg_restore_bin="$postgres_bin_dir/pg_restore"
unset command_path postgres_bin_dir

rollback_dir_input="$CORTEX_NEON_ROLLBACK_DIR"
[[ "$rollback_dir_input" = /* &&
  -d "$rollback_dir_input" &&
  ! -L "$rollback_dir_input" &&
  -w "$rollback_dir_input" ]] || {
  echo "Rollback directory must be an existing writable absolute directory." >&2
  exit 1
}
rollback_dir="$(cd "$rollback_dir_input" && pwd -P)"
case "$rollback_dir/" in
  "$repo_root/"*)
    echo "Rollback directory must remain outside the Git checkout." >&2
    exit 1
    ;;
esac
rollback_mode="$(stat -f '%Lp' "$rollback_dir" 2>/dev/null ||
  stat -c '%a' "$rollback_dir")"
[[ "$rollback_mode" =~ ^[0-7]{3,4}$ ]] &&
  (( (8#$rollback_mode & 8#77) == 0 )) || {
    echo "Rollback directory must deny group and other access." >&2
    exit 1
  }
unset rollback_dir_input rollback_mode

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/cortex-neon-migration.XXXXXX")"
work_dir="$(cd "$work_dir" && pwd -P)"
case "$work_dir/" in
  "$repo_root/"*)
    rmdir "$work_dir"
    echo "Temporary migration directory must remain outside the Git checkout." >&2
    exit 1
    ;;
esac
service_file="$work_dir/pg_service.conf"
pass_file="$work_dir/pgpass"
snapshot_fifo="$work_dir/source-snapshot.fifo"
snapshot_output="$work_dir/source-snapshot.out"
dump_partial=""
snapshot_holder_pid=""
snapshot_writer_open=false

release_snapshot() {
  if [[ "$snapshot_writer_open" == "true" ]]; then
    printf 'ROLLBACK;\n\\q\n' >&9 2>/dev/null || true
    exec 9>&-
    snapshot_writer_open=false
  fi
  if [[ -n "$snapshot_holder_pid" ]]; then
    if kill -0 "$snapshot_holder_pid" 2>/dev/null; then
      wait "$snapshot_holder_pid" 2>/dev/null || true
    else
      wait "$snapshot_holder_pid" 2>/dev/null || true
    fi
    snapshot_holder_pid=""
  fi
}

cleanup() {
  release_snapshot
  if [[ -n "$dump_partial" && -f "$dump_partial" ]]; then
    rm -f "$dump_partial"
  fi
  find "$work_dir" -type f -delete 2>/dev/null || true
  find "$work_dir" -type p -delete 2>/dev/null || true
  rmdir "$work_dir" 2>/dev/null || true
  unset runtime_password
}
trap cleanup EXIT

python3 - "$service_file" "$pass_file" <<'PY'
import os
import re
import sys
from urllib.parse import parse_qsl, unquote, urlsplit


class ContractError(Exception):
    pass


ALLOWED_OPTIONS = {
    "application_name",
    "channel_binding",
    "client_encoding",
    "connect_timeout",
    "fallback_application_name",
    "gssencmode",
    "gsslib",
    "keepalives",
    "keepalives_count",
    "keepalives_idle",
    "keepalives_interval",
    "krbsrvname",
    "load_balance_hosts",
    "options",
    "replication",
    "requirepeer",
    "sslcert",
    "sslcertmode",
    "sslcompression",
    "sslcrl",
    "sslcrldir",
    "sslkey",
    "ssl_max_protocol_version",
    "ssl_min_protocol_version",
    "sslmode",
    "sslnegotiation",
    "sslrootcert",
    "sslsni",
    "target_session_attrs",
    "tcp_user_timeout",
}


def safe_text(value, message):
    if not value or "\x00" in value or "\r" in value or "\n" in value:
        raise ContractError(message)
    return value


def parse_uri(label, raw_uri, neon=False):
    try:
        parsed = urlsplit(raw_uri)
        port = parsed.port or 5432
    except ValueError as exc:
        raise ContractError(f"{label} PostgreSQL URI is invalid.") from exc

    if parsed.scheme not in {"postgres", "postgresql"}:
        raise ContractError(f"{label} PostgreSQL URI is invalid.")
    if parsed.fragment:
        raise ContractError(f"{label} PostgreSQL URI is invalid.")

    host = safe_text(parsed.hostname or "", f"{label} PostgreSQL URI is invalid.")
    user = safe_text(
        unquote(parsed.username or ""),
        f"{label} PostgreSQL URI is invalid.",
    )
    database = safe_text(
        unquote(parsed.path[1:] if parsed.path.startswith("/") else ""),
        f"{label} PostgreSQL URI is invalid.",
    )
    password = unquote(parsed.password or "")  # environment placeholder, never a literal
    if any(character in password for character in "\x00\r\n"):
        raise ContractError(f"{label} PostgreSQL URI is invalid.")

    try:
        pairs = parse_qsl(
            parsed.query,
            keep_blank_values=True,
            strict_parsing=True,
        )
    except ValueError as exc:
        raise ContractError(f"{label} PostgreSQL URI is invalid.") from exc

    options = {}
    for raw_key, value in pairs:
        key = raw_key.lower()
        if key in options:
            if neon:
                raise ContractError(
                    "Neon admin URI has duplicate query parameter."
                )
            raise ContractError(
                "Source PostgreSQL URI has duplicate query parameter."
            )
        if not re.fullmatch(r"[a-z_][a-z0-9_]*", key):
            raise ContractError(f"{label} PostgreSQL URI has unsafe option.")
        if key not in ALLOWED_OPTIONS:
            raise ContractError(f"{label} PostgreSQL URI has unsupported option.")
        if any(character in value for character in "\x00\r\n"):
            raise ContractError(f"{label} PostgreSQL URI has unsafe option.")
        options[key] = value

    if neon:
        if not host.lower().endswith(".neon.tech"):
            raise ContractError("Neon admin URI must target Neon.")
        if not password:
            raise ContractError("Neon admin URI must include owner credentials.")
        if options.get("sslmode") not in {"require", "verify-ca", "verify-full"}:
            raise ContractError("Neon admin URI must enforce TLS.")
        if options.get("channel_binding") not in {
            None,
            "prefer",
            "require",
        }:
            raise ContractError(
                "Neon admin URI has unsafe channel binding."
            )
        if "options" in options and not re.fullmatch(
            r"endpoint=[A-Za-z0-9._-]+",
            options["options"],
        ):
            raise ContractError("Neon admin URI has unsafe options.")

    return {
        "host": host,
        "port": str(port),
        "database": database,
        "user": user,
        "password": password,
        "options": options,
    }


def write_service(handle, name, connection, database=None):
    handle.write(f"[{name}]\n")
    values = {
        "host": connection["host"],
        "port": connection["port"],
        "dbname": database or connection["database"],
        "user": connection["user"],
        **connection["options"],
    }
    values.setdefault("connect_timeout", "10")
    for key, value in values.items():
        handle.write(f"{key}={value}\n")
    handle.write("\n")


def pgpass_value(value):
    return value.replace("\\", "\\\\").replace(":", "\\:")


def write_password(handle, connection, database=None):
    if not connection["password"]:
        return
    fields = (
        connection["host"],
        connection["port"],
        database or connection["database"],
        connection["user"],
        connection["password"],
    )
    handle.write(":".join(pgpass_value(value) for value in fields) + "\n")


try:
    source = parse_uri(
        "Source",
        os.environ["CORTEX_SOURCE_PGURI"],
    )
    neon = parse_uri(
        "Neon",
        os.environ["CORTEX_NEON_ADMIN_PGURI"],
        neon=True,
    )
    if source["database"] != "StaviasCortex":
        raise ContractError(
            "Source PostgreSQL URI must target StaviasCortex."
        )
    if source["host"].lower().endswith(".neon.tech"):
        raise ContractError(
            "Source PostgreSQL URI must remain outside Neon."
        )

    service_path, pass_path = sys.argv[1:3]
    with open(service_path, "x", encoding="utf-8") as service_handle:
        write_service(service_handle, "source", source)
        write_service(service_handle, "neon_admin", neon)
        write_service(
            service_handle,
            "neon_target",
            neon,
            database="StaviasCortex",
        )
    with open(pass_path, "x", encoding="utf-8") as pass_handle:
        write_password(pass_handle, source)
        write_password(pass_handle, neon)
        write_password(pass_handle, neon, database="StaviasCortex")
    os.chmod(service_path, 0o600)
    os.chmod(pass_path, 0o600)
except KeyError:
    print("Required PostgreSQL environment is unavailable.", file=sys.stderr)
    raise SystemExit(1)
except ContractError as error:
    print(str(error), file=sys.stderr)
    raise SystemExit(1)
PY

unset CORTEX_SOURCE_PGURI
unset CORTEX_NEON_ADMIN_PGURI
unset CORTEX_NEON_RUNTIME_PASSWORD
unset PGAPPNAME
unset PGCHANNELBINDING
unset PGCLIENTENCODING
unset PGCONNECT_TIMEOUT
unset PGDATABASE
unset PGGSSENCMODE
unset PGGSSLIB
unset PGHOST
unset PGHOSTADDR
unset PGKEEPALIVES
unset PGKEEPALIVESCOUNT
unset PGKEEPALIVESIDLE
unset PGKEEPALIVESINTERVAL
unset PGKRBSRVNAME
unset PGLOADBALANCEHOSTS
unset PGOPTIONS
unset PGPASSFILE
unset PGPASSWORD
unset PGPORT
unset PGREPLICATION
unset PGREQUIREPEER
unset PGREQUIRESSL
unset PGSERVICE
unset PGSERVICEFILE
unset PGSSLCERT
unset PGSSLCERTMODE
unset PGSSLCRL
unset PGSSLCRLDIR
unset PGSSLCOMPRESSION
unset PGSSLKEY
unset PGSSLMAXPROTOCOLVERSION
unset PGSSLMINPROTOCOLVERSION
unset PGSSLMODE
unset PGSSLNEGOTIATION
unset PGSSLROOTCERT
unset PGSSLSNI
unset PGSYSCONFDIR
unset PGTARGETSESSIONATTRS
unset PGTCPUSER_TIMEOUT
unset PGUSER
export PGSERVICEFILE="$service_file"
export PGPASSFILE="$pass_file"

if ! server_version="$(
  PGSERVICE=neon_admin "$psql_bin" \
    -X -Atq -w -v ON_ERROR_STOP=1 \
    -c "select current_setting('server_version_num')" \
    2>/dev/null
)"; then
  echo "Unable to verify the Neon PostgreSQL version." >&2
  exit 1
fi
[[ "$server_version" =~ ^18[0-9]{4}$ ]] || {
  echo "Neon target must run PostgreSQL 18." >&2
  exit 1
}

if ! PGSERVICE=neon_admin "$psql_bin" \
  -X -q -w -v ON_ERROR_STOP=1 \
  -f "$repo_root/scripts/deploy/prepare-neon-database.sql" \
  > /dev/null 2>/dev/null; then
  echo "Unable to bootstrap the canonical Neon database." >&2
  exit 1
fi

inventory_sql="
WITH user_schemas AS (
  SELECT oid, nspname
  FROM pg_namespace
  WHERE nspname <> 'information_schema'
    AND nspname !~ '^pg_'
),
user_objects AS (
  SELECT c.oid
  FROM pg_class c
  JOIN user_schemas n ON n.oid = c.relnamespace
  UNION ALL
  SELECT p.oid
  FROM pg_proc p
  JOIN user_schemas n ON n.oid = p.pronamespace
  UNION ALL
  SELECT t.oid
  FROM pg_type t
  JOIN user_schemas n ON n.oid = t.typnamespace
  WHERE NOT EXISTS (
    SELECT 1 FROM pg_class c WHERE c.reltype = t.oid
  )
  UNION ALL
  SELECT c.oid
  FROM pg_collation c
  JOIN user_schemas n ON n.oid = c.collnamespace
  UNION ALL
  SELECT c.oid
  FROM pg_conversion c
  JOIN user_schemas n ON n.oid = c.connamespace
  UNION ALL
  SELECT o.oid
  FROM pg_operator o
  JOIN user_schemas n ON n.oid = o.oprnamespace
  UNION ALL
  SELECT o.oid
  FROM pg_opclass o
  JOIN user_schemas n ON n.oid = o.opcnamespace
  UNION ALL
  SELECT o.oid
  FROM pg_opfamily o
  JOIN user_schemas n ON n.oid = o.opfnamespace
  UNION ALL
  SELECT t.oid
  FROM pg_ts_config t
  JOIN user_schemas n ON n.oid = t.cfgnamespace
  UNION ALL
  SELECT t.oid
  FROM pg_ts_dict t
  JOIN user_schemas n ON n.oid = t.dictnamespace
  UNION ALL
  SELECT t.oid
  FROM pg_ts_parser t
  JOIN user_schemas n ON n.oid = t.prsnamespace
  UNION ALL
  SELECT t.oid
  FROM pg_ts_template t
  JOIN user_schemas n ON n.oid = t.tmplnamespace
  UNION ALL
  SELECT e.oid
  FROM pg_extension e
  JOIN user_schemas n ON n.oid = e.extnamespace
  UNION ALL
  SELECT n.oid
  FROM user_schemas n
  WHERE n.nspname <> 'public'
)
SELECT count(*) FROM user_objects"
if ! target_objects="$(
  PGSERVICE=neon_target "$psql_bin" \
    -X -Atq -w -v ON_ERROR_STOP=1 \
    -c "$inventory_sql" \
    2>/dev/null
)"; then
  echo "Unable to inventory the target database." >&2
  exit 1
fi
[[ "$target_objects" == "0" ]] || {
  echo "Target StaviasCortex database is not empty; migration stopped." >&2
  exit 1
}
unset inventory_sql target_objects

dump_file="$rollback_dir/StaviasCortex-pre-neon.dump"
[[ ! -e "$dump_file" && ! -L "$dump_file" ]] || {
  echo "Protected rollback dump already exists; migration stopped." >&2
  exit 1
}

if ! migrator_ready="$(
  PGSERVICE=neon_target "$psql_bin" \
    -X -Atq -w -v ON_ERROR_STOP=1 \
    -v migrator_role="$migrator_role" \
    2>/dev/null <<'SQL'
SELECT CASE
  WHEN EXISTS (
    SELECT 1 FROM pg_roles WHERE rolname = :'migrator_role'
  )
  THEN pg_has_role(current_user, :'migrator_role', 'MEMBER')::int
  ELSE 0
END;
SQL
)"; then
  echo "Unable to verify the configured migrator role." >&2
  exit 1
fi
[[ "$migrator_ready" == "1" ]] || {
  echo "Neon owner must be a member of the configured migrator role." >&2
  exit 1
}
unset migrator_ready

mkfifo "$snapshot_fifo"
PGSERVICE=source "$psql_bin" \
  -X -Atq -w -v ON_ERROR_STOP=1 \
  < "$snapshot_fifo" \
  > "$snapshot_output" \
  2>/dev/null &
snapshot_holder_pid=$!
exec 9>"$snapshot_fifo"
snapshot_writer_open=true
printf '%s\n' \
  'BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;' \
  'SELECT pg_export_snapshot();' \
  >&9

source_snapshot=""
snapshot_attempt=0
while (( snapshot_attempt < 200 )); do
  if [[ -s "$snapshot_output" ]]; then
    IFS= read -r source_snapshot < "$snapshot_output"
    break
  fi
  if ! kill -0 "$snapshot_holder_pid" 2>/dev/null; then
    break
  fi
  sleep 0.05
  ((snapshot_attempt += 1))
done
[[ "$source_snapshot" =~ ^[0-9A-Fa-f]+-[0-9A-Fa-f]+-[0-9A-Fa-f]+$ ]] || {
  echo "Unable to establish a shared source snapshot." >&2
  exit 1
}

dump_partial="$(mktemp "$rollback_dir/.StaviasCortex-pre-neon.dump.XXXXXX")"
if ! PGSERVICE=source "$pg_dump_bin" \
  --no-password \
  --snapshot="$source_snapshot" \
  --format=custom \
  --no-owner \
  --no-acl \
  --file="$dump_partial" \
  2>/dev/null; then
  echo "PostgreSQL source dump failed." >&2
  exit 1
fi
[[ -s "$dump_partial" ]] || {
  echo "PostgreSQL dump is empty; migration stopped." >&2
  exit 1
}
if ! "$pg_restore_bin" \
  --list \
  "$dump_partial" \
  > /dev/null 2>/dev/null; then
  echo "PostgreSQL dump is unreadable; migration stopped." >&2
  exit 1
fi
chmod 600 "$dump_partial"
mv "$dump_partial" "$dump_file"
dump_partial=""

write_count_query() {
  cat <<'SQL'
SELECT table_name || '|' || row_count
FROM (
  SELECT 1 AS ordinal, 'auth_identity' AS table_name,
    count(*) AS row_count FROM public.auth_identity
  UNION ALL
  SELECT 2, 'auth_capacidade_administrativa',
    count(*) FROM public.auth_capacidade_administrativa
  UNION ALL
  SELECT 3, 'colaborador', count(*) FROM public.colaborador
  UNION ALL
  SELECT 4, 'obra', count(*) FROM public.obra
  UNION ALL
  SELECT 5, 'rdo', count(*) FROM public.rdo
  UNION ALL
  SELECT 6, 'rdo_mao_obra', count(*) FROM public.rdo_mao_obra
  UNION ALL
  SELECT 7, 'vinculo_colaborador_obra',
    count(*) FROM public.vinculo_colaborador_obra
  UNION ALL
  SELECT 8, 'cortex_evento_operacional',
    count(*) FROM public.cortex_evento_operacional
  UNION ALL
  SELECT 9, 'finance_lancamento',
    count(*) FROM public.finance_lancamento
  UNION ALL
  SELECT 10, 'stored_object', count(*) FROM public.stored_object
) AS core_counts
ORDER BY ordinal;
SQL
}

source_counts_file="$work_dir/source-counts"
if ! {
  printf '%s\n' \
    'BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;' \
    "SET TRANSACTION SNAPSHOT :'source_snapshot';"
  write_count_query
  printf '%s\n' 'COMMIT;'
} | PGSERVICE=source "$psql_bin" \
  -X -Atq -w -v ON_ERROR_STOP=1 \
  -v source_snapshot="$source_snapshot" \
  > "$source_counts_file" 2>/dev/null; then
  echo "Unable to count source tables in the shared snapshot." >&2
  exit 1
fi
release_snapshot

if ! PGSERVICE=neon_target "$pg_restore_bin" \
  --dbname=service=neon_target \
  --no-password \
  --exit-on-error \
  --single-transaction \
  --no-owner \
  --no-acl \
  "$dump_file" \
  > /dev/null 2>/dev/null; then
  echo "Neon restore failed atomically; the source and rollback dump remain preserved." >&2
  exit 1
fi

if ! CORTEX_RUNTIME_PASSWORD_INPUT="$runtime_password" \
  PGSERVICE=neon_target "$psql_bin" \
  -X -q -w -v ON_ERROR_STOP=1 \
  -v migrator_role="$migrator_role" \
  > /dev/null 2>/dev/null <<'SQL'
\getenv runtime_password CORTEX_RUNTIME_PASSWORD_INPUT
BEGIN;
SELECT format(
  'CREATE ROLE cortex_runtime LOGIN PASSWORD %L',
  :'runtime_password'
)
WHERE NOT EXISTS (
  SELECT 1 FROM pg_roles WHERE rolname = 'cortex_runtime'
)
\gexec
ALTER ROLE cortex_runtime WITH
  LOGIN INHERIT PASSWORD :'runtime_password'; -- environment placeholder
DO $runtime_role_guard$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_roles
    WHERE rolname = 'cortex_runtime'
      AND rolcanlogin
      AND rolinherit
      AND NOT rolsuper
      AND NOT rolcreatedb
      AND NOT rolcreaterole
      AND NOT rolreplication
      AND NOT rolbypassrls
  ) THEN
    RAISE EXCEPTION 'cortex_runtime has unsafe role attributes';
  END IF;
END
$runtime_role_guard$;
GRANT CONNECT ON DATABASE "StaviasCortex" TO cortex_runtime;
GRANT USAGE ON SCHEMA public TO cortex_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE
  ON ALL TABLES IN SCHEMA public TO cortex_runtime;
GRANT USAGE, SELECT, UPDATE
  ON ALL SEQUENCES IN SCHEMA public TO cortex_runtime;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO cortex_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_role" IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cortex_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_role" IN SCHEMA public
  GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO cortex_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_role" IN SCHEMA public
  GRANT EXECUTE ON FUNCTIONS TO cortex_runtime;
COMMIT;
SQL
then
  echo "Runtime role/grant transaction failed; target requires controlled reprovisioning." >&2
  exit 1
fi
unset runtime_password

target_counts_file="$work_dir/target-counts"
if ! write_count_query | PGSERVICE=neon_target "$psql_bin" \
  -X -Atq -w -v ON_ERROR_STOP=1 \
  > "$target_counts_file" 2>/dev/null; then
  echo "Unable to count restored target tables." >&2
  exit 1
fi

core_tables=(
  auth_identity
  auth_capacidade_administrativa
  colaborador
  obra
  rdo
  rdo_mao_obra
  vinculo_colaborador_obra
  cortex_evento_operacional
  finance_lancamento
  stored_object
)

exec 3< "$source_counts_file"
exec 4< "$target_counts_file"
for table_name in "${core_tables[@]}"; do
  IFS='|' read -r source_table source_count <&3 || {
    echo "Source count manifest is incomplete." >&2
    exit 1
  }
  IFS='|' read -r target_table target_count <&4 || {
    echo "Target count manifest is incomplete." >&2
    exit 1
  }
  [[ "$source_table" == "$table_name" &&
    "$target_table" == "$table_name" &&
    "$source_count" =~ ^[0-9]+$ &&
    "$target_count" =~ ^[0-9]+$ ]] || {
    echo "A core-table count manifest is invalid." >&2
    exit 1
  }

  printf '%s|%s|%s\n' "$table_name" "$source_count" "$target_count"
  [[ "$source_count" == "$target_count" ]] || exit 1
done
if IFS= read -r _ <&3 || IFS= read -r _ <&4; then
  echo "A core-table count manifest has unexpected rows." >&2
  exit 1
fi
exec 3<&-
exec 4<&-
