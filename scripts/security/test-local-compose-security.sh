#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
compose_file="$repo_root/compose.local.yml"
production_compose_file="$repo_root/compose.production.example.yml"
postgresql_env_template="$repo_root/.env.postgresql.example"
activation_script="$repo_root/scripts/dev/start-postgres-activation.sh"
api_dockerfile="$repo_root/apps/api/Dockerfile"
web_dockerfile="$repo_root/apps/web/Dockerfile"
web_nginx_config="$repo_root/apps/web/nginx.conf"
import_diagnostic="$repo_root/scripts/dev/check-imports.sh"

service_block() {
  local source_file="$1"
  local service_name="$2"

  awk -v service_name="$service_name" '
    $0 == "  " service_name ":" {
      in_service = 1
      print
      next
    }
    in_service && ($0 ~ /^  [[:alnum:]_-]+:[[:space:]]*$/ || $0 ~ /^[^[:space:]]/) {
      exit
    }
    in_service {
      print
    }
  ' "$source_file"
}

assert_service_hardening() {
  local source_file="$1"
  local service_name="$2"
  local block

  block="$(service_block "$source_file" "$service_name")"
  [[ -n "$block" ]] || {
    echo "missing service $service_name in $source_file" >&2
    exit 1
  }

  grep -Fq 'security_opt:' <<< "$block"
  grep -Fq -- '- no-new-privileges:true' <<< "$block"
  grep -Fq 'cap_drop:' <<< "$block"
  grep -Eq '^[[:space:]]+- ALL$' <<< "$block"
  grep -Fq 'read_only: true' <<< "$block"
  grep -Fq -- '- /tmp:rw,noexec,nosuid,nodev' <<< "$block"
}

assert_production_compose_renders_from_documented_contract() {
  command -v docker >/dev/null 2>&1 || {
    echo "docker compose is required to validate the production contract" >&2
    exit 1
  }
  docker compose version >/dev/null 2>&1 || {
    echo "docker compose is required to validate the production contract" >&2
    exit 1
  }

  local rendered
  contract_secret_dir="$(mktemp -d "${TMPDIR:-/tmp}/cortex-compose-contract.XXXXXX")"
  cleanup_contract_secrets() {
    if [[ -n "${contract_secret_dir:-}" ]]; then
      for secret_name in \
        postgres \
        academy \
        zeladoria \
        cpf_hmac \
        offline_private \
        offline_public \
        memory_cursor_hmac; do
        rm -f -- "$contract_secret_dir/$secret_name"
      done
      rmdir "$contract_secret_dir" 2>/dev/null || true
    fi
  }
  trap cleanup_contract_secrets EXIT

  for secret_name in \
    postgres \
    academy \
    zeladoria \
    cpf_hmac \
    offline_private \
    offline_public \
    memory_cursor_hmac; do
    printf 'contract-secret' > "$contract_secret_dir/$secret_name"
  done

  if ! rendered="$(env \
    CORTEX_POSTGRES_URL='jdbc:postgresql://postgres.contract.internal:5432/CortexContract' \
    CORTEX_POSTGRES_USER='cortex_contract' \
    CORTEX_POSTGRES_PASSWORD_FILE="$contract_secret_dir/postgres" \
    CORTEX_POSTGRES_RUNTIME_READY='true' \
    CORTEX_PUBLIC_ORIGIN='https://cortex.example.invalid' \
    CORTEX_AUTH_WEBAUTHN_RP_ID='cortex.example.invalid' \
    CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_ID='cpf-contract-key' \
    CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE="$contract_secret_dir/cpf_hmac" \
    CORTEX_AUTH_OFFLINE_GRANT_KEY_ID='offline-contract-key' \
    CORTEX_AUTH_OFFLINE_GRANT_PRIVATE_KEY_FILE="$contract_secret_dir/offline_private" \
    CORTEX_AUTH_OFFLINE_GRANT_PUBLIC_KEY_FILE="$contract_secret_dir/offline_public" \
    CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID='memory-contract-key' \
    CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_FILE="$contract_secret_dir/memory_cursor_hmac" \
    VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256='contract-public-key-fingerprint' \
    CORTEX_ACADEMY_DB_URL='jdbc:mysql://academy.contract.internal:3306/academy?sslMode=VERIFY_IDENTITY' \
    CORTEX_ACADEMY_DB_USER='academy_readonly' \
    CORTEX_ACADEMY_DB_PASSWORD_FILE="$contract_secret_dir/academy" \
    CORTEX_ZELADORIA_DB_URL='jdbc:mysql://zeladoria.contract.internal:3306/zeladoria' \
    CORTEX_ZELADORIA_DB_USER='zeladoria_readonly' \
    CORTEX_ZELADORIA_DB_PASSWORD_FILE="$contract_secret_dir/zeladoria" \
    docker compose -f "$production_compose_file" config 2>&1)"; then
    printf '%s\n' "$rendered" >&2
    exit 1
  fi

  grep -Fq 'host_ip: 127.0.0.1' <<< "$rendered"
  grep -Fq 'published: "8080"' <<< "$rendered"
  grep -Fq 'CORTEX_ACADEMY_DB_PASSWORD_FILE: /run/secrets/cortex_academy_password' <<< "$rendered"
  grep -Fq 'target: cortex_academy_password' <<< "$rendered"
  grep -Fq 'CORTEX_SYNC_ACADEMY_ENABLED: "false"' <<< "$rendered"
  grep -Fq 'CORTEX_SYNC_ACADEMY_READINESS_MAX_AGE_MS: "900000"' <<< "$rendered"
  grep -Fq 'CORTEX_SYNC_ZELADORIA_ENABLED: "false"' <<< "$rendered"
  grep -Fq 'target: CORTEX_ZELADORIA_DB_PASSWORD' <<< "$rendered"
  if grep -Eq 'CORTEX_AUTH_OTP_HMAC_KEY_FILE|cortex_otp_hmac|CORTEX_EMAIL_|CORTEX_SMTP_|cortex_smtp_password|CORTEX_FINANCE_EMAIL_' <<< "$rendered"; then
    echo "rendered normal production compose still contains activation OTP or legacy e-mail delivery" >&2
    exit 1
  fi
  if grep -Fq 'contract-secret' <<< "$rendered"; then
    echo "docker compose config leaked temporary secret contents" >&2
    exit 1
  fi
}

