\set ON_ERROR_STOP on

BEGIN;

DO $guard$
DECLARE
  database_owner name;
  schema_owner name;
  unsafe_routine_count bigint;
  unsupported_object_count bigint;
BEGIN
  IF current_database() <> 'StaviasCortex' THEN
    RAISE EXCEPTION 'ownership reconciliation must target StaviasCortex';
  END IF;

  SELECT pg_get_userbyid(datdba)
    INTO database_owner
  FROM pg_database
  WHERE datname = current_database();

  IF database_owner <> 'neondb_owner' OR current_user <> database_owner THEN
    RAISE EXCEPTION
      'ownership reconciliation requires the canonical Neon database owner';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM pg_depend d
    WHERE d.classid = 'pg_namespace'::regclass
      AND d.objid = 'public'::regnamespace
      AND d.refclassid = 'pg_extension'::regclass
      AND d.deptype = 'e'
  ) THEN
    RAISE EXCEPTION
      'public schema is an extension member and requires manual review';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM pg_largeobject_metadata
  ) THEN
    RAISE EXCEPTION
      'large objects are unsupported and require manual review';
  END IF;

  IF EXISTS (
    WITH RECURSIVE owner_transfer_object(classid, objid, objsubid) AS (
      SELECT
        'pg_class'::regclass::oid,
        app_relation.oid,
        0::integer
      FROM pg_class app_relation
      JOIN pg_namespace app_namespace
        ON app_namespace.oid = app_relation.relnamespace
      WHERE app_namespace.nspname = 'public'
        AND app_relation.relkind IN ('r', 'p', 'v', 'm', 'f')
        AND app_relation.relowner = 'neondb_owner'::regrole
        AND NOT EXISTS (
          SELECT 1
          FROM pg_depend app_extension
          WHERE app_extension.classid = 'pg_class'::regclass
            AND app_extension.objid = app_relation.oid
            AND app_extension.refclassid = 'pg_extension'::regclass
            AND app_extension.deptype = 'e'
        )
      UNION
      SELECT
        ownership_dependency.classid,
        ownership_dependency.objid,
        ownership_dependency.objsubid
      FROM pg_depend ownership_dependency
      JOIN owner_transfer_object referenced_object
        ON ownership_dependency.refclassid = referenced_object.classid
        AND ownership_dependency.refobjid = referenced_object.objid
        AND (
          referenced_object.objsubid = 0
          OR ownership_dependency.refobjsubid = referenced_object.objsubid
        )
      WHERE ownership_dependency.deptype IN ('a', 'i')
    )
    SELECT 1
    FROM owner_transfer_object transferred_object
    JOIN pg_depend dependent_extension
      ON dependent_extension.classid = transferred_object.classid
      AND dependent_extension.objid = transferred_object.objid
      AND dependent_extension.refclassid = 'pg_extension'::regclass
      AND dependent_extension.deptype = 'e'
  ) THEN
    RAISE EXCEPTION
      'extension member depends on a non-extension public relation and requires manual review';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM pg_roles
    WHERE rolname = 'cortex_migrator'
      AND rolcanlogin
      AND NOT rolsuper
      AND NOT rolcreatedb
      AND NOT rolcreaterole
      AND NOT rolreplication
      AND NOT rolbypassrls
  ) THEN
    RAISE EXCEPTION 'cortex_migrator is missing or has unsafe attributes';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM pg_roles
    WHERE rolname = 'cortex_runtime'
      AND rolcanlogin
      AND NOT rolsuper
      AND NOT rolcreatedb
      AND NOT rolcreaterole
      AND NOT rolreplication
      AND NOT rolbypassrls
  ) THEN
    RAISE EXCEPTION 'cortex_runtime is missing or has unsafe attributes';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM pg_roles
    WHERE rolname = 'cloud_admin'
  ) THEN
    IF NOT EXISTS (
      SELECT 1
      FROM pg_roles
      WHERE rolname = 'cloud_admin'
        AND rolsuper
        AND rolcreaterole
        AND rolcanlogin
    ) OR (
      SELECT count(*)
      FROM pg_auth_members m
      WHERE m.roleid = 'cortex_migrator'::regrole
        AND m.member = current_user::regrole
    ) <> 2 OR (
      SELECT count(*)
      FROM pg_auth_members m
      WHERE m.roleid = 'cortex_migrator'::regrole
        AND m.member = current_user::regrole
        AND m.grantor = current_user::regrole
        AND NOT m.admin_option
        AND NOT m.inherit_option
        AND m.set_option
    ) <> 1 OR (
      SELECT count(*)
      FROM pg_auth_members m
      WHERE m.roleid = 'cortex_migrator'::regrole
        AND m.member = current_user::regrole
        AND m.grantor = 'cloud_admin'::regrole
        AND m.admin_option
        AND NOT m.inherit_option
        AND NOT m.set_option
    ) <> 1 THEN
      RAISE EXCEPTION
        'database owner membership in cortex_migrator is unsafe';
    END IF;
  ELSIF (
    SELECT count(*)
    FROM pg_auth_members m
    WHERE m.roleid = 'cortex_migrator'::regrole
      AND m.member = current_user::regrole
  ) <> 1 OR NOT EXISTS (
    SELECT 1
    FROM pg_auth_members m
    JOIN pg_roles grantor_role
      ON grantor_role.oid = m.grantor
    WHERE m.roleid = 'cortex_migrator'::regrole
      AND m.member = current_user::regrole
      AND (
        m.grantor = current_user::regrole
        OR grantor_role.rolsuper
      )
      AND NOT m.admin_option
      AND NOT m.inherit_option
      AND m.set_option
  ) THEN
    RAISE EXCEPTION
      'database owner membership in cortex_migrator is unsafe';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM pg_roles
    WHERE rolname = 'cloud_admin'
  ) THEN
    IF (
      SELECT count(*)
      FROM pg_auth_members m
      WHERE m.roleid = 'cortex_runtime'::regrole
    ) <> 1 OR NOT EXISTS (
      SELECT 1
      FROM pg_auth_members m
      WHERE m.roleid = 'cortex_runtime'::regrole
        AND m.member = current_user::regrole
        AND m.grantor = 'cloud_admin'::regrole
        AND m.admin_option
        AND NOT m.inherit_option
        AND NOT m.set_option
    ) THEN
      RAISE EXCEPTION
        'database owner membership in cortex_runtime is unsafe';
    END IF;
  ELSIF EXISTS (
    SELECT 1
    FROM pg_auth_members m
    WHERE m.roleid = 'cortex_runtime'::regrole
  ) THEN
    RAISE EXCEPTION
      'cortex_runtime has an unexpected member';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM pg_auth_members m
    WHERE m.roleid = 'cortex_migrator'::regrole
      AND m.member <> current_user::regrole
  ) THEN
    RAISE EXCEPTION
      'cortex_migrator has an unexpected member';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM pg_auth_members m
    WHERE m.member = 'cortex_runtime'::regrole
  ) THEN
    RAISE EXCEPTION
      'cortex_runtime has an unexpected parent-role membership';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM pg_auth_members m
    WHERE m.member = 'cortex_migrator'::regrole
  ) THEN
    RAISE EXCEPTION
      'cortex_migrator has an unexpected parent-role membership';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM pg_default_acl d
    WHERE d.defaclrole = 'cortex_runtime'::regrole
  ) THEN
    RAISE EXCEPTION
      'cortex_runtime has unexpected default privileges';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM pg_default_acl d
    WHERE d.defaclrole = 'cortex_migrator'::regrole
      AND d.defaclnamespace NOT IN (
        0,
        'public'::regnamespace
      )
  ) THEN
    RAISE EXCEPTION
      'cortex_migrator has default privileges outside public';
  END IF;

  SELECT pg_get_userbyid(nspowner)
    INTO schema_owner
  FROM pg_namespace
  WHERE nspname = 'public';

  IF schema_owner NOT IN (
    'neondb_owner',
    'pg_database_owner',
    'cortex_migrator'
  ) THEN
    RAISE EXCEPTION 'public schema has an unexpected owner';
  END IF;

  SELECT count(*)
    INTO unsafe_routine_count
  FROM pg_proc p
  JOIN pg_namespace n ON n.oid = p.pronamespace
  WHERE n.nspname = 'public'
    AND p.prosecdef
    AND NOT EXISTS (
      SELECT 1
      FROM pg_depend d
      WHERE d.classid = 'pg_proc'::regclass
        AND d.objid = p.oid
        AND d.deptype = 'e'
    );

  IF unsafe_routine_count <> 0 THEN
    RAISE EXCEPTION
      'non-extension SECURITY DEFINER routines are not supported';
  END IF;

  SELECT count(*)
    INTO unsupported_object_count
  FROM (
    SELECT 'unsupported routine' AS object_class, p.oid
    FROM pg_proc p
    JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public'
      AND p.prokind NOT IN ('f', 'p', 'a')
      AND NOT EXISTS (
        SELECT 1
        FROM pg_depend d
        WHERE d.classid = 'pg_proc'::regclass
          AND d.objid = p.oid
          AND d.deptype = 'e'
      )
    UNION ALL
    SELECT 'composite type', c.oid
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public'
      AND c.relkind = 'c'
      AND NOT EXISTS (
        SELECT 1
        FROM pg_depend d
        WHERE d.classid = 'pg_class'::regclass
          AND d.objid = c.oid
          AND d.deptype = 'e'
      )
      AND NOT EXISTS (
        SELECT 1
        FROM pg_type t
        JOIN pg_depend d
          ON d.classid = 'pg_type'::regclass
          AND d.objid = t.oid
          AND d.deptype = 'e'
        WHERE t.typrelid = c.oid
      )
    UNION ALL
    SELECT 'type', t.oid
    FROM pg_type t
    JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = 'public'
      AND t.typrelid = 0
      AND t.typelem = 0
      AND NOT EXISTS (
        SELECT 1
        FROM pg_depend d
        WHERE d.classid = 'pg_type'::regclass
          AND d.objid = t.oid
          AND d.deptype = 'e'
      )
    UNION ALL
    SELECT 'collation', c.oid
    FROM pg_collation c
    JOIN pg_namespace n ON n.oid = c.collnamespace
    WHERE n.nspname = 'public'
      AND NOT EXISTS (
        SELECT 1
        FROM pg_depend d
        WHERE d.classid = 'pg_collation'::regclass
          AND d.objid = c.oid
          AND d.deptype = 'e'
      )
    UNION ALL
    SELECT 'conversion', c.oid
    FROM pg_conversion c
    JOIN pg_namespace n ON n.oid = c.connamespace
    WHERE n.nspname = 'public'
      AND NOT EXISTS (
        SELECT 1
        FROM pg_depend d
        WHERE d.classid = 'pg_conversion'::regclass
          AND d.objid = c.oid
          AND d.deptype = 'e'
      )
    UNION ALL
    SELECT 'operator', o.oid
    FROM pg_operator o
    JOIN pg_namespace n ON n.oid = o.oprnamespace
    WHERE n.nspname = 'public'
      AND NOT EXISTS (
        SELECT 1
        FROM pg_depend d
        WHERE d.classid = 'pg_operator'::regclass
          AND d.objid = o.oid
          AND d.deptype = 'e'
      )
    UNION ALL
    SELECT 'operator class', o.oid
    FROM pg_opclass o
    JOIN pg_namespace n ON n.oid = o.opcnamespace
    WHERE n.nspname = 'public'
      AND NOT EXISTS (
        SELECT 1
        FROM pg_depend d
        WHERE d.classid = 'pg_opclass'::regclass
          AND d.objid = o.oid
          AND d.deptype = 'e'
      )
    UNION ALL
    SELECT 'operator family', o.oid
    FROM pg_opfamily o
    JOIN pg_namespace n ON n.oid = o.opfnamespace
    WHERE n.nspname = 'public'
      AND NOT EXISTS (
        SELECT 1
        FROM pg_depend d
        WHERE d.classid = 'pg_opfamily'::regclass
          AND d.objid = o.oid
          AND d.deptype = 'e'
      )
    UNION ALL
    SELECT 'text search configuration', t.oid
    FROM pg_ts_config t
    JOIN pg_namespace n ON n.oid = t.cfgnamespace
    WHERE n.nspname = 'public'
      AND NOT EXISTS (
        SELECT 1
        FROM pg_depend d
        WHERE d.classid = 'pg_ts_config'::regclass
          AND d.objid = t.oid
          AND d.deptype = 'e'
      )
    UNION ALL
    SELECT 'text search dictionary', t.oid
    FROM pg_ts_dict t
    JOIN pg_namespace n ON n.oid = t.dictnamespace
    WHERE n.nspname = 'public'
      AND NOT EXISTS (
        SELECT 1
        FROM pg_depend d
        WHERE d.classid = 'pg_ts_dict'::regclass
          AND d.objid = t.oid
          AND d.deptype = 'e'
      )
    UNION ALL
    SELECT 'text search parser', t.oid
    FROM pg_ts_parser t
    JOIN pg_namespace n ON n.oid = t.prsnamespace
    WHERE n.nspname = 'public'
      AND NOT EXISTS (
        SELECT 1
        FROM pg_depend d
        WHERE d.classid = 'pg_ts_parser'::regclass
          AND d.objid = t.oid
          AND d.deptype = 'e'
      )
    UNION ALL
    SELECT 'text search template', t.oid
    FROM pg_ts_template t
    JOIN pg_namespace n ON n.oid = t.tmplnamespace
    WHERE n.nspname = 'public'
      AND NOT EXISTS (
        SELECT 1
        FROM pg_depend d
        WHERE d.classid = 'pg_ts_template'::regclass
          AND d.objid = t.oid
          AND d.deptype = 'e'
      )
  ) unsupported;

  IF unsupported_object_count <> 0 THEN
    RAISE EXCEPTION
      '% unsupported non-extension public objects require manual review',
      unsupported_object_count;
  END IF;
