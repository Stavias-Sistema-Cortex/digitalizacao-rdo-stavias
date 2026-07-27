#!/usr/bin/env bash
set -euo pipefail

migrator_user="${CORTEX_POSTGRES_MIGRATOR_USER:-cortex_migrator}"
runtime_user="${CORTEX_POSTGRES_RUNTIME_USER:-cortex_runtime}"
database_name="${POSTGRES_DB:-StaviasCortex}"

for role_name in "$migrator_user" "$runtime_user"; do
  if [[ ! "$role_name" =~ ^[a-z_][a-z0-9_]*$ ]]; then
    echo "PostgreSQL role names must be lowercase SQL identifiers." >&2
    exit 1
  fi
done

migrator_password="$(</run/secrets/postgres_migrator_password)"
runtime_password="$(</run/secrets/postgres_runtime_password)"

if [[ -z "$migrator_password" || -z "$runtime_password" ]]; then
  echo "PostgreSQL role password files must not be empty." >&2
  exit 1
fi

psql \
  --set=ON_ERROR_STOP=1 \
  --set=database_name="$database_name" \
  --set=migrator_user="$migrator_user" \
  --set=runtime_user="$runtime_user" \
  --set=migrator_password="$migrator_password" \
  --set=runtime_password="$runtime_password" \
  --username "$POSTGRES_USER" \
  --dbname "$database_name" <<'SQL'
SELECT format(
  'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT',
  :'migrator_user',
  :'migrator_password'
)
WHERE NOT EXISTS (
  SELECT 1 FROM pg_roles WHERE rolname = :'migrator_user'
)
\gexec

SELECT format(
  'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT',
  :'runtime_user',
  :'runtime_password'
)
WHERE NOT EXISTS (
  SELECT 1 FROM pg_roles WHERE rolname = :'runtime_user'
)
\gexec

ALTER DATABASE :"database_name" OWNER TO :"migrator_user";
GRANT CONNECT ON DATABASE :"database_name" TO :"runtime_user";
GRANT USAGE ON SCHEMA public TO :"runtime_user";

ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_user" IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"runtime_user";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_user" IN SCHEMA public
  GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO :"runtime_user";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_user" IN SCHEMA public
  GRANT EXECUTE ON FUNCTIONS TO :"runtime_user";
SQL

unset migrator_password runtime_password
