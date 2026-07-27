#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
render_file="$repo_root/render.yaml"
pages_root="$repo_root/apps/web"

for required in \
  "$render_file" \
  "$pages_root/functions/api/[[path]].ts" \
  "$pages_root/public/_routes.json" \
  "$pages_root/public/_headers" \
  "$pages_root/public/_redirects"; do
  [[ -f "$required" ]] || {
    echo "missing hosted deployment file: $required" >&2
    exit 1
  }
done

grep -Fq 'plan: free' "$render_file"
grep -Fq 'region: ohio' "$render_file"
grep -Fq 'runtime: docker' "$render_file"
grep -Fq 'healthCheckPath: /api/readiness' "$render_file"
grep -Fq 'SPRING_PROFILES_ACTIVE' "$render_file"
grep -Fq 'value: production,postgresql' "$render_file"
grep -Fq 'CORTEX_POSTGRES_RUNTIME_READY' "$render_file"
grep -Fq 'CORTEX_STORAGE_S3_SEND_SSE_HEADER' "$render_file"
grep -Fq 'value: "false"' "$render_file"
grep -Fq 'CORTEX_AUTH_DEV_ADMIN_ENABLED' "$render_file"
grep -Fq 'CORTEX_AUTH_PROVISIONING_ENABLED' "$render_file"

if grep -Eiq \
  '(postgres(ql)?://[^[:space:]]+:[^[:space:]@]+@|BEGIN .*PRIVATE KEY|AWS_SECRET_ACCESS_KEY:[[:space:]]*[^[:space:]]+)' \
  "$render_file"; then
  echo "render.yaml contains an inline credential" >&2
  exit 1
fi

echo "Hosted Render and Cloudflare contracts passed."
