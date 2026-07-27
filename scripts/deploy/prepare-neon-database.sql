\set ON_ERROR_STOP on

SELECT 'CREATE DATABASE "StaviasCortex"'
WHERE NOT EXISTS (
    SELECT 1 FROM pg_database WHERE datname = 'StaviasCortex'
)
\gexec
