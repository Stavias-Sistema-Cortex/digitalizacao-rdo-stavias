#!/usr/bin/env bash
set -euo pipefail

repository_root="$(git rev-parse --show-toplevel)"
real_docker="$(command -v docker || true)"
if [[ -z "$real_docker" ]] || ! "$real_docker" compose version >/dev/null 2>&1; then
  echo "Docker Compose is required to render the normal-runtime descendant environment." >&2
  exit 1
fi
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
activation_marker="activation-marker"
academy_url_marker="jdbc:mysql://academy.local.invalid:3306/academy"
academy_user_marker="academy-local-contract-user"
academy_password_marker="academy-local-contract-password-do-not-log"

write_contract_environment() {
  local academy_enabled="$1"
  local academy_password="$2"

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
CORTEX_PUBLIC_ORIGIN=http://localhost:15473
CORTEX_CORS_ALLOWED_ORIGINS=http://localhost:5174
CORTEX_AUTH_WEBAUTHN_ALLOWED_ORIGINS=http://localhost:5174
CORTEX_SYNC_ACADEMY_ENABLED=$academy_enabled
CORTEX_ACADEMY_DB_URL=$academy_url_marker
CORTEX_ACADEMY_DB_USER=$academy_user_marker
CORTEX_ACADEMY_DB_PASSWORD=$academy_password
CORTEX_SYNC_ZELADORIA_ENABLED=true
OTP=$activation_marker
VENDOR_OTP_SECRET=$activation_marker
CORTEX_AUTH_OTP_ROTATION_TOKEN=$activation_marker
CORTEX_EMAIL_PROVIDER=smtp
CORTEX_SMTP_HOST=smtp.invalid
CORTEX_FINANCE_EMAIL_ENABLED=true
EOF
}

write_contract_environment true "$academy_password_marker"

cat > "$fake_bin/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "compose" && " $* " == *" up "* ]]; then
  "$CORTEX_CONTRACT_REAL_DOCKER" compose \
    --env-file "$CORTEX_CONTRACT_COMPOSE_ENV_FILE" \
    -f "$CORTEX_CONTRACT_COMPOSE_FILE" \
    config --format json > "$CORTEX_CONTRACT_DESCENDANT_CONFIG"
fi
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
{
  printf '%s\n' "${SERVER_PORT:-missing}"
  printf '%s\n' "${CORTEX_CORS_ALLOWED_ORIGINS:-missing}"
  printf '%s\n' "${CORTEX_AUTH_WEBAUTHN_ALLOWED_ORIGINS:-missing}"
} > "$CORTEX_CONTRACT_SERVER_PORT"
EOF
chmod +x "$fake_bin/docker" "$fake_bin/lsof" "$mirror_root/apps/api/mvnw"

run_contract() {
  local launcher="$1"
  local name="$2"
  local arguments="$capture_root/$name.arguments"
  local environment="$capture_root/$name.environment"
  local server_port="$capture_root/$name.server-port"
  local descendant_config="$capture_root/$name.descendant-config.json"

  : > "$arguments"
  : > "$environment"
  PATH="$fake_bin:/usr/bin:/bin" \
    CORTEX_CONTRACT_ARGUMENTS="$arguments" \
    CORTEX_CONTRACT_REAL_DOCKER="$real_docker" \
    CORTEX_CONTRACT_COMPOSE_ENV_FILE="$mirror_root/.env" \
    CORTEX_CONTRACT_COMPOSE_FILE="$mirror_root/compose.local.yml" \
    CORTEX_CONTRACT_DESCENDANT_CONFIG="$descendant_config" \
    CORTEX_CONTRACT_ENVIRONMENT="$environment" \
    CORTEX_CONTRACT_SERVER_PORT="$server_port" \
    bash "$mirror_root/$launcher" \
      > "$capture_root/$name.stdout" \
      2> "$capture_root/$name.stderr"
}

run_contract scripts/dev/run-compose.sh compose
run_contract scripts/dev/run-api-docker.sh docker
run_contract scripts/dev/run-api.sh api

node -e '
const { readFileSync } = require("node:fs");
const rendered = JSON.parse(readFileSync(process.argv[1], "utf8"));
for (const [serviceName, service] of Object.entries(rendered.services ?? {})) {
  for (const variableName of Object.keys(service.environment ?? {})) {
    console.log(`${serviceName}\t${variableName}`);
  }
}
' "$capture_root/compose.descendant-config.json" \
  > "$capture_root/compose.descendant-environment"

node -e '
const { readFileSync } = require("node:fs");
const rendered = JSON.parse(readFileSync(process.argv[1], "utf8"));
const environment = rendered.services?.["cortex-api"]?.environment ?? {};
if (
  environment.CORTEX_ACADEMY_DB_URL !== process.argv[2]
  || environment.CORTEX_ACADEMY_DB_USER !== process.argv[3]
  || environment.CORTEX_ACADEMY_DB_PASSWORD !== process.argv[4]
) {
  process.exit(1);
}
' \
  "$capture_root/compose.descendant-config.json" \
  "$academy_url_marker" \
  "$academy_user_marker" \
  "$academy_password_marker"

