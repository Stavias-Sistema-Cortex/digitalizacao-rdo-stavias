#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
render_file="${CORTEX_HOSTED_RENDER_FILE:-$repo_root/render.yaml}"
pages_root="$repo_root/apps/web"
hosted_runbook="$repo_root/docs/operations/cortex-hosted-pilot.md"

for required in \
  "$render_file" \
  "$hosted_runbook" \
  "$pages_root/functions/api/[[path]].ts" \
  "$pages_root/public/_routes.json" \
  "$pages_root/public/_headers" \
  "$pages_root/public/_redirects"; do
  [[ -f "$required" ]] || {
    echo "missing hosted deployment file: $required" >&2
    exit 1
  }
done

command -v ruby >/dev/null 2>&1 || {
  echo "ruby with the standard YAML parser is required for the hosted deployment contract" >&2
  exit 1
}

RENDER_FILE="$render_file" ruby <<'RUBY'
require "yaml"

def fail_contract(message)
  warn "hosted deployment contract violation: #{message}"
  exit 1
end

begin
  document = YAML.safe_load(
    File.read(ENV.fetch("RENDER_FILE")),
    permitted_classes: [],
    permitted_symbols: [],
    aliases: false
  )
rescue Psych::Exception, ArgumentError
  fail_contract("render.yaml must be valid YAML without aliases")
end

fail_contract("top-level document must only declare services") unless document.is_a?(Hash) \
  && document.keys == ["services"]

services = document.fetch("services")
fail_contract("services must be a list") unless services.is_a?(Array)

api_services = services.select do |service|
  service.is_a?(Hash) && service["type"] == "web" && service["name"] == "cortex-api"
end
fail_contract("must declare exactly one cortex-api web service") unless api_services.length == 1

service = api_services.first
expected_service = {
  "type" => "web",
  "name" => "cortex-api",
  "runtime" => "docker",
  "plan" => "free",
  "region" => "ohio",
  "dockerfilePath" => "./apps/api/Dockerfile",
  "dockerContext" => "./apps/api",
  "branch" => "develop",
  "autoDeployTrigger" => false,
  "healthCheckPath" => "/api/readiness"
}

expected_service.each do |key, expected_value|
  fail_contract("service #{key} has an invalid mapping") unless service[key] == expected_value
end

fail_contract("service keys do not match the hosted API contract") unless service.keys.sort == \
  (expected_service.keys + ["envVars"]).sort

