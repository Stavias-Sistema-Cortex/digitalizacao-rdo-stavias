\set ON_ERROR_STOP on

SELECT 'CREATE DATABASE "StaviasCortex"'
WHERE NOT EXISTS (
    SELECT 1 FROM pg_database WHERE datname = 'StaviasCortex'
)
\gexec

SELECT format(
    'CREATE ROLE cortex_runtime LOGIN PASSWORD %L',
    :'runtime_password'
)
WHERE NOT EXISTS (
    SELECT 1 FROM pg_roles WHERE rolname = 'cortex_runtime'
)
\gexec

ALTER ROLE cortex_runtime PASSWORD :'runtime_password';
GRANT CONNECT ON DATABASE "StaviasCortex" TO cortex_runtime;
