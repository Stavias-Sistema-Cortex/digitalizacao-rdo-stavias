#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
compose_file="$repo_root/compose.local.yml"
production_compose_file="$repo_root/compose.production.example.yml"
api_dockerfile="$repo_root/apps/api/Dockerfile"
web_dockerfile="$repo_root/apps/web/Dockerfile"

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
grep -Fq 'CORTEX_POSTGRES_PASSWORD_SECRET_FILE' \
  "$production_compose_file"

if grep -Eq '^[[:space:]]+(CORTEX_POSTGRES_PASSWORD|AWS_SECRET_ACCESS_KEY):' \
  "$production_compose_file"; then
  echo "production compose exposes a secret through the container environment" >&2
  exit 1
fi

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

echo "Local PostgreSQL/loopback, production secret-mount, and container hardening contracts passed."
