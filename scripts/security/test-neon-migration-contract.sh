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

bash -n "$script"
grep -Fq 'set -euo pipefail' "$script"
grep -Fq 'umask 077' "$script"
grep -Fq 'mktemp -d' "$script"
grep -Fq 'trap cleanup EXIT' "$script"
grep -Fq 'pg_dump' "$script"
grep -Fq -- '--format=custom' "$script"
grep -Fq -- '--no-owner' "$script"
grep -Fq -- '--no-acl' "$script"
grep -Fq 'pg_restore' "$script"
grep -Fq -- '--exit-on-error' "$script"
grep -Fq 'sslmode=require' "$script"

if grep -Eq -- '--clean|--if-exists|dropdb|DROP DATABASE' "$script"; then
  echo "Migration script contains a destructive target operation" >&2
  exit 1
fi
if grep -Eq 'set -x|echo .*(PGURI|PASSWORD)' "$script"; then
  echo "Migration script can disclose a credential" >&2
  exit 1
fi

echo "Neon migration safety contract passed."