END
$guard$;

REVOKE ALL PRIVILEGES
  ON DATABASE "StaviasCortex"
  FROM PUBLIC, cortex_migrator, cortex_runtime CASCADE;
GRANT CONNECT, CREATE
  ON DATABASE "StaviasCortex" TO cortex_migrator;
GRANT CONNECT
  ON DATABASE "StaviasCortex" TO cortex_runtime;

DO $schema_owner$
BEGIN
  IF (
    SELECT pg_get_userbyid(nspowner)
    FROM pg_namespace
    WHERE nspname = 'public'
  ) <> 'cortex_migrator' THEN
    ALTER SCHEMA public OWNER TO cortex_migrator;
  END IF;
END
$schema_owner$;

DO $relation_owners$
DECLARE
  app_object record;
  owner_command text;
BEGIN
  FOR app_object IN
    SELECT c.relkind, n.nspname, c.relname
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public'
      AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
      AND c.relowner = 'neondb_owner'::regrole
      AND NOT EXISTS (
        SELECT 1
        FROM pg_depend d
        WHERE d.classid = 'pg_class'::regclass
          AND d.objid = c.oid
          AND d.deptype = 'e'
      )
    ORDER BY n.nspname, c.relname
  LOOP
    owner_command := CASE app_object.relkind
      WHEN 'v' THEN 'ALTER VIEW'
      WHEN 'm' THEN 'ALTER MATERIALIZED VIEW'
      WHEN 'f' THEN 'ALTER FOREIGN TABLE'
      ELSE 'ALTER TABLE'
    END;
    EXECUTE format(
      '%s %I.%I OWNER TO cortex_migrator',
      owner_command,
      app_object.nspname,
      app_object.relname
    );
  END LOOP;

  FOR app_object IN
    SELECT n.nspname, c.relname
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public'
      AND c.relkind = 'S'
      AND c.relowner = 'neondb_owner'::regrole
      AND NOT EXISTS (
        SELECT 1
        FROM pg_depend d
        WHERE d.classid = 'pg_class'::regclass
          AND d.objid = c.oid
          AND d.deptype = 'e'
      )
    ORDER BY n.nspname, c.relname
  LOOP
    EXECUTE format(
      'ALTER SEQUENCE %I.%I OWNER TO cortex_migrator',
      app_object.nspname,
      app_object.relname
    );
  END LOOP;