for environment_file in "$capture_root"/*.environment; do
  if grep -Eqi '(^|_)OTP(_|$)|^CORTEX_EMAIL_|^CORTEX_SMTP_|^CORTEX_FINANCE_EMAIL_' \
    "$environment_file"; then
    echo "normal launcher leaked activation or legacy e-mail environment names" >&2
    exit 1
  fi
done

if grep -Eqi '(^|_)OTP(_|$)|(^|	)CORTEX_EMAIL_|(^|	)CORTEX_SMTP_|(^|	)CORTEX_FINANCE_EMAIL_' \
  "$capture_root/compose.descendant-environment"; then
  echo "normal Compose runtime recreated activation or legacy e-mail environment names in a descendant container" >&2
  exit 1
fi

grep -Fq $'docker\tcompose\t-f\t'"$mirror_root/compose.local.yml"$'\tup\t--build\t-d\t--remove-orphans' \
  "$capture_root/compose.arguments"
grep -Fq $'selected-ports\t15473\t18091' "$capture_root/compose.arguments"
grep -Fq 'CORTEX_WEB_PORT' "$capture_root/compose.environment"
grep -Fq 'CORTEX_API_PORT' "$capture_root/compose.environment"
grep -Fq 'CORTEX_SYNC_ACADEMY_ENABLED' "$capture_root/compose.environment"
grep -Fq 'CORTEX_SYNC_ZELADORIA_ENABLED' "$capture_root/compose.environment"
if grep -Fq 'CORTEX_SYNC_ENABLED' "$capture_root/compose.environment"; then
  echo "normal Compose runtime still depends on the removed global sync flag" >&2
  exit 1
fi

grep -Fq $'\t-p\t127.0.0.1:18091:8080' "$capture_root/docker.arguments"
grep -Fq $'\t-e\tCORTEX_WEB_PORT=15473' "$capture_root/docker.arguments"
for academy_variable in \
  CORTEX_ACADEMY_DB_URL \
  CORTEX_ACADEMY_DB_USER \
  CORTEX_ACADEMY_DB_PASSWORD; do
  grep -Fq $'\t-e\t'"$academy_variable"$'\t' \
    "$capture_root/docker.arguments"
  if grep -Fq "${academy_variable}=" "$capture_root/docker.arguments"; then
    echo "local Docker launcher placed an Academy value in argv" >&2
    exit 1
  fi
done

for captured_file in \
  "$capture_root/compose.arguments" \
  "$capture_root/compose.stdout" \
  "$capture_root/compose.stderr" \
  "$capture_root/docker.arguments" \
  "$capture_root/docker.stdout" \
  "$capture_root/docker.stderr"; do
  if grep -Fq "$academy_url_marker" "$captured_file" \
    || grep -Fq "$academy_user_marker" "$captured_file" \
    || grep -Fq "$academy_password_marker" "$captured_file"; then
    echo "local Academy QA credentials leaked through launcher output or argv" >&2
    exit 1
  fi
done

write_contract_environment false "$academy_password_marker"
run_contract scripts/dev/run-compose.sh compose-disabled
run_contract scripts/dev/run-api-docker.sh docker-disabled

node -e '
const { readFileSync } = require("node:fs");
const rendered = JSON.parse(readFileSync(process.argv[1], "utf8"));
const environment = rendered.services?.["cortex-api"]?.environment ?? {};
for (const name of [
  "CORTEX_ACADEMY_DB_URL",
  "CORTEX_ACADEMY_DB_USER",
  "CORTEX_ACADEMY_DB_PASSWORD",
]) {
  if (environment[name] !== "") process.exit(1);
}
' "$capture_root/compose-disabled.descendant-config.json"

if grep -Fq 'CORTEX_ACADEMY_DB_' "$capture_root/docker-disabled.arguments"; then
  echo "disabled local Docker runtime still forwarded Academy credentials" >&2
  exit 1
fi

write_contract_environment true ""
if run_contract scripts/dev/run-api-docker.sh docker-incomplete; then
  echo "local Docker launcher accepted incomplete Academy QA credentials" >&2
  exit 1
fi
grep -Fxq 'CORTEX_ACADEMY_DB_PASSWORD must be configured.' \
  "$capture_root/docker-incomplete.stderr"
if [[ -s "$capture_root/docker-incomplete.arguments" ]]; then
  echo "local Docker launcher invoked Docker before Academy QA preflight" >&2
  exit 1
fi

sed -n '1p' "$capture_root/api.server-port" | grep -Fxq '18092'
sed -n '2p' "$capture_root/api.server-port" |
  grep -Fxq 'http://localhost:15473'
sed -n '3p' "$capture_root/api.server-port" |
  grep -Fxq 'http://localhost:15473'
grep -Fq 'CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE' "$capture_root/api.environment"
grep -Fq 'CORTEX_AUTH_OFFLINE_GRANT_PRIVATE_KEY_FILE' "$capture_root/api.environment"
grep -Fq 'CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_FILE' "$capture_root/api.environment"

echo "Normal launcher child environments and selected port arguments passed."
