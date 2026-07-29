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
  GITHUB_OUTPUT; do
  require_value "$name"
done

if [[ ! "$CORTEX_RELEASE_SHA" =~ ^[a-f0-9]{40}$ ]]; then
  echo "CORTEX_RELEASE_SHA must be a full Git commit SHA." >&2
  exit 1
fi
if [[ ! "$CORTEX_RENDER_ORIGIN" =~ ^https://[a-z0-9-]+\.onrender\.com$ ]]; then
  echo "CORTEX_RENDER_ORIGIN must be the direct HTTPS Render origin." >&2
  exit 1
fi

timeout_seconds="${CORTEX_RENDER_CAPTURE_TIMEOUT_SECONDS:-90}"
interval_seconds="${CORTEX_RENDER_CAPTURE_INTERVAL_SECONDS:-5}"
if [[ ! "$timeout_seconds" =~ ^[0-9]+$ || "$timeout_seconds" -gt 300 ]]; then
  echo "CORTEX_RENDER_CAPTURE_TIMEOUT_SECONDS must be between 0 and 300." >&2
  exit 1
fi
if [[ ! "$interval_seconds" =~ ^[0-9]+$ || "$interval_seconds" -gt 60 ]]; then
  echo "CORTEX_RENDER_CAPTURE_INTERVAL_SECONDS must be between 0 and 60." >&2
  exit 1
fi

command -v curl >/dev/null 2>&1 || {
  echo "curl is required to capture the Render runtime instance." >&2
  exit 1
}
command -v python3 >/dev/null 2>&1 || {
  echo "python3 is required to validate the Render readiness response." >&2
  exit 1
}

umask 077
capture_root="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/cortex-render-instance.XXXXXX")"
response_file="$capture_root/readiness.json"
cleanup() {
  [[ ! -f "$response_file" ]] || rm -f "$response_file"
  [[ ! -d "$capture_root" ]] || rmdir "$capture_root"
}
trap cleanup EXIT

started_at="$SECONDS"
while true; do
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

  if [[ "$readiness_status" == "200" ]]; then
    set +e
    captured_value="$(
      python3 - "$response_file" "$CORTEX_RELEASE_SHA" <<'PY'
import base64
import binascii
import json
import pathlib
import re
import sys

try:
    document = json.loads(pathlib.Path(sys.argv[1]).read_text())
except (OSError, UnicodeError, json.JSONDecodeError):
    raise SystemExit(1)

expected_revision = sys.argv[2]
revision = document.get("revision")
if (
    document.get("status") != "READY"
    or not isinstance(revision, str)
    or not re.fullmatch(r"[a-f0-9]{40}", revision)
):
    raise SystemExit(1)

if "runtimeInstanceSha256" not in document:
    if revision == expected_revision:
        raise SystemExit(2)
    print("none")
    raise SystemExit(0)

fingerprint = document["runtimeInstanceSha256"]
if not isinstance(fingerprint, str) or not re.fullmatch(
    r"[A-Za-z0-9_-]{43}", fingerprint
):
    raise SystemExit(1)

try:
    decoded = base64.urlsafe_b64decode(fingerprint + "=")
except (ValueError, TypeError, binascii.Error):
    raise SystemExit(1)

if (
    len(decoded) != 32
    or base64.urlsafe_b64encode(decoded).decode().rstrip("=") != fingerprint
):
    raise SystemExit(1)

print(fingerprint)
PY
    )"
    parse_status="$?"
    set -e

    if [[ "$parse_status" == "0" ]]; then
      printf 'previous_render_instance_sha256=%s\n' "$captured_value" \
        >> "$GITHUB_OUTPUT"
      echo "Captured the current Render runtime instance evidence."
      exit 0
    fi
    if [[ "$parse_status" == "2" ]]; then
      echo "Render is already serving the target revision without runtime instance evidence." >&2
      exit 1
    fi
  fi

  if ((SECONDS - started_at >= timeout_seconds)); then
    break
  fi
  sleep "$interval_seconds"
done

echo "Unable to capture valid Render runtime instance evidence." >&2
exit 1