END
$relation_owners$;

DO $routine_owners$
DECLARE
  app_routine record;
  owner_command text;
BEGIN
  FOR app_routine IN
    SELECT
      p.prokind,
      n.nspname,
      p.proname,
      pg_get_function_identity_arguments(p.oid) AS identity_arguments
    FROM pg_proc p
    JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public'
      AND p.prokind IN ('f', 'p', 'a')
      AND p.proowner = 'neondb_owner'::regrole
      AND NOT EXISTS (
        SELECT 1
        FROM pg_depend d
        WHERE d.classid = 'pg_proc'::regclass
          AND d.objid = p.oid
          AND d.deptype = 'e'
      )
    ORDER BY n.nspname, p.proname, p.oid
  LOOP
    owner_command := CASE app_routine.prokind
      WHEN 'p' THEN 'ALTER PROCEDURE'
      WHEN 'a' THEN 'ALTER AGGREGATE'
      ELSE 'ALTER FUNCTION'
    END;
    EXECUTE format(
      '%s %I.%I(%s) OWNER TO cortex_migrator',
      owner_command,
      app_routine.nspname,
      app_routine.proname,
      app_routine.identity_arguments
    );
  END LOOP;
END
$routine_owners$;

DO $statistics_owners$
DECLARE
  app_statistics record;