expected_env = {
  "SPRING_PROFILES_ACTIVE" => { "value" => "production,postgresql" },
  "CORTEX_POSTGRES_URL" => { "sync" => false },
  "CORTEX_POSTGRES_USER" => { "sync" => false },
  "CORTEX_POSTGRES_PASSWORD" => { "sync" => false },
  "CORTEX_POSTGRES_RUNTIME_READY" => { "value" => "true" },
  "CORTEX_POSTGRES_RELEASE_MARKER_REQUIRED" => { "value" => "true" },
  "CORTEX_CORS_ALLOWED_ORIGINS" => { "sync" => false },
  "CORTEX_AUTH_COOKIE_SECURE" => { "value" => "true" },
  "CORTEX_AUTH_COOKIE_SAME_SITE" => { "value" => "Lax" },
  "CORTEX_AUTH_WEBAUTHN_RP_ID" => { "sync" => false },
  "CORTEX_AUTH_WEBAUTHN_RP_NAME" => { "value" => "Córtex" },
  "CORTEX_AUTH_WEBAUTHN_ALLOWED_ORIGINS" => { "sync" => false },
  "CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_ID" => { "sync" => false },
  "CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE" => { "value" => "/etc/secrets/cortex-cpf-hmac" },
  "CORTEX_AUTH_OFFLINE_GRANT_KEY_ID" => { "sync" => false },
  "CORTEX_AUTH_OFFLINE_GRANT_PRIVATE_KEY_FILE" => { "value" => "/etc/secrets/cortex-offline-private.pem" },
  "CORTEX_AUTH_OFFLINE_GRANT_PUBLIC_KEY_FILE" => { "value" => "/etc/secrets/cortex-offline-public.pem" },
  "CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID" => { "sync" => false },
  "CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_FILE" => { "value" => "/etc/secrets/cortex-memory-cursor-hmac" },
  "CORTEX_AUTH_DEV_ADMIN_ENABLED" => { "value" => "false" },
  "CORTEX_AUTH_PROVISIONING_ENABLED" => { "value" => "false" },
  "CORTEX_STORAGE_PROVIDER" => { "value" => "s3" },
  "CORTEX_STORAGE_S3_BUCKET" => { "sync" => false },
  "CORTEX_STORAGE_S3_REGION" => { "value" => "auto" },
  "CORTEX_STORAGE_S3_ENDPOINT" => { "sync" => false },
  "CORTEX_STORAGE_S3_PREFIX" => { "value" => "production" },
  "CORTEX_STORAGE_S3_PATH_STYLE" => { "value" => "true" },
  "CORTEX_STORAGE_S3_SEND_SSE_HEADER" => { "value" => "false" },
  "AWS_ACCESS_KEY_ID" => { "sync" => false },
  "AWS_SECRET_ACCESS_KEY" => { "sync" => false },
  "CORTEX_IMPORT_ENABLED" => { "value" => "false" },
  "CORTEX_ACADEMY_DB_URL" => { "sync" => false },
  "CORTEX_ACADEMY_DB_USER" => { "sync" => false },
  "CORTEX_ACADEMY_DB_PASSWORD_FILE" => { "value" => "/etc/secrets/cortex-academy-password" },
  "CORTEX_SYNC_ACADEMY_ENABLED" => { "value" => "false" },
  "CORTEX_SYNC_ACADEMY_READINESS_MAX_AGE_MS" => { "value" => "900000" },
  "CORTEX_SYNC_ZELADORIA_ENABLED" => { "value" => "false" }
}

env_vars = service["envVars"]
fail_contract("envVars must be a list") unless env_vars.is_a?(Array)
by_key = {}
env_vars.each do |entry|
  fail_contract("each env var must be a mapping") unless entry.is_a?(Hash)
  key = entry["key"]
  fail_contract("env var key must be unique and known") unless key.is_a?(String) \
    && expected_env.key?(key) && !by_key.key?(key)
  by_key[key] = entry
end

fail_contract("env var keys do not match the hosted API contract") unless by_key.keys.sort == expected_env.keys.sort

expected_env.each do |key, expected_mapping|
  expected_entry = { "key" => key }.merge(expected_mapping)
  fail_contract("env var #{key} has an invalid mapping") unless by_key[key] == expected_entry
end

sensitive_keys = expected_env.select { |_key, mapping| mapping == { "sync" => false } }.keys
sensitive_keys.each do |key|
  fail_contract("sensitive env var #{key} must use sync: false") unless by_key[key] == { "key" => key, "sync" => false }
end

inline_connection = env_vars.any? do |entry|
  entry["value"].is_a?(String) && entry["value"].match?(/(?:jdbc:|postgres(?:ql)?:\/\/)/i)
end
fail_contract("inline database connection values are forbidden") if inline_connection
RUBY

grep -Fq '/etc/secrets/cortex-academy-truststore.p12' "$render_file"
grep -Fq '/etc/secrets/cortex-academy-truststore.p12' "$hosted_runbook"
grep -Fq 'fallbackToSystemTrustStore=false' "$hosted_runbook"
if [[ -n "$(git -C "$repo_root" ls-files '*.p12' '*.pfx' '*.jks')" ]]; then
  echo "hosted deployment contract forbids tracked truststore material" >&2
  exit 1
fi

echo "Hosted Render and Cloudflare contracts passed."