grep -Fq 'CORTEX_AUTH_DEV_ADMIN_ENABLED: "false"' \
  "$compose_file"
grep -Fq 'SPRING_PROFILES_ACTIVE: local,postgresql' "$compose_file"
grep -Fq 'VITE_CORTEX_AUTH_MODE: postgresql' "$compose_file"
grep -Fq 'target: CORTEX_POSTGRES_PASSWORD' "$compose_file"
grep -Fq 'CORTEX_POSTGRES_RUNTIME_READY:' "$compose_file"

if grep -Eq 'cortex-mysql|jdbc:mysql|cortex_dev|CORTEX_DB_|VITE_CORTEX_AUTH_MODE: legacy' \
  "$compose_file"; then
  echo "local compose still exposes a legacy primary runtime" >&2
  exit 1
fi

published_ports="$(sed -n '/^[[:space:]]*ports:/,/^[[:space:]]*[a-zA-Z]/p' \
  "$compose_file" | sed -n 's/^[[:space:]]*-[[:space:]]*"\([^"]*\)"/\1/p')"

if [[ -z "$published_ports" ]]; then
  echo "compose.local.yml does not publish the expected local ports" >&2
  exit 1
fi

while IFS= read -r published_port; do
  [[ "$published_port" == 127.0.0.1:* ]] || {
    echo "non-loopback local port: $published_port" >&2
    exit 1
  }
done <<< "$published_ports"

grep -Fq 'SPRING_CONFIG_IMPORT: configtree:/run/secrets/' \
  "$production_compose_file"
grep -Fq 'target: CORTEX_POSTGRES_PASSWORD' \
  "$production_compose_file"
grep -Fq 'CORTEX_POSTGRES_PASSWORD_FILE' \
  "$production_compose_file"
grep -Fq 'CORTEX_ACADEMY_DB_URL:' "$production_compose_file"
grep -Fq 'CORTEX_ACADEMY_DB_USER:' "$production_compose_file"
grep -Fq 'CORTEX_ACADEMY_DB_PASSWORD_FILE: /run/secrets/cortex_academy_password' \
  "$production_compose_file"
grep -Fq 'target: cortex_academy_password' "$production_compose_file"
grep -Fq 'CORTEX_ACADEMY_DB_PASSWORD_FILE' "$production_compose_file"
grep -Fq 'CORTEX_SYNC_ACADEMY_ENABLED:' "$production_compose_file"
grep -Fq 'CORTEX_SYNC_ACADEMY_READINESS_MAX_AGE_MS:' \
  "$production_compose_file"
grep -Fq 'CORTEX_SYNC_ZELADORIA_ENABLED:' "$production_compose_file"
grep -Fq 'CORTEX_ZELADORIA_DB_URL:' "$production_compose_file"
grep -Fq 'CORTEX_ZELADORIA_DB_USER:' "$production_compose_file"
grep -Fq 'target: CORTEX_ZELADORIA_DB_PASSWORD' "$production_compose_file"
grep -Fq 'CORTEX_ZELADORIA_DB_PASSWORD_FILE' "$production_compose_file"
grep -Fq '"${CORTEX_WEB_BIND_ADDRESS:-127.0.0.1}:${CORTEX_WEB_PORT:-8080}:8080"' \
  "$production_compose_file"

for documented_variable in \
  CORTEX_POSTGRES_PASSWORD_FILE \
  CORTEX_PUBLIC_ORIGIN \
  CORTEX_WEB_BIND_ADDRESS \
  CORTEX_WEB_PORT \
  CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_ID \
  CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE \
  CORTEX_AUTH_WEBAUTHN_RP_ID \
  CORTEX_AUTH_OFFLINE_GRANT_KEY_ID \
  CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID \
  VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256 \
  CORTEX_ACADEMY_DB_URL \
  CORTEX_ACADEMY_DB_USER \
  CORTEX_ACADEMY_DB_PASSWORD_FILE \
  CORTEX_SYNC_ACADEMY_ENABLED \
  CORTEX_SYNC_ACADEMY_READINESS_MAX_AGE_MS \
  CORTEX_SYNC_ZELADORIA_ENABLED \
  CORTEX_ZELADORIA_DB_URL \
  CORTEX_ZELADORIA_DB_USER \
  CORTEX_ZELADORIA_DB_PASSWORD_FILE; do
  grep -Eq "^${documented_variable}=" "$postgresql_env_template" || {
    echo "missing ${documented_variable} from .env.postgresql.example" >&2
    exit 1
  }
done

if grep -Fq 'CORTEX_SYNC_ENABLED' \
  "$compose_file" "$production_compose_file" "$postgresql_env_template"; then
  echo "runtime configuration still depends on the removed global sync flag" >&2
  exit 1
fi

if grep -Eq 'CORTEX_AUTH_OTP_HMAC_KEY_FILE|cortex_otp_hmac' \
  "$production_compose_file"; then
  echo "normal production compose still requires or mounts activation OTP" >&2
  exit 1
fi

if grep -Eq '^CORTEX_AUTH_OTP_HMAC_KEY_FILE=' "$postgresql_env_template"; then
  echo "normal PostgreSQL runtime template still requires activation OTP" >&2
  exit 1
fi

if grep -Eq 'CORTEX_EMAIL_|CORTEX_SMTP_|CORTEX_FINANCE_EMAIL_' \
  "$production_compose_file" "$postgresql_env_template"; then
  echo "normal PostgreSQL runtime still declares legacy e-mail or scheduler configuration" >&2
  exit 1
fi

grep -Fq 'cortex_require_secret_file CORTEX_AUTH_OTP_HMAC_KEY_FILE' \
  "$activation_script"

if grep -Eq '^[[:space:]]+(CORTEX_POSTGRES_PASSWORD|CORTEX_ACADEMY_DB_PASSWORD|CORTEX_ZELADORIA_DB_PASSWORD|AWS_SECRET_ACCESS_KEY):' \
  "$production_compose_file"; then
  echo "production compose exposes a secret through the container environment" >&2
  exit 1
fi

[[ -x "$import_diagnostic" ]] || {
  echo "check-imports.sh must remain executable" >&2
  exit 1
}

if grep -Eq '(^|[[:space:]])mysql([[:space:]]|$)|cortex_dev|CORTEX_DB_|-p\$?\{?CORTEX_DB_PASSWORD|export[[:space:]]+PGPASSWORD' "$import_diagnostic"; then
  echo "check-imports.sh still treats MySQL as the Córtex primary database" >&2
  exit 1
fi

grep -Fq 'PGPASSFILE' "$import_diagnostic"
grep -Fq 'CORTEX_POSTGRES_PASSWORD_FILE' "$import_diagnostic"

for hardened_compose_file in "$compose_file" "$production_compose_file"; do
  assert_service_hardening "$hardened_compose_file" cortex-api
  assert_service_hardening "$hardened_compose_file" cortex-web

  web_block="$(service_block "$hardened_compose_file" cortex-web)"
  grep -Fq -- '- /var/cache/nginx:rw,noexec,nosuid,nodev' <<< "$web_block"
  grep -Fq -- '- /var/run:rw,noexec,nosuid,nodev' <<< "$web_block"
done

grep -Fq 'USER cortex' "$api_dockerfile"
grep -Fq 'USER nginx' "$web_dockerfile"
grep -Fq 'ENTRYPOINT ["nginx"]' "$web_dockerfile"
grep -Fq 'location ~ ^/api/auth/email/challenges(?:/.*)?$' "$web_nginx_config"
grep -Fq 'client_max_body_size 4k;' "$web_nginx_config"

assert_production_compose_renders_from_documented_contract

echo "Local PostgreSQL/loopback, normal-runtime OTP/e-mail isolation, production secret-mount, source-read-only wiring, and container hardening contracts passed."