BEGIN
  FOR app_statistics IN
    SELECT n.nspname, s.stxname
    FROM pg_statistic_ext s
    JOIN pg_namespace n ON n.oid = s.stxnamespace
    WHERE n.nspname = 'public'
      AND s.stxowner = 'neondb_owner'::regrole
      AND NOT EXISTS (
        SELECT 1
        FROM pg_depend d
        WHERE d.classid = 'pg_statistic_ext'::regclass
          AND d.objid = s.oid
          AND d.deptype = 'e'
      )
    ORDER BY n.nspname, s.stxname
  LOOP
    EXECUTE format(
      'ALTER STATISTICS %I.%I OWNER TO cortex_migrator',
      app_statistics.nspname,
      app_statistics.stxname
    );
  END LOOP;
END
$statistics_owners$;

SET LOCAL ROLE cortex_migrator;
REVOKE ALL PRIVILEGES
  ON SCHEMA public
  FROM PUBLIC, cortex_runtime CASCADE;
GRANT USAGE ON SCHEMA public TO cortex_runtime;

DO $relation_acl$
DECLARE
  app_relation record;
BEGIN
  FOR app_relation IN
    SELECT n.nspname, c.relname
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public'
      AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
      AND c.relowner = 'cortex_migrator'::regrole
      AND NOT EXISTS (
        SELECT 1
        FROM pg_depend d
        WHERE d.classid = 'pg_class'::regclass
          AND d.objid = c.oid
          AND d.deptype = 'e'
      )
    ORDER BY n.nspname, c.relname
  LOOP
    EXECUTE format(
      'REVOKE ALL PRIVILEGES ON TABLE %I.%I '
      || 'FROM PUBLIC, cortex_runtime CASCADE',
      app_relation.nspname,
      app_relation.relname
    );
    IF app_relation.relname IN (
      'flyway_schema_history',
      'cortex_release_marker'
    ) THEN
      EXECUTE format(
        'GRANT SELECT ON TABLE %I.%I TO cortex_runtime',
        app_relation.nspname,
        app_relation.relname
      );
    ELSE
      EXECUTE format(
        'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE %I.%I '
        || 'TO cortex_runtime',
        app_relation.nspname,
        app_relation.relname
      );
    END IF;
  END LOOP;
END
$relation_acl$;

DO $sequence_acl$
DECLARE
  app_sequence record;
BEGIN
  FOR app_sequence IN
    SELECT n.nspname, c.relname
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public'
      AND c.relkind = 'S'
      AND c.relowner = 'cortex_migrator'::regrole
      AND NOT EXISTS (
        SELECT 1
        FROM pg_depend d
        WHERE d.classid = 'pg_class'::regclass
          AND d.objid = c.oid
          AND d.deptype = 'e'
      )
    ORDER BY n.nspname, c.relname
  LOOP
    EXECUTE format(
      'REVOKE ALL PRIVILEGES ON SEQUENCE %I.%I '
      || 'FROM PUBLIC, cortex_runtime CASCADE',
      app_sequence.nspname,
      app_sequence.relname
    );
    EXECUTE format(
      'GRANT USAGE ON SEQUENCE %I.%I '
      || 'TO cortex_runtime',
      app_sequence.nspname,
      app_sequence.relname
    );
  END LOOP;
END
$sequence_acl$;

DO $routine_grants$
DECLARE
  app_routine record;
