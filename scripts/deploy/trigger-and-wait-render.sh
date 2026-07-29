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
  CORTEX_RENDER_DEPLOY_HOOK_URL \
  CORTEX_RENDER_ORIGIN \
  CORTEX_PREVIOUS_RENDER_INSTANCE_SHA256 \
  CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256 \
  CORTEX_DATABASE_RELEASE_MARKER; do
  require_value "$name"
done

is_sha256_base64url() {
  [[ "$1" =~ ^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$ ]]
}

if [[ ! "$CORTEX_RELEASE_SHA" =~ ^[a-f0-9]{40}$ ]]; then
  echo "CORTEX_RELEASE_SHA must be a full Git commit SHA." >&2
  exit 1
fi
if ! is_sha256_base64url "$CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256"; then
  echo "CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256 is invalid." >&2
  exit 1
fi
if ! is_sha256_base64url "$CORTEX_DATABASE_RELEASE_MARKER"; then
  echo "CORTEX_DATABASE_RELEASE_MARKER is invalid." >&2
  exit 1
fi
if [[ "$CORTEX_PREVIOUS_RENDER_INSTANCE_SHA256" != "none" ]] \
  && ! is_sha256_base64url "$CORTEX_PREVIOUS_RENDER_INSTANCE_SHA256"; then
  echo "CORTEX_PREVIOUS_RENDER_INSTANCE_SHA256 is invalid." >&2
  exit 1
fi
if [[ ! "$CORTEX_RENDER_DEPLOY_HOOK_URL" =~ ^https://api\.render\.com/deploy/srv-[a-z0-9]+\?key=[A-Za-z0-9_-]+$ ]]; then
  echo "CORTEX_RENDER_DEPLOY_HOOK_URL is not a valid Render deploy hook." >&2
  exit 1
fi
if [[ ! "$CORTEX_RENDER_ORIGIN" =~ ^https://[A-Za-z0-9.-]+\.onrender\.com$ ]]; then
  echo "CORTEX_RENDER_ORIGIN must be the direct HTTPS Render origin." >&2
  exit 1
fi

timeout_seconds="${CORTEX_RENDER_WAIT_TIMEOUT_SECONDS:-900}"
interval_seconds="${CORTEX_RENDER_WAIT_INTERVAL_SECONDS:-10}"
if [[ ! "$timeout_seconds" =~ ^[1-9][0-9]*$ || "$timeout_seconds" -gt 3600 ]]; then
  echo "CORTEX_RENDER_WAIT_TIMEOUT_SECONDS must be between 1 and 3600." >&2
  exit 1
fi
if [[ ! "$interval_seconds" =~ ^[0-9]+$ || "$interval_seconds" -gt 60 ]]; then
  echo "CORTEX_RENDER_WAIT_INTERVAL_SECONDS must be between 0 and 60." >&2
  exit 1
fi

command -v curl >/dev/null 2>&1 || {
  echo "curl is required to deploy and verify Render." >&2
  exit 1
}
command -v python3 >/dev/null 2>&1 || {
  echo "python3 is required to verify Render responses." >&2
  exit 1
}

release_root="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/cortex-render-release.XXXXXX")"
hook_config="$release_root/hook.curl"
response_file="$release_root/response.json"
cleanup() {
  [[ ! -f "$hook_config" ]] || rm -f "$hook_config"
  [[ ! -f "$response_file" ]] || rm -f "$response_file"
  [[ ! -d "$release_root" ]] || rmdir "$release_root"
}
trap cleanup EXIT

umask 077
printf 'url = "%s&ref=%s"\n' \
  "$CORTEX_RENDER_DEPLOY_HOOK_URL" \
  "$CORTEX_RELEASE_SHA" > "$hook_config"
chmod 600 "$hook_config"
unset CORTEX_RENDER_DEPLOY_HOOK_URL

hook_status="$(
  curl \
    --disable \
    --silent \
    --show-error \
    --connect-timeout 5 \
    --max-time 30 \
    --request POST \
    --config "$hook_config" \
    --output "$response_file" \
    --write-out '%{http_code}' 2>/dev/null
)" || {
  echo "Render deploy hook request failed." >&2
  exit 1
}
if [[ "$hook_status" != "200" && "$hook_status" != "202" ]]; then
  echo "Render deploy hook did not start an identifiable deployment." >&2
  exit 1
fi

if [[ "$hook_status" == "200" ]] \
  && ! python3 - "$response_file" <<'PY' >/dev/null; then
import json
import pathlib
import re
import sys

document = json.loads(pathlib.Path(sys.argv[1]).read_text())
deploy_id = document.get("id")
if not deploy_id and isinstance(document.get("deploy"), dict):
    deploy_id = document["deploy"].get("id")
if not isinstance(deploy_id, str) or not re.fullmatch(r"dep-[a-z0-9]+", deploy_id):
    raise SystemExit(1)
PY
  echo "Render deploy hook response did not contain a deploy identifier." >&2
  exit 1
fi
rm -f "$hook_config"

started_at="$SECONDS"
while ((SECONDS - started_at < timeout_seconds)); do
  health_status="$(
    curl \
      --disable \
      --silent \
      --show-error \
      --connect-timeout 5 \
      --max-time 15 \
      --output "$response_file" \
      --write-out '%{http_code}' \
      "$CORTEX_RENDER_ORIGIN/api/health" 2>/dev/null
  )" || health_status="000"

  health_matches=false
  if [[ "$health_status" == "200" ]] && python3 - "$response_file" "$CORTEX_RELEASE_SHA" <<'PY' >/dev/null 2>&1; then
