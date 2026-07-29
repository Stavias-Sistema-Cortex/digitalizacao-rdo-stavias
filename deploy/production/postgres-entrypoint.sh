#!/usr/bin/env sh
set -eu

source_secret_dir="/run/secrets"
staged_secret_dir="/run/postgresql/cortex-secrets"

if [ "$(id -u)" -ne 0 ]; then
  echo "PostgreSQL secret staging must start as root." >&2
  exit 1
fi

stage_secret() {
  source_path="$1"
  destination_path="$2"
  source_mode="$(stat -c '%a' "$source_path" 2>/dev/null || true)"

  if [ ! -f "$source_path" ] ||
    [ -L "$source_path" ] ||
    [ "$source_mode" != "600" ]; then
    echo "PostgreSQL source secrets must be regular mode 0600 files." >&2
    exit 1
  fi

  install \
    -o postgres \
    -g postgres \
    -m 600 \
    "$source_path" \
    "$destination_path"
}

rm -rf "$staged_secret_dir"
install -d -o postgres -g postgres -m 700 "$staged_secret_dir"

stage_secret \
  "$source_secret_dir/postgres_admin_password" \
  "$staged_secret_dir/admin"
stage_secret \
  "$source_secret_dir/postgres_migrator_password" \
  "$staged_secret_dir/migrator"
stage_secret \
  "$source_secret_dir/postgres_runtime_password" \
  "$staged_secret_dir/runtime"

export POSTGRES_PASSWORD_FILE="$staged_secret_dir/admin"
export CORTEX_POSTGRES_MIGRATOR_PASSWORD_FILE="$staged_secret_dir/migrator"
export CORTEX_POSTGRES_RUNTIME_PASSWORD_FILE="$staged_secret_dir/runtime"

exec /usr/local/bin/docker-entrypoint.sh "$@"
