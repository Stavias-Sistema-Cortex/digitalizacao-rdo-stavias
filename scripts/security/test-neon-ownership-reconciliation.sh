#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
sql="$repo_root/scripts/deploy/reconcile-neon-ownership.sql"
container_name="cortex-neon-ownership-contract-$$"
database_name="StaviasCortex"
fixture_credential="contract-only"

[[ -f "$sql" ]] || {
  echo "Neon ownership reconciliation SQL is missing." >&2
  exit 1
}

if grep -Eqi 'PASSWORD|CREATE ROLE|ALTER ROLE|REASSIGN OWNED|ALTER DATABASE .* OWNER' \
  "$sql"; then
  echo "Neon ownership reconciliation contains credential or broad-owner mutation logic." >&2
  exit 1
fi

for contract in \
  'BEGIN;' \
  'current_database() <> '\''StaviasCortex'\''' \
  'current_user <> database_owner' \
  'm.member = current_user::regrole' \
  'm.set_option' \
  'm.member = '\''cortex_runtime'\''::regrole' \
  'm.member = '\''cortex_migrator'\''::regrole' \
  'prosecdef' \
  'deptype = '\''e'\''' \
  'ALTER SCHEMA public OWNER TO cortex_migrator' \
  'GRANT CONNECT, CREATE' \
  'ON DATABASE "StaviasCortex" TO cortex_migrator' \
  'ALTER DEFAULT PRIVILEGES FOR ROLE cortex_migrator' \
  'cortex_release_marker' \
  'COMMIT;'; do
  grep -Fq "$contract" "$sql" || {
    echo "Neon ownership reconciliation is missing a fail-closed contract." >&2
    exit 1
  }
done