import json
import pathlib
import sys

document = json.loads(pathlib.Path(sys.argv[1]).read_text())
expected_revision = sys.argv[2]
if document.get("status") != "UP" or document.get("revision") != expected_revision:
    raise SystemExit(1)
PY
    health_matches=true
  fi

  if [[ "$health_matches" == "true" ]]; then
    readiness_status="$(
      curl \
        --disable \
        --silent \
        --show-error \
        --connect-timeout 5 \
        --max-time 15 \
        --output "$response_file" \
        --write-out '%{http_code}' \
        "$CORTEX_RENDER_ORIGIN/api/readiness" 2>/dev/null
    )" || readiness_status="000"

    if [[ "$readiness_status" == "200" ]] \
      && python3 - "$response_file" "$CORTEX_RELEASE_SHA" \
        "$CORTEX_PREVIOUS_RENDER_INSTANCE_SHA256" \
        "$CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256" \
        "$CORTEX_DATABASE_RELEASE_MARKER" <<'PY' >/dev/null 2>&1; then
import base64
import json
import pathlib
import re
import sys

document = json.loads(pathlib.Path(sys.argv[1]).read_text())
expected_revision = sys.argv[2]
previous_runtime_instance = sys.argv[3]
expected_offline_fingerprint = sys.argv[4]
expected_database_marker = sys.argv[5]
runtime_instance = document.get("runtimeInstanceSha256")

def is_canonical_sha256(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{43}", value):
        return False
    try:
        decoded = base64.urlsafe_b64decode(value + "=")
    except (ValueError, TypeError):
        return False
    return (
        len(decoded) == 32
        and base64.urlsafe_b64encode(decoded).decode().rstrip("=") == value
    )

if (
    document.get("status") != "READY"
    or document.get("revision") != expected_revision
    or not is_canonical_sha256(runtime_instance)
    or (
        previous_runtime_instance != "none"
        and runtime_instance == previous_runtime_instance
    )
    or document.get("offlineGrantPublicKeySha256") != expected_offline_fingerprint
    or document.get("objectStorage") != "READY"
    or document.get("databaseReleaseRevision") != expected_revision
    or document.get("databaseReleaseMarker") != expected_database_marker
):
    raise SystemExit(1)
PY
      echo "Render is serving the requested revision and is READY."
      exit 0
    fi
  fi

  sleep "$interval_seconds"
done

echo "Render did not serve the requested READY revision before the timeout." >&2
exit 1