BEGIN
  FOR app_routine IN
    SELECT
      n.nspname,
      p.proname,
      pg_get_function_identity_arguments(p.oid) AS identity_arguments
    FROM pg_proc p
    JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public'
      AND p.prokind IN ('f', 'p', 'a')
      AND p.proowner = 'cortex_migrator'::regrole
      AND NOT EXISTS (
        SELECT 1
        FROM pg_depend d
        WHERE d.classid = 'pg_proc'::regclass
          AND d.objid = p.oid
          AND d.deptype = 'e'
      )
    ORDER BY n.nspname, p.proname, p.oid
  LOOP
    EXECUTE format(
      'REVOKE ALL PRIVILEGES ON ROUTINE %I.%I(%s) '
      || 'FROM PUBLIC, cortex_runtime CASCADE',
      app_routine.nspname,
      app_routine.proname,
      app_routine.identity_arguments
    );
    EXECUTE format(
      'GRANT EXECUTE ON ROUTINE %I.%I(%s) TO cortex_runtime',
      app_routine.nspname,
      app_routine.proname,
      app_routine.identity_arguments
    );
  END LOOP;
END
$routine_grants$;

ALTER DEFAULT PRIVILEGES FOR ROLE cortex_migrator
  REVOKE ALL PRIVILEGES ON TABLES
  FROM PUBLIC, cortex_runtime CASCADE;
ALTER DEFAULT PRIVILEGES FOR ROLE cortex_migrator IN SCHEMA public
  REVOKE ALL PRIVILEGES ON TABLES
  FROM PUBLIC, cortex_runtime CASCADE;
ALTER DEFAULT PRIVILEGES FOR ROLE cortex_migrator
  REVOKE ALL PRIVILEGES ON SEQUENCES
  FROM PUBLIC, cortex_runtime CASCADE;
ALTER DEFAULT PRIVILEGES FOR ROLE cortex_migrator IN SCHEMA public
  REVOKE ALL PRIVILEGES ON SEQUENCES
  FROM PUBLIC, cortex_runtime CASCADE;
ALTER DEFAULT PRIVILEGES FOR ROLE cortex_migrator
  REVOKE ALL PRIVILEGES ON FUNCTIONS
  FROM PUBLIC, cortex_runtime CASCADE;
ALTER DEFAULT PRIVILEGES FOR ROLE cortex_migrator IN SCHEMA public
  REVOKE ALL PRIVILEGES ON FUNCTIONS
  FROM PUBLIC, cortex_runtime CASCADE;
ALTER DEFAULT PRIVILEGES FOR ROLE cortex_migrator
  REVOKE ALL PRIVILEGES ON TYPES
  FROM PUBLIC, cortex_runtime CASCADE;
ALTER DEFAULT PRIVILEGES FOR ROLE cortex_migrator IN SCHEMA public
  REVOKE ALL PRIVILEGES ON TYPES
  FROM PUBLIC, cortex_runtime CASCADE;
ALTER DEFAULT PRIVILEGES FOR ROLE cortex_migrator
  REVOKE ALL PRIVILEGES ON SCHEMAS
  FROM PUBLIC, cortex_runtime CASCADE;
ALTER DEFAULT PRIVILEGES FOR ROLE cortex_migrator
  REVOKE ALL PRIVILEGES ON LARGE OBJECTS
  FROM PUBLIC, cortex_runtime CASCADE;
ALTER DEFAULT PRIVILEGES FOR ROLE cortex_migrator IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cortex_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE cortex_migrator IN SCHEMA public
  GRANT USAGE ON SEQUENCES TO cortex_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE cortex_migrator IN SCHEMA public
  GRANT EXECUTE ON FUNCTIONS TO cortex_runtime;
RESET ROLE;

ALTER DEFAULT PRIVILEGES FOR ROLE neondb_owner
  REVOKE ALL PRIVILEGES ON TABLES
  FROM PUBLIC, cortex_runtime CASCADE;
ALTER DEFAULT PRIVILEGES FOR ROLE neondb_owner IN SCHEMA public
  REVOKE ALL PRIVILEGES ON TABLES
  FROM PUBLIC, cortex_runtime CASCADE;
ALTER DEFAULT PRIVILEGES FOR ROLE neondb_owner
  REVOKE ALL PRIVILEGES ON SEQUENCES
  FROM PUBLIC, cortex_runtime CASCADE;
ALTER DEFAULT PRIVILEGES FOR ROLE neondb_owner IN SCHEMA public
  REVOKE ALL PRIVILEGES ON SEQUENCES
  FROM PUBLIC, cortex_runtime CASCADE;
ALTER DEFAULT PRIVILEGES FOR ROLE neondb_owner
  REVOKE ALL PRIVILEGES ON FUNCTIONS
  FROM PUBLIC, cortex_runtime CASCADE;
ALTER DEFAULT PRIVILEGES FOR ROLE neondb_owner IN SCHEMA public
  REVOKE ALL PRIVILEGES ON FUNCTIONS
  FROM PUBLIC, cortex_runtime CASCADE;
ALTER DEFAULT PRIVILEGES FOR ROLE neondb_owner
  REVOKE ALL PRIVILEGES ON TYPES
  FROM PUBLIC, cortex_runtime CASCADE;
ALTER DEFAULT PRIVILEGES FOR ROLE neondb_owner IN SCHEMA public
  REVOKE ALL PRIVILEGES ON TYPES
  FROM PUBLIC, cortex_runtime CASCADE;
ALTER DEFAULT PRIVILEGES FOR ROLE neondb_owner
  REVOKE ALL PRIVILEGES ON SCHEMAS
  FROM PUBLIC, cortex_runtime CASCADE;
ALTER DEFAULT PRIVILEGES FOR ROLE neondb_owner
  REVOKE ALL PRIVILEGES ON LARGE OBJECTS
  FROM PUBLIC, cortex_runtime CASCADE;