cleanup() {
  docker rm -f -v "$container_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker run --detach \
  --name "$container_name" \
  --env "POSTGRES_PASSWORD=$fixture_credential" \
  postgres:18-alpine >/dev/null

postgres_ready=false
for _ in $(seq 1 60); do
  if [[ "$(docker logs "$container_name" 2>&1)" == \
      *"PostgreSQL init process complete; ready for start up."* ]] &&
    docker exec "$container_name" pg_isready -U postgres >/dev/null 2>&1; then
    postgres_ready=true
    break
  fi
  sleep 1
done
[[ "$postgres_ready" == "true" ]] || {
  echo "PostgreSQL 18 ownership contract did not become ready." >&2
  exit 1
}

docker exec "$container_name" psql \
  -U postgres \
  -d postgres \
  -v ON_ERROR_STOP=1 \
  -c "
    CREATE ROLE neondb_owner LOGIN CREATEROLE CREATEDB;
    CREATE ROLE cortex_migrator LOGIN
      NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    CREATE ROLE cortex_runtime LOGIN
      NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    CREATE ROLE cortex_runtime_delegate NOLOGIN;
    GRANT cortex_migrator TO neondb_owner
      WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;
  " >/dev/null
docker exec "$container_name" createdb \
  -U postgres \
  -O neondb_owner \
  "$database_name"

docker exec "$container_name" psql \
  -U neondb_owner \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    CREATE SCHEMA contract_extension;
    CREATE EXTENSION pg_trgm WITH SCHEMA contract_extension;
    CREATE TABLE public.contract_row (
      id bigserial PRIMARY KEY,
      payload text NOT NULL
    );
    INSERT INTO public.contract_row(payload) VALUES ('preserved');
    CREATE TABLE public.flyway_schema_history (
      installed_rank integer PRIMARY KEY,
      version varchar(50),
      success boolean NOT NULL
    );
    INSERT INTO public.flyway_schema_history(
      installed_rank,
      version,
      success
    ) VALUES (1, '1', true);
    CREATE TABLE public.cortex_release_marker (
      revision varchar(40) PRIMARY KEY,
      marker varchar(43) NOT NULL
    );
    INSERT INTO public.cortex_release_marker(revision, marker)
    VALUES (
      repeat('a', 40),
      repeat('b', 43)
    );
    CREATE VIEW public.contract_view AS
      SELECT id, payload FROM public.contract_row;
    CREATE MATERIALIZED VIEW public.contract_materialized AS
      SELECT count(*) AS total FROM public.contract_row;
    CREATE TABLE public.contract_partitioned (
      id bigint,
      happened_on date
    ) PARTITION BY RANGE (happened_on);
    CREATE TABLE public.contract_partition_2026
      PARTITION OF public.contract_partitioned
      FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
    CREATE FUNCTION public.contract_echo(value text)
    RETURNS text
    LANGUAGE sql
    IMMUTABLE
    AS 'SELECT value';
    CREATE PROCEDURE public.contract_noop()
    LANGUAGE sql
    AS 'SELECT 1';
    CREATE FUNCTION public.contract_sum_state(state bigint, value bigint)
    RETURNS bigint
    LANGUAGE sql
    IMMUTABLE
    AS 'SELECT coalesce(state, 0) + coalesce(value, 0)';
    CREATE AGGREGATE public.contract_sum(bigint) (
      SFUNC = public.contract_sum_state,
      STYPE = bigint,
      INITCOND = '0'
    );
    CREATE STATISTICS public.contract_row_dependencies (dependencies)
      ON id, payload
      FROM public.contract_row;
    REVOKE EXECUTE
      ON ROUTINE public.contract_echo(text)
      FROM PUBLIC;
    REVOKE EXECUTE
      ON ROUTINE public.contract_noop()
      FROM PUBLIC;
    REVOKE EXECUTE
      ON ROUTINE public.contract_sum_state(bigint, bigint)
      FROM PUBLIC;
    REVOKE EXECUTE
      ON ROUTINE public.contract_sum(bigint)
      FROM PUBLIC;
    GRANT CREATE, TEMPORARY
      ON DATABASE \"StaviasCortex\"
      TO cortex_runtime WITH GRANT OPTION;
    GRANT CREATE
      ON SCHEMA public
      TO cortex_runtime WITH GRANT OPTION;
    GRANT SELECT, INSERT, UPDATE, DELETE,
      TRUNCATE, REFERENCES, TRIGGER, MAINTAIN
      ON TABLE public.contract_row
      TO cortex_runtime WITH GRANT OPTION;
    GRANT SELECT, INSERT, UPDATE, DELETE,
      TRUNCATE, REFERENCES, TRIGGER, MAINTAIN
      ON TABLE public.cortex_release_marker
      TO cortex_runtime WITH GRANT OPTION;
    GRANT TRUNCATE, REFERENCES, TRIGGER, MAINTAIN
      ON TABLE public.contract_row
      TO PUBLIC;
    GRANT UPDATE (payload), REFERENCES (id)
      ON TABLE public.contract_row
      TO cortex_runtime WITH GRANT OPTION;
    GRANT REFERENCES (id)
      ON TABLE public.contract_row
      TO PUBLIC;
    GRANT ALL PRIVILEGES
      ON SEQUENCE public.contract_row_id_seq
      TO cortex_runtime WITH GRANT OPTION;
    GRANT EXECUTE
      ON ROUTINE public.contract_echo(text)
      TO cortex_runtime WITH GRANT OPTION;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public
      GRANT SELECT, INSERT, UPDATE, DELETE
      ON TABLES TO cortex_runtime;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public
      GRANT USAGE, SELECT, UPDATE
      ON SEQUENCES TO cortex_runtime;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public
      GRANT EXECUTE
      ON FUNCTIONS TO cortex_runtime;
    ALTER DEFAULT PRIVILEGES
      GRANT CREATE
      ON SCHEMAS TO cortex_runtime WITH GRANT OPTION;
    SET ROLE cortex_migrator;
    ALTER DEFAULT PRIVILEGES
      GRANT ALL PRIVILEGES ON TABLES
      TO cortex_runtime WITH GRANT OPTION;
    ALTER DEFAULT PRIVILEGES
      GRANT ALL PRIVILEGES ON SEQUENCES
      TO cortex_runtime WITH GRANT OPTION;
    ALTER DEFAULT PRIVILEGES
      GRANT EXECUTE ON FUNCTIONS
      TO cortex_runtime WITH GRANT OPTION;
    ALTER DEFAULT PRIVILEGES
      GRANT CREATE ON SCHEMAS
      TO cortex_runtime WITH GRANT OPTION;
    ALTER DEFAULT PRIVILEGES
      GRANT USAGE ON TYPES
      TO cortex_runtime WITH GRANT OPTION;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public
      GRANT USAGE ON TYPES
      TO cortex_runtime WITH GRANT OPTION;
    ALTER DEFAULT PRIVILEGES
      GRANT SELECT, UPDATE ON LARGE OBJECTS
      TO cortex_runtime WITH GRANT OPTION;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public
      GRANT ALL PRIVILEGES ON TABLES
      TO cortex_runtime WITH GRANT OPTION;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public
      GRANT ALL PRIVILEGES ON SEQUENCES
      TO cortex_runtime WITH GRANT OPTION;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public
      GRANT EXECUTE ON FUNCTIONS
      TO cortex_runtime WITH GRANT OPTION;
    ALTER DEFAULT PRIVILEGES
      GRANT ALL PRIVILEGES ON TABLES TO PUBLIC;
    ALTER DEFAULT PRIVILEGES
      GRANT ALL PRIVILEGES ON SEQUENCES TO PUBLIC;
    ALTER DEFAULT PRIVILEGES
      GRANT CREATE ON SCHEMAS TO PUBLIC;
    ALTER DEFAULT PRIVILEGES
      GRANT USAGE ON TYPES TO PUBLIC;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public
      GRANT USAGE ON TYPES TO PUBLIC;
    ALTER DEFAULT PRIVILEGES
      GRANT SELECT, UPDATE ON LARGE OBJECTS TO PUBLIC;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public
      GRANT ALL PRIVILEGES ON TABLES TO PUBLIC;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public
      GRANT ALL PRIVILEGES ON SEQUENCES TO PUBLIC;
    RESET ROLE;
  " >/dev/null

docker exec "$container_name" psql \
  -U cortex_runtime \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    GRANT CREATE, TEMPORARY
      ON DATABASE \"StaviasCortex\"
      TO cortex_runtime_delegate;
    GRANT CREATE
      ON SCHEMA public
      TO cortex_runtime_delegate;
    GRANT TRUNCATE
      ON TABLE public.contract_row
      TO cortex_runtime_delegate;
  " >/dev/null

extension_fingerprint() {
  docker exec "$container_name" psql \
    -U postgres \
    -d "$database_name" \
    -Atq \
    -v ON_ERROR_STOP=1 \
    -c "
      WITH members AS (
        SELECT d.classid, d.objid, d.objsubid
        FROM pg_depend d
        JOIN pg_extension e
          ON e.oid = d.refobjid
          AND d.refclassid = 'pg_extension'::regclass
        WHERE e.extname = 'pg_trgm'
          AND d.deptype = 'e'
      )
      SELECT item
      FROM (
        SELECT
          'member|' || m.classid || '|' || m.objid || '|' || m.objsubid
            || '|' || pg_describe_object(
              m.classid,
              m.objid,
              m.objsubid
            ) AS item
        FROM members m
        UNION ALL
        SELECT
          'schema|' || n.oid || '|' || n.nspowner || '|'
            || coalesce(n.nspacl::text, '')
        FROM members m
        JOIN pg_namespace n
          ON m.classid = 'pg_namespace'::regclass
          AND n.oid = m.objid
        UNION ALL
        SELECT
          'relation|' || c.oid || '|' || c.relowner || '|'
            || coalesce(c.relacl::text, '')
        FROM members m
        JOIN pg_class c
          ON m.classid = 'pg_class'::regclass
          AND c.oid = m.objid
        UNION ALL
        SELECT
          'routine|' || p.oid || '|' || p.proowner || '|'
            || coalesce(p.proacl::text, '')
        FROM members m
        JOIN pg_proc p
          ON m.classid = 'pg_proc'::regclass
          AND p.oid = m.objid
        UNION ALL
        SELECT
          'statistics|' || s.oid || '|' || s.stxowner
        FROM members m
        JOIN pg_statistic_ext s
          ON m.classid = 'pg_statistic_ext'::regclass
          AND s.oid = m.objid
        UNION ALL
        SELECT
          'type|' || t.oid || '|' || t.typowner || '|'
            || coalesce(t.typacl::text, '')
        FROM members m
        JOIN pg_type t
          ON m.classid = 'pg_type'::regclass
          AND t.oid = m.objid
      ) extension_state
      ORDER BY item;
    "
}
extension_fingerprint_before="$(extension_fingerprint)"

docker cp "$sql" "$container_name:/tmp/reconcile-neon-ownership.sql"
reconciliation_fingerprint() {
  docker exec "$container_name" psql \
    -U postgres \
    -d "$database_name" \
    -Atq \
    -v ON_ERROR_STOP=1 \
    -c "
      SELECT item
      FROM (
        SELECT
          'database|' || d.oid || '|' || coalesce(d.datacl::text, '')
            AS item
        FROM pg_database d
        WHERE d.datname = current_database()
        UNION ALL
        SELECT
          'schema|' || n.oid || '|' || n.nspowner || '|'
            || coalesce(n.nspacl::text, '')
        FROM pg_namespace n
        WHERE n.nspname = 'public'
        UNION ALL
        SELECT
          'membership|' || m.roleid || '|' || m.member || '|' || m.grantor
            || '|' || m.admin_option || '|' || m.inherit_option
            || '|' || m.set_option
        FROM pg_auth_members m
        WHERE m.roleid IN (
          'cortex_migrator'::regrole,
          'cortex_runtime'::regrole
        )
          OR m.member IN (
            'cortex_migrator'::regrole,
            'cortex_runtime'::regrole,
            'neondb_owner'::regrole
          )
        UNION ALL
        SELECT
          'relation|' || c.oid || '|' || c.relowner || '|'
            || coalesce(c.relacl::text, '')
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public'
        UNION ALL
        SELECT
          'column|' || a.attrelid || '|' || a.attnum || '|'
            || coalesce(a.attacl::text, '')
        FROM pg_attribute a
        JOIN pg_class c ON c.oid = a.attrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public'
          AND a.attnum > 0
          AND NOT a.attisdropped
        UNION ALL
        SELECT
          'routine|' || p.oid || '|' || p.proowner || '|'
            || coalesce(p.proacl::text, '')
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'public'
        UNION ALL
        SELECT
          'statistics|' || s.oid || '|' || s.stxowner
        FROM pg_statistic_ext s
        JOIN pg_namespace n ON n.oid = s.stxnamespace
        WHERE n.nspname = 'public'
        UNION ALL
        SELECT
          'default|' || d.defaclrole || '|'
            || d.defaclnamespace || '|' || d.defaclobjtype::text || '|'
            || d.defaclacl::text
        FROM pg_default_acl d
        WHERE d.defaclrole IN (
          'neondb_owner'::regrole,
          'cortex_migrator'::regrole,
          'cortex_runtime'::regrole
        )
        UNION ALL
        SELECT
          'large-object|' || m.oid || '|' || m.lomowner || '|'
            || coalesce(m.lomacl::text, '')
        FROM pg_largeobject_metadata m
      ) state
      ORDER BY item;
    "
}

docker exec "$container_name" psql \
  -U neondb_owner \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -f /tmp/reconcile-neon-ownership.sql >/dev/null
first_reconciliation_fingerprint="$(reconciliation_fingerprint)"
docker exec "$container_name" psql \
  -U neondb_owner \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -f /tmp/reconcile-neon-ownership.sql >/dev/null
second_reconciliation_fingerprint="$(reconciliation_fingerprint)"
[[ "$second_reconciliation_fingerprint" == "$first_reconciliation_fingerprint" ]] || {
  echo "Neon ownership reconciliation is not state-idempotent." >&2
  diff -u \
    <(printf '%s\n' "$first_reconciliation_fingerprint") \
    <(printf '%s\n' "$second_reconciliation_fingerprint") >&2 || true
  exit 1
}

ownership_state="$(
  docker exec "$container_name" psql \
    -U postgres \
    -d "$database_name" \
    -Atq \
    -v ON_ERROR_STOP=1 \
    -c "
      SELECT pg_get_userbyid(datdba)
      FROM pg_database
      WHERE datname = current_database();
      SELECT pg_get_userbyid(nspowner)
      FROM pg_namespace
      WHERE nspname = 'public';
      SELECT count(*)
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = 'public'
        AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f', 'i', 'I')
        AND c.relowner <> 'cortex_migrator'::regrole
        AND NOT EXISTS (
          SELECT 1
          FROM pg_depend d
          WHERE d.classid = 'pg_class'::regclass
            AND d.objid = c.oid
            AND d.deptype = 'e'
        );
      SELECT pg_get_userbyid(extowner)
      FROM pg_extension
      WHERE extname = 'pg_trgm';
      SELECT count(*)
      FROM pg_proc p
      JOIN pg_namespace n ON n.oid = p.pronamespace
      WHERE n.nspname = 'public'
        AND p.prokind IN ('f', 'p', 'a')
        AND p.proowner <> 'cortex_migrator'::regrole
        AND NOT EXISTS (
          SELECT 1
          FROM pg_depend d
          WHERE d.classid = 'pg_proc'::regclass
            AND d.objid = p.oid
            AND d.deptype = 'e'
        );
      SELECT count(*)
      FROM pg_statistic_ext s
      JOIN pg_namespace n ON n.oid = s.stxnamespace
      WHERE n.nspname = 'public'
        AND s.stxowner <> 'cortex_migrator'::regrole
        AND NOT EXISTS (
          SELECT 1
          FROM pg_depend d
          WHERE d.classid = 'pg_statistic_ext'::regclass
            AND d.objid = s.oid
            AND d.deptype = 'e'
        );
      SELECT count(*)
      FROM pg_proc p
      JOIN pg_namespace n ON n.oid = p.pronamespace
      WHERE n.nspname = 'public'
        AND p.proname LIKE 'contract_%'
        AND NOT has_function_privilege(
          'cortex_runtime',
          p.oid,
          'EXECUTE'
        );
      SELECT
        CASE WHEN m.admin_option THEN '1' ELSE '0' END
        || CASE WHEN m.inherit_option THEN '1' ELSE '0' END
        || CASE WHEN m.set_option THEN '1' ELSE '0' END
      FROM pg_auth_members m
      WHERE m.roleid = 'cortex_migrator'::regrole
        AND m.member = 'neondb_owner'::regrole;
      SELECT
        CASE WHEN has_database_privilege(
          'cortex_runtime',
          current_database(),
          'CONNECT'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_database_privilege(
          'cortex_runtime',
          current_database(),
          'CREATE'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_database_privilege(
          'cortex_runtime',
          current_database(),
          'TEMPORARY'
        ) THEN '1' ELSE '0' END;
      SELECT
        CASE WHEN has_schema_privilege(
          'cortex_runtime',
          'public',
          'USAGE'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_schema_privilege(
          'cortex_runtime',
          'public',
          'CREATE'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_table_privilege(
          'cortex_runtime',
          'public.contract_row',
          'SELECT'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_table_privilege(
          'cortex_runtime',
          'public.contract_row',
          'INSERT'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_table_privilege(
          'cortex_runtime',
          'public.contract_row',
          'UPDATE'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_table_privilege(
          'cortex_runtime',
          'public.contract_row',
          'DELETE'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_table_privilege(
          'cortex_runtime',
          'public.contract_row',
          'TRUNCATE'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_table_privilege(
          'cortex_runtime',
          'public.contract_row',
          'REFERENCES'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_table_privilege(
          'cortex_runtime',
          'public.contract_row',
          'TRIGGER'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_table_privilege(
          'cortex_runtime',
          'public.contract_row',
          'MAINTAIN'
        ) THEN '1' ELSE '0' END;
      SELECT
        CASE WHEN has_column_privilege(
          'cortex_runtime',
          'public.contract_row',
          'id',
          'REFERENCES'
        ) THEN '1' ELSE '0' END;
      SELECT count(*)
      FROM pg_attribute a
      JOIN pg_class c ON c.oid = a.attrelid
      JOIN pg_namespace n ON n.oid = c.relnamespace
      CROSS JOIN LATERAL aclexplode(a.attacl) acl
      WHERE n.nspname = 'public'
        AND c.relname = 'contract_row'
        AND a.attnum > 0
        AND NOT a.attisdropped
        AND acl.grantee IN (0, 'cortex_runtime'::regrole);
      SELECT
        CASE WHEN has_database_privilege(
          'cortex_runtime_delegate',
          current_database(),
          'CREATE'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_database_privilege(
          'cortex_runtime_delegate',
          current_database(),
          'TEMPORARY'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_schema_privilege(
          'cortex_runtime_delegate',
          'public',
          'CREATE'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_table_privilege(
          'cortex_runtime_delegate',
          'public.contract_row',
          'TRUNCATE'
        ) THEN '1' ELSE '0' END;
      SELECT
        CASE WHEN has_sequence_privilege(
          'cortex_runtime',
          'public.contract_row_id_seq',
          'USAGE'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_sequence_privilege(
          'cortex_runtime',
          'public.contract_row_id_seq',
          'SELECT'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_sequence_privilege(
          'cortex_runtime',
          'public.contract_row_id_seq',
          'UPDATE'
        ) THEN '1' ELSE '0' END;
      SELECT count(*)
      FROM pg_default_acl d
      CROSS JOIN LATERAL aclexplode(d.defaclacl) acl
      WHERE d.defaclrole = 'cortex_migrator'::regrole
        AND d.defaclobjtype IN ('r', 'S', 'f', 'T', 'n', 'L')
        AND acl.grantee <> 'cortex_migrator'::regrole
        AND NOT (
          acl.grantee = 'cortex_runtime'::regrole
          AND acl.grantor = 'cortex_migrator'::regrole
          AND (
              d.defaclnamespace = 'public'::regnamespace
              AND (
                (
                  d.defaclobjtype = 'r'
                  AND acl.privilege_type IN (
                    'SELECT',
                    'INSERT',
                    'UPDATE',
                    'DELETE'
                  )
                )
                OR (
                  d.defaclobjtype = 'S'
                  AND acl.privilege_type = 'USAGE'
                )
                OR (
                  d.defaclobjtype = 'f'
                  AND acl.privilege_type = 'EXECUTE'
                )
              )
              AND NOT acl.is_grantable
          )
        );
      SELECT count(*)
      FROM pg_default_acl d
      CROSS JOIN LATERAL aclexplode(d.defaclacl) acl
      WHERE d.defaclrole = 'neondb_owner'::regrole
        AND acl.grantee <> 'neondb_owner'::regrole;
      SELECT count(*) FROM public.contract_row;
    "
)"
expected_state=$'neondb_owner\ncortex_migrator\n0\nneondb_owner\n0\n0\n0\n001\n100\n1011110000\n0\n0\n0000\n100\n0\n0\n1'
[[ "$ownership_state" == "$expected_state" ]] || {
  echo "Neon ownership reconciliation produced an unsafe ownership or ACL state." >&2
  exit 1
}

extension_fingerprint_after="$(extension_fingerprint)"
[[ "$extension_fingerprint_after" == "$extension_fingerprint_before" ]] || {
  echo "Neon ownership reconciliation mutated extension membership, owner, or ACL state." >&2
  exit 1
}

docker exec "$container_name" psql \
  -U cortex_runtime \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    BEGIN;
    INSERT INTO public.contract_row(payload) VALUES ('runtime-write');
    SELECT nextval('public.contract_row_id_seq');
    UPDATE public.contract_row
      SET payload = 'runtime-updated'
      WHERE payload = 'runtime-write';
    SELECT payload
      FROM public.contract_row
      WHERE payload = 'runtime-updated';
    SELECT public.contract_echo('runtime-call');
    CALL public.contract_noop();
    SELECT public.contract_sum(id)
      FROM public.contract_row;
    DELETE FROM public.contract_row
      WHERE payload = 'runtime-updated';
    ROLLBACK;
  " >/dev/null

set +e
runtime_setval_output="$(
  docker exec "$container_name" psql \
    -U cortex_runtime \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -c "
      SELECT setval(
        'public.contract_row_id_seq',
        1,
        false
      );
    " 2>&1
)"
runtime_setval_status=$?
set -e
[[ $runtime_setval_status -ne 0 &&
  "$runtime_setval_output" == *"permission denied for sequence contract_row_id_seq"* ]] || {
  echo "cortex_runtime can reset an application sequence." >&2
  exit 1
}

flyway_runtime_acl="$(
  docker exec "$container_name" psql \
    -U postgres \
    -d "$database_name" \
    -Atq \
    -v ON_ERROR_STOP=1 \
    -c "
      SELECT
        CASE WHEN has_table_privilege(
          'cortex_runtime',
          'public.flyway_schema_history',
          'SELECT'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_table_privilege(
          'cortex_runtime',
          'public.flyway_schema_history',
          'INSERT'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_table_privilege(
          'cortex_runtime',
          'public.flyway_schema_history',
          'UPDATE'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_table_privilege(
          'cortex_runtime',
          'public.flyway_schema_history',
          'DELETE'
        ) THEN '1' ELSE '0' END;
    "
)"
[[ "$flyway_runtime_acl" == "1000" ]] || {
  echo "cortex_runtime can mutate Flyway migration history." >&2
  exit 1
}

release_marker_runtime_acl="$(
  docker exec "$container_name" psql \
    -U postgres \
    -d "$database_name" \
    -Atq \
    -v ON_ERROR_STOP=1 \
    -c "
      SELECT
        CASE WHEN has_table_privilege(
          'cortex_runtime',
          'public.cortex_release_marker',
          'SELECT'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_table_privilege(
          'cortex_runtime',
          'public.cortex_release_marker',
          'INSERT'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_table_privilege(
          'cortex_runtime',
          'public.cortex_release_marker',
          'UPDATE'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_table_privilege(
          'cortex_runtime',
          'public.cortex_release_marker',
          'DELETE'
        ) THEN '1' ELSE '0' END;
    "
)"
[[ "$release_marker_runtime_acl" == "1000" ]] || {
  echo "cortex_runtime can mutate the database release marker." >&2
  exit 1
}

set +e
runtime_release_marker_insert_output="$(
  docker exec "$container_name" psql \
    -U cortex_runtime \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -c "
      INSERT INTO public.cortex_release_marker(revision, marker)
      VALUES (repeat('c', 40), repeat('d', 43));
    " 2>&1
)"
runtime_release_marker_insert_status=$?
set -e
[[ $runtime_release_marker_insert_status -ne 0 &&
  "$runtime_release_marker_insert_output" == *"permission denied"* ]] || {
  echo "cortex_runtime can insert a database release marker." >&2
  exit 1
}

set +e
runtime_truncate_output="$(
  docker exec "$container_name" psql \
    -U cortex_runtime \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -c "TRUNCATE TABLE public.contract_row;" 2>&1
)"
runtime_truncate_status=$?
set -e
[[ $runtime_truncate_status -ne 0 &&
  "$runtime_truncate_output" == *"permission denied"* ]] || {
  echo "cortex_runtime retained an operational TRUNCATE privilege." >&2
  exit 1
}

set +e
runtime_ddl_output="$(
  docker exec "$container_name" psql \
    -U cortex_runtime \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -c "CREATE TABLE public.runtime_ddl_probe(id bigint);" 2>&1
)"
runtime_ddl_status=$?
set -e
[[ $runtime_ddl_status -ne 0 &&
  "$runtime_ddl_output" == *"permission denied"* ]] || {
  echo "cortex_runtime retained an operational schema CREATE privilege." >&2
  exit 1
}

set +e
runtime_temp_output="$(
  docker exec "$container_name" psql \
    -U cortex_runtime \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -c "CREATE TEMPORARY TABLE runtime_temp_probe(id bigint);" 2>&1
)"
runtime_temp_status=$?
set -e
[[ $runtime_temp_status -ne 0 &&
  "$runtime_temp_output" == *"permission denied"* ]] || {
  echo "cortex_runtime retained an operational TEMPORARY privilege." >&2
  exit 1
}

docker exec "$container_name" psql \
  -U cortex_migrator \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    CREATE TABLE public.contract_future (
      id bigserial PRIMARY KEY,
      payload text NOT NULL
    );
    CREATE SCHEMA contract_future_schema;
  " >/dev/null
future_acl="$(
  docker exec "$container_name" psql \
    -U postgres \
    -d "$database_name" \
    -Atq \
    -v ON_ERROR_STOP=1 \
    -c "
      SELECT
        CASE WHEN has_table_privilege(
          'cortex_runtime',
          'public.contract_future',
          'SELECT'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_table_privilege(
          'cortex_runtime',
          'public.contract_future',
          'INSERT'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_table_privilege(
          'cortex_runtime',
          'public.contract_future',
          'UPDATE'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_table_privilege(
          'cortex_runtime',
          'public.contract_future',
          'DELETE'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_table_privilege(
          'cortex_runtime',
          'public.contract_future',
          'TRUNCATE'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_table_privilege(
          'cortex_runtime',
          'public.contract_future',
          'REFERENCES'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_table_privilege(
          'cortex_runtime',
          'public.contract_future',
          'TRIGGER'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_table_privilege(
          'cortex_runtime',
          'public.contract_future',
          'MAINTAIN'
        ) THEN '1' ELSE '0' END;
      SELECT
        CASE WHEN has_sequence_privilege(
          'cortex_runtime',
          'public.contract_future_id_seq',
          'USAGE'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_sequence_privilege(
          'cortex_runtime',
          'public.contract_future_id_seq',
          'SELECT'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_sequence_privilege(
          'cortex_runtime',
          'public.contract_future_id_seq',
          'UPDATE'
        ) THEN '1' ELSE '0' END;
      SELECT
        CASE WHEN has_schema_privilege(
          'cortex_runtime',
          'contract_future_schema',
          'USAGE'
        ) THEN '1' ELSE '0' END
        || CASE WHEN has_schema_privilege(
          'cortex_runtime',
          'contract_future_schema',
          'CREATE'
        ) THEN '1' ELSE '0' END;
    "
)"
[[ "$future_acl" == $'11110000\n100\n00' ]] || {
  echo "Neon ownership reconciliation left unsafe default runtime ACLs." >&2
  exit 1
}
docker exec "$container_name" psql \
  -U cortex_migrator \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "DROP SCHEMA contract_future_schema;" >/dev/null

docker exec "$container_name" psql \
  -U cortex_runtime \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    SELECT lo_create(424242);
    GRANT SELECT, UPDATE
      ON LARGE OBJECT 424242
      TO PUBLIC;
  " >/dev/null
large_object_state_before="$(reconciliation_fingerprint)"
set +e
large_object_output="$(
  docker exec "$container_name" psql \
    -U neondb_owner \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -f /tmp/reconcile-neon-ownership.sql 2>&1
)"
large_object_status=$?
set -e
[[ $large_object_status -ne 0 &&
  "$large_object_output" == *"large objects are unsupported and require manual review"* &&
  "$large_object_output" != *$'\nREVOKE\n'* &&
  "$large_object_output" != *$'\nCOMMIT\n'* ]] || {
  echo "Neon ownership reconciliation accepted an existing large object." >&2
  exit 1
}
large_object_state_after="$(reconciliation_fingerprint)"
[[ "$large_object_state_after" == "$large_object_state_before" ]] || {
  echo "Failed large-object reconciliation mutated database state." >&2
  exit 1
}
docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "SELECT lo_unlink(424242);" >/dev/null

docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    CREATE ROLE cortex_acl_grantor LOGIN;
    GRANT CONNECT
      ON DATABASE \"StaviasCortex\"
      TO cortex_acl_grantor WITH GRANT OPTION;
    GRANT USAGE
      ON SCHEMA public
      TO cortex_acl_grantor WITH GRANT OPTION;
  " >/dev/null
docker exec "$container_name" psql \
  -U cortex_acl_grantor \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    GRANT CONNECT
      ON DATABASE \"StaviasCortex\"
      TO cortex_runtime WITH GRANT OPTION;
    GRANT USAGE
      ON SCHEMA public
      TO cortex_runtime WITH GRANT OPTION;
  " >/dev/null
set +e
alternate_grantor_output="$(
  docker exec "$container_name" psql \
    -U neondb_owner \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -f /tmp/reconcile-neon-ownership.sql 2>&1
)"
alternate_grantor_status=$?
set -e
[[ $alternate_grantor_status -ne 0 &&
  "$alternate_grantor_output" == *"database or public schema ACLs are not exact"* ]] || {
  echo "Neon ownership reconciliation accepted alternate ACL grantors." >&2
  exit 1
}
docker exec "$container_name" psql \
  -U cortex_acl_grantor \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    REVOKE CONNECT
      ON DATABASE \"StaviasCortex\"
      FROM cortex_runtime;
    REVOKE USAGE
      ON SCHEMA public
      FROM cortex_runtime;
  " >/dev/null
docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    REVOKE CONNECT
      ON DATABASE \"StaviasCortex\"
      FROM cortex_acl_grantor CASCADE;
    REVOKE USAGE
      ON SCHEMA public
      FROM cortex_acl_grantor CASCADE;
    DROP ROLE cortex_acl_grantor;
  " >/dev/null

docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "CREATE ROLE cortex_unexpected_grantee NOLOGIN;" >/dev/null

docker exec "$container_name" psql \
  -U cortex_migrator \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    GRANT SELECT
      ON TABLE public.contract_row
      TO cortex_unexpected_grantee;
  " >/dev/null
set +e
relation_grantee_output="$(
  docker exec "$container_name" psql \
    -U neondb_owner \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -f /tmp/reconcile-neon-ownership.sql 2>&1
)"
relation_grantee_status=$?
set -e
[[ $relation_grantee_status -ne 0 &&
  "$relation_grantee_output" == *"app relation ACLs retain"* ]] || {
  echo "Neon ownership reconciliation accepted a third-party relation ACL." >&2
  exit 1
}
docker exec "$container_name" psql \
  -U cortex_migrator \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    REVOKE SELECT
      ON TABLE public.contract_row
      FROM cortex_unexpected_grantee;
    GRANT UPDATE (payload)
      ON TABLE public.contract_row
      TO cortex_unexpected_grantee;
  " >/dev/null
set +e
column_grantee_output="$(
  docker exec "$container_name" psql \
    -U neondb_owner \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -f /tmp/reconcile-neon-ownership.sql 2>&1
)"
column_grantee_status=$?
set -e
[[ $column_grantee_status -ne 0 &&
  "$column_grantee_output" == *"app column ACLs retain"* ]] || {
  echo "Neon ownership reconciliation accepted a third-party column ACL." >&2
  exit 1
}
docker exec "$container_name" psql \
  -U cortex_migrator \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    REVOKE UPDATE (payload)
      ON TABLE public.contract_row
      FROM cortex_unexpected_grantee;
    GRANT USAGE
      ON SEQUENCE public.contract_row_id_seq
      TO cortex_unexpected_grantee;
  " >/dev/null
set +e
sequence_grantee_output="$(
  docker exec "$container_name" psql \
    -U neondb_owner \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -f /tmp/reconcile-neon-ownership.sql 2>&1
)"
sequence_grantee_status=$?
set -e
[[ $sequence_grantee_status -ne 0 &&
  "$sequence_grantee_output" == *"app sequence ACLs retain"* ]] || {
  echo "Neon ownership reconciliation accepted a third-party sequence ACL." >&2
  exit 1
}
docker exec "$container_name" psql \
  -U cortex_migrator \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    REVOKE USAGE
      ON SEQUENCE public.contract_row_id_seq
      FROM cortex_unexpected_grantee;
    GRANT EXECUTE
      ON ROUTINE public.contract_echo(text)
      TO cortex_unexpected_grantee;
  " >/dev/null
set +e
routine_grantee_output="$(
  docker exec "$container_name" psql \
    -U neondb_owner \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -f /tmp/reconcile-neon-ownership.sql 2>&1
)"
routine_grantee_status=$?
set -e
[[ $routine_grantee_status -ne 0 &&
  "$routine_grantee_output" == *"app routine ACLs retain"* ]] || {
  echo "Neon ownership reconciliation accepted a third-party routine ACL." >&2
  exit 1
}
docker exec "$container_name" psql \
  -U cortex_migrator \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    REVOKE EXECUTE
      ON ROUTINE public.contract_echo(text)
      FROM cortex_unexpected_grantee;
  " >/dev/null
docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "DROP ROLE cortex_unexpected_grantee;" >/dev/null

docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    CREATE SCHEMA cortex_unexpected_schema;
    GRANT USAGE
      ON SCHEMA cortex_unexpected_schema
      TO cortex_runtime;
  " >/dev/null
set +e
external_schema_output="$(
  docker exec "$container_name" psql \
    -U neondb_owner \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -f /tmp/reconcile-neon-ownership.sql 2>&1
)"
external_schema_status=$?
set -e
[[ $external_schema_status -ne 0 &&
  "$external_schema_output" == *"runtime can access user schemas outside public"* ]] || {
  echo "Neon ownership reconciliation accepted runtime access outside public." >&2
  exit 1
}
docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "DROP SCHEMA cortex_unexpected_schema;" >/dev/null

docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    CREATE ROLE cortex_unexpected_parent NOLOGIN;
    GRANT cortex_unexpected_parent TO cortex_runtime
      WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;
  " >/dev/null
set +e
runtime_membership_output="$(
  docker exec "$container_name" psql \
    -U neondb_owner \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -f /tmp/reconcile-neon-ownership.sql 2>&1
)"
runtime_membership_status=$?
set -e
[[ $runtime_membership_status -ne 0 &&
  "$runtime_membership_output" == *"cortex_runtime has an unexpected parent-role membership"* ]] || {
  echo "Neon ownership reconciliation accepted a runtime parent-role membership." >&2
  exit 1
}
docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    REVOKE cortex_unexpected_parent FROM cortex_runtime;
    GRANT cortex_unexpected_parent TO cortex_migrator
      WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;
  " >/dev/null
set +e
migrator_membership_output="$(
  docker exec "$container_name" psql \
    -U neondb_owner \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -f /tmp/reconcile-neon-ownership.sql 2>&1
)"
migrator_membership_status=$?
set -e
[[ $migrator_membership_status -ne 0 &&
  "$migrator_membership_output" == *"cortex_migrator has an unexpected parent-role membership"* ]] || {
  echo "Neon ownership reconciliation accepted a migrator parent-role membership." >&2
  exit 1
}
docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    REVOKE cortex_unexpected_parent FROM cortex_migrator;
    DROP ROLE cortex_unexpected_parent;
  " >/dev/null

docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    CREATE ROLE cortex_unexpected_member NOLOGIN;
    GRANT cortex_runtime TO cortex_unexpected_member
      WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;
  " >/dev/null
set +e
runtime_member_output="$(
  docker exec "$container_name" psql \
    -U neondb_owner \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -f /tmp/reconcile-neon-ownership.sql 2>&1
)"
runtime_member_status=$?
set -e
[[ $runtime_member_status -ne 0 &&
  "$runtime_member_output" == *"cortex_runtime has an unexpected member"* ]] || {
  echo "Neon ownership reconciliation accepted an unexpected runtime member." >&2
  exit 1
}
docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    REVOKE cortex_runtime FROM cortex_unexpected_member;
    GRANT cortex_migrator TO cortex_unexpected_member
      WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;
  " >/dev/null
set +e
migrator_member_output="$(
  docker exec "$container_name" psql \
    -U neondb_owner \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -f /tmp/reconcile-neon-ownership.sql 2>&1
)"
migrator_member_status=$?
set -e
[[ $migrator_member_status -ne 0 &&
  "$migrator_member_output" == *"cortex_migrator has an unexpected member"* ]] || {
  echo "Neon ownership reconciliation accepted an unexpected migrator member." >&2
  exit 1
}
docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    REVOKE cortex_migrator FROM cortex_unexpected_member;
    DROP ROLE cortex_unexpected_member;
  " >/dev/null

docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    REVOKE cortex_migrator
      FROM neondb_owner
      GRANTED BY postgres;
    CREATE ROLE cortex_bootstrap_operator LOGIN SUPERUSER;
  " >/dev/null
docker exec "$container_name" psql \
  -U cortex_bootstrap_operator \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    ALTER ROLE postgres RENAME TO cloud_admin;
  " >/dev/null
docker exec "$container_name" psql \
  -U neondb_owner \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    SET createrole_self_grant = '';
    CREATE ROLE cortex_pg18_creator_grant_probe NOLOGIN;
  " >/dev/null
pg18_creator_grant_state="$(
  docker exec "$container_name" psql \
    -U cloud_admin \
    -d "$database_name" \
    -Atq \
    -v ON_ERROR_STOP=1 \
    -c "
      SELECT
        pg_get_userbyid(m.grantor)
        || '|'
        || CASE WHEN m.admin_option THEN '1' ELSE '0' END
        || CASE WHEN m.inherit_option THEN '1' ELSE '0' END
        || CASE WHEN m.set_option THEN '1' ELSE '0' END
      FROM pg_auth_members m
      WHERE m.roleid = 'cortex_pg18_creator_grant_probe'::regrole
        AND m.member = 'neondb_owner'::regrole;
    "
)"
[[ "$pg18_creator_grant_state" == 'cloud_admin|100' ]] || {
  echo "PostgreSQL 18 did not materialize the expected creator grant." >&2
  printf '%s\n' "$pg18_creator_grant_state" >&2
  exit 1
}
docker exec "$container_name" psql \
  -U cloud_admin \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "DROP ROLE cortex_pg18_creator_grant_probe;" >/dev/null
docker exec "$container_name" psql \
  -U cloud_admin \
  -d postgres \
  -v ON_ERROR_STOP=1 \
  -c "
    GRANT cortex_migrator TO neondb_owner
      WITH ADMIN TRUE, INHERIT FALSE, SET FALSE;
    GRANT cortex_runtime TO neondb_owner
      WITH ADMIN TRUE, INHERIT FALSE, SET FALSE;
    SET ROLE neondb_owner;
    GRANT cortex_migrator TO neondb_owner
      WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;
    RESET ROLE;
  " >/dev/null
neon_owner_membership_state="$(
  docker exec "$container_name" psql \
    -U cloud_admin \
    -d "$database_name" \
    -Atq \
    -v ON_ERROR_STOP=1 \
    -c "
      SELECT
        pg_get_userbyid(m.grantor)
        || '|'
        || CASE WHEN m.admin_option THEN '1' ELSE '0' END
        || CASE WHEN m.inherit_option THEN '1' ELSE '0' END
        || CASE WHEN m.set_option THEN '1' ELSE '0' END
      FROM pg_auth_members m
      WHERE m.roleid = 'cortex_migrator'::regrole
        AND m.member = 'neondb_owner'::regrole
      ORDER BY pg_get_userbyid(m.grantor);
    "
)"
[[ "$neon_owner_membership_state" == $'cloud_admin|100\nneondb_owner|001' ]] || {
  echo "PostgreSQL 18 Neon membership fixture did not create the expected rows." >&2
  printf '%s\n' "$neon_owner_membership_state" >&2
  exit 1
}
neon_runtime_membership_state="$(
  docker exec "$container_name" psql \
    -U cloud_admin \
    -d "$database_name" \
    -Atq \
    -v ON_ERROR_STOP=1 \
    -c "
      SELECT
        pg_get_userbyid(m.grantor)
        || '|'
        || CASE WHEN m.admin_option THEN '1' ELSE '0' END
        || CASE WHEN m.inherit_option THEN '1' ELSE '0' END
        || CASE WHEN m.set_option THEN '1' ELSE '0' END
      FROM pg_auth_members m
      WHERE m.roleid = 'cortex_runtime'::regrole
        AND m.member = 'neondb_owner'::regrole
      ORDER BY pg_get_userbyid(m.grantor);
    "
)"
[[ "$neon_runtime_membership_state" == 'cloud_admin|100' ]] || {
  echo "PostgreSQL 18 Neon runtime membership fixture did not create the expected row." >&2
  printf '%s\n' "$neon_runtime_membership_state" >&2
  exit 1
}
docker exec "$container_name" psql \
  -U neondb_owner \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -f /tmp/reconcile-neon-ownership.sql >/dev/null
neon_owner_membership_state_after="$(
  docker exec "$container_name" psql \
    -U cloud_admin \
    -d "$database_name" \
    -Atq \
    -v ON_ERROR_STOP=1 \
    -c "
      SELECT
        pg_get_userbyid(m.grantor)
        || '|'
        || CASE WHEN m.admin_option THEN '1' ELSE '0' END
        || CASE WHEN m.inherit_option THEN '1' ELSE '0' END
        || CASE WHEN m.set_option THEN '1' ELSE '0' END
      FROM pg_auth_members m
      WHERE m.roleid = 'cortex_migrator'::regrole
        AND m.member = 'neondb_owner'::regrole
      ORDER BY pg_get_userbyid(m.grantor);
    "
)"
[[ "$neon_owner_membership_state_after" == "$neon_owner_membership_state" ]] || {
  echo "Neon ownership reconciliation did not preserve the exact managed owner memberships." >&2
  exit 1
}
neon_runtime_membership_state_after="$(
  docker exec "$container_name" psql \
    -U cloud_admin \
    -d "$database_name" \
    -Atq \
    -v ON_ERROR_STOP=1 \
    -c "
      SELECT
        pg_get_userbyid(m.grantor)
        || '|'
        || CASE WHEN m.admin_option THEN '1' ELSE '0' END
        || CASE WHEN m.inherit_option THEN '1' ELSE '0' END
        || CASE WHEN m.set_option THEN '1' ELSE '0' END
      FROM pg_auth_members m
      WHERE m.roleid = 'cortex_runtime'::regrole
        AND m.member = 'neondb_owner'::regrole
      ORDER BY pg_get_userbyid(m.grantor);
    "
)"
[[ "$neon_runtime_membership_state_after" == \
  "$neon_runtime_membership_state" ]] || {
  echo "Neon ownership reconciliation did not preserve the managed runtime membership." >&2
  exit 1
}

expect_neon_membership_rejected() {
  local case_name="$1"
  local expected_message="$2"
  local output
  local status

  set +e
  output="$(
    docker exec "$container_name" psql \
      -U neondb_owner \
      -d "$database_name" \
      -v ON_ERROR_STOP=1 \
      -f /tmp/reconcile-neon-ownership.sql 2>&1
  )"
  status=$?
  set -e

  [[ $status -ne 0 && "$output" == *"$expected_message"* ]] || {
    echo "Neon ownership reconciliation accepted $case_name." >&2
    exit 1
  }
}

cloud_admin_attribute_cases=(
  "NOCREATEROLE|CREATEROLE"
  "NOLOGIN|LOGIN"
)
for cloud_admin_attribute_case in "${cloud_admin_attribute_cases[@]}"; do
  IFS='|' read -r unsafe_attribute restored_attribute \
    <<< "$cloud_admin_attribute_case"
  docker exec "$container_name" psql \
    -U cortex_bootstrap_operator \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -c "ALTER ROLE cloud_admin $unsafe_attribute;" >/dev/null
  expect_neon_membership_rejected \
    "unsafe cloud_admin attributes" \
    "database owner membership in cortex_migrator is unsafe"
  docker exec "$container_name" psql \
    -U cortex_bootstrap_operator \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -c "ALTER ROLE cloud_admin $restored_attribute;" >/dev/null
done

cloud_admin_membership_option_cases=(
  "TRUE TRUE FALSE"
  "TRUE FALSE TRUE"
)
for cloud_admin_membership_option_case in \
  "${cloud_admin_membership_option_cases[@]}"; do
  read -r membership_admin membership_inherit membership_set \
    <<< "$cloud_admin_membership_option_case"
  docker exec "$container_name" psql \
    -U cloud_admin \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -c "
      GRANT cortex_migrator TO neondb_owner
        WITH ADMIN $membership_admin,
          INHERIT $membership_inherit,
          SET $membership_set
        GRANTED BY cloud_admin;
    " >/dev/null
  expect_neon_membership_rejected \
    "unsafe cloud_admin-managed membership options" \
    "database owner membership in cortex_migrator is unsafe"
  docker exec "$container_name" psql \
    -U cloud_admin \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -c "
      GRANT cortex_migrator TO neondb_owner
        WITH ADMIN TRUE, INHERIT FALSE, SET FALSE
        GRANTED BY cloud_admin;
    " >/dev/null
done

cloud_admin_runtime_membership_option_cases=(
  "FALSE FALSE FALSE"
  "TRUE TRUE FALSE"
  "TRUE FALSE TRUE"
)
for cloud_admin_runtime_membership_option_case in \
  "${cloud_admin_runtime_membership_option_cases[@]}"; do
  read -r membership_admin membership_inherit membership_set \
    <<< "$cloud_admin_runtime_membership_option_case"
  docker exec "$container_name" psql \
    -U cloud_admin \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -c "
      GRANT cortex_runtime TO neondb_owner
        WITH ADMIN $membership_admin,
          INHERIT $membership_inherit,
          SET $membership_set
        GRANTED BY cloud_admin;
    " >/dev/null
  expect_neon_membership_rejected \
    "unsafe cloud_admin-managed runtime membership options" \
    "database owner membership in cortex_runtime is unsafe"
  docker exec "$container_name" psql \
    -U cloud_admin \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -c "
      GRANT cortex_runtime TO neondb_owner
        WITH ADMIN TRUE, INHERIT FALSE, SET FALSE
        GRANTED BY cloud_admin;
    " >/dev/null
done

docker exec "$container_name" psql \
  -U cloud_admin \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    REVOKE cortex_runtime
      FROM neondb_owner
      GRANTED BY cloud_admin;
    CREATE ROLE cortex_unexpected_runtime_member NOLOGIN;
    GRANT cortex_runtime TO cortex_unexpected_runtime_member
      WITH ADMIN TRUE, INHERIT FALSE, SET FALSE;
  " >/dev/null
expect_neon_membership_rejected \
  "a runtime membership for the wrong member" \
  "database owner membership in cortex_runtime is unsafe"
docker exec "$container_name" psql \
  -U cloud_admin \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    REVOKE cortex_runtime
      FROM cortex_unexpected_runtime_member
      GRANTED BY cloud_admin;
    GRANT cortex_runtime TO neondb_owner
      WITH ADMIN TRUE, INHERIT FALSE, SET FALSE
      GRANTED BY cloud_admin;
  " >/dev/null

docker exec "$container_name" psql \
  -U cloud_admin \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    GRANT cortex_runtime TO cortex_unexpected_runtime_member
      WITH ADMIN TRUE, INHERIT FALSE, SET FALSE;
  " >/dev/null
expect_neon_membership_rejected \
  "an extra cloud-admin runtime membership" \
  "database owner membership in cortex_runtime is unsafe"
docker exec "$container_name" psql \
  -U cloud_admin \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    REVOKE cortex_runtime
      FROM cortex_unexpected_runtime_member
      GRANTED BY cloud_admin;
    DROP ROLE cortex_unexpected_runtime_member;
  " >/dev/null

docker exec "$container_name" psql \
  -U cloud_admin \
  -d postgres \
  -v ON_ERROR_STOP=1 \
  -c "
    SET ROLE neondb_owner;
    REVOKE cortex_migrator
      FROM neondb_owner
      GRANTED BY neondb_owner;
    RESET ROLE;
  " >/dev/null
expect_neon_membership_rejected \
  "a missing canonical owner membership" \
  "database owner membership in cortex_migrator is unsafe"
docker exec "$container_name" psql \
  -U cloud_admin \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    SET ROLE neondb_owner;
    GRANT cortex_migrator TO neondb_owner
      WITH ADMIN FALSE, INHERIT FALSE, SET TRUE
      GRANTED BY neondb_owner;
    RESET ROLE;
  " >/dev/null

docker exec "$container_name" psql \
  -U cloud_admin \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    CREATE ROLE cortex_membership_grantor NOLOGIN;
    GRANT cortex_migrator TO cortex_membership_grantor
      WITH ADMIN TRUE, INHERIT FALSE, SET FALSE;
    SET ROLE neondb_owner;
    REVOKE cortex_migrator
      FROM neondb_owner
      GRANTED BY neondb_owner;
    RESET ROLE;
    SET ROLE cortex_membership_grantor;
    GRANT cortex_migrator TO neondb_owner
      WITH ADMIN FALSE, INHERIT FALSE, SET TRUE
      GRANTED BY cortex_membership_grantor;
    RESET ROLE;
  " >/dev/null
expect_neon_membership_rejected \
  "a canonical membership from an alternate grantor" \
  "database owner membership in cortex_migrator is unsafe"
docker exec "$container_name" psql \
  -U cloud_admin \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    SET ROLE neondb_owner;
    GRANT cortex_migrator TO neondb_owner
      WITH ADMIN FALSE, INHERIT FALSE, SET TRUE
      GRANTED BY neondb_owner;
    RESET ROLE;
  " >/dev/null
expect_neon_membership_rejected \
  "an extra owner membership row" \
  "database owner membership in cortex_migrator is unsafe"
docker exec "$container_name" psql \
  -U cloud_admin \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    SET ROLE cortex_membership_grantor;
    REVOKE cortex_migrator
      FROM neondb_owner
      GRANTED BY cortex_membership_grantor;
    RESET ROLE;
    REVOKE cortex_migrator
      FROM cortex_membership_grantor
      GRANTED BY cloud_admin;
    DROP ROLE cortex_membership_grantor;
  " >/dev/null

docker exec "$container_name" psql \
  -U cloud_admin \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    CREATE ROLE cortex_unexpected_member NOLOGIN;
    GRANT cortex_migrator TO cortex_unexpected_member
      WITH ADMIN FALSE, INHERIT FALSE, SET FALSE;
  " >/dev/null
expect_neon_membership_rejected \
  "an unexpected cloud-managed member" \
  "cortex_migrator has an unexpected member"
docker exec "$container_name" psql \
  -U cloud_admin \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    REVOKE cortex_migrator
      FROM cortex_unexpected_member
      GRANTED BY cloud_admin;
    DROP ROLE cortex_unexpected_member;
  " >/dev/null

docker exec "$container_name" psql \
  -U cloud_admin \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    SET ROLE neondb_owner;
    REVOKE cortex_migrator
      FROM neondb_owner
      GRANTED BY neondb_owner;
    RESET ROLE;
    SET ROLE cloud_admin;
    REVOKE cortex_migrator
      FROM neondb_owner
      GRANTED BY cloud_admin;
    REVOKE cortex_runtime
      FROM neondb_owner
      GRANTED BY cloud_admin;
    RESET ROLE;
  " >/dev/null
docker exec "$container_name" psql \
  -U cortex_bootstrap_operator \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    ALTER ROLE cloud_admin RENAME TO postgres;
  " >/dev/null
docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    DROP ROLE cortex_bootstrap_operator;
    GRANT cortex_migrator TO neondb_owner
      WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;
  " >/dev/null

docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    REVOKE cortex_migrator
      FROM neondb_owner
      GRANTED BY postgres;
    CREATE ROLE cloud_admin LOGIN CREATEROLE SUPERUSER;
    GRANT cortex_migrator TO cloud_admin
      WITH ADMIN TRUE, INHERIT FALSE, SET FALSE
      GRANTED BY postgres;
  " >/dev/null
docker exec "$container_name" psql \
  -U cloud_admin \
  -d postgres \
  -v ON_ERROR_STOP=1 \
  -c "
    GRANT cortex_migrator TO neondb_owner
      WITH ADMIN TRUE, INHERIT FALSE, SET FALSE
      GRANTED BY cloud_admin;
  " >/dev/null
docker exec "$container_name" psql \
  -U neondb_owner \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    GRANT cortex_migrator TO neondb_owner
      WITH ADMIN FALSE, INHERIT FALSE, SET TRUE
      GRANTED BY neondb_owner;
  " >/dev/null
docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    GRANT cortex_runtime TO neondb_owner
      WITH ADMIN TRUE, INHERIT FALSE, SET FALSE
      GRANTED BY postgres;
  " >/dev/null
runtime_alternate_grantor_state="$(
  docker exec "$container_name" psql \
    -U postgres \
    -d "$database_name" \
    -Atq \
    -v ON_ERROR_STOP=1 \
    -c "
      SELECT
        pg_get_userbyid(m.grantor)
        || '|'
        || CASE WHEN m.admin_option THEN '1' ELSE '0' END
        || CASE WHEN m.inherit_option THEN '1' ELSE '0' END
        || CASE WHEN m.set_option THEN '1' ELSE '0' END
      FROM pg_auth_members m
      WHERE m.roleid = 'cortex_runtime'::regrole
        AND m.member = 'neondb_owner'::regrole;
    "
)"
[[ "$runtime_alternate_grantor_state" == 'postgres|100' ]] || {
  echo "Runtime alternate-grantor fixture did not create the expected row." >&2
  printf '%s\n' "$runtime_alternate_grantor_state" >&2
  exit 1
}
expect_neon_membership_rejected \
  "a runtime membership from an alternate grantor" \
  "database owner membership in cortex_runtime is unsafe"
docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    REVOKE cortex_runtime
      FROM neondb_owner
      GRANTED BY postgres;
    ALTER ROLE cloud_admin NOSUPERUSER;
  " >/dev/null
expect_neon_membership_rejected \
  "a non-superuser cloud_admin grantor" \
  "database owner membership in cortex_migrator is unsafe"
docker exec "$container_name" psql \
  -U neondb_owner \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    REVOKE cortex_migrator
      FROM neondb_owner
      GRANTED BY neondb_owner;
  " >/dev/null
docker exec "$container_name" psql \
  -U cloud_admin \
  -d postgres \
  -v ON_ERROR_STOP=1 \
  -c "
    REVOKE cortex_migrator
      FROM neondb_owner
      GRANTED BY cloud_admin;
  " >/dev/null
docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    REVOKE cortex_migrator
      FROM cloud_admin
      GRANTED BY postgres;
    DROP ROLE cloud_admin;
    GRANT cortex_migrator TO neondb_owner
      WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;
  " >/dev/null

owner_membership_cases=(
  "TRUE FALSE TRUE"
  "FALSE TRUE TRUE"
  "FALSE FALSE FALSE"
)
for owner_membership_case in "${owner_membership_cases[@]}"; do
  read -r membership_admin membership_inherit membership_set \
    <<< "$owner_membership_case"
  docker exec "$container_name" psql \
    -U postgres \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -c "
      REVOKE cortex_migrator FROM neondb_owner;
      GRANT cortex_migrator TO neondb_owner
        WITH ADMIN $membership_admin,
          INHERIT $membership_inherit,
          SET $membership_set;
    " >/dev/null
  set +e
  owner_membership_output="$(
    docker exec "$container_name" psql \
      -U neondb_owner \
      -d "$database_name" \
      -v ON_ERROR_STOP=1 \
      -f /tmp/reconcile-neon-ownership.sql 2>&1
  )"
  owner_membership_status=$?
  set -e
  [[ $owner_membership_status -ne 0 &&
    "$owner_membership_output" == *"database owner membership in cortex_migrator is unsafe"* ]] || {
    echo "Neon ownership reconciliation accepted unsafe owner membership options." >&2
    exit 1
  }
done
docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    REVOKE cortex_migrator FROM neondb_owner;
    GRANT cortex_migrator TO neondb_owner
      WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;
  " >/dev/null

docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    CREATE ROLE cortex_membership_grantor NOLOGIN;
    GRANT cortex_migrator TO cortex_membership_grantor
      WITH ADMIN TRUE, INHERIT FALSE, SET FALSE;
    SET ROLE cortex_membership_grantor;
    GRANT cortex_migrator TO neondb_owner
      WITH ADMIN FALSE, INHERIT TRUE, SET TRUE;
    RESET ROLE;
  " >/dev/null
set +e
membership_grantor_output="$(
  docker exec "$container_name" psql \
    -U neondb_owner \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -f /tmp/reconcile-neon-ownership.sql 2>&1
)"
membership_grantor_status=$?
set -e
[[ $membership_grantor_status -ne 0 &&
  "$membership_grantor_output" == *"database owner membership in cortex_migrator is unsafe"* ]] || {
  echo "Neon ownership reconciliation accepted duplicate membership grantors." >&2
  exit 1
}
docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    SET ROLE cortex_membership_grantor;
    REVOKE cortex_migrator FROM neondb_owner;
    RESET ROLE;
    REVOKE cortex_migrator FROM cortex_membership_grantor;
    DROP ROLE cortex_membership_grantor;
  " >/dev/null

docker exec "$container_name" psql \
  -U cortex_runtime \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    ALTER DEFAULT PRIVILEGES
      GRANT SELECT ON TABLES TO PUBLIC;
  " >/dev/null
set +e
runtime_default_acl_output="$(
  docker exec "$container_name" psql \
    -U neondb_owner \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -f /tmp/reconcile-neon-ownership.sql 2>&1
)"
runtime_default_acl_status=$?
set -e
[[ $runtime_default_acl_status -ne 0 &&
  "$runtime_default_acl_output" == *"cortex_runtime has unexpected default privileges"* ]] || {
  echo "Neon ownership reconciliation accepted runtime-owned default ACLs." >&2
  exit 1
}
docker exec "$container_name" psql \
  -U cortex_runtime \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    ALTER DEFAULT PRIVILEGES
      REVOKE SELECT ON TABLES FROM PUBLIC;
  " >/dev/null

docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    ALTER SCHEMA public OWNER TO neondb_owner;
    ALTER EXTENSION pg_trgm ADD SCHEMA public;
  " >/dev/null
extension_schema_fingerprint_before="$(extension_fingerprint)"
extension_schema_state_before="$(reconciliation_fingerprint)"
set +e
extension_schema_output="$(
  docker exec "$container_name" psql \
    -U neondb_owner \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -f /tmp/reconcile-neon-ownership.sql 2>&1
)"
extension_schema_status=$?
set -e
[[ $extension_schema_status -ne 0 &&
  "$extension_schema_output" == *"public schema is an extension member"* &&
  "$extension_schema_output" != *$'\nREVOKE\n'* &&
  "$extension_schema_output" != *$'\nCOMMIT\n'* ]] || {
  echo "Neon ownership reconciliation accepted an extension-owned public schema." >&2
  exit 1
}
extension_schema_fingerprint_after="$(extension_fingerprint)"
extension_schema_state_after="$(reconciliation_fingerprint)"
[[ "$extension_schema_fingerprint_after" == "$extension_schema_fingerprint_before" &&
  "$extension_schema_state_after" == "$extension_schema_state_before" ]] || {
  echo "Failed extension-schema reconciliation mutated database state." >&2
  exit 1
}
docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    ALTER EXTENSION pg_trgm DROP SCHEMA public;
    ALTER SCHEMA public OWNER TO cortex_migrator;
  " >/dev/null

docker exec "$container_name" psql \
  -U cortex_migrator \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    CREATE TYPE public.contract_composite AS (
      payload text
    );
  " >/dev/null
set +e
composite_output="$(
  docker exec "$container_name" psql \
    -U neondb_owner \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -f /tmp/reconcile-neon-ownership.sql 2>&1
)"
composite_status=$?
set -e
[[ $composite_status -ne 0 &&
  "$composite_output" == *"unsupported non-extension public objects require manual review"* ]] || {
  echo "Neon ownership reconciliation accepted an unsupported composite type." >&2
  exit 1
}
docker exec "$container_name" psql \
  -U cortex_migrator \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "DROP TYPE public.contract_composite;" >/dev/null

docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    CREATE TABLE public.contract_extension_parent (
      id bigint
    );
    CREATE SEQUENCE public.contract_extension_owned_sequence
      OWNED BY public.contract_extension_parent.id;
    ALTER TABLE public.contract_extension_parent
      OWNER TO neondb_owner;
    ALTER EXTENSION pg_trgm
      ADD SEQUENCE public.contract_extension_owned_sequence;
  " >/dev/null
extension_dependency_fingerprint_before="$(extension_fingerprint)"
extension_dependency_state_before="$(reconciliation_fingerprint)"
set +e
extension_dependency_output="$(
  docker exec "$container_name" psql \
    -U neondb_owner \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -f /tmp/reconcile-neon-ownership.sql 2>&1
)"
extension_dependency_status=$?
set -e
[[ $extension_dependency_status -ne 0 &&
  "$extension_dependency_output" == *"extension member depends on a non-extension public relation"* &&
  "$extension_dependency_output" != *$'\nREVOKE\n'* &&
  "$extension_dependency_output" != *$'\nCOMMIT\n'* ]] || {
  echo "Neon ownership reconciliation accepted a cross-owned extension dependency." >&2
  exit 1
}
extension_dependency_fingerprint_after="$(extension_fingerprint)"
extension_dependency_state_after="$(reconciliation_fingerprint)"
[[ "$extension_dependency_fingerprint_after" == "$extension_dependency_fingerprint_before" &&
  "$extension_dependency_state_after" == "$extension_dependency_state_before" ]] || {
  echo "Failed extension-dependency reconciliation mutated database state." >&2
  exit 1
}
docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    ALTER EXTENSION pg_trgm
      DROP SEQUENCE public.contract_extension_owned_sequence;
    DROP TABLE public.contract_extension_parent;
  " >/dev/null

docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    CREATE EXTENSION file_fdw;
    CREATE SERVER contract_file_server
      FOREIGN DATA WRAPPER file_fdw;
    CREATE FOREIGN TABLE public.contract_extension_foreign_parent (
      id bigint
    )
    SERVER contract_file_server
    OPTIONS (filename '/tmp/contract-extension-foreign-parent.csv');
    CREATE SEQUENCE public.contract_extension_foreign_sequence
      OWNED BY public.contract_extension_foreign_parent.id;
    ALTER FOREIGN TABLE public.contract_extension_foreign_parent
      OWNER TO neondb_owner;
    ALTER EXTENSION pg_trgm
      ADD SEQUENCE public.contract_extension_foreign_sequence;
  " >/dev/null
extension_foreign_fingerprint_before="$(extension_fingerprint)"
extension_foreign_state_before="$(reconciliation_fingerprint)"
set +e
extension_foreign_output="$(
  docker exec "$container_name" psql \
    -U neondb_owner \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -f /tmp/reconcile-neon-ownership.sql 2>&1
)"
extension_foreign_status=$?
set -e
[[ $extension_foreign_status -ne 0 &&
  "$extension_foreign_output" == *"extension member depends on a non-extension public relation"* &&
  "$extension_foreign_output" != *$'\nREVOKE\n'* &&
  "$extension_foreign_output" != *$'\nCOMMIT\n'* ]] || {
  echo "Neon ownership reconciliation accepted an extension dependency on a foreign table." >&2
  exit 1
}
extension_foreign_fingerprint_after="$(extension_fingerprint)"
extension_foreign_state_after="$(reconciliation_fingerprint)"
[[ "$extension_foreign_fingerprint_after" == "$extension_foreign_fingerprint_before" &&
  "$extension_foreign_state_after" == "$extension_foreign_state_before" ]] || {
  echo "Failed foreign-table extension reconciliation mutated database state." >&2
  exit 1
}
docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    ALTER EXTENSION pg_trgm
      DROP SEQUENCE public.contract_extension_foreign_sequence;
    DROP FOREIGN TABLE public.contract_extension_foreign_parent;
    DROP SERVER contract_file_server;
    DROP EXTENSION file_fdw;
  " >/dev/null

docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    CREATE TABLE public.contract_extension_row_type (
      id bigint
    );
    ALTER TABLE public.contract_extension_row_type
      OWNER TO neondb_owner;
    ALTER EXTENSION pg_trgm
      ADD TYPE public.contract_extension_row_type;
  " >/dev/null
extension_type_fingerprint_before="$(extension_fingerprint)"
extension_type_state_before="$(reconciliation_fingerprint)"
set +e
extension_type_output="$(
  docker exec "$container_name" psql \
    -U neondb_owner \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -f /tmp/reconcile-neon-ownership.sql 2>&1
)"
extension_type_status=$?
set -e
[[ $extension_type_status -ne 0 &&
  "$extension_type_output" == *"extension member depends on a non-extension public relation"* &&
  "$extension_type_output" != *$'\nREVOKE\n'* &&
  "$extension_type_output" != *$'\nCOMMIT\n'* ]] || {
  echo "Neon ownership reconciliation accepted an extension-owned row type." >&2
  exit 1
}
extension_type_fingerprint_after="$(extension_fingerprint)"
extension_type_state_after="$(reconciliation_fingerprint)"
[[ "$extension_type_fingerprint_after" == "$extension_type_fingerprint_before" &&
  "$extension_type_state_after" == "$extension_type_state_before" ]] || {
  echo "Failed extension-row-type reconciliation mutated database state." >&2
  exit 1
}
docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    ALTER EXTENSION pg_trgm
      DROP TYPE public.contract_extension_row_type;
    DROP TABLE public.contract_extension_row_type;
  " >/dev/null

docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    CREATE TABLE public.contract_extension_array (
      id bigint
    );
    ALTER TABLE public.contract_extension_array
      OWNER TO neondb_owner;
    ALTER EXTENSION pg_trgm
      ADD TYPE public._contract_extension_array;
  " >/dev/null
extension_array_fingerprint_before="$(extension_fingerprint)"
extension_array_state_before="$(reconciliation_fingerprint)"
set +e
extension_array_output="$(
  docker exec "$container_name" psql \
    -U neondb_owner \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -f /tmp/reconcile-neon-ownership.sql 2>&1
)"
extension_array_status=$?
set -e
[[ $extension_array_status -ne 0 &&
  "$extension_array_output" == *"extension member depends on a non-extension public relation"* &&
  "$extension_array_output" != *$'\nREVOKE\n'* &&
  "$extension_array_output" != *$'\nCOMMIT\n'* ]] || {
  echo "Neon ownership reconciliation accepted an extension-owned array row type." >&2
  exit 1
}
extension_array_fingerprint_after="$(extension_fingerprint)"
extension_array_state_after="$(reconciliation_fingerprint)"
[[ "$extension_array_fingerprint_after" == "$extension_array_fingerprint_before" &&
  "$extension_array_state_after" == "$extension_array_state_before" ]] || {
  echo "Failed extension-array reconciliation mutated database state." >&2
  exit 1
}
docker exec "$container_name" psql \
  -U postgres \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    ALTER EXTENSION pg_trgm
      DROP TYPE public._contract_extension_array;
    DROP TABLE public.contract_extension_array;
  " >/dev/null

docker exec "$container_name" psql \
  -U cortex_migrator \
  -d "$database_name" \
  -v ON_ERROR_STOP=1 \
  -c "
    CREATE FUNCTION public.contract_privileged()
    RETURNS integer
    LANGUAGE sql
    SECURITY DEFINER
    AS 'SELECT 1';
  " >/dev/null
set +e
security_output="$(
  docker exec "$container_name" psql \
    -U neondb_owner \
    -d "$database_name" \
    -v ON_ERROR_STOP=1 \
    -f /tmp/reconcile-neon-ownership.sql 2>&1
)"
security_status=$?
set -e
[[ $security_status -ne 0 &&
  "$security_output" == *"non-extension SECURITY DEFINER routines are not supported"* ]] || {
  echo "Neon ownership reconciliation accepted a SECURITY DEFINER routine." >&2
  exit 1
}

echo "Neon ownership reconciliation contract passed."
