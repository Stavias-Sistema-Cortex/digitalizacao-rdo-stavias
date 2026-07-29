#!/usr/bin/env bash
set -euo pipefail

require_value() {
  local name="$1"
  local value="${!name:-}"
  if [[ -z "$value" || "$value" == *$'\r'* || "$value" == *$'\n'* ]]; then
    echo "$name must be configured as one non-empty line." >&2
    exit 1
  fi
}

for name in \
  CORTEX_RELEASE_SHA \
  CORTEX_RENDER_ORIGIN \
  CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256 \
  CORTEX_DATABASE_RELEASE_MARKER \
  CLOUDFLARE_API_TOKEN \
  CLOUDFLARE_ACCOUNT_ID \
  CLOUDFLARE_PAGES_PROJECT_NAME; do
  require_value "$name"
done

if [[ ! "$CORTEX_RELEASE_SHA" =~ ^[a-f0-9]{40}$ ]]; then
  echo "CORTEX_RELEASE_SHA must be a full Git commit SHA." >&2
  exit 1
fi
if [[ ! "$CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256" =~ ^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$ ]]; then
  echo "CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256 must be canonical SHA-256 base64url." >&2
  exit 1
fi
if [[ ! "$CORTEX_DATABASE_RELEASE_MARKER" =~ ^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$ ]]; then
  echo "CORTEX_DATABASE_RELEASE_MARKER must be canonical SHA-256 base64url." >&2
  exit 1
fi
if [[ ! "$CORTEX_RENDER_ORIGIN" =~ ^https://[A-Za-z0-9.-]+\.onrender\.com$ ]]; then
  echo "CORTEX_RENDER_ORIGIN must be the direct HTTPS Render origin." >&2
  exit 1
fi
if [[ ! "$CLOUDFLARE_ACCOUNT_ID" =~ ^[a-f0-9]{32}$ ]]; then
  echo "CLOUDFLARE_ACCOUNT_ID is invalid." >&2
  exit 1
fi
if [[ ! "$CLOUDFLARE_PAGES_PROJECT_NAME" =~ ^[a-z0-9][a-z0-9-]{0,57}$ ]]; then
  echo "CLOUDFLARE_PAGES_PROJECT_NAME is invalid." >&2
  exit 1
fi
if [[ ! "$CLOUDFLARE_API_TOKEN" =~ ^[A-Za-z0-9_-]+$ ]] \
  || [[ "${#CLOUDFLARE_API_TOKEN}" -lt 20 ]] \
  || [[ "${#CLOUDFLARE_API_TOKEN}" -gt 256 ]]; then
  echo "CLOUDFLARE_API_TOKEN has an unsafe format." >&2
  exit 1
fi

timeout_seconds="${CORTEX_PAGES_WAIT_TIMEOUT_SECONDS:-300}"
interval_seconds="${CORTEX_PAGES_WAIT_INTERVAL_SECONDS:-5}"
if [[ ! "$timeout_seconds" =~ ^[1-9][0-9]*$ || "$timeout_seconds" -gt 1800 ]]; then
  echo "CORTEX_PAGES_WAIT_TIMEOUT_SECONDS must be between 1 and 1800." >&2
  exit 1
fi
if [[ ! "$interval_seconds" =~ ^[0-9]+$ || "$interval_seconds" -gt 60 ]]; then
  echo "CORTEX_PAGES_WAIT_INTERVAL_SECONDS must be between 0 and 60." >&2
  exit 1
fi

for command_name in npx python3 curl; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "$command_name is required for the Cloudflare Pages release." >&2
    exit 1
  }
done

repo_root="$(git rev-parse --show-toplevel)"
web_root="$repo_root/apps/web"
[[ -d "$web_root/dist" ]] || {
  echo "The production Pages artifact is missing." >&2
  exit 1
}
cd "$web_root"

release_root="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/cortex-pages-release.XXXXXX")"
auth_config="$release_root/cloudflare.curl"
project_file="$release_root/project.json"
deployment_file="$release_root/deployment.json"
response_file="$release_root/response.json"
wrangler_output_file="$release_root/wrangler-output.ndjson"
cleanup() {
  find "$release_root" -type f -delete 2>/dev/null || true
  rmdir "$release_root" 2>/dev/null || true
}
trap cleanup EXIT
umask 077

printf 'header = "Authorization: Bearer %s"\n' \
  "$CLOUDFLARE_API_TOKEN" > "$auth_config"
chmod 600 "$auth_config"

cloudflare_get() {
  local url="$1"
  local output_file="$2"
  local status
  status="$(
    env -u CLOUDFLARE_API_TOKEN \
      curl --disable --silent --show-error \
        --connect-timeout 5 --max-time 30 \
        --config "$auth_config" \
        --header 'Accept: application/json' \
        --output "$output_file" --write-out '%{http_code}' \
        "$url" 2>/dev/null
  )" || return 1
  [[ "$status" == "200" ]]
}

