#!/usr/bin/env bash
set -euo pipefail

repository_root="$(git rev-parse --show-toplevel)"
contract_root="$(mktemp -d "${TMPDIR:-/tmp}/cortex-normal-launchers.XXXXXX")"
contract_root="$(cd "$contract_root" && pwd)"

cleanup() {
  if [[ -d "$contract_root" && "$contract_root" == "${TMPDIR:-/tmp}"/cortex-normal-launchers.* ]]; then
    find "$contract_root" -depth -delete
  fi
}
trap cleanup EXIT

mirror_root="$contract_root/repository"
fake_bin="$contract_root/bin"
capture_root="$contract_root/capture"
secret_root="$contract_root/secrets"
mkdir -p \
  "$mirror_root/scripts/dev" \
  "$mirror_root/scripts/security" \
  "$mirror_root/apps/api" \
  "$fake_bin" \
  "$capture_root" \
  "$secret_root"

for source_path in \
  scripts/dev/load-local-env.sh \
  scripts/dev/normal-runtime-env.sh \
  scripts/dev/operational-memory-cursor-preflight.sh \
  scripts/dev/postgres-cortex-common.sh \
  scripts/dev/run-api.sh \
  scripts/dev/run-compose.sh \
  scripts/dev/run-api-docker.sh; do
  if [[ -f "$repository_root/$source_path" ]]; then
    cp "$repository_root/$source_path" "$mirror_root/$source_path"
  fi
done
cp "$repository_root/compose.local.yml" "$mirror_root/compose.local.yml"

for secret_name in postgres cpf_hmac offline_private offline_public memory_cursor; do
  printf 'disposable-contract-value-0000000000000000' > "$secret_root/$secret_name"
done

canonical_database="Sta""vias""Cortex"
cat > "$mirror_root/.env" <<EOF
CORTEX_POSTGRES_URL=jdbc:postgresql://127.0.0.1:5432/$canonical_database
CORTEX_POSTGRES_DOCKER_URL=jdbc:postgresql://host.docker.internal:5432/$canonical_database
CORTEX_POSTGRES_USER=contract_user
CORTEX_POSTGRES_PASSWORD_FILE=$secret_root/postgres
CORTEX_POSTGRES_RUNTIME_READY=true
CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_ID=cpf-contract
CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE=$secret_root/cpf_hmac
CORTEX_AUTH_OFFLINE_GRANT_KEY_ID=offline-contract
CORTEX_AUTH_OFFLINE_GRANT_PRIVATE_KEY_FILE=$secret_root/offline_private
CORTEX_AUTH_OFFLINE_GRANT_PUBLIC_KEY_FILE=$secret_root/offline_public
CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID=cursor-contract
CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_FILE=$secret_root/memory_cursor
VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256=contract-fingerprint
CORTEX_WEB_PORT=15473
CORTEX_API_PORT=18091
PORT=18092
CORTEX_SYNC_ENABLED=true
OTP=activation-marker
VENDOR_OTP_SECRET=activation-marker
CORTEX_AUTH_OTP_ROTATION_TOKEN=activation-marker
CORTEX_EMAIL_PROVIDER=smtp
CORTEX_SMTP_HOST=smtp.invalid
CORTEX_FINANCE_EMAIL_ENABLED=true
EOF

cat > "$fake_bin/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
{
  printf 'docker'
  printf '\t%s' "$@"
  printf '\n'
  printf 'selected-ports\t%s\t%s\n' \
    "${CORTEX_WEB_PORT:-missing}" "${CORTEX_API_PORT:-missing}"
} >> "$CORTEX_CONTRACT_ARGUMENTS"
{
  printf 'docker-environment\n'
  env | sed 's/=.*//' | LC_ALL=C sort
} >> "$CORTEX_CONTRACT_ENVIRONMENT"
EOF

cat > "$fake_bin/lsof" <<'EOF'
#!/usr/bin/env bash
exit 1
EOF

cat > "$mirror_root/apps/api/mvnw" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
{
  printf 'mvnw'
  printf '\t%s' "$@"
  printf '\n'
} >> "$CORTEX_CONTRACT_ARGUMENTS"
{
  printf 'maven-environment\n'
  env | sed 's/=.*//' | LC_ALL=C sort
} >> "$CORTEX_CONTRACT_ENVIRONMENT"
printf '%s\n' "${SERVER_PORT:-missing}" > "$CORTEX_CONTRACT_SERVER_PORT"
EOF
chmod +x "$fake_bin/docker" "$fake_bin/lsof" "$mirror_root/apps/api/mvnw"

run_contract() {
  local launcher="$1"
  local name="$2"
  local arguments="$capture_root/$name.arguments"
  local environment="$capture_root/$name.environment"
  local server_port="$capture_root/$name.server-port"

  : > "$arguments"
  : > "$environment"
  PATH="$fake_bin:/usr/bin:/bin" \
    CORTEX_CONTRACT_ARGUMENTS="$arguments" \
    CORTEX_CONTRACT_ENVIRONMENT="$environment" \
    CORTEX_CONTRACT_SERVER_PORT="$server_port" \
    bash "$mirror_root/$launcher" > "$capture_root/$name.stdout"
}

run_contract scripts/dev/run-compose.sh compose
run_contract scripts/dev/run-api-docker.sh docker
run_contract scripts/dev/run-api.sh api

for environment_file in "$capture_root"/*.environment; do
  if grep -Eqi '(^|_)OTP(_|$)|^CORTEX_EMAIL_|^CORTEX_SMTP_|^CORTEX_FINANCE_EMAIL_' \
    "$environment_file"; then
    echo "normal launcher leaked activation or legacy e-mail environment names" >&2
    exit 1
  fi
done

grep -Fq $'docker\tcompose\t-f\t'"$mirror_root/compose.local.yml"$'\tup\t--build\t-d\t--remove-orphans' \
  "$capture_root/compose.arguments"
grep -Fq $'selected-ports\t15473\t18091' "$capture_root/compose.arguments"
grep -Fq 'CORTEX_WEB_PORT' "$capture_root/compose.environment"
grep -Fq 'CORTEX_API_PORT' "$capture_root/compose.environment"
grep -Fq 'CORTEX_SYNC_ENABLED' "$capture_root/compose.environment"

grep -Fq $'\t-p\t127.0.0.1:18091:8080' "$capture_root/docker.arguments"
grep -Fq $'\t-e\tCORTEX_WEB_PORT=15473' "$capture_root/docker.arguments"

grep -Fxq '18092' "$capture_root/api.server-port"
grep -Fq 'CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE' "$capture_root/api.environment"
grep -Fq 'CORTEX_AUTH_OFFLINE_GRANT_PRIVATE_KEY_FILE' "$capture_root/api.environment"
grep -Fq 'CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_FILE' "$capture_root/api.environment"

echo "Normal launcher child environments and selected port arguments passed."
