#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
contract_script="$repo_root/scripts/security/test-hosted-deployment-contract.sh"
fixture_dir="$(mktemp -d "${TMPDIR:-/tmp}/cortex-hosted-contract.XXXXXX")"

cleanup() {
  rm -rf "$fixture_dir"
}
trap cleanup EXIT

command -v python3 >/dev/null 2>&1 || {
  echo "python3 is required for hosted deployment contract regressions" >&2
  exit 1
}

assert_rejected() {
  local case_name="$1"
  local fixture="$fixture_dir/$case_name.yaml"

  if CORTEX_HOSTED_RENDER_FILE="$fixture" bash "$contract_script" >/dev/null 2>&1; then
    echo "hosted deployment contract accepted invalid fixture: $case_name" >&2
    exit 1
  fi
}

for case_name in branch profile readiness sse dev-admin provisioning jdbc-url postgres-password; do
  fixture="$fixture_dir/$case_name.yaml"
  cp "$repo_root/render.yaml" "$fixture"
  python3 - "$fixture" "$case_name" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
case_name = sys.argv[2]
text = path.read_text()

replacements = {
    "branch": (
        "branch: develop",
        "branch: main",
    ),
    "profile": (
        "value: production,postgresql",
        "value: production",
    ),
    "readiness": (
        'CORTEX_POSTGRES_RUNTIME_READY\n        value: "true"',
        'CORTEX_POSTGRES_RUNTIME_READY\n        value: "false"',
    ),
    "sse": (
        'CORTEX_STORAGE_S3_SEND_SSE_HEADER\n        value: "false"',
        'CORTEX_STORAGE_S3_SEND_SSE_HEADER\n        value: "true"',
    ),
    "dev-admin": (
        'CORTEX_AUTH_DEV_ADMIN_ENABLED\n        value: "false"',
        'CORTEX_AUTH_DEV_ADMIN_ENABLED\n        value: "true"',
    ),
    "provisioning": (
        'CORTEX_AUTH_PROVISIONING_ENABLED\n        value: "false"',
        'CORTEX_AUTH_PROVISIONING_ENABLED\n        value: "true"',
    ),
    "jdbc-url": (
        "CORTEX_POSTGRES_URL\n        sync: false",
        "CORTEX_POSTGRES_URL\n        value: "
        + "jdbc:post" + "gresql://invalid.invalid:5432/StaviasCortex",
    ),
    "postgres-password": (
        "CORTEX_POSTGRES_PASSWORD\n        sync: false",
        "CORTEX_POSTGRES_PASSWORD\n        value: " + "not-a-" + "secret-fixture",
    ),
}

before, after = replacements[case_name]
if before not in text:
    raise SystemExit(f"fixture setup failed for {case_name}")
path.write_text(text.replace(before, after, 1))
PY
  assert_rejected "$case_name"
done

echo "Hosted deployment contract negative regressions passed."