project_url="https://api.cloudflare.com/client/v4/accounts/${CLOUDFLARE_ACCOUNT_ID}/pages/projects/${CLOUDFLARE_PAGES_PROJECT_NAME}"
if ! cloudflare_get "$project_url" "$project_file"; then
  echo "Unable to inspect the Cloudflare Pages project." >&2
  exit 1
fi

pages_origin="$(
  env -u CLOUDFLARE_API_TOKEN \
    python3 - "$project_file" "$CLOUDFLARE_PAGES_PROJECT_NAME" <<'PY'
import json
import pathlib
import re
import sys

document = json.loads(pathlib.Path(sys.argv[1]).read_text())
name = sys.argv[2]
project = document.get("result")
if document.get("success") is not True or not isinstance(project, dict):
    raise SystemExit(1)
if project.get("name") != name or project.get("production_branch") != "develop":
    raise SystemExit(1)
expected_subdomain = f"{name}.pages.dev"
subdomain = project.get("subdomain")
if not isinstance(subdomain, str) or not re.fullmatch(
    rf"{re.escape(name)}\.pages\.dev", subdomain
):
    raise SystemExit(1)
print(f"https://{subdomain}", end="")
PY
)" || {
  echo "Cloudflare Pages project identity or production branch is invalid." >&2
  exit 1
}

if ! printf '%s' "$CORTEX_RENDER_ORIGIN" |
  npx --no-install wrangler pages secret put CORTEX_API_ORIGIN \
    --project-name "$CLOUDFLARE_PAGES_PROJECT_NAME" >/dev/null; then
  echo "Unable to bind the trusted Render origin to Cloudflare Pages." >&2
  exit 1
fi

marker_relative_path="cortex-release/${CORTEX_RELEASE_SHA}.json"
marker_file="$web_root/dist/$marker_relative_path"
mkdir -p "$(dirname "$marker_file")"
printf '{"revision":"%s","releaseMarker":"%s"}\n' \
  "$CORTEX_RELEASE_SHA" "$CORTEX_DATABASE_RELEASE_MARKER" > "$marker_file"

WRANGLER_OUTPUT_FILE_PATH="$wrangler_output_file" \
  npx --no-install wrangler pages deploy dist \
    --project-name "$CLOUDFLARE_PAGES_PROJECT_NAME" \
    --branch develop \
    --commit-hash "$CORTEX_RELEASE_SHA" \
    --commit-dirty=false

deployment_id="$(
  python3 - "$wrangler_output_file" "$CLOUDFLARE_PAGES_PROJECT_NAME" <<'PY'
import json
import pathlib
import re
import sys

path = pathlib.Path(sys.argv[1])
if not path.is_file():
    raise SystemExit(1)
project_name = sys.argv[2]
deployment_ids = set()
for line in path.read_text().splitlines():
    if not line.strip():
        continue
    document = json.loads(line)
    if document.get("type") not in {"pages-deploy", "pages-deploy-detailed"}:
        continue
    if document.get("pages_project") != project_name:
        raise SystemExit(1)
    deployment_id = document.get("deployment_id")
    if not isinstance(deployment_id, str) or not re.fullmatch(
        r"[A-Za-z0-9_-]{8,128}", deployment_id
    ):
        raise SystemExit(1)
    deployment_ids.add(deployment_id)
if len(deployment_ids) != 1:
    raise SystemExit(1)
print(deployment_ids.pop(), end="")
PY
)" || {
  echo "Wrangler did not identify exactly one Cloudflare Pages deployment." >&2
  exit 1
}

deployment_url="${project_url}/deployments/${deployment_id}"
if ! cloudflare_get "$deployment_url" "$deployment_file"; then
  echo "Unable to inspect the exact Cloudflare Pages deployment." >&2
  exit 1
fi
unset CLOUDFLARE_API_TOKEN
rm -f "$auth_config"

deployment_origin="$(
  python3 - "$deployment_file" "$CLOUDFLARE_PAGES_PROJECT_NAME" \
    "$CORTEX_RELEASE_SHA" "$deployment_id" <<'PY'
import json
import pathlib
import re
import sys
import urllib.parse

document = json.loads(pathlib.Path(sys.argv[1]).read_text())
deployment = document.get("result")
if document.get("success") is not True or not isinstance(deployment, dict):
    raise SystemExit(1)
metadata = deployment.get("deployment_trigger", {}).get("metadata", {})
stage = deployment.get("latest_stage", {})
source = deployment.get("source")
source_branch = None
if source is not None:
    if not isinstance(source, dict):
        raise SystemExit(1)
    source_config = source.get("config")
    if source_config is not None:
        if not isinstance(source_config, dict):
            raise SystemExit(1)
        source_branch = source_config.get("production_branch")
