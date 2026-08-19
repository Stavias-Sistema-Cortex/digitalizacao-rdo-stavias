#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
script="$repo_root/scripts/deploy/configure-local-upstream-resolution.sh"
fixture="$(mktemp -d "${TMPDIR:-/tmp}/cortex-hosts-contract.XXXXXX")"
trap 'rm -rf "$fixture"' EXIT

file_mode() {
  if stat -c '%a' "$1" >/dev/null 2>&1; then
    stat -c '%a' "$1"
  else
    stat -f '%Lp' "$1"
  fi
}

assert_file_mode() {
  local expected="$1"
  local path="$2"
  local actual
  actual="$(file_mode "$path")"
  if [[ "$actual" != "$expected" ]]; then
    echo "Expected $path to have mode $expected, got $actual." >&2
    exit 1
  fi
}

hosts="$fixture/hosts"
backup="$fixture/hosts.before"
cat > "$hosts" <<'HOSTS'
127.0.0.1 localhost
192.168.0.15 cortex.portalstavias.com.br webserver # internal aliases
HOSTS
# Espelha a regressao encontrada no servidor: o arquivo ativo havia ficado
# privado e, por isso, os workers www-data caiam de volta no DNS publico.
chmod 600 "$hosts"

CORTEX_HOSTS_FILE="$hosts" \
CORTEX_HOSTS_BACKUP_FILE="$backup" \
  bash "$script"

grep -Fxq '127.0.0.1 cortex.portalstavias.com.br # cortex-local-upstream' "$hosts"
grep -Fxq '192.168.0.15 webserver # internal aliases' "$hosts"
[[ "$(grep -c 'cortex.portalstavias.com.br' "$hosts")" == "1" ]]
grep -Fxq '192.168.0.15 cortex.portalstavias.com.br webserver # internal aliases' "$backup"
assert_file_mode 644 "$hosts"
assert_file_mode 600 "$backup"

# Reapplying is idempotent and keeps a single canonical mapping.
CORTEX_HOSTS_FILE="$hosts" \
CORTEX_HOSTS_BACKUP_FILE="$fixture/hosts.second.before" \
  bash "$script"
[[ "$(grep -c 'cortex.portalstavias.com.br' "$hosts")" == "1" ]]

ln -s "$hosts" "$fixture/hosts-link"
if CORTEX_HOSTS_FILE="$fixture/hosts-link" \
  CORTEX_HOSTS_BACKUP_FILE="$fixture/refused.before" \
  bash "$script"; then
  echo "The local-upstream resolver accepted a symbolic-link hosts file." >&2
  exit 1
fi

# A privileged backup must never be created in a directory where another
# account could replace the destination with a symlink between validation and
# creation.
unsafe_dir="$fixture/group-writable-backups"
mkdir "$unsafe_dir"
chmod 0770 "$unsafe_dir"
cp "$hosts" "$fixture/hosts-before-unsafe-dir"
if CORTEX_HOSTS_FILE="$hosts" \
  CORTEX_HOSTS_BACKUP_FILE="$unsafe_dir/hosts.before" \
  bash "$script"; then
  echo "The local-upstream resolver accepted a group-writable backup directory." >&2
  exit 1
fi
cmp -s "$hosts" "$fixture/hosts-before-unsafe-dir"
[[ ! -e "$unsafe_dir/hosts.before" && ! -L "$unsafe_dir/hosts.before" ]]

echo "Local upstream resolution contract passed."