DO $verify$
DECLARE
  database_owner name;
  unexpected_owner_count bigint;
  unexpected_routine_owner_count bigint;
  unexpected_statistics_owner_count bigint;
  unsafe_runtime_relation_count bigint;
  unsafe_runtime_relation_acl_count bigint;
  unsafe_runtime_column_acl_count bigint;
  unsafe_runtime_sequence_count bigint;
  unsafe_runtime_sequence_acl_count bigint;
  unsafe_runtime_routine_count bigint;
  unsafe_runtime_routine_acl_count bigint;
  runtime_default_acl_count bigint;
  unsafe_runtime_default_acl_count bigint;
  owner_default_acl_count bigint;
  unsafe_database_acl_count bigint;
  runtime_database_acl_count bigint;
  migrator_database_acl_count bigint;
  unsafe_public_schema_acl_count bigint;
  runtime_public_schema_acl_count bigint;
  unsafe_external_schema_access_count bigint;
BEGIN
  SELECT pg_get_userbyid(datdba)
    INTO database_owner
  FROM pg_database
  WHERE datname = current_database();

  IF database_owner <> 'neondb_owner' THEN
    RAISE EXCEPTION 'database ownership changed unexpectedly';
  END IF;

  IF (
    SELECT pg_get_userbyid(nspowner)
    FROM pg_namespace
    WHERE nspname = 'public'
  ) <> 'cortex_migrator' THEN
    RAISE EXCEPTION 'public schema ownership was not reconciled';
  END IF;

  IF NOT has_database_privilege(
    'cortex_migrator',
    current_database(),
    'CONNECT'
  ) OR NOT has_database_privilege(
    'cortex_migrator',
    current_database(),
    'CREATE'
  ) THEN
    RAISE EXCEPTION 'migrator database privileges were not reconciled';
  END IF;

  IF NOT has_database_privilege(
    'cortex_runtime',
    current_database(),
    'CONNECT'
  ) OR has_database_privilege(
    'cortex_runtime',
    current_database(),
    'CREATE'
  ) OR has_database_privilege(
    'cortex_runtime',
    current_database(),
    'TEMPORARY'
  ) OR has_schema_privilege(
    'cortex_runtime',
    'public',
    'CREATE'
  ) OR NOT has_schema_privilege(
    'cortex_runtime',
    'public',
    'USAGE'
  ) THEN
    RAISE EXCEPTION 'runtime database or schema privileges are unsafe';
  END IF;

  SELECT count(*)
    INTO unsafe_database_acl_count
  FROM pg_database d
  CROSS JOIN LATERAL aclexplode(d.datacl) acl
  WHERE d.datname = current_database()
    AND NOT (
      acl.grantee = 'neondb_owner'::regrole
      OR (
        acl.grantee = 'cortex_runtime'::regrole
        AND acl.grantor = 'neondb_owner'::regrole
        AND acl.privilege_type = 'CONNECT'
        AND NOT acl.is_grantable
      )
      OR (
        acl.grantee = 'cortex_migrator'::regrole
        AND acl.grantor = 'neondb_owner'::regrole
        AND acl.privilege_type IN ('CONNECT', 'CREATE')
        AND NOT acl.is_grantable
      )
    );

  SELECT count(*)
    INTO runtime_database_acl_count
  FROM pg_database d
  CROSS JOIN LATERAL aclexplode(d.datacl) acl
  WHERE d.datname = current_database()
    AND acl.grantee = 'cortex_runtime'::regrole
    AND acl.grantor = 'neondb_owner'::regrole
    AND acl.privilege_type = 'CONNECT'
    AND NOT acl.is_grantable;

  SELECT count(*)
    INTO migrator_database_acl_count
  FROM pg_database d
  CROSS JOIN LATERAL aclexplode(d.datacl) acl
  WHERE d.datname = current_database()
    AND acl.grantee = 'cortex_migrator'::regrole
    AND acl.grantor = 'neondb_owner'::regrole
    AND acl.privilege_type IN ('CONNECT', 'CREATE')
    AND NOT acl.is_grantable;

  SELECT count(*)
    INTO unsafe_public_schema_acl_count
  FROM pg_namespace n
  CROSS JOIN LATERAL aclexplode(n.nspacl) acl
  WHERE n.nspname = 'public'
    AND NOT (
      acl.grantee = 'cortex_migrator'::regrole
      OR (
        acl.grantee = 'cortex_runtime'::regrole
        AND acl.grantor = 'cortex_migrator'::regrole
        AND acl.privilege_type = 'USAGE'
        AND NOT acl.is_grantable
      )
    );

  SELECT count(*)
    INTO runtime_public_schema_acl_count
  FROM pg_namespace n
  CROSS JOIN LATERAL aclexplode(n.nspacl) acl
  WHERE n.nspname = 'public'
    AND acl.grantee = 'cortex_runtime'::regrole
    AND acl.grantor = 'cortex_migrator'::regrole
    AND acl.privilege_type = 'USAGE'
    AND NOT acl.is_grantable;

  IF unsafe_database_acl_count <> 0
    OR runtime_database_acl_count <> 1
    OR migrator_database_acl_count <> 2
    OR unsafe_public_schema_acl_count <> 0
    OR runtime_public_schema_acl_count <> 1 THEN
    RAISE EXCEPTION
      'database or public schema ACLs are not exact';
  END IF;

  SELECT count(*)
    INTO unsafe_external_schema_access_count
  FROM pg_namespace n
  WHERE n.nspname <> 'public'
    AND n.nspname <> 'information_schema'
    AND n.nspname NOT LIKE 'pg\_%' ESCAPE '\'
    AND (
      n.nspowner = 'cortex_runtime'::regrole
      OR has_schema_privilege('cortex_runtime', n.oid, 'USAGE')
      OR has_schema_privilege('cortex_runtime', n.oid, 'CREATE')
    );

  IF unsafe_external_schema_access_count <> 0 THEN
    RAISE EXCEPTION
      'runtime can access user schemas outside public';
  END IF;

  SELECT count(*)
    INTO unexpected_owner_count
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

  IF unexpected_owner_count <> 0 THEN
    RAISE EXCEPTION
      '% non-extension public relations still have another owner',
      unexpected_owner_count;
  END IF;

  SELECT count(*)
    INTO unexpected_routine_owner_count
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

  IF unexpected_routine_owner_count <> 0 THEN
    RAISE EXCEPTION
      '% non-extension public routines still have another owner',
      unexpected_routine_owner_count;
  END IF;

  SELECT count(*)
    INTO unexpected_statistics_owner_count
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

  IF unexpected_statistics_owner_count <> 0 THEN
    RAISE EXCEPTION
      '% non-extension public statistics still have another owner',
      unexpected_statistics_owner_count;
  END IF;

  SELECT count(*)
    INTO unsafe_runtime_relation_count
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = 'public'
    AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
    AND c.relowner = 'cortex_migrator'::regrole
    AND NOT EXISTS (
      SELECT 1
      FROM pg_depend d
      WHERE d.classid = 'pg_class'::regclass
        AND d.objid = c.oid
        AND d.deptype = 'e'
    )
    AND (
      NOT has_table_privilege('cortex_runtime', c.oid, 'SELECT')
      OR (
        c.relname IN (
          'flyway_schema_history',
          'cortex_release_marker'
        )
        AND (
          has_table_privilege('cortex_runtime', c.oid, 'INSERT')
          OR has_table_privilege('cortex_runtime', c.oid, 'UPDATE')
          OR has_table_privilege('cortex_runtime', c.oid, 'DELETE')
        )
      )
      OR (
        c.relname NOT IN (
          'flyway_schema_history',
          'cortex_release_marker'
        )
        AND (
          NOT has_table_privilege('cortex_runtime', c.oid, 'INSERT')
          OR NOT has_table_privilege('cortex_runtime', c.oid, 'UPDATE')
          OR NOT has_table_privilege('cortex_runtime', c.oid, 'DELETE')
        )
      )
      OR has_table_privilege('cortex_runtime', c.oid, 'TRUNCATE')
      OR has_table_privilege('cortex_runtime', c.oid, 'REFERENCES')
      OR has_table_privilege('cortex_runtime', c.oid, 'TRIGGER')
      OR has_table_privilege('cortex_runtime', c.oid, 'MAINTAIN')
    );

  IF unsafe_runtime_relation_count <> 0 THEN
    RAISE EXCEPTION
      '% app relations have unsafe runtime privileges',
      unsafe_runtime_relation_count;
  END IF;

  SELECT count(*)
    INTO unsafe_runtime_relation_acl_count
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  CROSS JOIN LATERAL aclexplode(c.relacl) acl
  WHERE n.nspname = 'public'
    AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
    AND c.relowner = 'cortex_migrator'::regrole
    AND NOT EXISTS (
      SELECT 1
      FROM pg_depend d
      WHERE d.classid = 'pg_class'::regclass
        AND d.objid = c.oid
        AND d.deptype = 'e'
    )
    AND NOT (
      acl.grantee = 'cortex_migrator'::regrole
      OR (
        acl.grantee = 'cortex_runtime'::regrole
        AND acl.grantor = 'cortex_migrator'::regrole
        AND NOT acl.is_grantable
        AND (
          (
            c.relname IN (
              'flyway_schema_history',
              'cortex_release_marker'
            )
            AND acl.privilege_type = 'SELECT'
          )
          OR (
            c.relname NOT IN (
              'flyway_schema_history',
              'cortex_release_marker'
            )
            AND acl.privilege_type IN (
              'SELECT',
              'INSERT',
              'UPDATE',
              'DELETE'
            )
          )
        )
      )
    );

  IF unsafe_runtime_relation_acl_count <> 0 THEN
    RAISE EXCEPTION
      '% app relation ACLs retain PUBLIC, extra, or grant-option privileges',
      unsafe_runtime_relation_acl_count;
  END IF;

  SELECT count(*)
    INTO unsafe_runtime_column_acl_count
  FROM pg_attribute a
  JOIN pg_class c ON c.oid = a.attrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
  CROSS JOIN LATERAL aclexplode(a.attacl) acl
  WHERE n.nspname = 'public'
    AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
    AND c.relowner = 'cortex_migrator'::regrole
    AND a.attnum > 0
    AND NOT a.attisdropped
    AND NOT EXISTS (
      SELECT 1
      FROM pg_depend d
      WHERE d.classid = 'pg_class'::regclass
        AND d.objid = c.oid
        AND d.deptype = 'e'
    )
    AND acl.grantee <> 'cortex_migrator'::regrole;

  IF unsafe_runtime_column_acl_count <> 0 THEN
    RAISE EXCEPTION
      '% app column ACLs retain PUBLIC or runtime grants',
      unsafe_runtime_column_acl_count;
  END IF;

  SELECT count(*)
    INTO unsafe_runtime_sequence_count
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = 'public'
    AND c.relkind = 'S'
    AND c.relowner = 'cortex_migrator'::regrole
    AND NOT EXISTS (
      SELECT 1
      FROM pg_depend d
      WHERE d.classid = 'pg_class'::regclass
        AND d.objid = c.oid
        AND d.deptype = 'e'
    )
    AND (
      NOT has_sequence_privilege('cortex_runtime', c.oid, 'USAGE')
      OR has_sequence_privilege('cortex_runtime', c.oid, 'SELECT')
      OR has_sequence_privilege('cortex_runtime', c.oid, 'UPDATE')
    );

  IF unsafe_runtime_sequence_count <> 0 THEN
    RAISE EXCEPTION
      '% app sequences have unsafe runtime privileges',
      unsafe_runtime_sequence_count;
  END IF;

  SELECT count(*)
    INTO unsafe_runtime_sequence_acl_count
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  CROSS JOIN LATERAL aclexplode(c.relacl) acl
  WHERE n.nspname = 'public'
    AND c.relkind = 'S'
    AND c.relowner = 'cortex_migrator'::regrole
    AND NOT EXISTS (
      SELECT 1
      FROM pg_depend d
      WHERE d.classid = 'pg_class'::regclass
        AND d.objid = c.oid
        AND d.deptype = 'e'
    )
    AND NOT (
      acl.grantee = 'cortex_migrator'::regrole
      OR (
        acl.grantee = 'cortex_runtime'::regrole
        AND acl.grantor = 'cortex_migrator'::regrole
        AND NOT acl.is_grantable
        AND acl.privilege_type = 'USAGE'
      )
    );

  IF unsafe_runtime_sequence_acl_count <> 0 THEN
    RAISE EXCEPTION
      '% app sequence ACLs retain PUBLIC, extra, or grant-option privileges',
      unsafe_runtime_sequence_acl_count;
  END IF;

  SELECT count(*)
    INTO unsafe_runtime_routine_count
  FROM pg_proc p
  JOIN pg_namespace n ON n.oid = p.pronamespace
  WHERE n.nspname = 'public'
    AND p.prokind IN ('f', 'p', 'a')
    AND p.proowner = 'cortex_migrator'::regrole
    AND NOT EXISTS (
      SELECT 1
      FROM pg_depend d
      WHERE d.classid = 'pg_proc'::regclass
        AND d.objid = p.oid
        AND d.deptype = 'e'
    )
    AND NOT has_function_privilege(
      'cortex_runtime',
      p.oid,
      'EXECUTE'
    );

  IF unsafe_runtime_routine_count <> 0 THEN
    RAISE EXCEPTION
      '% app routines are not executable by runtime',
      unsafe_runtime_routine_count;
  END IF;

  SELECT count(*)
    INTO unsafe_runtime_routine_acl_count
  FROM pg_proc p
  JOIN pg_namespace n ON n.oid = p.pronamespace
  CROSS JOIN LATERAL aclexplode(p.proacl) acl
  WHERE n.nspname = 'public'
    AND p.prokind IN ('f', 'p', 'a')
    AND p.proowner = 'cortex_migrator'::regrole
    AND NOT EXISTS (
      SELECT 1
      FROM pg_depend d
      WHERE d.classid = 'pg_proc'::regclass
        AND d.objid = p.oid
        AND d.deptype = 'e'
    )
    AND NOT (
      acl.grantee = 'cortex_migrator'::regrole
      OR (
        acl.grantee = 'cortex_runtime'::regrole
        AND acl.grantor = 'cortex_migrator'::regrole
        AND NOT acl.is_grantable
        AND acl.privilege_type = 'EXECUTE'
      )
    );

  IF unsafe_runtime_routine_acl_count <> 0 THEN
    RAISE EXCEPTION
      '% app routine ACLs retain PUBLIC, extra, or grant-option privileges',
      unsafe_runtime_routine_acl_count;
  END IF;

  SELECT count(*)
    INTO runtime_default_acl_count
  FROM pg_default_acl d
  CROSS JOIN LATERAL aclexplode(d.defaclacl) acl
  WHERE d.defaclrole = 'cortex_migrator'::regrole
    AND d.defaclnamespace = 'public'::regnamespace
    AND acl.grantee = 'cortex_runtime'::regrole
    AND acl.grantor = 'cortex_migrator'::regrole
    AND NOT acl.is_grantable
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
    );

  IF runtime_default_acl_count <> 6 THEN
    RAISE EXCEPTION
      'runtime default privilege allowlist is incomplete';
  END IF;

  SELECT count(*)
    INTO owner_default_acl_count
  FROM pg_default_acl d
  CROSS JOIN LATERAL aclexplode(d.defaclacl) acl
  WHERE d.defaclrole = 'neondb_owner'::regrole
    AND acl.grantee <> 'neondb_owner'::regrole;

  IF owner_default_acl_count <> 0 THEN
    RAISE EXCEPTION
      '% database-owner default grants remain after reconciliation',
      owner_default_acl_count;
  END IF;

  SELECT count(*)
    INTO unsafe_runtime_default_acl_count
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

  IF unsafe_runtime_default_acl_count <> 0 THEN
    RAISE EXCEPTION
      '% runtime default ACLs remain unsafe',
      unsafe_runtime_default_acl_count;
  END IF;
END
$verify$;

COMMIT;