if (
    deployment.get("id") != sys.argv[4]
    or deployment.get("project_name") != sys.argv[2]
    or deployment.get("environment") != "production"
    or (source_branch is not None and source_branch != "develop")
    or metadata.get("branch") != "develop"
    or metadata.get("commit_hash") != sys.argv[3]
    or stage.get("name") != "deploy"
    or stage.get("status") != "success"
):
    raise SystemExit(1)
deployment_url = deployment.get("url")
if not isinstance(deployment_url, str):
    raise SystemExit(1)
parsed = urllib.parse.urlsplit(deployment_url)
expected_hostname = rf"[a-z0-9-]+\.{re.escape(sys.argv[2])}\.pages\.dev"
if (
    parsed.scheme != "https"
    or parsed.username
    or parsed.password
    or parsed.port is not None
    or not isinstance(parsed.hostname, str)
    or not re.fullmatch(expected_hostname, parsed.hostname)
    or parsed.path not in {"", "/"}
    or parsed.query
    or parsed.fragment
):
    raise SystemExit(1)
print(f"https://{parsed.hostname}", end="")
PY
)" || {
  echo "Cloudflare Pages did not publish the exact identified production deployment." >&2
  exit 1
}

marker_status="$(
  curl --disable --silent --show-error \
    --connect-timeout 5 --max-time 15 \
    --output "$response_file" --write-out '%{http_code}' \
    "${deployment_origin}/${marker_relative_path}" 2>/dev/null
)" || marker_status="000"
if [[ "$marker_status" != "200" ]] \
  || ! python3 - "$response_file" "$CORTEX_RELEASE_SHA" \
    "$CORTEX_DATABASE_RELEASE_MARKER" <<'PY' >/dev/null 2>&1; then
import json
import pathlib
import sys

document = json.loads(pathlib.Path(sys.argv[1]).read_text())
if document != {"revision": sys.argv[2], "releaseMarker": sys.argv[3]}:
    raise SystemExit(1)
PY
  echo "Cloudflare Pages did not serve the release marker from the exact deployment." >&2
  exit 1
fi

started_at="$SECONDS"
while ((SECONDS - started_at < timeout_seconds)); do
  canonical_marker_status="$(
    curl --disable --silent --show-error \
      --connect-timeout 5 --max-time 15 \
      --output "$response_file" --write-out '%{http_code}' \
      "${pages_origin}/${marker_relative_path}" 2>/dev/null
  )" || canonical_marker_status="000"
  canonical_marker_matches=false
  if [[ "$canonical_marker_status" == "200" ]] \
    && python3 - "$response_file" "$CORTEX_RELEASE_SHA" \
      "$CORTEX_DATABASE_RELEASE_MARKER" <<'PY' >/dev/null 2>&1; then
import json
import pathlib
import sys

document = json.loads(pathlib.Path(sys.argv[1]).read_text())
if document != {"revision": sys.argv[2], "releaseMarker": sys.argv[3]}:
    raise SystemExit(1)
PY
    canonical_marker_matches=true
  fi

  health_matches=false
  if [[ "$canonical_marker_matches" == "true" ]]; then
    health_status="$(
      curl --disable --silent --show-error \
        --connect-timeout 5 --max-time 15 \
        --output "$response_file" --write-out '%{http_code}' \
        "$pages_origin/api/health" 2>/dev/null
    )" || health_status="000"
    if [[ "$health_status" == "200" ]] \
      && python3 - "$response_file" "$CORTEX_RELEASE_SHA" <<'PY' >/dev/null 2>&1; then
import json
import pathlib
import sys

document = json.loads(pathlib.Path(sys.argv[1]).read_text())
if document.get("status") != "UP" or document.get("revision") != sys.argv[2]:
    raise SystemExit(1)
PY
      health_matches=true
    fi
  fi

  if [[ "$health_matches" == "true" ]]; then
    readiness_status="$(
      curl --disable --silent --show-error \
        --connect-timeout 5 --max-time 15 \
        --output "$response_file" --write-out '%{http_code}' \
        "$pages_origin/api/readiness" 2>/dev/null
    )" || readiness_status="000"
    if [[ "$readiness_status" == "200" ]] \
      && python3 - "$response_file" "$CORTEX_RELEASE_SHA" \
        "$CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256" \
        "$CORTEX_DATABASE_RELEASE_MARKER" <<'PY' >/dev/null 2>&1; then
import json
import pathlib
import sys

document = json.loads(pathlib.Path(sys.argv[1]).read_text())
expected_revision = sys.argv[2]
if (
    document.get("status") != "READY"
    or document.get("revision") != expected_revision
    or document.get("offlineGrantPublicKeySha256") != sys.argv[3]
    or document.get("objectStorage") != "READY"
    or document.get("databaseReleaseRevision") != expected_revision
    or document.get("databaseReleaseMarker") != sys.argv[4]
):
    raise SystemExit(1)
PY
      echo "Cloudflare Pages is serving the requested production revision."
      exit 0
    fi
  fi
  sleep "$interval_seconds"
done

echo "Cloudflare Pages did not serve the requested READY revision before the timeout." >&2
exit 1
